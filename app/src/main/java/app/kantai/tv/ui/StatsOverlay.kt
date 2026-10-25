package app.kantai.tv.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.kantai.tv.play.PlayerStats
import app.kantai.tv.play.Players
import kotlinx.coroutines.delay

/** 播放统计：画质、编码、帧率、码率、网速、缓冲、丢帧 */
@OptIn(UnstableApi::class)
@Composable
fun StatsOverlay(player: ExoPlayer, modifier: Modifier = Modifier) {
    var fps by remember { mutableStateOf(0f) }
    var tick by remember { mutableLongStateOf(0L) }

    // 用解码器实际输出的帧数算帧率，不依赖源有没有写
    LaunchedEffect(player) {
        var lastFrames = -1
        var lastTime = 0L
        while (true) {
            player.videoDecoderCounters?.let { c ->
                c.ensureUpdated()
                val n = c.renderedOutputBufferCount
                val t = System.nanoTime()
                if (lastFrames >= 0 && n >= lastFrames) fps = (n - lastFrames) * 1e9f / (t - lastTime)
                lastFrames = n
                lastTime = t
            }
            tick++
            delay(1_000)
        }
    }

    tick // 每秒刷新一次
    val decoder = PlayerStats.decoder
    val downloadBps = PlayerStats.downloadBps
    val segmentBps = PlayerStats.segmentBps
    val dropped = PlayerStats.dropped
    val v = player.videoFormat
    val a = player.audioFormat
    val size = player.videoSize
    val rows = buildList {
        val w = size.width.takeIf { it > 0 } ?: v?.width ?: 0
        val h = size.height.takeIf { it > 0 } ?: v?.height ?: 0
        add("画质" to if (h > 0) "${quality(h)}  ($w×$h)" else "—")
        add("视频编码" to (v?.let { codec(it) } ?: "—"))
        add("帧率" to buildString {
            if (fps > 0) append("约 %.0f fps".format(fps)) else append("—")
            v?.frameRate?.takeIf { it > 0 }?.let { append("（源标注 %.2f）".format(it)) }
        })
        val declared = v?.bitrate?.takeIf { it > 0 } ?: v?.averageBitrate?.takeIf { it > 0 }
        add("视频码率" to when {
            segmentBps > 0 -> mbps(segmentBps) + "（按分片实测）"
            declared != null -> mbps(declared.toLong()) + "（源标注）"
            else -> "—"
        })
        add("下载速度" to if (downloadBps > 0) mbps(downloadBps) else "—")
        add("缓冲" to "%.1f 秒".format(player.totalBufferedDuration / 1000f))
        add("丢帧" to "$dropped")
        add("解码器" to decoder.ifEmpty { "—" } + if (decoder.startsWith("c2.android") || decoder.startsWith("OMX.google")) "（软件解码）" else "")
        add("字幕" to (Players.subtitleInfo(player)?.let { "$it · ${if (Players.subtitlesOn) "开" else "关"}" } ?: "无"))
        add("音频" to (a?.let { "${codecName(it.sampleMimeType)} · ${it.channelCount} 声道 · ${it.sampleRate / 1000.0} kHz" } ?: "—"))
    }

    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text("视频统计信息", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        rows.forEach { (k, value) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(k, color = Palette.muted, fontSize = 13.sp, modifier = Modifier.width(76.dp))
                Text(value, color = Color.White, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("再按菜单键 / 长按确认 / 返回 关闭", color = Palette.muted, fontSize = 12.sp)
    }
}

private fun quality(h: Int) = when {
    h >= 2000 -> "4K"
    h >= 1400 -> "2K"
    h >= 1000 -> "1080p"
    h >= 700 -> "720p"
    h >= 520 -> "576p"
    else -> "${h}p"
}

private fun codecName(mime: String?) = when (mime) {
    MimeTypes.VIDEO_H264 -> "H.264"
    MimeTypes.VIDEO_H265 -> "H.265 / HEVC"
    MimeTypes.VIDEO_AV1 -> "AV1"
    MimeTypes.VIDEO_VP9 -> "VP9"
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_AC3 -> "Dolby AC-3"
    MimeTypes.AUDIO_E_AC3 -> "Dolby E-AC-3"
    MimeTypes.AUDIO_MPEG -> "MP3"
    else -> mime?.substringAfter('/') ?: "—"
}

private fun codec(f: Format): String = buildString {
    append(codecName(f.sampleMimeType))
    f.codecs?.let { append("（$it）") }
    when (f.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> append(" · HDR10")
        C.COLOR_TRANSFER_HLG -> append(" · HLG")
    }
}

private fun mbps(bps: Long) = "%.1f Mbps".format(bps / 1e6)
