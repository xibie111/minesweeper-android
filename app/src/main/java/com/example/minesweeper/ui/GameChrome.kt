package com.example.minesweeper.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.minesweeper.R
import com.example.minesweeper.core.GamePhase
import com.example.minesweeper.data.AppSettings
import com.example.minesweeper.data.ThemeChoice
import java.util.Locale

/** Control chrome may scroll independently, so short windows always retain a board viewport. */
@Composable
internal fun GameWorkspace(
    state: MinesweeperUiState,
    dark: Boolean,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onDifficulty: () -> Unit,
    onRestart: () -> Unit,
    onFlagMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxSize().testTag("game-workspace")) {
        val wide = maxWidth >= 480.dp && maxWidth > maxHeight
        // Measure the reserved height after the workspace's top and bottom gutters.
        val contentHeight = (maxHeight - 24.dp).coerceAtLeast(0.dp)
        val compact = contentHeight < 440.dp || fontScale >= 1.5f
        if (wide) {
            val sidebarWidth = (maxWidth * .32f).coerceIn(164.dp, 224.dp)
            Row(
                Modifier.fillMaxSize().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GameControlPanel(
                    state = state,
                    dark = dark,
                    compact = compact,
                    sidebar = true,
                    onDifficulty = onDifficulty,
                    onRestart = onRestart,
                    onFlagMode = onFlagMode,
                    modifier = Modifier.width(sidebarWidth).fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                )
                MineBoard(
                    state.game, dark, onTap, onLongPress,
                    Modifier.weight(1f).fillMaxHeight(), state.flagMode,
                )
            }
        } else {
            val controlHeight = (contentHeight * .48f).coerceAtMost(260.dp)
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                GameControlPanel(
                    state = state,
                    dark = dark,
                    compact = compact,
                    sidebar = false,
                    onDifficulty = onDifficulty,
                    onRestart = onRestart,
                    onFlagMode = onFlagMode,
                    modifier = Modifier.fillMaxWidth().heightIn(max = controlHeight)
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(10.dp))
                MineBoard(
                    state.game, dark, onTap, onLongPress,
                    Modifier.fillMaxWidth().weight(1f), state.flagMode,
                )
            }
        }
    }
}

