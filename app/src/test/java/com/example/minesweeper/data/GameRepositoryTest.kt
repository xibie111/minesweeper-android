package com.example.minesweeper.data

import android.content.SharedPreferences
import com.example.minesweeper.core.GameConfig
import com.example.minesweeper.core.GameEngine
import com.example.minesweeper.core.GamePhase
import com.example.minesweeper.core.GameState
import java.lang.reflect.Proxy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real JSON and engine code, with only the Android preference boundary replaced. */
class GameRepositoryTest {
    @Test fun emptyStorageStartsAReadyBoard() {
        val loaded = GameRepository(MemoryPreferences().preferences).load()
        assertEquals(GamePhase.READY, loaded.session.game.phase)
        assertEquals(GameConfig.BEGINNER, loaded.session.game.config)
        assertNull(loaded.notice)
        assertTrue(loaded.session.records.isEmpty())
    }

    @Test fun readyFlagsSettingsAndFullWidthSeedRoundTrip() {
        val memory = MemoryPreferences()
        val repository = GameRepository(memory.preferences)
        val game = GameEngine.toggleFlag(
            GameEngine.newGame(GameConfig.BEGINNER, seed = Long.MIN_VALUE, id = "ready"), 0,
        )
        val expected = session(game).copy(settings = AppSettings(ThemeChoice.DARK, false))
        assertTrue(repository.save(expected))
        val loaded = repository.load()
        assertEquals(expected, loaded.session)
        assertEquals(0L, loaded.session.game.elapsedMillis)
        assertNull(loaded.notice)
        assertEquals(listOf("snapshot"), memory.writtenKeys)
        assertEquals(1, memory.commits)
    }

    @Test fun playingBoardRestoresMinesAndElapsedWithoutRegeneration() {
        val memory = MemoryPreferences()
        val repository = GameRepository(memory.preferences)
        val game = GameEngine.reveal(
            GameEngine.newGame(GameConfig.BEGINNER, seed = Long.MAX_VALUE, id = "playing"), 40,
        ).copy(elapsedMillis = 12_345L)
        assertEquals(GamePhase.PLAYING, game.phase)
        val expected = session(game)
        assertTrue(repository.save(expected))
        assertEquals(expected, repository.load().session)
    }

    @Test fun terminalGameAndStatisticsRoundTripTogether() {
        for (phase in listOf(GamePhase.WON, GamePhase.LOST)) {
            val memory = MemoryPreferences()
            val repository = GameRepository(memory.preferences)
            val expected = finalizeGame(session(terminal(phase)))
            assertTrue(repository.save(expected))
            val restored = repository.load()
            assertEquals(expected, restored.session)
            assertNull(restored.notice)
        }
    }

    @Test fun restoringFinishedBoardCannotCountItTwice() {
        val memory = MemoryPreferences()
        val repository = GameRepository(memory.preferences)
        val finalized = finalizeGame(session(terminal(GamePhase.WON)))
        assertTrue(repository.save(finalized))
        val restored = repository.load().session
        assertEquals(restored, finalizeGame(restored))
        assertEquals(1, restored.records.single().completed)
        assertEquals(1, restored.records.single().wins)
    }

    @Test fun clearingRecordsPreservesTheFinishedBoardLedger() {
        val memory = MemoryPreferences()
        val repository = GameRepository(memory.preferences)
        val cleared = finalizeGame(session(terminal(GamePhase.WON))).copy(records = emptyList())
        assertTrue(repository.save(cleared))
        val restored = repository.load().session
        assertEquals(GamePhase.WON, restored.game.phase)
        assertTrue(finalizeGame(restored).records.isEmpty())
    }

    @Test fun onlyNewTerminalIdsCountAndBestUsesWinningTime() {
        val first = finalizeGame(session(terminal(GamePhase.WON, "first", 5_000)))
        val second = finalizeGame(first.copy(game = terminal(GamePhase.WON, "second", 3_000)))
        val third = finalizeGame(second.copy(game = terminal(GamePhase.LOST, "third", 1_000)))
        assertEquals(3, third.records.single().completed)
        assertEquals(2, third.records.single().wins)
        assertEquals(3_000L, third.records.single().bestMillis)
        assertEquals(third, finalizeGame(third))
        val ongoing = third.copy(game = GameEngine.newGame(GameConfig.BEGINNER, id = "new"))
        assertEquals(ongoing, finalizeGame(ongoing))
    }

    @Test fun customDimensionsHaveIndependentRecords() {
        val first = finalizeGame(session(terminal(GamePhase.WON, "a")))
        val config = GameConfig(5, 6, 1)
        val game = terminal(GamePhase.WON, "b").copy(
            config = config,
            revealed = (1 until config.rows * config.cols).toSet(),
        )
        val second = finalizeGame(first.copy(game = game))
        assertEquals(2, second.records.size)
        assertEquals(setOf("5x5x1", "5x6x1"), second.records.map { it.key }.toSet())
    }

