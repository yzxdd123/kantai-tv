package app.kantai.tv.play

import android.net.Uri
import android.util.SparseArray
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.BundledHlsMediaChunkExtractor
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * 绕开 Media3 的一个 H.265 TS 解析 bug。
 *
 * HLS 模式下，每个分片读完时 TsExtractor 会给视频流发一个“合成的空 PUSI”，让 H265Reader 把最后一帧输出。
 * 如果这一帧末尾带着填充数据（FD，NAL 类型 38，编码器为了恒定码率加的），H265Reader 在遇到 FD 时
 * 已经输出过这一帧，分片结束时又输出一次，两帧在缓冲里重叠，SampleQueue 校验失败，播放报错。
 * 咪咕直播 4K-1 的部分分片就是这样。
 *
 * 做法：H.265 流的读取器外面包一层（不是 PesReader，TsExtractor 就不会给它发合成 PUSI），
 * 最后一帧改由下一个分片开头的 AUD 正常结束。解析器在连续分片之间本来就会复用，所以不丢帧。
 */
@OptIn(UnstableApi::class)
class HevcSafeHlsExtractorFactory : HlsExtractorFactory {
    override fun createExtractor(
        uri: Uri,
        format: Format,
        muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster,
        responseHeaders: Map<String, List<String>>,
        sniffingExtractorInput: ExtractorInput,
        playerId: PlayerId,
    ): HlsMediaChunkExtractor {
        val ts = TsExtractor(TsExtractor.MODE_HLS, timestampAdjuster, payloadReaderFactory(muxedCaptionFormats))
        val isTs = try {
            ts.sniff(sniffingExtractorInput)
        } finally {
            sniffingExtractorInput.resetPeekPosition()
        }
        return if (isTs) BundledHlsMediaChunkExtractor(ts, format, timestampAdjuster)
        else HlsExtractorFactory.DEFAULT.createExtractor(
            uri, format, muxedCaptionFormats, timestampAdjuster, responseHeaders, sniffingExtractorInput, playerId,
        )
    }

    companion object {
        /** 和 Media3 默认的 HLS TS 设置一致，只是 H.265 流不接收分片末尾的强制输出 */
        fun payloadReaderFactory(muxedCaptionFormats: List<Format>?): TsPayloadReader.Factory {
            val base = DefaultTsPayloadReaderFactory(
                DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM or
                    (if (muxedCaptionFormats != null) DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS else 0),
                muxedCaptionFormats ?: emptyList(),
            )
            return object : TsPayloadReader.Factory {
                override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = base.createInitialPayloadReaders()

                override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
                    val reader = base.createPayloadReader(streamType, esInfo) ?: return null
                    return if (streamType == TsExtractor.TS_STREAM_TYPE_H265) NoEndFlush(reader) else reader
                }
            }
        }
    }

    private class NoEndFlush(private val inner: TsPayloadReader) : TsPayloadReader {
        override fun init(timestampAdjuster: TimestampAdjuster, output: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) =
            inner.init(timestampAdjuster, output, idGenerator)

        override fun seek() = inner.seek()

        override fun consume(data: ParsableByteArray, flags: Int) = inner.consume(data, flags)
    }
}
