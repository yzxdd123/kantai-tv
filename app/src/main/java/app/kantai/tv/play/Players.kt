package app.kantai.tv.play

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.C
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.HlsMediaSource
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

@OptIn(UnstableApi::class)
object Players {
    private lateinit var hls: HlsMediaSource.Factory

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
        val data = DefaultDataSource.Factory(ctx, http)
        hls = HlsMediaSource.Factory(data).setExtractorFactory(HevcSafeHlsExtractorFactory())
        prefs = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)
        return ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(data))
            .setLoadControl(load)
            .build()
            .also {
                PlayerStats.attach(it)
                applySubtitles(it)
            }
    }

    private lateinit var prefs: SharedPreferences

    /** 节目自带字幕开关（CEA-608 隐藏字幕、HLS 里的 WebVTT 等），默认开 */
    val subtitlesOn: Boolean get() = prefs.getBoolean("subtitles", true)

    fun setSubtitles(player: ExoPlayer, on: Boolean) {
        prefs.edit().putBoolean("subtitles", on).apply()
        applySubtitles(player)
    }

    private fun applySubtitles(player: ExoPlayer) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesOn)
            // 隐藏字幕通常不标语言，不放开的话播放器不会选它
            .setSelectUndeterminedTextLanguage(true)
            .setPreferredTextLanguages("zh", "en")
            .build()
    }

    /** 当前节目带的字幕，没有返回 null；例如“CEA-608 隐藏字幕（英语）” */
    fun subtitleInfo(player: ExoPlayer): String? {
        val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT } ?: return null
        val f = group.getTrackFormat(0)
        val kind = when (f.sampleMimeType) {
            MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_MP4CEA608 -> "CEA-608 隐藏字幕"
            MimeTypes.APPLICATION_CEA708 -> "CEA-708 隐藏字幕"
            MimeTypes.TEXT_VTT -> "WebVTT 字幕"
            MimeTypes.APPLICATION_DVBSUBS -> "DVB 字幕"
            else -> "字幕"
        }
        val lang = f.language?.takeIf { it != "und" }?.let { java.util.Locale(it).getDisplayLanguage(java.util.Locale.CHINESE) }
        return kind + (lang?.let { "（$it）" } ?: "")
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

    /**
     * 重试也没用的错误：地址失效（4xx，超时和限流除外）、返回的不是视频、电视不支持的格式。
     * 网络中断、超时、服务器 5xx 之类值得重连。
     */
    fun isRetryable(e: PlaybackException): Boolean {
        val http = generateSequence(e.cause) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        if (http != null) return http.responseCode >= 500 || http.responseCode == 408 || http.responseCode == 429
        return e.errorCode !in setOf(
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        )
    }

    /** 当前在播的频道（直播或回看），节目单预览据此判断要不要换台 */
    @Volatile var playingKey: String? = null
        private set

    /** 同一个地址正在播就不重新加载，让预览窗切到全屏时不断流 */
    fun play(player: ExoPlayer, url: String, key: String? = null, force: Boolean = false) {
        playingKey = key
        val current = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (!force && current == url && player.playbackState != Player.STATE_IDLE && player.playerError == null) {
            player.playWhenReady = true
            return
        }
        val isHls = url.substringBefore('?').endsWith(".m3u8") || url.contains("m3u8?")
        val item = MediaItem.Builder().setUri(url).apply { if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        // HLS 用自己的解析器工厂，绕开 H.265 分片末尾带填充数据时的 Media3 bug
        if (isHls) player.setMediaSource(hls.createMediaSource(item)) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }
}
