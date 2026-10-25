package app.kantai.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.kantai.tv.data.AppRepo
import app.kantai.tv.data.Channel
import app.kantai.tv.data.Epg
import app.kantai.tv.data.Programme
import app.kantai.tv.play.Catchup
import app.kantai.tv.play.Players
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

object Guide {
    const val MIN = 60_000L
    const val HALF_HOUR = 30 * MIN
    const val SPAN = 150 * MIN // 网格一屏显示 2.5 小时
    const val DAY = 24 * 60 * MIN
    val ROW = 46.dp
    val CHANNEL_CELL = 210.dp

    fun floorHalfHour(t: Long) = t - Math.floorMod(t, HALF_HOUR)

    /** 节目单为空时用一整格占位，保证每个频道都能选中 */
    private const val HOUR = 60 * MIN

    fun cells(ch: Channel, epg: Epg, around: Long): List<Programme> =
        dedupe(epg.forChannel(ch)).ifEmpty {
            if (Catchup.supports(ch)) hourly(ch, around) else listOf(Programme(around - 2 * DAY, around + 2 * DAY, ""))
        }

    /** 没有可用节目单但能回看的频道：按小时切块，可以按时间回看 */
    private fun hourly(ch: Channel, now: Long): List<Programme> {
        val first = now - Catchup.days(ch) * DAY
        var t = first - Math.floorMod(first, HOUR) + HOUR
        val out = mutableListOf(Programme(first, t, ""))
        while (t < now + DAY) {
            val title = when {
                t + HOUR <= now -> "按时间回看 ${clock(t)}–${clock(t + HOUR)}"
                t <= now -> "直播中"
                else -> ""
            }
            out += Programme(t, t + HOUR, title)
            t += HOUR
        }
        return out
    }

    /** 节目单里有时会有重叠的节目，后一个从前一个结束处开始，避免网格里文字叠在一起 */
    private fun dedupe(list: List<Programme>): List<Programme> {
        val out = ArrayList<Programme>(list.size)
        for (p in list) {
            val prevStop = out.lastOrNull()?.stop ?: Long.MIN_VALUE
            val start = maxOf(p.start, prevStop)
            if (p.stop > start) out += if (start == p.start) p else p.copy(start = start)
        }
        return out
    }

    fun at(cells: List<Programme>, t: Long): Programme? =
        cells.firstOrNull { t >= it.start && t < it.stop } ?: cells.firstOrNull { it.start >= t } ?: cells.lastOrNull()
}

