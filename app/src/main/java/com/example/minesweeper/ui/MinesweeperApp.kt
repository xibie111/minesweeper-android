package com.example.minesweeper.ui

import android.app.Activity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import com.example.minesweeper.core.GameConfig
import com.example.minesweeper.core.GamePhase
import com.example.minesweeper.data.ThemeChoice
import com.example.minesweeper.R
import java.util.Locale

private val LightPalette = lightColorScheme(
    primary = Color(0xFF147D65), onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F2E5), onPrimaryContainer = Color(0xFF09543E),
    background = Color(0xFFF5F7F2), surface = Color(0xFFF5F7F2),
    surfaceContainer = Color(0xFFE9EFE6), secondary = Color(0xFF50675B),
)
private val DarkPalette = darkColorScheme(
    primary = Color(0xFF8BD6B5), onPrimary = Color(0xFF083B2D),
    primaryContainer = Color(0xFF235C47), onPrimaryContainer = Color(0xFFB8F1D3),
    background = Color(0xFF131C17), surface = Color(0xFF131C17),
    surfaceContainer = Color(0xFF233129), secondary = Color(0xFFB4CBBB),
)

fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinesweeperApp(model: GameViewModel) {
    val state by model.uiState.collectAsStateWithLifecycle()
    val dark = when (state.settings.theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.setForeground(true)
                Lifecycle.Event.ON_STOP -> model.setForeground(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        model.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); model.setForeground(false) }
    }

    var showDifficulty by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showStatistics by rememberSaveable { mutableStateOf(false) }
    var clearConfirmation by rememberSaveable { mutableStateOf(false) }
    var dismissedResultId by rememberSaveable { mutableStateOf("") }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var pendingConfigKey by rememberSaveable { mutableStateOf("") }
    val pendingConfig = remember(pendingConfigKey) {
        pendingConfigKey.split('x').mapNotNull(String::toIntOrNull).takeIf { it.size == 3 }
            ?.let { GameConfig(it[0], it[1], it[2]) }?.takeIf { it.validationError() == null }
    }
    fun requestGame(config: GameConfig) {
        if (state.game.phase == GamePhase.PLAYING) pendingConfigKey = config.key
        else model.startNew(config)
    }
    val haptic = LocalHapticFeedback.current
    val tap: (Int) -> Unit = { index ->
        if (state.settings.haptics && state.flagMode && !state.game.isFinished &&
            index !in state.game.revealed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        model.tapCell(index)
    }
    val longPress: (Int) -> Unit = { index ->
        if (state.settings.haptics && !state.game.isFinished && index !in state.game.revealed)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        model.longPressCell(index)
    }
    LaunchedEffect(state.game.id, state.game.phase) {
        if (state.game.isFinished && state.settings.haptics)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    IslandTheme(dark) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Image(painterResource(R.drawable.icon_foreground_v2), null,
                                Modifier.size(32.dp).graphicsLayer(scaleX = 1.45f, scaleY = 1.45f))
                            Text("小岛扫雷", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showHelp = true }) {
                            Icon(Icons.AutoMirrored.Filled.HelpOutline, "玩法帮助")
                        }
                        IconButton(onClick = { showStatistics = true }) {
                            Icon(Icons.Default.BarChart, "查看统计")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Tune, "打开设置")
                        }
                    },
                )
            },
        ) { padding ->
            GameWorkspace(
                state = state, dark = dark, onTap = tap, onLongPress = longPress,
                onDifficulty = { showDifficulty = true },
                onRestart = { requestGame(state.game.config) }, onFlagMode = model::setFlagMode,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }

        if (showDifficulty) DifficultyDialog(
            current = state.game.config,
            onDismiss = { showDifficulty = false },
            onChoose = { config -> showDifficulty = false; requestGame(config) },
        )
        pendingConfig?.let { config ->
            AlertDialog(
                onDismissRequest = { pendingConfigKey = "" },
                title = { Text("开始新对局？") },
                text = { Text("当前对局正在进行，开始新对局后会替换当前存档。") },
                confirmButton = { TextButton(onClick = { pendingConfigKey = ""; model.startNew(config) }) { Text("开始新局") } },
                dismissButton = { TextButton(onClick = { pendingConfigKey = "" }) { Text("继续当前对局") } },
            )
        }
        if (showSettings) AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("设置") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AppearanceSettings(state.settings, model::setTheme, model::setHaptics)
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("完成") } },
        )
        if (showStatistics) AlertDialog(
            onDismissRequest = { showStatistics = false },
            title = { Text("我的成绩") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.records.isEmpty()) Text("完成第一局后，成绩就会出现在这里。")
                    state.records.forEach { record ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(record.name, fontWeight = FontWeight.SemiBold)
                                Text("完成 ${record.completed} 局 · 胜利 ${record.wins} 局 · 胜率 ${if (record.completed == 0) 0 else record.wins.toLong() * 100 / record.completed}%")
                                Text("最佳用时 ${record.bestMillis?.let(::formatTime) ?: "—"}", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showStatistics = false }) { Text("完成") } },
            dismissButton = { TextButton(onClick = { clearConfirmation = true }, enabled = state.records.isNotEmpty()) { Text("清空统计") } },
        )
        if (clearConfirmation) AlertDialog(
            onDismissRequest = { clearConfirmation = false },
            title = { Text("清空所有统计？") },
            text = { Text("历史成绩和最佳纪录将被删除，当前棋盘会保留。") },
            confirmButton = { TextButton(onClick = { model.clearRecords(); clearConfirmation = false }) { Text("清空") } },
            dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("取消") } },
        )
        if (state.game.isFinished && dismissedResultId != state.game.id) {
            val won = state.game.phase == GamePhase.WON
            val best = state.records.firstOrNull { it.key == state.game.config.key }?.bestMillis
            val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
            val artSize = if (windowHeight < 480.dp || LocalDensity.current.fontScale > 1.3f) 48.dp else 96.dp
            AlertDialog(
                onDismissRequest = { dismissedResultId = state.game.id },
                icon = {
                    ResultArtwork(won, artSize)
                },
                title = { Text(if (won) "岛屿已安全！" else "碰到地雷了") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${state.game.config.displayName} · 用时 ${formatTime(state.game.elapsedMillis)}")
                        if (won && best == state.game.elapsedMillis) Text("本次达到个人最佳纪录", color = MaterialTheme.colorScheme.primary)
                        if (!won) Text("可以查看地雷和错误旗帜，再试一局。")
                    }
                },
                confirmButton = { TextButton(onClick = { dismissedResultId = state.game.id; model.startNew(state.game.config) }) { Text("再来一局") } },
                dismissButton = { TextButton(onClick = { dismissedResultId = state.game.id }) { Text("查看棋盘") } },
            )
        }
        if (showHelp) HelpDialog { showHelp = false }
        state.notice?.let { notice ->
            AlertDialog(
                onDismissRequest = model::dismissNotice,
                title = { Text("存档提示") }, text = { Text(notice) },
                confirmButton = { TextButton(onClick = model::dismissNotice) { Text("知道了") } },
            )
        }
    }
}

