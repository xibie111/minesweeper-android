package com.example.minesweeper.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.minesweeper.core.GameConfig
import com.example.minesweeper.core.GameEngine
import com.example.minesweeper.data.AppSettings
import com.example.minesweeper.data.ThemeChoice
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Layout and settings are mounted directly: no ViewModel, timer or persistence is involved. */
@RunWith(AndroidJUnit4::class)
class GameLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun narrowShortWindowWithLargeFontRetainsUsableBoardViewport() {
        var pixelsPerDp = 1f
        compose.setContent {
            pixelsPerDp = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(pixelsPerDp, fontScale = 1.8f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, 360.dp)) {
                        GameWorkspace(
                            state = initialState(), dark = false,
                            onTap = {}, onLongPress = {}, onDifficulty = {}, onRestart = {}, onFlagMode = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        val board = compose.onNodeWithTag("mine-board").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Controls must reserve a usable board height", board.height >= 100f * pixelsPerDp)
        assertTrue("Board must retain the narrow window's width", board.width >= 280f * pixelsPerDp)
        compose.onNodeWithTag("mode-reveal").assertIsDisplayed()
        compose.onNodeWithTag("mode-flag").assertIsDisplayed()
        compose.onNodeWithContentDescription("剩余雷数：10").assertIsDisplayed()
        compose.onNodeWithContentDescription("用时：00:00").assertIsDisplayed()
    }

    @Test
    fun narrowLandscapeWindowPlacesScrollableSidebarBesideBoard() {
        var pixelsPerDp = 1f
        compose.setContent {
            // Keep this synthetic landscape viewport inside a portrait test host as well.
            pixelsPerDp = LocalDensity.current.density * .6f
            CompositionLocalProvider(LocalDensity provides Density(pixelsPerDp, fontScale = 1.8f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(480.dp, 280.dp)) {
                        GameWorkspace(
                            state = initialState(), dark = false,
                            onTap = {}, onLongPress = {}, onDifficulty = {}, onRestart = {}, onFlagMode = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        val board = compose.onNodeWithTag("mine-board").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val difficulty = compose.onNodeWithTag("difficulty-control").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val reveal = compose.onNodeWithTag("mode-reveal").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val flag = compose.onNodeWithTag("mode-flag").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Narrow landscape controls must remain to the board's left", difficulty.right < board.left)
        assertTrue("The board must retain landscape height", board.height >= 180f * pixelsPerDp)
        assertTrue("Narrow sidebar mode options must occupy separate full-width rows", flag.top >= reveal.bottom)
        assertTrue("Sidebar mode labels must retain usable width", reveal.width >= 140f * pixelsPerDp)
    }

    @Test
    fun longCustomDifficultyTitleCannotOverlapRestartAtLargeFont() {
        val config = GameConfig(rows = 30, cols = 30, mineCount = 891)
        compose.setContent {
            val currentDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(currentDensity, fontScale = 1.8f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, 440.dp)) {
                        GameWorkspace(
                            state = initialState(config), dark = false,
                            onTap = {}, onLongPress = {}, onDifficulty = {}, onRestart = {}, onFlagMode = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        val title = compose.onNodeWithTag("difficulty-title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val restart = compose.onNodeWithTag("restart-control").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Difficulty text must stay inside its allocated column", title.right <= restart.left)
        compose.onNodeWithTag("difficulty-control").assertContentDescriptionEquals(
            "选择难度：${config.displayName}，30 列，30 行，891 个地雷",
        )
        compose.onNodeWithTag("restart-control").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun settingsExposeOneLabeledActionForEachThemeAndHaptics() {
        var settings by mutableStateOf(AppSettings())
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(320.dp, 500.dp)) {
                    AppearanceSettings(
                        settings = settings,
                        onTheme = { settings = settings.copy(theme = it) },
                        onHaptics = { settings = settings.copy(haptics = it) },
                    )
                }
            }
        }
        // The radio circles and switch itself are decorative: the four rows own all actions.
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)
            .assertCountEquals(4)
        compose.onNodeWithTag("theme-system").assertTextContains("跟随系统")
            .assertIsSelected().assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        compose.onNodeWithTag("theme-light").assertTextContains("浅色")
        compose.onNodeWithTag("theme-dark").assertTextContains("深色").performClick()
        compose.onNodeWithTag("theme-dark").assertIsSelected()
        compose.onNodeWithTag("haptics-setting").assertTextContains("振动反馈")
            .assertIsOn().assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .performClick()
        compose.onNodeWithTag("haptics-setting").assertIsOff()
    }

    @Test
    fun revealAndFlagOptionsExposeSelectionAndMinimumTouchHeight() {
        var state by mutableStateOf(initialState())
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(320.dp, 500.dp)) {
                    GameWorkspace(
                        state = state, dark = false,
                        onTap = {}, onLongPress = {}, onDifficulty = {}, onRestart = {},
                        onFlagMode = { state = state.copy(flagMode = it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.onNodeWithTag("mode-reveal").assertIsSelected().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("mode-flag").assertIsNotSelected().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("mode-flag").assertIsSelected()
        compose.onNodeWithTag("mode-reveal").assertIsNotSelected()
    }

    private fun initialState(config: GameConfig = GameConfig.BEGINNER) = MinesweeperUiState(
        game = GameEngine.newGame(config, seed = 42L, id = "layout-board"),
        settings = AppSettings(),
        records = emptyList(),
    )
}
