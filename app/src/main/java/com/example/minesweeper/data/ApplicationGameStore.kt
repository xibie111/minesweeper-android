package com.example.minesweeper.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One process-wide owner of persistence. Recreated ViewModels read the latest
 * accepted in-memory snapshot, even if the disk writer is still busy.
 * It retains only application context, immutable snapshots and its IO actor.
 */
class ApplicationGameStore private constructor(context: Context) {
    private val repository = GameRepository(context.applicationContext)
    private val coordinator = GameStoreCoordinator(
        initial = repository.load(),
        persist = repository::save,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    internal val persistenceIssue: StateFlow<String?> get() = coordinator.persistenceIssue

    internal fun attach(): SessionLease = coordinator.attach()

    internal fun publish(lease: SessionLease, session: PersistedSession): Boolean =
        coordinator.publish(lease, session)

    internal fun isCurrent(lease: SessionLease): Boolean = coordinator.isCurrent(lease)

    companion object {
        @Volatile private var instance: ApplicationGameStore? = null

        fun get(context: Context): ApplicationGameStore = instance ?: synchronized(this) {
            instance ?: ApplicationGameStore(context.applicationContext).also { instance = it }
        }
    }
}

internal data class SessionLease(val owner: Long, val restored: LoadedSession)

internal const val SAVE_FAILURE_NOTICE =
    "本地存档写入失败，本次对局仍可继续，请检查可用存储空间。"

/**
 * Pure coordinator, with the storage boundary and coroutine scope injected.
 * A lease also prevents a superseded ViewModel's late onCleared/timer snapshot
 * from overwriting state already accepted from the replacement ViewModel.
 */
internal class GameStoreCoordinator(
    initial: LoadedSession,
    private val persist: (PersistedSession) -> Boolean,
    scope: CoroutineScope,
) {
    private data class WriteRequest(val revision: Long, val session: PersistedSession)

    private val lock = Any()
    private var latest = initial
    private var owner = 0L
    private var revision = 0L
    private val queue = Channel<WriteRequest>(Channel.CONFLATED)
    private val _persistenceIssue = MutableStateFlow<String?>(null)
    val persistenceIssue: StateFlow<String?> = _persistenceIssue.asStateFlow()

    init {
        scope.launch {
            for (request in queue) {
                val saved = try {
                    persist(request.session)
                } catch (_: Exception) {
                    false
                }
                synchronized(lock) {
                    // An obsolete failed write should not announce an error
                    // for a newer snapshot already waiting to be persisted.
                    if (request.revision == revision) {
                        _persistenceIssue.value = if (saved) null else SAVE_FAILURE_NOTICE
                    }
                }
            }
        }
    }

    fun attach(): SessionLease = synchronized(lock) {
        owner += 1L
        SessionLease(owner, latest)
    }

    fun isCurrent(lease: SessionLease): Boolean = synchronized(lock) { lease.owner == owner }

    fun publish(lease: SessionLease, session: PersistedSession): Boolean = synchronized(lock) {
        if (lease.owner != owner) return@synchronized false
        revision += 1L
        // Publish before returning; restoration never depends on IO progress.
        latest = LoadedSession(session)
        queue.trySend(WriteRequest(revision, session)).isSuccess
    }
}
