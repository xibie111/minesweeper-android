package com.example.minesweeper.core

import org.junit.Assert.*
import org.junit.Test

class GameEngineTest {
    private val config = GameConfig(5, 5, 1)

    private fun playing(
        mines: Set<Int> = setOf(24),
        revealed: Set<Int> = setOf(18),
        flags: Set<Int> = emptySet(),
    ) = GameState(
        id = "fixture",
        config = config.copy(mineCount = mines.size),
        seed = 7,
        mines = mines,
        revealed = revealed,
        flags = flags,
        phase = GamePhase.PLAYING,
    )

    @Test fun presetDimensionsAndNamesAreCorrect() {
        assertEquals(GameConfig(9, 9, 10), GameConfig.BEGINNER)
        assertEquals(GameConfig(16, 16, 40), GameConfig.INTERMEDIATE)
        assertEquals(GameConfig(16, 30, 99), GameConfig.EXPERT)
        assertEquals("初级", GameConfig.BEGINNER.displayName)
        assertEquals("16x30x99", GameConfig.EXPERT.key)
        assertTrue(GameConfig(5, 6, 7).displayName.startsWith("自定义"))
    }

    @Test fun configurationRejectsInvalidInputsAndAcceptsLimits() {
        listOf(GameConfig(4, 5, 1), GameConfig(31, 5, 1), GameConfig(5, 4, 1),
            GameConfig(5, 31, 1), GameConfig(5, 5, 0), GameConfig(5, 5, 17),
            GameConfig(Int.MAX_VALUE, Int.MAX_VALUE, 1)).forEach {
            assertNotNull("Should reject $it", it.validationError())
        }
        assertNull(GameConfig(5, 5, 16).validationError())
        assertNull(GameConfig(30, 30, 891).validationError())
    }

    @Test(expected = IllegalArgumentException::class)
    fun newGameRejectsInvalidConfiguration() {
        GameEngine.newGame(GameConfig(5, 5, 17))
    }

    @Test fun newGameDefersMinesAndAllowsFlagsBeforeStarting() {
        val initial = GameEngine.newGame(config, seed = 1, id = "ready")
        assertEquals(GamePhase.READY, initial.phase)
        assertTrue(initial.mines.isEmpty())
        val flagged = GameEngine.toggleFlag(initial, 12)
        assertEquals(setOf(12), flagged.flags)
        assertEquals(GamePhase.READY, flagged.phase)
        assertSame(flagged, GameEngine.reveal(flagged, 12))
        assertTrue(GameEngine.isValid(flagged))
    }

    @Test fun firstRevealProtectsEveryCornerEdgeAndInteriorAcrossSeeds() {
        for (board in listOf(GameConfig(5, 5, 16), GameConfig.BEGINNER, GameConfig.EXPERT)) {
            val cells = listOf(0, board.cols - 1, (board.rows - 1) * board.cols,
                board.rows * board.cols - 1, board.cols / 2,
                (board.rows - 1) * board.cols + board.cols / 2,
                (board.rows / 2) * board.cols,
                (board.rows / 2) * board.cols + board.cols - 1,
                (board.rows / 2) * board.cols + board.cols / 2)
            for (seed in 0L..15L) for (first in cells) {
                val result = GameEngine.reveal(GameEngine.newGame(board, seed), first)
                assertEquals(board.mineCount, result.mines.size)
                assertTrue((result.neighbors(first) + first).none { it in result.mines })
                assertTrue(first in result.revealed)
                assertNotEquals(GamePhase.LOST, result.phase)
                assertTrue(GameEngine.isValid(result))
            }
        }
    }

    @Test fun identicalSeedAndFirstCellReproduceLayoutAndReveal() {
        val a = GameEngine.reveal(GameEngine.newGame(GameConfig.INTERMEDIATE, 413, "a"), 120)
        val b = GameEngine.reveal(GameEngine.newGame(GameConfig.INTERMEDIATE, 413, "b"), 120)
        assertEquals(a.mines, b.mines)
        assertEquals(a.revealed, b.revealed)
    }

    @Test fun neighborsRespectBoardEdgesAndDoNotWrapRows() {
        val state = GameEngine.newGame(config)
        assertEquals(listOf(1, 5, 6), state.neighbors(0))
        assertEquals(listOf(3, 8, 9), state.neighbors(4))
        assertEquals(listOf(15, 16, 21), state.neighbors(20))
        assertEquals(listOf(18, 19, 23), state.neighbors(24))
        assertEquals(8, state.neighbors(12).size)
        assertTrue(state.neighbors(-1).isEmpty())
        assertTrue(state.neighbors(25).isEmpty())
    }

