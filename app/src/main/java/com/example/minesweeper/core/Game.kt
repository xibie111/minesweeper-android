package com.example.minesweeper.core

import java.util.ArrayDeque
import java.util.UUID
import kotlin.random.Random

data class GameConfig(val rows: Int, val cols: Int, val mineCount: Int) {
    val key: String get() = "${rows}x${cols}x$mineCount"
    val displayName: String
        get() = when (this) {
            BEGINNER -> "初级"
            INTERMEDIATE -> "中级"
            EXPERT -> "高级"
            else -> "自定义 ${cols}×${rows} · $mineCount 雷"
        }

    fun validationError(): String? = when {
        rows !in 5..30 -> "行数必须为 5～30"
        cols !in 5..30 -> "列数必须为 5～30"
        mineCount !in 1..(rows * cols - 9) -> "雷数必须为 1～${rows * cols - 9}"
        else -> null
    }

    companion object {
        val BEGINNER = GameConfig(rows = 9, cols = 9, mineCount = 10)
        val INTERMEDIATE = GameConfig(rows = 16, cols = 16, mineCount = 40)
        val EXPERT = GameConfig(rows = 16, cols = 30, mineCount = 99)
    }
}

enum class GamePhase { READY, PLAYING, WON, LOST }

data class GameState(
    val id: String,
    val config: GameConfig,
    val seed: Long,
    val mines: Set<Int> = emptySet(),
    val revealed: Set<Int> = emptySet(),
    val flags: Set<Int> = emptySet(),
    val phase: GamePhase = GamePhase.READY,
    val explodedIndex: Int? = null,
    val elapsedMillis: Long = 0,
) {
    val remainingMines: Int get() = config.mineCount - flags.size
    val isFinished: Boolean get() = phase == GamePhase.WON || phase == GamePhase.LOST

    fun neighbors(index: Int): List<Int> {
        if (config.validationError() != null || index !in 0 until config.rows * config.cols) {
            return emptyList()
        }
        val row = index / config.cols
        val col = index % config.cols
        return buildList(8) {
            for (nextRow in (row - 1).coerceAtLeast(0)..(row + 1).coerceAtMost(config.rows - 1)) {
                for (nextCol in (col - 1).coerceAtLeast(0)..(col + 1).coerceAtMost(config.cols - 1)) {
                    val neighbor = nextRow * config.cols + nextCol
                    if (neighbor != index) add(neighbor)
                }
            }
        }
    }

    fun adjacentMines(index: Int): Int = neighbors(index).count { it in mines }
}

/** Immutable, platform-independent rules. Timing and persistence belong to the caller. */
object GameEngine {
    fun newGame(
        config: GameConfig,
        seed: Long = Random.Default.nextLong(),
        id: String = UUID.randomUUID().toString(),
    ): GameState {
        require(config.validationError() == null) { config.validationError().orEmpty() }
        require(id.isNotBlank()) { "对局 ID 不能为空" }
        return GameState(id = id, config = config, seed = seed)
    }

    fun reveal(state: GameState, index: Int): GameState {
        if (!canInteract(state, index) || index in state.flags || index in state.revealed) return state
        val started = if (state.phase == GamePhase.READY) generateMines(state, index) else state
        return revealCells(started, listOf(index))
    }

    fun toggleFlag(state: GameState, index: Int): GameState {
        if (!canInteract(state, index) || index in state.revealed) return state
        val flags = if (index in state.flags) state.flags - index else state.flags + index
        return state.copy(flags = flags)
    }

    fun chord(state: GameState, index: Int): GameState {
        if (!canInteract(state, index) || state.phase != GamePhase.PLAYING || index !in state.revealed) {
            return state
        }
        val number = state.adjacentMines(index)
        val neighbors = state.neighbors(index)
        if (number == 0 || neighbors.count { it in state.flags } != number) return state
        val targets = neighbors.filter { it !in state.flags && it !in state.revealed }
        if (targets.isEmpty()) return state
        return revealCells(state, targets)
    }

    /** Reject corrupt or incompatible snapshots before restoring them into the game. */
    fun isValid(state: GameState): Boolean {
        if (state.config.validationError() != null || state.id.isBlank() || state.elapsedMillis < 0) return false
        val bounds = 0 until state.config.rows * state.config.cols
        if (state.mines.any { it !in bounds } || state.flags.any { it !in bounds } ||
            state.revealed.any { it !in bounds } || state.flags.any { it in state.revealed }) return false

        if (state.phase == GamePhase.READY) {
            return state.mines.isEmpty() && state.revealed.isEmpty() &&
                state.explodedIndex == null && state.elapsedMillis == 0L
        }
        if (state.mines.size != state.config.mineCount || state.revealed.isEmpty()) return false
        val revealedMines = state.revealed.intersect(state.mines)
        val safeCount = state.config.rows * state.config.cols - state.config.mineCount
        return when (state.phase) {
            GamePhase.READY -> false // Handled above.
            GamePhase.PLAYING -> state.explodedIndex == null && revealedMines.isEmpty() &&
                state.revealed.size < safeCount
            GamePhase.WON -> state.explodedIndex == null && revealedMines.isEmpty() &&
                state.revealed.size == safeCount && state.flags == state.mines
            GamePhase.LOST -> state.explodedIndex != null && state.explodedIndex in state.mines &&
                revealedMines == setOf(state.explodedIndex)
        }
    }

    private fun canInteract(state: GameState, index: Int): Boolean =
        !state.isFinished && state.config.validationError() == null &&
            index in 0 until state.config.rows * state.config.cols

    private fun generateMines(state: GameState, firstIndex: Int): GameState {
        val protected = state.neighbors(firstIndex).toSet() + firstIndex
        val candidates = (0 until state.config.rows * state.config.cols)
            .filter { it !in protected }.toMutableList()
        val random = Random(state.seed)
        // Partial Fisher-Yates: each eligible cell has an equal chance, without retries or duplicates.
        for (i in 0 until state.config.mineCount) {
            val swapIndex = random.nextInt(i, candidates.size)
            val previous = candidates[i]
            candidates[i] = candidates[swapIndex]
            candidates[swapIndex] = previous
        }
        return state.copy(
            mines = candidates.take(state.config.mineCount).toSet(),
            phase = GamePhase.PLAYING,
        )
    }

    private fun revealCells(state: GameState, targets: List<Int>): GameState {
        val revealed = state.revealed.toMutableSet()
        val queue = ArrayDeque<Int>()
        targets.forEach(queue::addLast)
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            if (cell in state.flags || cell in revealed) continue
            revealed.add(cell)
            if (cell in state.mines) {
                return state.copy(
                    revealed = revealed.toSet(),
                    phase = GamePhase.LOST,
                    explodedIndex = cell,
                )
            }
            if (state.adjacentMines(cell) == 0) {
                state.neighbors(cell).forEach { neighbor ->
                    if (neighbor !in revealed && neighbor !in state.flags) queue.addLast(neighbor)
                }
            }
        }
        val won = revealed.size == state.config.rows * state.config.cols - state.config.mineCount
        return state.copy(
            revealed = revealed.toSet(),
            phase = if (won) GamePhase.WON else GamePhase.PLAYING,
            flags = if (won) state.mines.toSet() else state.flags,
        )
    }
}
