package app.kantai.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.kantai.tv.data.AppRepo
import app.kantai.tv.data.Channel
import app.kantai.tv.play.Catchup
import app.kantai.tv.play.Players
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    player: ExoPlayer,
    req: Screen.Play,
    channels: List<Channel>,
    onSwitch: (Channel) -> Unit,
    onBack: () -> Unit,
) {
    val ch = req.channel
    val prog = req.catchup
    val epg by AppRepo.epg.collectAsState()

    var error by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var showList by remember { mutableStateOf(false) }
    var osdUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var attempt by remember(req) { mutableIntStateOf(0) }
    var showStats by remember { mutableStateOf(false) }
    var okLongPressed by remember { mutableStateOf(false) }
    var okDown by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    fun osd() { osdUntil = System.currentTimeMillis() + 4_000 }

    LaunchedEffect(req, attempt) {
        error = null
        val url = if (prog != null) Catchup.buildUrl(ch, prog.start, prog.stop) else ch.url
        if (url == null) {
            error = "这个频道不支持回看"
            return@LaunchedEffect
        }
        Players.play(player, url, force = attempt > 0)
        osd()
    }

    DisposableEffect(player, req) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "无法播放：${Players.describe(e)}"
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    // 直播出错自动重连几次
    LaunchedEffect(error) {
        if (error != null && prog == null && attempt < 3) {
            delay(3_000)
            attempt++
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    LaunchedEffect(showList) {
        if (!showList) runCatching { focus.requestFocus() }
    }

    BackHandler {
        when {
            showList -> showList = false
            showStats -> showStats = false
            else -> onBack()
        }
    }

    val group = remember(channels, ch) { channels.filter { it.group == ch.group }.ifEmpty { channels } }

    fun step(d: Int) {
        if (group.isEmpty()) return
        val i = group.indexOfFirst { it.key == ch.key }.coerceAtLeast(0)
        onSwitch(group[((i + d) % group.size + group.size) % group.size])
    }

    fun seek(d: Long) {
        val dur = player.duration.takeIf { it > 0 } ?: return
        player.seekTo((player.currentPosition + d).coerceIn(0, dur - 1_000))
        osd()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                if (showList) return@onKeyEvent false
                val ok = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                // 长按确认键也能开关统计信息（有的遥控器菜单键不会发给 App），所以确认键在松开时才执行
                if (ok && ev.type == KeyEventType.KeyDown) {
                    if (ev.nativeKeyEvent.repeatCount == 0) okDown = true
                    if (ev.nativeKeyEvent.repeatCount == 1) {
                        showStats = !showStats
                        okLongPressed = true
                    }
                    return@onKeyEvent true
                }
                if (ok && ev.type == KeyEventType.KeyUp) {
                    // 没在本页按下（例如从节目单按确认进来时的松开）就忽略
                    if (!okDown) return@onKeyEvent true
                    okDown = false
                    if (okLongPressed) okLongPressed = false
                    else when {
                        error != null -> attempt++
                        prog != null -> { if (player.isPlaying) player.pause() else player.play(); osd() }
                        else -> showList = true
                    }
                    return@onKeyEvent true
                }
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionUp -> { if (prog == null) step(-1) else seek(5 * 60_000); true }
                    Key.DirectionDown -> { if (prog == null) step(1) else seek(-5 * 60_000); true }
                    Key.DirectionLeft -> { if (prog != null) seek(-30_000) else osd(); true }
                    Key.DirectionRight -> { if (prog != null) seek(30_000) else osd(); true }
                    Key.MediaFastForward -> { seek(60_000); true }
                    Key.MediaRewind -> { seek(-60_000); true }
                    Key.MediaPlayPause -> {
                        if (prog != null) { if (player.isPlaying) player.pause() else player.play(); osd() }
                        true
                    }
                    Key.Menu, Key.Info -> { showStats = !showStats; true }
                    else -> false
                }
            },
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

        val paused = prog != null && !player.isPlaying && !buffering && error == null
        if (now < osdUntil || paused) Osd(ch, prog, player, epg.nowPlaying(ch, now)?.title, now, paused)

        when {
            error != null -> Centered("$error\n按确认键重试，返回键退出")
            buffering -> Centered("缓冲中…")
        }

        if (showStats) StatsOverlay(player, Modifier.align(Alignment.TopEnd).padding(24.dp))

        if (showList) ChannelOverlay(group, ch) { picked ->
            showList = false
            if (picked.key != ch.key) onSwitch(picked)
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = Color.White,
            fontSize = 20.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xAA000000)).padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun Osd(ch: Channel, prog: app.kantai.tv.data.Programme?, player: ExoPlayer, liveTitle: String?, now: Long, paused: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(horizontal = 40.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ch.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                if (prog != null) Tag(if (paused) "回看 · 已暂停" else "回看") else Tag("直播", Palette.live)
                Spacer(Modifier.weight(1f))
                Text(clock(now), color = Color.White, fontSize = 20.sp)
            }
            Text(prog?.title ?: liveTitle ?: "", color = Palette.muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (prog != null) {
                val pos = player.currentPosition
                val dur = player.duration.takeIf { it > 0 } ?: (prog.stop - prog.start)
                Spacer(Modifier.height(10.dp))
                ProgressBar(pos.toFloat() / dur, Palette.accent, Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row {
                    Text("${clock(prog.start + pos)}  (${fmt(pos)} / ${fmt(dur)})", color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Text("←→ 快退/快进 30 秒   ↑↓ 5 分钟   确认 暂停   菜单/长按确认 统计信息", color = Palette.muted, fontSize = 14.sp)
                }
            } else {
                Text("↑↓ 换台   确认 频道列表   菜单/长按确认 统计信息   返回 退出", color = Palette.muted, fontSize = 14.sp)
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

@Composable
private fun ChannelOverlay(list: List<Channel>, current: Channel, onPick: (Channel) -> Unit) {
    val idx = list.indexOfFirst { it.key == current.key }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (idx - 3).coerceAtLeast(0))
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { fr.requestFocus() }
    }
    Box(Modifier.fillMaxHeight().width(420.dp).background(Color(0xE6101418)).padding(16.dp)) {
        LazyColumn(state = state) {
            itemsIndexed(list, key = { _, c -> c.key }) { i, c ->
                TvItem(
                    modifier = if (i == idx) Modifier.focusRequester(fr) else Modifier,
                    selected = c.key == current.key,
                    onClick = { onPick(c) },
                ) {
                    Text(c.name, color = Palette.text, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