    @Test fun adjacentCountsAreCorrectForAllEightDirections() {
        val mines = setOf(6, 7, 8, 11, 13, 16, 17, 18)
        val state = playing(mines, setOf(12))
        assertEquals(8, state.adjacentMines(12))
        assertEquals(1, state.adjacentMines(0))
        assertEquals(3, state.adjacentMines(2))
        assertEquals(0, state.adjacentMines(-1))
    }

    @Test fun zeroFloodIncludesNumberBorderAndNeverMines() {
        val state = playing(mines = setOf(0, 1, 2, 3, 4), revealed = setOf(5))
        val result = GameEngine.reveal(state, 24)
        assertEquals((5..24).toSet(), result.revealed)
        assertEquals(GamePhase.WON, result.phase)
        assertEquals(state.mines, result.flags)
        assertTrue(GameEngine.isValid(result))
    }

    @Test fun floodDoesNotRevealFlaggedSafeCell() {
        val result = GameEngine.reveal(playing(flags = setOf(0)), 1)
        assertFalse(0 in result.revealed)
        assertEquals(setOf(0), result.flags)
        assertEquals(GamePhase.PLAYING, result.phase)
        val unflagged = GameEngine.toggleFlag(result, 0)
        assertEquals(GamePhase.WON, GameEngine.reveal(unflagged, 0).phase)
    }

    @Test fun lastSafeRevealWinsRegardlessOfFlagCount() {
        val state = playing(revealed = (0..23).toSet() - 23)
        assertEquals(1, state.remainingMines)
        val result = GameEngine.reveal(state, 23)
        assertEquals(GamePhase.WON, result.phase)
        assertEquals(setOf(24), result.flags)
        assertEquals(0, result.remainingMines)
        assertTrue(GameEngine.isValid(result))
    }

    @Test fun flagsAloneNeverWinAndCounterMayBecomeNegative() {
        var state = GameEngine.newGame(config)
        for (index in 0..2) state = GameEngine.toggleFlag(state, index)
        assertEquals(-2, state.remainingMines)
        assertEquals(GamePhase.READY, state.phase)
        assertTrue(GameEngine.isValid(state))
    }

    @Test fun revealMineLosesAndKeepsExplodedIndex() {
        val result = GameEngine.reveal(playing(), 24)
        assertEquals(GamePhase.LOST, result.phase)
        assertEquals(24, result.explodedIndex)
        assertEquals(setOf(18, 24), result.revealed)
        assertTrue(GameEngine.isValid(result))
    }

    @Test fun correctChordRevealsUnflaggedNeighborsWithoutCrossingNumberBorder() {
        val state = playing(mines = setOf(6), revealed = setOf(12), flags = setOf(6))
        val result = GameEngine.chord(state, 12)
        // Cells 0, 1 and 5 each border mine 6 and have no adjacent zero to expand from.
        assertEquals(GamePhase.PLAYING, result.phase)
        assertEquals((0..24).toSet() - setOf(0, 1, 5, 6), result.revealed)
        assertFalse(6 in result.revealed)
        assertTrue(GameEngine.isValid(result))
        val corner = GameEngine.reveal(result, 0)
        assertEquals(GamePhase.PLAYING, corner.phase)
        val edge = GameEngine.reveal(corner, 1)
        assertEquals(GamePhase.PLAYING, edge.phase)
        val won = GameEngine.reveal(edge, 5)
        assertEquals(GamePhase.WON, won.phase)
        assertEquals((0..24).toSet() - 6, won.revealed)
        assertTrue(GameEngine.isValid(won))
    }

    @Test fun incorrectChordCanExplodeAnUnflaggedMine() {
        val state = playing(mines = setOf(6), revealed = setOf(12), flags = setOf(7))
        val result = GameEngine.chord(state, 12)
        assertEquals(GamePhase.LOST, result.phase)
        assertEquals(6, result.explodedIndex)
        assertEquals(setOf(7), result.flags)
        assertTrue(GameEngine.isValid(result))
    }

    @Test fun chordRequiresRevealedNumberAndMatchingFlagCount() {
        val state = playing(mines = setOf(6), revealed = setOf(12))
        assertSame(state, GameEngine.chord(state, 12))
        assertSame(state, GameEngine.chord(state, 11))
        val tooMany = state.copy(flags = setOf(6, 7))
        assertSame(tooMany, GameEngine.chord(tooMany, 12))
        val zero = playing(revealed = setOf(0))
        assertSame(zero, GameEngine.chord(zero, 0))
    }

