package com.example.minesweeper.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.minesweeper.core.GameConfig
import com.example.minesweeper.core.GameEngine
import com.example.minesweeper.core.GamePhase
import com.example.minesweeper.core.GameState
import com.example.minesweeper.data.AppSettings
import com.example.minesweeper.data.ApplicationGameStore
import com.example.minesweeper.data.GameRecord
import com.example.minesweeper.data.PersistedSession
import com.example.minesweeper.data.SAVE_FAILURE_NOTICE
import com.example.minesweeper.data.ThemeChoice
import com.example.minesweeper.data.finalizeGame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class MinesweeperUiState(
    val game: GameState,
    val settings: AppSettings,
    val records: List<GameRecord>,
    val flagMode: Boolean = false,
    val notice: String? = null,
)

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ApplicationGameStore.get(application)
    private val lease = store.attach()
    private val restored = lease.restored
    private val _uiState = MutableStateFlow(
        MinesweeperUiState(
            game = restored.session.game,
            settings = restored.session.settings,
            records = restored.session.records,
            notice = restored.notice,
        ),
    )
    val uiState: StateFlow<MinesweeperUiState> = _uiState.asStateFlow()

    private var finalizedGameIds = restored.session.finalizedGameIds
    private var foreground = false
    private var runningSince: Long? = null
    private var lastSavedSecond = restored.session.game.elapsedMillis / 1_000L

    init {
        viewModelScope.launch {
            store.persistenceIssue.collect { issue ->
                if (store.isCurrent(lease)) {
                    if (issue != null) {
                        _uiState.value = _uiState.value.copy(notice = issue)
                    } else if (_uiState.value.notice == SAVE_FAILURE_NOTICE) {
                        _uiState.value = _uiState.value.copy(notice = null)
                    }
                }
            }
        }
        // Persist a safe fallback after corrupt data, and persist the initial
        // READY board too so flags placed before the first reveal survive.
        saveSnapshot()
        viewModelScope.launch {
            while (true) {
                delay(250L)
                if (foreground && _uiState.value.game.phase == GamePhase.PLAYING) {
                    settleElapsed()
                    val second = _uiState.value.game.elapsedMillis / 1_000L
                    if (second != lastSavedSecond) saveSnapshot()
                }
            }
        }
    }

    fun startNew(config: GameConfig) {
        val error = config.validationError()
        if (error != null) {
            _uiState.value = _uiState.value.copy(notice = error)
            return
        }
        runningSince = null
        _uiState.value = _uiState.value.copy(
            game = GameEngine.newGame(config),
            flagMode = false,
            notice = null,
        )
        saveSnapshot()
    }

    fun tapCell(index: Int) {
        mutateGame { game ->
            when {
                _uiState.value.flagMode -> GameEngine.toggleFlag(game, index)
                index in game.revealed -> GameEngine.chord(game, index)
                else -> GameEngine.reveal(game, index)
            }
        }
    }

    fun longPressCell(index: Int) {
        mutateGame { game -> GameEngine.toggleFlag(game, index) }
    }

    fun setFlagMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(flagMode = enabled)
    }

    fun setTheme(choice: ThemeChoice) {
        if (_uiState.value.settings.theme == choice) return
        settleElapsed()
        _uiState.value = _uiState.value.let {
            it.copy(settings = it.settings.copy(theme = choice))
        }
        saveSnapshot()
    }

    fun setHaptics(enabled: Boolean) {
        if (_uiState.value.settings.haptics == enabled) return
        settleElapsed()
        _uiState.value = _uiState.value.let {
            it.copy(settings = it.settings.copy(haptics = enabled))
        }
        saveSnapshot()
    }

    fun clearRecords() {
        settleElapsed()
        // Preserve finalization IDs so the visible finished board cannot
        // re-populate cleared statistics when the process is recreated.
        _uiState.value = _uiState.value.copy(records = emptyList())
        saveSnapshot()
    }

    fun dismissNotice() {
        _uiState.value = _uiState.value.copy(notice = null)
    }

    fun setForeground(isForeground: Boolean) {
        if (foreground == isForeground) return
        if (!isForeground) settleElapsed()
        foreground = isForeground
        syncClock()
        if (!isForeground) saveSnapshot()
    }

    private fun mutateGame(transform: (GameState) -> GameState) {
        if (_uiState.value.game.isFinished) return
        settleElapsed()
        val before = _uiState.value
        val after = transform(before.game)
        if (after == before.game) return
        val finalized = finalizeGame(
            PersistedSession(after, before.settings, before.records, finalizedGameIds),
        )
        finalizedGameIds = finalized.finalizedGameIds
        _uiState.value = before.copy(game = after, records = finalized.records)
        syncClock()
        saveSnapshot()
    }

    private fun settleElapsed() {
        val started = runningSince ?: return
        val game = _uiState.value.game
        if (!foreground || game.phase != GamePhase.PLAYING) {
            runningSince = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        val delta = (now - started).coerceAtLeast(0L)
        runningSince = now
        if (delta == 0L) return
        val elapsed = if (Long.MAX_VALUE - game.elapsedMillis < delta) {
            Long.MAX_VALUE
        } else game.elapsedMillis + delta
        _uiState.value = _uiState.value.copy(game = game.copy(elapsedMillis = elapsed))
    }

    private fun syncClock() {
        if (foreground && _uiState.value.game.phase == GamePhase.PLAYING) {
            if (runningSince == null) runningSince = SystemClock.elapsedRealtime()
        } else {
            runningSince = null
        }
    }

    private fun saveSnapshot() {
        val current = _uiState.value
        lastSavedSecond = current.game.elapsedMillis / 1_000L
        store.publish(
            lease,
            PersistedSession(current.game, current.settings, current.records, finalizedGameIds),
        )
    }

    override fun onCleared() {
        settleElapsed()
        saveSnapshot()
        // The process-wide actor remains alive. Superseded leases are ignored,
        // so this final flush cannot undo a replacement ViewModel's progress.
        super.onCleared()
    }

}
