package com.example.minesweeper.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.minesweeper.core.GamePhase
import com.example.minesweeper.core.GameState
import com.example.minesweeper.R
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private fun boundedOffset(position: Offset, viewport: IntSize, width: Float, height: Float): Offset {
    fun axis(value: Float, available: Int, content: Float): Float =
        if (content <= available) (available - content) / 2f
        else value.coerceIn(available - content - 12f, 12f)
    return Offset(axis(position.x, viewport.width, width), axis(position.y, viewport.height, height))
}

@Composable
private fun rememberBoardImage(@DrawableRes resource: Int): ImageBitmap {
    val resources = LocalResources.current
    return remember(resources, resource) {
        // Assets retain their original PNG alpha. Decode once at half resolution,
        // sufficient for zoomed grid markers without allocating full artwork per cell.
        BitmapFactory.decodeResource(resources, resource, BitmapFactory.Options().apply {
            inSampleSize = 2
            inScaled = false
        }).asImageBitmap()
    }
}

private fun DrawScope.drawBoardImage(image: ImageBitmap, center: Offset, edge: Float) {
    val ratio = edge / max(image.width, image.height)
    val width = (image.width * ratio).roundToInt().coerceAtLeast(1)
    val height = (image.height * ratio).roundToInt().coerceAtLeast(1)
    drawImage(
        image = image,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset((center.x - width / 2f).roundToInt(), (center.y - height / 2f).roundToInt()),
        dstSize = IntSize(width, height),
        filterQuality = FilterQuality.Medium,
    )
}

