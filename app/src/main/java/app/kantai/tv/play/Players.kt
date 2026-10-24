package app.kantai.tv.play

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.kantai.tv.data.Http

object Players {
    fun create(ctx: Context): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
        // 咪咕约 8.5 Mbps，缓冲放大一些以扛住 CDN 波动
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 90_000, 2_500, 5_000)
            .build()
        return ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(DefaultDataSource.Factory(ctx, http)))
            .setLoadControl(load)
            .build()
            .also { PlayerStats.attach(it) }
    }

    /** 把播放器错误翻译成看得懂的原因 */
    fun describe(e: PlaybackException): String {
        val http = generateSequence(e.cause) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        return when {
            http != null -> "服务器拒绝访问（HTTP ${http.responseCode}），频道可能已失效"
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                "服务器返回的不是视频，频道可能已失效或限制地区"
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "连不上服务器"
            e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "电视不支持这种视频格式"
            e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "直播进度落后太多，请重试"
            else -> e.errorCodeName
        }
    }

    /** 同一个地址正在播就不重新加载，让预览窗切到全屏时不断流 */
    fun play(player: ExoPlayer, url: String, force: Boolean = false) {
        val current = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (!force && current == url && player.playbackState != Player.STATE_IDLE && player.playerError == null) {
            player.playWhenReady = true
            return
        }
        val item = MediaItem.Builder().setUri(url)
        if (url.substringBefore('?').endsWith(".m3u8") || url.contains("m3u8?")) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(item.build())
        player.prepare()
        player.playWhenReady = true
    }
}