    @Test fun repeatedAndOutOfBoundsInputsDoNotChangeState() {
        val state = playing()
        assertSame(state, GameEngine.reveal(state, 18))
        assertSame(state, GameEngine.toggleFlag(state, 18))
        for (index in listOf(-1, 25, Int.MAX_VALUE)) {
            assertSame(state, GameEngine.reveal(state, index))
            assertSame(state, GameEngine.toggleFlag(state, index))
            assertSame(state, GameEngine.chord(state, index))
        }
        val flagged = GameEngine.toggleFlag(state, 0)
        assertEquals(state, GameEngine.toggleFlag(flagged, 0))
        assertSame(flagged, GameEngine.reveal(flagged, 0))
    }

    @Test fun finishedGamesRejectEveryInteraction() {
        val lost = GameEngine.reveal(playing(), 24)
        val won = GameEngine.reveal(playing(revealed = (0..22).toSet()), 23)
        for (state in listOf(won, lost)) {
            assertTrue(state.isFinished)
            assertSame(state, GameEngine.reveal(state, 0))
            assertSame(state, GameEngine.toggleFlag(state, 0))
            assertSame(state, GameEngine.chord(state, 18))
        }
    }

    @Test fun engineDoesNotMutatePreviousSnapshotOrElapsedTime() {
        val state = playing().copy(elapsedMillis = 12_345)
        val result = GameEngine.reveal(state, 0)
        assertEquals(setOf(18), state.revealed)
        assertTrue(state.flags.isEmpty())
        assertEquals(12_345L, result.elapsedMillis)
        assertNotEquals(state.revealed, result.revealed)
    }

    @Test fun validSnapshotsCoverEveryPhaseAndRemainDeterministicAfterRestore() {
        val ready = GameEngine.newGame(GameConfig.BEGINNER, 777, "restore")
        val started = GameEngine.reveal(ready, 40)
        assertTrue(GameEngine.isValid(ready))
        assertTrue(GameEngine.isValid(started))
        val target = started.mines.first()
        assertEquals(GameEngine.reveal(started, target), GameEngine.reveal(started.copy(), target))
        assertTrue(GameEngine.isValid(GameEngine.reveal(started, target)))
    }

    @Test fun validationRejectsCorruptCoordinatesCountsIdentityAndElapsedTime() {
        val state = playing()
        listOf(state.copy(id = " "), state.copy(elapsedMillis = -1),
            state.copy(config = GameConfig(4, 5, 1)), state.copy(mines = emptySet()),
            state.copy(mines = setOf(25)), state.copy(revealed = setOf(-1)),
            state.copy(flags = setOf(25)), state.copy(flags = setOf(18)),
            state.copy(revealed = emptySet()), state.copy(revealed = setOf(24)),
            state.copy(explodedIndex = 24)).forEach { assertFalse("Should reject $it", GameEngine.isValid(it)) }
    }

    @Test fun validationRejectsInconsistentReadyAndWonSnapshots() {
        val ready = GameEngine.newGame(config)
        assertFalse(GameEngine.isValid(ready.copy(elapsedMillis = 1)))
        assertFalse(GameEngine.isValid(ready.copy(mines = setOf(24))))
        assertFalse(GameEngine.isValid(ready.copy(revealed = setOf(0))))
        val won = playing(revealed = (0..23).toSet(), flags = setOf(24)).copy(phase = GamePhase.WON)
        assertTrue(GameEngine.isValid(won))
        assertFalse(GameEngine.isValid(won.copy(flags = emptySet())))
        assertFalse(GameEngine.isValid(won.copy(revealed = (0..22).toSet())))
        assertFalse(GameEngine.isValid(won.copy(phase = GamePhase.PLAYING)))
    }

    @Test fun validationRejectsInconsistentLostSnapshots() {
        val lost = GameEngine.reveal(playing(), 24)
        assertFalse(GameEngine.isValid(lost.copy(explodedIndex = null)))
        assertFalse(GameEngine.isValid(lost.copy(explodedIndex = 18)))
        assertFalse(GameEngine.isValid(lost.copy(revealed = setOf(18))))
        val multiple = lost.copy(config = config.copy(mineCount = 2), mines = setOf(23, 24),
            revealed = setOf(18, 23, 24))
        assertFalse(GameEngine.isValid(multiple))
    }

    @Test fun largestSparseBoardFloodCompletesWithoutRecursiveStack() {
        val board = GameConfig(30, 30, 1)
        val state = GameState(id = "large", config = board, seed = 12,
            mines = setOf(899), revealed = setOf(868), phase = GamePhase.PLAYING)
        val result = GameEngine.reveal(state, 0)
        assertEquals(GamePhase.WON, result.phase)
        assertEquals(899, result.revealed.size)
        assertTrue(GameEngine.isValid(result))
    }
}
