package com.example.minesweeper.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.minesweeper.core.GameConfig
import com.example.minesweeper.core.GameEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Real touch injection tests: semantics actions would bypass the board's gesture recognizer. */
@RunWith(AndroidJUnit4::class)
class BoardGestureTest {
    @get:Rule
    val compose = createComposeRule()

    private var game by mutableStateOf(
        GameEngine.newGame(GameConfig.BEGINNER, seed = 42L, id = "gesture-board"),
    )
    private val taps = mutableListOf<Int>()
    private val longPresses = mutableListOf<Int>()
    private val observedLongPressCount = AtomicInteger()
    private var compactBoard by mutableStateOf(false)

    @Before
    fun mountBoard() {
        compose.setContent {
            MaterialTheme {
                MineBoard(
                    game = game,
                    dark = false,
                    onTap = { taps.add(it) },
                    onLongPress = {
                        longPresses.add(it)
                        observedLongPressCount.incrementAndGet()
                    },
                    modifier = if (compactBoard) Modifier.size(width = 280.dp, height = 196.dp) else Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag("cell-40").assertIsDisplayed()
    }

    @Test
    fun singleTapCallsExactlyOneCellCallback() {
        compose.onNodeWithTag("cell-40").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(40), taps)
            assertTrue(longPresses.isEmpty())
        }
    }

    @Test
    fun longPressDoesNotFallThroughToTapOnRelease() {
        compose.onNodeWithTag("cell-40").performTouchInput { down(center) }
        // Allow both virtual-clock and platform coroutine delays to finish before releasing.
        compose.mainClock.advanceTimeBy(800L)
        compose.waitUntil(timeoutMillis = 3_000L) { observedLongPressCount.get() == 1 }
        compose.onNodeWithTag("cell-40").performTouchInput {
            advanceEventTime(800L)
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf(40), longPresses)
            assertTrue(taps.isEmpty())
        }
    }

    @Test
    fun oneFingerDragCancelsBothTapAndLongPress() {
        compose.onNodeWithTag("cell-40").performTouchInput {
            down(center)
            moveTo(center + Offset(100f, 45f), delayMillis = 80L)
            up()
        }
        assertNoBoardActions()
    }

    @Test
    fun releaseWithoutMoveEventStillCancelsTapWhenPastTouchSlopWithinSameCell() {
        compose.onNodeWithTag("cell-40").performTouchInput {
            down(Offset(width * 0.15f, center.y))
            // Update the next event's coordinate without dispatching ACTION_MOVE.
            // The subsequent ACTION_UP remains in this cell but crosses the drag threshold.
            updatePointerTo(0, Offset(width * 0.85f, center.y))
            up()
        }
        assertNoBoardActions()
    }

    @Test
    fun twoFingerPinchCancelsTapAndLongPress() {
        compose.onNodeWithTag("mine-board").performTouchInput {
            pinch(
                start0 = center - Offset(45f, 0f),
                start1 = center + Offset(45f, 0f),
                end0 = center - Offset(100f, 0f),
                end1 = center + Offset(100f, 0f),
                durationMillis = 240L,
            )
        }
        assertNoBoardActions()
    }

    @Test
    fun cancelledTouchCannotRevealOrFlag() {
        compose.onNodeWithTag("cell-40").performTouchInput {
            down(center)
            cancel()
        }
        assertNoBoardActions()
    }