@Composable
fun HomeScreen(
    player: ExoPlayer,
    channels: List<Channel>,
    state: HomeState,
    onPlay: (Channel, Programme?) -> Unit,
    onSettings: () -> Unit,
) {
    val epg by AppRepo.epg.collectAsState()
    val status by AppRepo.status.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }

    val groups = remember(channels) { listOf(HomeState.ALL) + channels.map { it.group.ifBlank { "未分组" } }.distinct() }
    if (state.group !in groups) state.group = HomeState.ALL
    val inGroup = remember(channels, state.group) {
        if (state.group == HomeState.ALL) channels else channels.filter { it.group.ifBlank { "未分组" } == state.group }
    }
    val row = inGroup.indexOfFirst { it.key == state.focusedKey }.coerceAtLeast(0)
    val current = inGroup.getOrNull(row)
    val selected = current?.let { Guide.at(Guide.cells(it, epg, now), state.anchor) }

    // 选中频道停留一下就在预览窗里播放；这个频道已经在播（包括刚看的回看）就不打断
    LaunchedEffect(current?.key) {
        current ?: return@LaunchedEffect
        if (Players.playingKey == current.key && player.playerError == null) return@LaunchedEffect
        delay(700)
        Players.play(player, current.url, current.key)
    }

    val gridFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }
    val groupList = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 选中的分组可能滚出了屏幕（还没被创建），要先滚过去再给焦点
    fun focusGroups() {
        scope.launch {
            val i = groups.indexOf(state.group) + 1 // 第 0 项是“管理直播源”
            val visible = groupList.layoutInfo.visibleItemsInfo.map { it.index }
            if (i !in visible) groupList.scrollToItem((i - 3).coerceAtLeast(0))
            withFrameNanos { }
            runCatching { groupFocus.requestFocus() }
        }
    }

    LaunchedEffect(Unit) {
        delay(50)
        runCatching { gridFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        // 上半部分：节目信息 + 预览
        Row(Modifier.fillMaxWidth().height(232.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            val note = if (current != null && epg.isMerged(current)) {
                val from = selected?.start ?: now
                val to = selected?.stop ?: (now + 1)
                val games = epg.mergedDuring(current, from, to).map { it.title }.distinct()
                if (games.isEmpty()) "这个频道没有独立节目单（咪咕直播频道共用一份赛事总表），这个时段总表里没有赛事。"
                else "这个时段咪咕在播 ${games.size} 场，本台是其中之一：" + games.take(5).joinToString("、") +
                    (if (games.size > 5) " 等" else "")
            } else null
            InfoPanel(current, selected, now, status, note, Modifier.weight(1f).fillMaxHeight())
            Box(
                Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black),
            ) {
                AndroidView(
                    factory = { c ->
                        PlayerView(c).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            this.player = player
                        }
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
                PreviewStatus(player, current?.key)
            }
        }
        Spacer(Modifier.height(16.dp))

        // 下半部分：分组 + 频道 × 时间网格
        Row(Modifier.fillMaxSize()) {
            GroupColumn(
                groups, state, onSettings,
                focus = groupFocus,
                listState = groupList,
                onEnter = { runCatching { gridFocus.requestFocus() } },
                modifier = Modifier.width(150.dp).fillMaxHeight(),
            )
            Spacer(Modifier.width(12.dp))
            GuideGrid(
                channels = inGroup,
                allChannels = channels,
                epg = epg,
                now = now,
                state = state,
                row = row,
                focus = gridFocus,
                onExitLeft = ::focusGroups,
                onPlay = onPlay,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/** 预览窗上的加载/出错提示，避免出错时只剩黑屏 */
@Composable
private fun PreviewStatus(player: ExoPlayer, channelKey: String?) {
    var error by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    LaunchedEffect(channelKey) { error = null }
    DisposableEffect(player) {
        error = player.playerError?.let { Players.describe(it) }
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) error = null
            }

            override fun onPlayerError(e: PlaybackException) {
                error = Players.describe(e)
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    val msg = error
    when {
        msg != null -> Column(
            Modifier.fillMaxSize().background(Color(0xCC000000)).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("无法播放", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(msg, color = Palette.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        buffering -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("加载中…", color = Palette.muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun InfoPanel(ch: Channel?, p: Programme?, now: Long, status: String, note: String?, modifier: Modifier) {
    Column(modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ch != null) {
                AsyncImage(model = ch.logo.ifBlank { null }, contentDescription = null, modifier = Modifier.size(44.dp, 28.dp))
                Spacer(Modifier.width(10.dp))
                Text(ch.name, color = Palette.muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else Spacer(Modifier.weight(1f))
            Text(clock(now), color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(14.dp))
        if (ch == null) return@Column
        val real = p?.takeIf { it.title.isNotEmpty() }
        Text(
            real?.title ?: "暂无节目信息",
            color = Palette.text,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        if (real != null) {
            val live = now >= real.start && now < real.stop
            val past = real.stop <= now
            val canCatchup = past && Catchup.supports(ch) && real.start >= now - Catchup.days(ch) * Guide.DAY
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${shortTime(real.start, now)} – ${clock(real.stop)} · ${(real.stop - real.start) / Guide.MIN} 分钟",
                    color = Palette.muted,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.width(10.dp))
                when {
                    live -> Tag("直播中", Palette.live)
                    canCatchup -> Tag("可回看")
                    past -> Tag("已结束", Color(0xFF444C56))
                    else -> Tag("未开始", Color(0xFF444C56))
                }
            }
            if (live) {
                Spacer(Modifier.height(10.dp))
                ProgressBar(((now - real.start).toFloat() / (real.stop - real.start)), Palette.live, Modifier.width(320.dp))
            }
            if (note != null) {
                Spacer(Modifier.height(10.dp))
                Text(note, color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3)
            }
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    live -> "确认 全屏观看"
                    canCatchup -> "确认 回看这个节目"
                    else -> "←→ 选择节目   ↑↓ 换频道"
                },
                color = Palette.muted,
                fontSize = 13.sp,
            )
        } else {
            Spacer(Modifier.weight(1f))
            Text(status, color = Palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GroupColumn(
    groups: List<String>,
    state: HomeState,
    onSettings: () -> Unit,
    focus: FocusRequester,
    listState: LazyListState,
    onEnter: () -> Unit,
    modifier: Modifier,
) {
    val ctx = LocalContext.current
    var lastBack by remember { mutableLongStateOf(0L) }
    LazyColumn(
        state = listState,
        modifier = modifier.onKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (ev.key) {
                // 分组里按返回：再按一次才退出，免得误退
                Key.Back -> {
                    val t = System.currentTimeMillis()
                    if (t - lastBack < 2_000) return@onKeyEvent false
                    lastBack = t
                    Toast.makeText(ctx, "再按一次返回退出", Toast.LENGTH_SHORT).show()
                    true
                }
                Key.DirectionRight -> { onEnter(); true }
                else -> false
            }
        },
    ) {
        item {
            TvItem(onClick = onSettings, compact = true) { Text("⚙ 管理直播源", color = Palette.muted, fontSize = 14.sp) }
        }
        items(groups) { g ->
            TvItem(
                modifier = if (g == state.group) Modifier.focusRequester(focus) else Modifier,
                selected = g == state.group,
                onFocus = { state.group = g },
                onClick = { state.group = g; onEnter() },
                compact = true,
            ) {
                Text(g, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GuideGrid(
    channels: List<Channel>,
    allChannels: List<Channel>,
    epg: Epg,
    now: Long,
    state: HomeState,
    row: Int,
    focus: FocusRequester,
    onExitLeft: () -> Unit,
    onPlay: (Channel, Programme?) -> Unit,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 选中行保持在可见范围内
    LaunchedEffect(row, channels) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = listState.firstVisibleItemIndex
        val count = (visible.size - 1).coerceAtLeast(1)
        when {
            row < first -> listState.scrollToItem(row)
            row >= first + count -> listState.scrollToItem((row - count + 1).coerceAtLeast(0))
        }
    }

    fun open() {
        val ch = channels.getOrNull(row) ?: return
        if (state.onChannelCell) return onPlay(ch, null)
        val p = Guide.at(Guide.cells(ch, epg, now), state.anchor) ?: return onPlay(ch, null)
        when {
            p.title.isEmpty() || (now >= p.start && now < p.stop) -> onPlay(ch, null)
            p.stop <= now && Catchup.supports(ch) && p.start >= now - Catchup.days(ch) * Guide.DAY -> onPlay(ch, p)
        }
    }

    Column(
        modifier
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || channels.isEmpty()) return@onKeyEvent false
                val ch = channels[row]
                val cells = Guide.cells(ch, epg, now)
                val cur = Guide.at(cells, state.anchor)
                when (ev.key) {
                    Key.DirectionUp -> {
                        if (row > 0) state.focusedKey = channels[row - 1].key
                        true
                    }
                    Key.DirectionDown -> {
                        if (row < channels.size - 1) state.focusedKey = channels[row + 1].key
                        true
                    }
                    Key.DirectionRight -> {
                        if (state.onChannelCell) {
                            state.onChannelCell = false
                            state.anchor = now
                        } else {
                            val i = cells.indexOf(cur)
                            cells.getOrNull(i + 1)?.let { state.anchor = it.start + 1 }
                        }
                        true
                    }
                    Key.DirectionLeft -> {
                        if (state.onChannelCell) {
                            onExitLeft()
                            return@onKeyEvent true
                        }
                        // 往左最多翻到能回看的最早节目；不能回看的频道最多到正在播的节目
                        val earliest = if (Catchup.supports(ch)) now - Catchup.days(ch) * Guide.DAY else now
                        val prev = cells.getOrNull(cells.indexOf(cur) - 1)?.takeIf { it.stop > earliest }
                        if (prev == null) state.onChannelCell = true
                        else state.anchor = prev.start + 1
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        open()
                        true
                    }
                    Key.Back -> {
                        // 返回：先回到“现在”，已在现在就跳到左边分组
                        val atNow = !state.onChannelCell && cur != null && now >= cur.start && now < cur.stop
                        state.onChannelCell = false
                        state.anchor = now
                        state.windowStart = Guide.floorHalfHour(now)
                        if (atNow) onExitLeft()
                        true
                    }
                    else -> false
                }.also {
                    // 选中的时间点滚出网格时，按半小时对齐挪动时间窗
                    val t = state.anchor
                    if (t < state.windowStart) state.windowStart = Guide.floorHalfHour(t)
                    else if (t >= state.windowStart + Guide.SPAN) state.windowStart = Guide.floorHalfHour(t) - Guide.SPAN + 2 * Guide.HALF_HOUR
                }
            }
            .focusable(),
    ) {
        TimeHeader(state.windowStart, now)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val gridWidth = maxWidth - Guide.CHANNEL_CELL
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(channels, key = { _, c -> c.key }) { i, ch ->
                    GuideRow(
                        ch = ch,
                        number = allChannels.indexOf(ch) + 1,
                        cells = Guide.cells(ch, epg, now),
                        now = now,
                        state = state,
                        isRow = i == row,
                        gridFocused = focused,
                        gridWidth = gridWidth,
                    )
                }
            }
            NowLine(state.windowStart, now, gridWidth)
        }
    }
}

@Composable
private fun TimeHeader(windowStart: Long, now: Long) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(26.dp)) {
        val gridWidth = maxWidth - Guide.CHANNEL_CELL
        Text(
            shortTime(windowStart, now).let { if (it.length > 5) it.substring(0, 5) else "今天" },
            color = Palette.muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        )
        var t = windowStart
        while (t < windowStart + Guide.SPAN) {
            val x = Guide.CHANNEL_CELL + gridWidth * ((t - windowStart).toFloat() / Guide.SPAN)
            Text(clock(t), color = Palette.muted, fontSize = 13.sp, modifier = Modifier.offset(x = x + 6.dp, y = 4.dp))
            t += Guide.HALF_HOUR
        }
    }
}

@Composable
private fun NowLine(windowStart: Long, now: Long, gridWidth: Dp) {
    if (now < windowStart || now >= windowStart + Guide.SPAN) return
    val x = Guide.CHANNEL_CELL + gridWidth * ((now - windowStart).toFloat() / Guide.SPAN)
    Box(Modifier.offset(x = x).width(2.dp).fillMaxHeight().background(Palette.live))
}

@Composable
private fun GuideRow(
    ch: Channel,
    number: Int,
    cells: List<Programme>,
    now: Long,
    state: HomeState,
    isRow: Boolean,
    gridFocused: Boolean,
    gridWidth: Dp,
) {
    val ws = state.windowStart
    val we = ws + Guide.SPAN
    val sel = if (isRow) Guide.at(cells, state.anchor) else null
    val canCatchup = Catchup.supports(ch)
    val catchupFrom = now - Catchup.days(ch) * Guide.DAY

    Row(Modifier.fillMaxWidth().height(Guide.ROW).padding(vertical = 2.dp)) {
        // 频道格
        val chFocused = isRow && state.onChannelCell && gridFocused
        Row(
            Modifier
                .width(Guide.CHANNEL_CELL)
                .fillMaxHeight()
                .padding(end = 3.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    when {
                        chFocused -> Palette.focus
                        isRow -> Color(0xFF2A3038)
                        else -> Palette.panel
                    },
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$number", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.width(26.dp))
            AsyncImage(model = ch.logo.ifBlank { null }, contentDescription = null, modifier = Modifier.size(40.dp, 26.dp))
            Spacer(Modifier.width(8.dp))
            Text(ch.name, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (canCatchup) Text("⟲", color = Palette.accent, fontSize = 13.sp)
        }

        // 节目格
        Box(Modifier.width(gridWidth).fillMaxHeight()) {
            for (p in cells) {
                if (p.stop <= ws || p.start >= we) continue
                val s = maxOf(p.start, ws)
                val e = minOf(p.stop, we)
                val x = gridWidth * ((s - ws).toFloat() / Guide.SPAN)
                val w = gridWidth * ((e - s).toFloat() / Guide.SPAN)
                val isSel = p == sel && !state.onChannelCell
                val live = now >= p.start && now < p.stop
                val past = p.stop <= now
                val replayable = past && canCatchup && p.start >= catchupFrom
                Box(
                    Modifier
                        .offset(x = x)
                        .width(w)
                        .fillMaxHeight()
                        .padding(end = 2.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            when {
                                isSel && gridFocused -> Palette.focus
                                isSel -> Color(0xFF3A4350)
                                live -> Color(0xFF232B36)
                                else -> Palette.panel
                            },
                        )
                        .then(if (isSel && !gridFocused) Modifier.border(1.dp, Palette.focus, RoundedCornerShape(5.dp)) else Modifier)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (w > 24.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (replayable && w > 60.dp) Text("⟲ ", color = if (isSel && gridFocused) Color.White else Palette.accent, fontSize = 12.sp)
                            Text(
                                p.title.ifEmpty { "暂无节目信息" },
                                color = when {
                                    isSel && gridFocused -> Color.White
                                    past && !replayable -> Palette.muted
                                    else -> Palette.text
                                },
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
