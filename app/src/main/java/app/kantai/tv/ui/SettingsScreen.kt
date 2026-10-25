package app.kantai.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kantai.tv.KantaiApp
import app.kantai.tv.data.AppRepo
import app.kantai.tv.web.WebServer
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(onBack: (() -> Unit)?) {
    if (onBack != null) BackHandler(onBack = onBack)
    val sources by AppRepo.sources.collectAsState()
    val channels by AppRepo.channels.collectAsState()
    val status by AppRepo.status.collectAsState()
    val loading by AppRepo.loading.collectAsState()

    val url = remember { "http://${lanIp()}:${WebServer.PORT}" }
    val qr = remember(url) { qrBitmap(url, 360) }
    val pin = KantaiApp.instance.server.pin
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }

    Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.width(380.dp)) {
            Text("用手机管理直播源", color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(8.dp))
            Text("手机连同一个 Wi-Fi，扫码或在浏览器输入下面的地址", color = Palette.muted, fontSize = 15.sp)
            Spacer(Modifier.size(16.dp))
            Image(qr, contentDescription = null, modifier = Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.size(16.dp))
            Text(url, color = Palette.accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(12.dp))
            Text("配对码  $pin", color = Palette.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text("直播源", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(8.dp))
            Text(
                when {
                    loading -> "正在更新… $status"
                    sources.isEmpty() -> "还没有直播源。用手机打开左边的地址添加。"
                    else -> status
                },
                color = Palette.muted,
                fontSize = 15.sp,
            )
            Spacer(Modifier.size(12.dp))
            TvItem(Modifier.focusRequester(first), onClick = { AppRepo.refreshAsync(force = true) }) {
                Text("⟳ 立即刷新全部", color = Palette.text, fontSize = 17.sp)
            }
            if (onBack != null) {
                TvItem(onClick = onBack) { Text("← 返回频道列表", color = Palette.text, fontSize = 17.sp) }
            }
            Spacer(Modifier.size(12.dp))
            if (sources.isNotEmpty()) LazyColumn(Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.panel).padding(8.dp)) {
                items(sources, key = { it.id }) { s ->
                    TvItem {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = Palette.text, fontSize = 17.sp)
                            Text(s.url, color = Palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("${channels.count { it.sourceId == s.id }} 个频道", color = Palette.muted, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
