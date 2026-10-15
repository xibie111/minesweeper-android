package com.example.minesweeper.data

import com.example.minesweeper.core.GameConfig
import com.example.minesweeper.core.GameEngine
import com.example.minesweeper.core.GamePhase
import com.example.minesweeper.core.GameState
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationGameStoreTest {
    @Test fun replacementRestoresQueuedStateAndRejectsOldOwnerFlush() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CompletableDeferred<Unit>()
        val disk = AtomicReference(snapshot("disk"))
        val writes = Collections.synchronizedList(mutableListOf<String>())
        try {
            val coordinator = GameStoreCoordinator(LoadedSession(disk.get()), { session ->
                if (session.game.id == "first") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                disk.set(session)
                writes += session.game.id
                if (session.game.id == "replacement") finished.complete(Unit)
                true
            }, scope)
            val old = coordinator.attach()
            assertTrue(coordinator.publish(old, snapshot("first")))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals("disk", disk.get().game.id)

            // Disk still contains the previous board, but recreation must use
            // the immediately accepted first board from process memory.
            val replacement = coordinator.attach()
            assertEquals("first", replacement.restored.session.game.id)
            assertTrue(coordinator.publish(replacement, snapshot("replacement")))
            assertFalse(coordinator.publish(old, snapshot("stale-onCleared")))
            assertEquals("replacement", coordinator.attach().restored.session.game.id)
            release.countDown()
            withTimeout(5_000) { finished.await() }
            assertEquals("replacement", disk.get().game.id)
            assertEquals(listOf("first", "replacement"), writes.toList())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test fun aSingleActorKeepsNewestSnapshotWhenIntermediateWritesAreCoalesced() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val writes = Collections.synchronizedList(mutableListOf<String>())
        try {
            val coordinator = GameStoreCoordinator(LoadedSession(snapshot("initial")), { session ->
                val concurrent = active.incrementAndGet()
                maximumActive.updateAndGet { maxOf(it, concurrent) }
                if (session.game.id == "first") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                writes += session.game.id
                active.decrementAndGet()
                if (session.game.id == "latest") finished.complete(Unit)
                true
            }, scope)
            val lease = coordinator.attach()
            coordinator.publish(lease, snapshot("first"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            coordinator.publish(lease, snapshot("intermediate"))
            coordinator.publish(lease, snapshot("latest"))
            assertEquals("latest", coordinator.attach().restored.session.game.id)
            release.countDown()
            withTimeout(5_000) { finished.await() }
            assertEquals(listOf("first", "latest"), writes.toList())
            assertEquals(1, maximumActive.get())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test fun failedStorageStillRestoresMemoryAndTheActorRecoversOnTheNextWrite() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val recovered = CompletableDeferred<Unit>()
        val disk = AtomicReference(snapshot("initial"))
        try {
            val coordinator = GameStoreCoordinator(LoadedSession(disk.get()), { session ->
                if (session.game.id == "fails") error("Storage unavailable")
                disk.set(session)
                recovered.complete(Unit)
                true
            }, scope)
            val first = coordinator.attach()
            coordinator.publish(first, snapshot("fails"))
            withTimeout(5_000) { coordinator.persistenceIssue.first { it != null } }
            assertEquals(SAVE_FAILURE_NOTICE, coordinator.persistenceIssue.value)
            assertEquals("initial", disk.get().game.id)
            val replacement = coordinator.attach()
            assertEquals("fails", replacement.restored.session.game.id)
            assertTrue(coordinator.publish(replacement, snapshot("recovered")))
            withTimeout(5_000) { recovered.await() }
            withTimeout(5_000) { coordinator.persistenceIssue.first { it == null } }
            assertEquals("recovered", disk.get().game.id)
        } finally {
            scope.cancel()
        }
    }

    @Test fun anObsoleteFailureDoesNotReportAnErrorForAQueuedNewerSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val oldEntered = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val newEntered = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        val done = CompletableDeferred<Unit>()
        try {
            val coordinator = GameStoreCoordinator(LoadedSession(snapshot("initial")), { session ->
                if (session.game.id == "old") {
                    oldEntered.countDown()
                    check(releaseOld.await(5, TimeUnit.SECONDS))
                    false
                } else {
                    newEntered.countDown()
                    check(releaseNew.await(5, TimeUnit.SECONDS))
                    done.complete(Unit)
                    true
                }
            }, scope)
            val lease = coordinator.attach()
            coordinator.publish(lease, snapshot("old"))
            assertTrue(oldEntered.await(5, TimeUnit.SECONDS))
            coordinator.publish(lease, snapshot("new"))
            releaseOld.countDown()
            assertTrue(newEntered.await(5, TimeUnit.SECONDS))
            // The failed old write has already been handled, while the newer
            // write is deliberately blocked so it cannot clear an error.
            assertNull(coordinator.persistenceIssue.value)
            releaseNew.countDown()
            withTimeout(5_000) { done.await() }
        } finally {
            releaseOld.countDown()
            releaseNew.countDown()
            scope.cancel()
        }
    }

    @Test fun completionLedgerSurvivesRecreationBeforeTheDiskWriteFinishes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clearedSaved = CompletableDeferred<Unit>()
        try {
            val initial = snapshot("ready")
            val coordinator = GameStoreCoordinator(LoadedSession(initial), { session ->
                if (session.records.isNotEmpty()) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                } else if (session.game.isFinished) {
                    clearedSaved.complete(Unit)
                }
                true
            }, scope)
            val terminal = GameState(
                id = "won", config = GameConfig(5, 5, 1), seed = 1L,
                mines = setOf(0), revealed = (1 until 25).toSet(), flags = setOf(0),
                phase = GamePhase.WON, elapsedMillis = 2_000L,
            )
            val finalized = finalizeGame(initial.copy(game = terminal))
            val first = coordinator.attach()
            coordinator.publish(first, finalized)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val replacement = coordinator.attach()
            assertEquals(finalized, replacement.restored.session)
            assertEquals(1, finalizeGame(replacement.restored.session).records.single().completed)
            val cleared = replacement.restored.session.copy(records = emptyList())
            coordinator.publish(replacement, cleared)
            assertFalse(coordinator.publish(first, finalized))
            assertTrue(finalizeGame(coordinator.attach().restored.session).records.isEmpty())
            release.countDown()
            withTimeout(5_000) { clearedSaved.await() }
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    private fun snapshot(id: String) = PersistedSession(
        game = GameEngine.newGame(GameConfig.BEGINNER, seed = 42L, id = id),
        settings = AppSettings(), records = emptyList(), finalizedGameIds = emptySet(),
    )
}