@Composable
private fun GameControlPanel(
    state: MinesweeperUiState,
    dark: Boolean,
    compact: Boolean,
    sidebar: Boolean,
    onDifficulty: () -> Unit,
    onRestart: () -> Unit,
    onFlagMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onDifficulty,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .testTag("difficulty-control")
                    .semantics {
                        contentDescription = "选择难度：${state.game.config.displayName}，" +
                            "${state.game.config.cols} 列，${state.game.config.rows} 行，" +
                            "${state.game.config.mineCount} 个地雷"
                    },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = if (compact) 4.dp else 8.dp),
            ) {
                Text(
                    state.game.config.displayName,
                    modifier = Modifier.weight(1f).testTag("difficulty-title"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                )
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp))
            }
            Spacer(Modifier.width(4.dp))
            FilledTonalIconButton(
                onClick = onRestart,
                modifier = Modifier.size(48.dp).testTag("restart-control"),
            ) {
                Icon(Icons.Default.RestartAlt, "重新开始")
            }
        }

        // On short windows, the two primary actions stay above the scroll fold.
        if (compact) ModeChoices(state.flagMode, dark, onFlagMode)
        StatisticsCard(state, compact, preferTwoColumns = compact && !sidebar)
        if (!compact) ModeChoices(state.flagMode, dark, onFlagMode)

        if (!compact) {
            val safeTotal = state.game.config.rows * state.game.config.cols - state.game.config.mineCount
            // A losing reveal includes the exploded mine; progress describes safe cells only.
            val safeRevealed = state.game.revealed.count { it !in state.game.mines }.coerceIn(0, safeTotal)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("安全区域", style = MaterialTheme.typography.labelMedium)
                    Text("$safeRevealed / $safeTotal", style = MaterialTheme.typography.labelMedium)
                }
                key(state.game.id) {
                    val progress by animateFloatAsState(
                        targetValue = safeRevealed.toFloat() / safeTotal.coerceAtLeast(1),
                        animationSpec = tween(180),
                        label = "safe-area-progress",
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        trackColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                }
                Text(
                    when (state.game.phase) {
                        GamePhase.READY -> "首次揭格及周围格安全"
                        GamePhase.PLAYING -> "观察数字，慢慢探索"
                        GamePhase.WON -> "岛屿已安全，所有安全格已揭开"
                        GamePhase.LOST -> "探索已结束，可查看地雷与错误旗帜"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun StatisticsCard(state: MinesweeperUiState, compact: Boolean, preferTwoColumns: Boolean) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .7f),
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(if (compact) 6.dp else 12.dp)) {
            val stackValues = if (preferTwoColumns) maxWidth < 220.dp
                else maxWidth < 250.dp || LocalDensity.current.fontScale >= 1.5f
            if (stackValues) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatisticTile("剩余雷数", state.game.remainingMines.toString(), compact, true)
                    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = .15f))
                    StatisticTile("用时", formatTime(state.game.elapsedMillis), compact, false)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                    StatisticTile("剩余雷数", state.game.remainingMines.toString(), compact, true, Modifier.weight(1f))
                    StatisticTile("用时", formatTime(state.game.elapsedMillis), compact, false, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatisticTile(title: String, value: String, compact: Boolean, mine: Boolean, modifier: Modifier = Modifier) {
    if (compact) {
        // The compact row keeps the counters visible without overriding the user's font scale.
        Row(
            modifier.clearAndSetSemantics { contentDescription = "$title：$value" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (mine) Image(painterResource(R.drawable.board_mine_v2), null, Modifier.size(16.dp))
            else Icon(Icons.Default.Timer, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                value,
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        return
    }
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (mine) Image(painterResource(R.drawable.board_mine_v2), null, Modifier.size(20.dp))
            else Icon(Icons.Default.Timer, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun ModeChoices(flagMode: Boolean, dark: Boolean, onFlagMode: (Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 220.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(selected = !flagMode, flag = false, dark = dark,
                    onClick = { onFlagMode(false) }, modifier = Modifier.fillMaxWidth())
                ModeChip(selected = flagMode, flag = true, dark = dark,
                    onClick = { onFlagMode(true) }, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(selected = !flagMode, flag = false, dark = dark,
                    onClick = { onFlagMode(false) }, modifier = Modifier.weight(1f))
                ModeChip(selected = flagMode, flag = true, dark = dark,
                    onClick = { onFlagMode(true) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModeChip(selected: Boolean, flag: Boolean, dark: Boolean, onClick: () -> Unit, modifier: Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).testTag(if (flag) "mode-flag" else "mode-reveal")
            .semantics { contentDescription = if (flag) "插旗模式" else "揭格模式" },
        label = { Text(if (flag) "插旗" else "揭格", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) },
        leadingIcon = {
            if (flag) Image(painterResource(R.drawable.board_flag_v2), null, Modifier.size(20.dp))
            else Icon(Icons.Default.TouchApp, null, Modifier.size(18.dp))
        },
        colors = if (flag) FilterChipDefaults.filterChipColors(
            selectedContainerColor = if (dark) Color(0xFF60482D) else Color(0xFFF9E4C2),
            selectedLabelColor = if (dark) Color(0xFFFFDDA4) else Color(0xFF69451A),
        ) else FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/** Each setting owns one labeled accessibility action; decorative controls do not duplicate it. */
@Composable
internal fun AppearanceSettings(
    settings: AppSettings,
    onTheme: (ThemeChoice) -> Unit,
    onHaptics: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("外观", fontWeight = FontWeight.SemiBold)
        ThemeChoice.entries.forEach { choice ->
            val selected = settings.theme == choice
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .testTag("theme-${choice.name.lowercase(Locale.ROOT)}")
                    .semantics(mergeDescendants = true) {}
                    .selectable(selected = selected, onClick = { onTheme(choice) }, role = Role.RadioButton)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RadioButton(selected = selected, onClick = null)
                Text(
                    when (choice) {
                        ThemeChoice.SYSTEM -> "跟随系统"
                        ThemeChoice.LIGHT -> "浅色"
                        ThemeChoice.DARK -> "深色"
                    },
                    Modifier.weight(1f),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (selected) Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clip(RoundedCornerShape(16.dp))
                .testTag("haptics-setting")
                .semantics(mergeDescendants = true) {}
                .toggleable(value = settings.haptics, role = Role.Switch, onValueChange = onHaptics)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("振动反馈", Modifier.weight(1f))
            Switch(checked = settings.haptics, onCheckedChange = null)
        }
    }
}