    @Test fun corruptBoardFallsBackWhileKeepingSettingsAndStatistics() {
        val memory = MemoryPreferences()
        val repository = GameRepository(memory.preferences)
        val expected = finalizeGame(session(terminal(GamePhase.WON)))
            .copy(settings = AppSettings(ThemeChoice.LIGHT, false))
        assertTrue(repository.save(expected))
        memory.alter { it.getJSONObject("game").put("flags", JSONArray().put(999)) }
        val restored = repository.load()
        assertEquals(GamePhase.READY, restored.session.game.phase)
        assertEquals(expected.settings, restored.session.settings)
        assertEquals(expected.records, restored.session.records)
        assertNotNull(restored.notice)
    }

    @Test fun duplicateIndicesAreRejectedRatherThanSilentlyDeduplicated() {
        val memory = MemoryPreferences()
        val repository = GameRepository(memory.preferences)
        assertTrue(repository.save(session(GameEngine.newGame(GameConfig.BEGINNER))))
        memory.alter { it.getJSONObject("game").put("flags", JSONArray().put(0).put(0)) }
        val restored = repository.load()
        assertTrue(restored.session.game.flags.isEmpty())
        assertNotNull(restored.notice)
    }

    @Test fun missingTerminalLedgerDoesNotDuplicateExistingStatistics() {
        val memory = MemoryPreferences()
        val repository = GameRepository(memory.preferences)
        val expected = finalizeGame(session(terminal(GamePhase.WON)))
        assertTrue(repository.save(expected))
        memory.alter { it.put("finalizedGameIds", JSONArray()) }
        val restored = repository.load()
        assertEquals(GamePhase.READY, restored.session.game.phase)
        assertEquals(expected.records, restored.session.records)
        assertEquals(restored.session, finalizeGame(restored.session))
        assertNotNull(restored.notice)
    }

    @Test fun corruptStatisticsDoNotRecountTheCurrentFinishedGame() {
        val memory = MemoryPreferences()
        val repository = GameRepository(memory.preferences)
        val expected = finalizeGame(session(terminal(GamePhase.WON)))
        assertTrue(repository.save(expected))
        memory.alter { it.getJSONArray("records").getJSONObject(0).put("wins", 99) }
        val restored = repository.load()
        assertEquals(GamePhase.WON, restored.session.game.phase)
        assertTrue(restored.session.records.isEmpty())
        assertTrue(finalizeGame(restored.session).records.isEmpty())
        assertNotNull(restored.notice)
    }

    @Test fun unsupportedVersionAndMalformedJsonRecoverSafely() {
        val memory = MemoryPreferences()
        val repository = GameRepository(memory.preferences)
        assertTrue(repository.save(session(GameEngine.newGame(GameConfig.BEGINNER))))
        memory.alter { it.put("version", 99) }
        assertNotNull(repository.load().notice)
        memory.raw = "{this is not valid JSON"
        assertEquals(GamePhase.READY, repository.load().session.game.phase)
        assertNotNull(repository.load().notice)
    }

    @Test fun fractionalElapsedIsRejected() {
        val memory = MemoryPreferences()
        val repository = GameRepository(memory.preferences)
        assertTrue(repository.save(session(GameEngine.newGame(GameConfig.BEGINNER))))
        memory.alter { it.getJSONObject("game").put("elapsedMillis", 1.5) }
        assertNotNull(repository.load().notice)
        assertEquals(0L, repository.load().session.game.elapsedMillis)
    }

    @Test fun failedCommitIsReportedAndDoesNotReplaceThePreviousSnapshot() {
        val memory = MemoryPreferences()
        val repository = GameRepository(memory.preferences)
        val previous = session(GameEngine.newGame(GameConfig.BEGINNER, id = "previous"))
        assertTrue(repository.save(previous))
        memory.failCommits = true
        assertFalse(repository.save(session(GameEngine.newGame(GameConfig.EXPERT, id = "new"))))
        assertEquals(previous, repository.load().session)
    }

    private fun session(game: GameState) = PersistedSession(game, AppSettings(), emptyList(), emptySet())

    private fun terminal(phase: GamePhase, id: String = phase.name, elapsed: Long = 2_000L): GameState {
        val won = phase == GamePhase.WON
        return GameState(
            id = id,
            config = GameConfig(5, 5, 1),
            seed = 42L,
            mines = setOf(0),
            revealed = if (won) (1 until 25).toSet() else setOf(0, 1),
            flags = if (won) setOf(0) else emptySet(),
            phase = phase,
            explodedIndex = if (won) null else 0,
            elapsedMillis = elapsed,
        ).also { assertTrue(GameEngine.isValid(it)) }
    }

    /** Minimal fake implementing the real preference interface through JVM reflection. */
    private class MemoryPreferences {
        var raw: String? = null
        var failCommits = false
        var commits = 0
        val writtenKeys = mutableListOf<String>()
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getString" -> raw
                "edit" -> newEditor()
                else -> error("Unexpected preference method: ${method.name}")
            }
        } as SharedPreferences

        fun alter(block: (JSONObject) -> Unit) {
            raw = JSONObject(requireNotNull(raw)).also(block).toString()
        }

        private fun newEditor(): SharedPreferences.Editor {
            var pending: String? = raw
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "putString" -> {
                        writtenKeys += arguments[0] as String
                        pending = arguments[1] as String
                        proxy
                    }
                    "commit" -> {
                        commits++
                        if (failCommits) false else {
                            raw = pending
                            true
                        }
                    }
                    else -> error("Unexpected editor method: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