@Composable
internal fun IslandTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkPalette else LightPalette,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        content = content,
    )
}

@Composable
private fun ResultArtwork(won: Boolean, size: Dp) {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(260)) }
    Image(
        painterResource(if (won) R.drawable.result_victory_v2 else R.drawable.result_defeat_v2),
        contentDescription = null,
        modifier = Modifier.size(size).graphicsLayer {
            alpha = .65f + .35f * entrance.value
            scaleX = .88f + .12f * entrance.value
            scaleY = scaleX
        },
    )
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("玩法帮助") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(
                    "点击揭格" to "点一下未揭开的格子。首击及周围格安全，之后需要根据数字判断。",
                    "长按插旗" to "长按可插旗或取消；也可以选中插旗模式后直接点击。",
                    "数字展开" to "在揭格模式下，数字周围旗帜数匹配时点击数字，会展开未插旗的邻格。插错旗可能踩雷。",
                    "移动与缩放" to "单指拖动，双指缩放；棋盘下方可缩小、居中或放大。无障碍用户可使用棋盘的方向移动动作。",
                ).forEach { (title, description) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider()
                Text("揭开全部安全格即可获胜。退到后台时暂停计时，对局会自动保存。随机棋盘可能需要猜测。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("开始探索") } },
    )
}

@Composable
private fun DifficultyDialog(current: GameConfig, onDismiss: () -> Unit, onChoose: (GameConfig) -> Unit) {
    var rows by rememberSaveable { mutableStateOf(current.rows.toString()) }
    var cols by rememberSaveable { mutableStateOf(current.cols.toString()) }
    var mines by rememberSaveable { mutableStateOf(current.mineCount.toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择难度") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(GameConfig.BEGINNER, GameConfig.INTERMEDIATE, GameConfig.EXPERT).forEach { config ->
                    OutlinedButton(onClick = { onChoose(config) }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (current == config)
                            MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(config.displayName, fontWeight = FontWeight.SemiBold)
                            Text("${config.cols}×${config.rows} · ${config.mineCount} 雷", style = MaterialTheme.typography.bodySmall)
                        }
                        if (current == config) Icon(Icons.Default.CheckCircle, null, Modifier.size(20.dp))
                    }
                }
                HorizontalDivider()
                Text("自定义棋盘", fontWeight = FontWeight.SemiBold)
                if (LocalDensity.current.fontScale > 1.3f) {
                    OutlinedTextField(rows, { rows = it.take(3); error = null }, Modifier.fillMaxWidth(), label = { Text("行数") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(cols, { cols = it.take(3); error = null }, Modifier.fillMaxWidth(), label = { Text("列数") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(rows, { rows = it.take(3); error = null }, Modifier.weight(1f), label = { Text("行数") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(cols, { cols = it.take(3); error = null }, Modifier.weight(1f), label = { Text("列数") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
                OutlinedTextField(mines, { mines = it.take(4); error = null }, label = { Text("雷数") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Text(error ?: "行列 5～30；雷数最多为总格数减 9。", color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val config = GameConfig(rows.toIntOrNull() ?: 0, cols.toIntOrNull() ?: 0, mines.toIntOrNull() ?: 0)
                error = config.validationError()
                if (error == null) onChoose(config)
            }) { Text("开始自定义") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
