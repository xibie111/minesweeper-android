package com.example.minesweeper.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.minesweeper.core.GameConfig
import com.example.minesweeper.core.GameEngine
import com.example.minesweeper.data.AppSettings

/** Android Studio renders the actual stateless UI with sample data, without storage or timers. */
@Preview(name = "浅色 · 手机", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun LightPhonePreview() = WorkspacePreview(dark = false)

@Preview(name = "深色 · 横屏", widthDp = 720, heightDp = 360, showBackground = true)
@Composable
private fun DarkLandscapePreview() = WorkspacePreview(dark = true)

@Preview(name = "小屏 · 大字号", widthDp = 320, heightDp = 360, fontScale = 1.8f, showBackground = true)
@Composable
private fun LargeFontPreview() = WorkspacePreview(dark = false)

@Composable
private fun WorkspacePreview(dark: Boolean) {
    val revealed = GameEngine.reveal(GameEngine.newGame(GameConfig.BEGINNER, seed = 42L, id = "preview"), 40)
    val flagged = GameEngine.toggleFlag(revealed, revealed.mines.first()).copy(elapsedMillis = 142_000L)
    IslandTheme(dark) {
        GameWorkspace(
            state = MinesweeperUiState(flagged, AppSettings(), emptyList(), flagMode = true),
            dark = dark, onTap = {}, onLongPress = {}, onDifficulty = {}, onRestart = {}, onFlagMode = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}
