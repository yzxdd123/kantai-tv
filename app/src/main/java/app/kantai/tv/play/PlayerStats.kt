package app.kantai.tv.play

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData

/** 从播放器创建起就收集的统计，打开统计面板时立刻有数据（解码器名只在开播时上报一次） */
@OptIn(UnstableApi::class)
object PlayerStats {
    @Volatile var decoder = ""
    @Volatile var downloadBps = 0L
    /** 视频分片大小 ÷ 时长 = 实际码率（HLS 列表里常常不写码率） */
    @Volatile var segmentBps = 0L
    @Volatile var dropped = 0L

    fun attach(player: ExoPlayer) {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onMediaItemTransition(t: AnalyticsListener.EventTime, item: MediaItem?, reason: Int) {
                segmentBps = 0
                dropped = 0
            }

            override fun onVideoDecoderInitialized(t: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                decoder = name
            }

            override fun onBandwidthEstimate(t: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
                downloadBps = bitrateEstimate
            }

            override fun onDroppedVideoFrames(t: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                dropped += droppedFrames
            }

            override fun onLoadCompleted(t: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
                val dur = data.mediaEndTimeMs - data.mediaStartTimeMs
                if (data.dataType == C.DATA_TYPE_MEDIA && dur > 0 && info.bytesLoaded > 0) {
                    segmentBps = info.bytesLoaded * 8_000 / dur
                }
            }
        })
    }
}
