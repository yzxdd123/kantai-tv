package app.kantai.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.kantai.tv.data.AppRepo
import app.kantai.tv.data.Channel
import app.kantai.tv.data.Programme
import app.kantai.tv.play.Players

/** 界面按 1280 宽的画布设计，不同电视的 dpi 设置下大小一致 */
private const val CANVAS_WIDTH = 1280f

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    /** catchup 为空表示看直播 */
    data class Play(val channel: Channel, val catchup: Programme? = null) : Screen
}

class HomeState {
    var group by mutableStateOf(ALL)
    var focusedKey by mutableStateOf<String?>(null)
    /** 节目单网格里选中的时间点；选中的是覆盖这个时间点的节目 */
    var anchor by mutableLongStateOf(System.currentTimeMillis())
    var windowStart by mutableLongStateOf(Guide.floorHalfHour(System.currentTimeMillis()))
    /** 选中的是频道名那一格，而不是节目 */
    var onChannelCell by mutableStateOf(false)

    companion object {
        const val ALL = "全部频道"
    }
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val widthPx = ctx.resources.displayMetrics.widthPixels
    CompositionLocalProvider(LocalDensity provides Density(widthPx / CANVAS_WIDTH, 1f)) { Content() }
}

@Composable
private fun Content() {
    val ctx = LocalContext.current
    val player = remember { Players.create(ctx) }
    val home = remember { HomeState() }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val channels by AppRepo.channels.collectAsState()

    LaunchedEffect(Unit) { AppRepo.playRequests.collect { screen = Screen.Play(it) } }

    // 切到后台就停播，回来再接着放
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> player.stop()
                Lifecycle.Event.ON_START -> if (player.mediaItemCount > 0) {
                    player.prepare()
                    player.play()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            player.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        when (val s = screen) {
            Screen.Home ->
                if (channels.isEmpty()) SettingsScreen(onBack = null)
                else HomeScreen(player, channels, home, onPlay = { ch, p -> screen = Screen.Play(ch, p) }, onSettings = { screen = Screen.Settings })
            Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
            is Screen.Play -> PlayerScreen(
                player = player,
                req = s,
                channels = channels,
                onSwitch = { ch ->
                    home.focusedKey = ch.key
                    screen = Screen.Play(ch)
                },
                // 返回节目单时继续在预览窗里播
                onBack = {
                    home.focusedKey = s.channel.key
                    home.anchor = s.catchup?.let { it.start + 1 } ?: System.currentTimeMillis()
                    screen = Screen.Home
                },
            )
        }
    }
}