    @Test
    fun touchCoordinatesStillHitCorrectCellAfterZoom() {
        val originalWidth = compose.onNodeWithTag("cell-41").fetchSemanticsNode().boundsInRoot.width
        repeat(2) {
            compose.onNodeWithContentDescription("放大棋盘").performTouchInput { click() }
        }
        val enlargedWidth = compose.onNodeWithTag("cell-41").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Cell bounds should grow after zoom", enlargedWidth > originalWidth)
        compose.onNodeWithTag("cell-41").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(41), taps)
            assertTrue(longPresses.isEmpty())
        }
    }

    @Test
    fun overlayZoomControlsDoNotPassTheirClicksToBoard() {
        compose.onNodeWithContentDescription("放大棋盘").performTouchInput { click() }
        compose.onNodeWithContentDescription("缩小棋盘").performTouchInput { click() }
        compose.onNodeWithContentDescription("棋盘居中").performTouchInput { click() }
        assertNoBoardActions()
    }

    @Test
    fun zoomToolbarOccupiesSeparateSpaceBelowTouchBoard() {
        val board = compose.onNodeWithTag("mine-board").fetchSemanticsNode().boundsInRoot
        val toolbar = compose.onNodeWithTag("board-tools").fetchSemanticsNode().boundsInRoot
        assertTrue("Toolbar must sit outside the touch board", toolbar.top >= board.bottom)
        listOf("缩小棋盘", "棋盘居中", "放大棋盘").forEach { description ->
            val control = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
            assertTrue("$description overlaps the touch board", control.top >= board.bottom)
        }
    }

    @Test
    fun accessibilityPanReachesEveryRowWithClippedCellsInShortZoomedViewport() {
        compose.runOnIdle { compactBoard = true }
        repeat(5) { compose.onNodeWithContentDescription("放大棋盘").performClick() }
        moveBoardToBoundary("向右移动棋盘")
        moveBoardToBoundary("向下移动棋盘")

        val board = compose.onNodeWithTag("mine-board").fetchSemanticsNode().boundsInRoot
        val firstCell = compose.onNodeWithTag("cell-0").fetchSemanticsNode().boundsInRoot
        val partialCell = compose.onNodeWithTag("cell-9").fetchSemanticsNode().boundsInRoot
        assertTrue("Second row must retain its partially visible node", partialCell.height > 0f && partialCell.height < firstCell.height)
        assertTrue("Partially visible node must stay in the board", partialCell.top >= board.top && partialCell.bottom <= board.bottom)

        val visitedRows = mutableSetOf<Int>()
        var moved = true
        var steps = 0
        while (moved && steps++ < 40) {
            for (row in 0 until game.config.rows) {
                val nodes = compose.onAllNodesWithTag("cell-${row * game.config.cols}").fetchSemanticsNodes()
                nodes.forEach { node ->
                    val bounds = node.boundsInRoot
                    assertTrue("Row $row node extends outside the board", bounds.left >= board.left && bounds.right <= board.right && bounds.top >= board.top && bounds.bottom <= board.bottom)
                    visitedRows.add(row)
                }
            }
            moved = moveBoard("向上移动棋盘")
        }
        assertTrue("Pan must finish at the board boundary", !moved)
        assertEquals("No row may be skipped by accessibility panning", (0 until game.config.rows).toSet(), visitedRows)
        assertNoBoardActions()
    }

    @Test
    fun finishedGameDisablesTapLongPressAndAccessibilityClick() {
        compose.runOnIdle {
            val started = GameEngine.reveal(game, 40)
            game = GameEngine.reveal(started, started.mines.first())
            assertTrue(game.isFinished)
        }
        compose.onNodeWithTag("cell-40").assertHasNoClickAction()
        compose.onNodeWithTag("cell-40").performTouchInput { click() }
        compose.onNodeWithTag("cell-40").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(800L)
        compose.onNodeWithTag("cell-40").performTouchInput {
            advanceEventTime(800L)
            up()
        }
        assertNoBoardActions()
    }

    private fun assertNoBoardActions() {
        compose.runOnIdle {
            assertTrue("Unexpected reveal callback: $taps", taps.isEmpty())
            assertTrue("Unexpected flag callback: $longPresses", longPresses.isEmpty())
        }
    }

    private fun moveBoard(label: String): Boolean {
        val action = compose.onNodeWithTag("mine-board").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == label }
        var moved = false
        compose.runOnIdle { moved = action.action() }
        return moved
    }

    private fun moveBoardToBoundary(label: String) {
        var moved = true
        var steps = 0
        while (moved && steps++ < 40) moved = moveBoard(label)
        assertTrue("$label must finish at the board boundary", !moved)
    }
}