/** One gesture recognizer owns the board; taps never race long presses, drags or pinches. */
@Composable
fun MineBoard(
    game: GameState,
    dark: Boolean,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    modifier: Modifier = Modifier,
    flagMode: Boolean = false,
) {
    val density = LocalDensity.current
    val viewConfig = LocalViewConfiguration.current
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val cellPx = with(density) {
        ((viewportSize.width.toFloat() - 24.dp.toPx()) / game.config.cols).coerceIn(32.dp.toPx(), 44.dp.toPx())
    }
    val boardWidth = cellPx * game.config.cols
    val boardHeight = cellPx * game.config.rows
    var scale by remember(game.id, viewportSize) { mutableFloatStateOf(1f) }
    var offset by remember(game.id, viewportSize) {
        mutableStateOf(boundedOffset(Offset(12f, 12f), viewportSize, boardWidth, boardHeight))
    }
    val latestGame by rememberUpdatedState(game)
    val latestTap by rememberUpdatedState(onTap)
    val latestLongPress by rememberUpdatedState(onLongPress)
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER } }
    val flagImage = rememberBoardImage(R.drawable.board_flag_v2)
    val mineImage = rememberBoardImage(R.drawable.board_mine_v2)
    // Initial composition (including restored games) establishes a visual baseline.
    // Only newly changed cells animate; neither timing ticks nor redraws restart it.
    var previousRevealed by remember(game.id) { mutableStateOf(game.revealed) }
    var previousFlags by remember(game.id) { mutableStateOf(game.flags) }
    var revealingCells by remember(game.id) { mutableStateOf(emptySet<Int>()) }
    var poppingFlags by remember(game.id) { mutableStateOf(emptySet<Int>()) }
    val cellTransition = remember(game.id) { Animatable(1f) }
    LaunchedEffect(game.id, game.revealed, game.flags) {
        revealingCells = (game.revealed - previousRevealed) - game.mines
        poppingFlags = game.flags - previousFlags
        previousRevealed = game.revealed
        previousFlags = game.flags
        if (revealingCells.isNotEmpty() || poppingFlags.isNotEmpty()) {
            cellTransition.snapTo(0f)
            // Compose animations honor the system MotionDurationScale, including zero.
            cellTransition.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
        } else {
            cellTransition.snapTo(1f)
        }
        revealingCells = emptySet()
        poppingFlags = emptySet()
    }
    fun zoomTo(newScale: Float, center: Offset = Offset(viewportSize.width / 2f, viewportSize.height / 2f)) {
        val target = newScale.coerceIn(0.5f, 3f)
        offset = boundedOffset(center + (offset - center) * (target / scale), viewportSize, boardWidth * target, boardHeight * target)
        scale = target
    }
    fun panBy(delta: Offset): Boolean {
        if (viewportSize.width == 0 || viewportSize.height == 0) return false
        val target = boundedOffset(offset + delta, viewportSize, boardWidth * scale, boardHeight * scale)
        val moved = target != offset
        offset = target
        return moved
    }
    val hiddenColor = if (dark) Color(0xFF3D5748) else Color(0xFFDCE8DD)
    val revealedColor = if (dark) Color(0xFF1E2C23) else Color(0xFFEDF3EC)
    val numberColors = if (dark) listOf(Color.Transparent, Color(0xFF88C8FF), Color(0xFF8DE0B4), Color(0xFFFFA8A0), Color(0xFFC4ACFF), Color(0xFFFFCC88), Color(0xFF84DEDE), Color(0xFFE6E9E4), Color(0xFFB7C3BA))
        else listOf(Color.Transparent, Color(0xFF2166B2), Color(0xFF24764B), Color(0xFFB83F3A), Color(0xFF7053A0), Color(0xFF955718), Color(0xFF187B83), Color(0xFF333B37), Color(0xFF65716A))

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .onSizeChanged { viewportSize = it }
                .testTag("mine-board")
                .semantics {
                    contentDescription = "扫雷棋盘，${game.config.rows} 行，${game.config.cols} 列"
                    // Keep individual cell nodes; these actions bring offscreen cells into view.
                    customActions = listOf(
                        CustomAccessibilityAction("向左移动棋盘") { panBy(Offset(-min(viewportSize.width / 2f, cellPx * scale), 0f)) },
                        CustomAccessibilityAction("向右移动棋盘") { panBy(Offset(min(viewportSize.width / 2f, cellPx * scale), 0f)) },
                        CustomAccessibilityAction("向上移动棋盘") { panBy(Offset(0f, -min(viewportSize.height / 2f, cellPx * scale))) },
                        CustomAccessibilityAction("向下移动棋盘") { panBy(Offset(0f, min(viewportSize.height / 2f, cellPx * scale))) },
                    )
                }
                .pointerInput(game.id, viewportSize, cellPx) {
                    fun hit(position: Offset): Int? {
                        val local = (position - offset) / scale
                        val col = floor(local.x / cellPx).toInt()
                        val row = floor(local.y / cellPx).toInt()
                        return if (col in 0 until latestGame.config.cols && row in 0 until latestGame.config.rows)
                            row * latestGame.config.cols + col else null
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val cell = hit(down.position)
                        var moved = false
                        var multiple = false
                        var longPressed = false
                        var cancelled = down.isConsumed
                        var eventTime = down.uptimeMillis
                        while (true) {
                            val remaining = (viewConfig.longPressTimeoutMillis - (eventTime - down.uptimeMillis)).coerceAtLeast(1L)
                            val event = if (!moved && !multiple && !longPressed && !cancelled && cell != null && !latestGame.isFinished)
                                withTimeoutOrNull(remaining) { awaitPointerEvent() }
                            else awaitPointerEvent()
                            if (event == null) {
                                latestLongPress(cell!!)
                                longPressed = true
                                continue
                            }
                            eventTime = event.changes.maxOf { it.uptimeMillis }
                            if (event.changes.any { it.isConsumed }) cancelled = true
                            if (event.changes.size > 1) multiple = true
                            val originalPointer = event.changes.firstOrNull { it.id == down.id }
                            if (originalPointer != null &&
                                (originalPointer.position - down.position).getDistance() > viewConfig.touchSlop) moved = true
                            val allUp = event.changes.none { it.pressed }
                            if (!allUp && !cancelled) {
                                if (multiple) {
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val center = event.calculateCentroid(useCurrent = false)
                                    if (center != Offset.Unspecified) {
                                        val target = (scale * zoom).coerceIn(0.5f, 3f)
                                        offset = boundedOffset(center + (offset - center) * (target / scale) + pan, viewportSize, boardWidth * target, boardHeight * target)
                                        scale = target
                                    }
                                } else {
                                    if (moved) offset = boundedOffset(offset + event.calculatePan(), viewportSize, boardWidth * scale, boardHeight * scale)
                                }
                            }
                            if (moved || multiple || longPressed) event.changes.forEach { it.consume() }
                            if (allUp) {
                                val up = event.changes.firstOrNull { it.id == down.id }
                                if (!cancelled && !moved && !multiple && !longPressed && cell != null &&
                                    up != null && hit(up.position) == cell && !latestGame.isFinished) latestTap(cell)
                                break
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val unit = cellPx * scale
                val gap = (1.5.dp.toPx() * scale).coerceAtMost(unit * .12f)
                val corner = CornerRadius(4.dp.toPx() * scale)
                val edgeWidth = .7.dp.toPx() * scale
                val depth = 1.2.dp.toPx() * scale
                val progress = cellTransition.value
                val hiddenShadow = if (dark) Color(0xFF233C2D) else Color(0xFFB8CDBA)
                val hiddenEdge = if (dark) Color(0xFF536E5B) else Color(0xFFBCD0BD)
                val revealedEdge = if (dark) Color(0xFF304236) else Color(0xFFD8E3D7)
                val hiddenHighlight = if (dark) Color.White.copy(alpha = .14f) else Color.White.copy(alpha = .5f)
                for (index in 0 until game.config.rows * game.config.cols) {
                    val x = offset.x + (index % game.config.cols) * unit
                    val y = offset.y + (index / game.config.cols) * unit
                    if (x + unit < 0 || y + unit < 0 || x > size.width || y > size.height) continue
                    val revealed = index in game.revealed
                    val mine = index in game.mines && game.isFinished
                    val flagged = index in game.flags
                    val wrongFlag = game.phase == GamePhase.LOST && flagged && index !in game.mines
                    val exploded = index == game.explodedIndex
                    val fill = if (exploded) Color(0xFFE99689) else if (revealed || mine) {
                        if (index in revealingCells) lerp(hiddenColor, revealedColor, .4f + .6f * progress) else revealedColor
                    } else hiddenColor
                    val center = Offset(x + unit / 2, y + unit / 2)
                    val tileOrigin = Offset(x + gap / 2, y + gap / 2)
                    val tileSize = Size(unit - gap, unit - gap)
                    if (!revealed && !mine) {
                        drawRoundRect(hiddenShadow, tileOrigin, tileSize, corner)
                        val faceSize = tileSize.copy(height = tileSize.height - depth)
                        drawRoundRect(fill, tileOrigin, faceSize, corner)
                        drawRoundRect(hiddenEdge, tileOrigin, faceSize, corner, style = Stroke(edgeWidth))
                        drawLine(
                            hiddenHighlight,
                            tileOrigin + Offset(corner.x, edgeWidth),
                            tileOrigin + Offset(tileSize.width - corner.x, edgeWidth),
                            edgeWidth,
                        )
                    } else {
                        drawRoundRect(fill, tileOrigin, tileSize, corner)
                        drawRoundRect(if (exploded) Color(0xFFCE675B) else revealedEdge, tileOrigin, tileSize, corner, style = Stroke(edgeWidth))
                    }
                    if (flagged && !wrongFlag) {
                        val pop = if (index in poppingFlags) .88f + .12f * progress + .12f * sin(Math.PI.toFloat() * progress) else 1f
                        drawBoardImage(flagImage, center, unit * .86f * pop)
                    } else if (wrongFlag) {
                        drawLine(Color(0xFFD04B44), center - Offset(unit * .18f, unit * .18f), center + Offset(unit * .18f, unit * .18f), unit * .08f)
                        drawLine(Color(0xFFD04B44), center + Offset(-unit * .18f, unit * .18f), center + Offset(unit * .18f, -unit * .18f), unit * .08f)
                    } else if (mine) {
                        drawBoardImage(mineImage, center, unit * .82f)
                    } else if (revealed) {
                        val count = game.adjacentMines(index)
                        if (count > 0) {
                            paint.textSize = unit * .52f
                            paint.color = numberColors[count].toArgb()
                            drawIntoCanvas { canvas ->
                                canvas.nativeCanvas.drawText(count.toString(), center.x, center.y - (paint.ascent() + paint.descent()) / 2f, paint)
                            }
                        }
                    }
                }
            }
            // Transparent semantics nodes make a canvas board accessible without competing pointer handlers.
            for (index in 0 until game.config.rows * game.config.cols) {
                val unit = cellPx * scale
                val x = offset.x + (index % game.config.cols) * unit
                val y = offset.y + (index / game.config.cols) * unit
                // A partially visible cell still needs an accessibility action. Use only
                // its visible rectangle so focus and touch bounds remain inside the board.
                val visibleLeft = ceil(max(0f, x)).toInt().coerceIn(0, viewportSize.width)
                val visibleTop = ceil(max(0f, y)).toInt().coerceIn(0, viewportSize.height)
                val visibleRight = floor(min(viewportSize.width.toFloat(), x + unit)).toInt().coerceIn(0, viewportSize.width)
                val visibleBottom = floor(min(viewportSize.height.toFloat(), y + unit)).toInt().coerceIn(0, viewportSize.height)
                if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) continue
                val description = when {
                    game.phase == GamePhase.LOST && index in game.flags && index !in game.mines -> "错误旗帜"
                    index in game.flags -> "旗帜"
                    game.isFinished && index in game.mines -> "地雷"
                    index in game.revealed -> if (game.adjacentMines(index) == 0) "空格" else "数字 ${game.adjacentMines(index)}"
                    else -> "未揭开"
                }
                Box(
                    Modifier.absoluteOffset { IntOffset(visibleLeft, visibleTop) }
                        .size(
                            with(density) { (visibleRight - visibleLeft).toDp() },
                            with(density) { (visibleBottom - visibleTop).toDp() },
                        )
                        .testTag("cell-$index")
                        .semantics {
                            contentDescription = "第 ${index / game.config.cols + 1} 行，第 ${index % game.config.cols + 1} 列"
                            stateDescription = description
                            if (!game.isFinished) {
                                role = Role.Button
                                onClick(if (flagMode) "插旗或取消旗帜" else "揭开或展开格子") { onTap(index); true }
                                onLongClick("插旗或取消旗帜") { onLongPress(index); true }
                            }
                        },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().testTag("board-tools")
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${(scale * 100).roundToInt()}%",
                Modifier.weight(1f).padding(start = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FilledTonalIconButton(onClick = { zoomTo(scale / 1.25f) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.ZoomOut, "缩小棋盘") }
            FilledTonalIconButton(onClick = {
                val fit = min(viewportSize.width / boardWidth, viewportSize.height / boardHeight).coerceIn(0.5f, 1f)
                scale = fit
                offset = boundedOffset(Offset.Zero, viewportSize, boardWidth * fit, boardHeight * fit)
            }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.CenterFocusStrong, "棋盘居中") }
            FilledTonalIconButton(onClick = { zoomTo(scale * 1.25f) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.ZoomIn, "放大棋盘") }
        }
    }
}
