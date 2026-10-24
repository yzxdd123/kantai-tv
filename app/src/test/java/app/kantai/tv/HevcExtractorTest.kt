package app.kantai.tv

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import app.kantai.tv.play.HevcSafeHlsExtractorFactory
import androidx.media3.extractor.ts.TsPayloadReader
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 用 Media3 的 TS 解析器跑真实分片，检查 H.265 帧是否重叠（见 HevcSafeHlsExtractorFactory） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HevcExtractorTest {
    class Track(val id: Int) : TrackOutput {
        var written = 0L
        var prevEnd = -1L
        var count = 0
        var overlaps = 0
        var type = ""
        override fun format(format: Format) { type = format.sampleMimeType ?: "" }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val buf = ByteArray(length)
            val n = input.read(buf, 0, length)
            if (n == C.RESULT_END_OF_INPUT) return n
            written += n
            return n
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
            written += length
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val end = written - offset
            val start = end - size
            if (start < prevEnd) overlaps++
            if (start < prevEnd) println("OVERLAP track=$id $type sample#$count t=$timeUs size=$size offset=$offset start=$start prevEnd=$prevEnd written=$written key=${flags and C.BUFFER_FLAG_KEY_FRAME != 0}")
            if (size < 0) println("NEGATIVE SIZE track=$id sample#$count size=$size")
            prevEnd = end
            count++
        }
    }

    private fun run(files: List<File>, reuse: Boolean, factory: () -> TsPayloadReader.Factory): Pair<Int, Int> {
        val tracks = HashMap<Int, Track>()
        val out = object : ExtractorOutput {
            override fun track(id: Int, type: Int) = tracks.getOrPut(id) { Track(id) }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        }
        var ex: Extractor? = null
        var overlaps = 0
        for (f in files) {
            if (ex == null || !reuse) {
                ex = TsExtractor(TsExtractor.MODE_HLS, TimestampAdjuster(0), factory())
                ex.init(out)
            }
            val bytes = f.readBytes()
            var p = 0
            val src = DataReader { buf, off, len ->
                if (p >= bytes.size) C.RESULT_END_OF_INPUT
                else minOf(len, bytes.size - p).also { n -> System.arraycopy(bytes, p, buf, off, n); p += n }
            }
            val input = DefaultExtractorInput(src, 0, bytes.size.toLong())
            val pos = PositionHolder()
            while (ex.read(input, pos) != Extractor.RESULT_END_OF_INPUT) Unit
        }
        val video = tracks.values.first { it.type == "video/hevc" }
        overlaps = video.overlaps
        return video.count to overlaps
    }

    /** 需要先把真实分片下载到 SEG_DIR（例如 4K-1 带 FD 填充的分片），否则跳过 */
    @Test
    fun hevcFillerAtSegmentEnd() {
        val dir = System.getenv("SEG_DIR")
        assumeTrue(dir != null)
        val files = File(dir!!).listFiles { f -> f.name.endsWith(".ts") }!!.sortedBy { it.name.filter(Char::isDigit).toInt() }
        val (defFrames, defOverlaps) = run(files, reuse = false) { DefaultTsPayloadReaderFactory() }
        val (fixFrames, fixOverlaps) = run(files, reuse = true) { HevcSafeHlsExtractorFactory.payloadReaderFactory(null) }
        println("default: frames=$defFrames overlaps=$defOverlaps; fixed: frames=$fixFrames overlaps=$fixOverlaps")
        assertEquals(0, fixOverlaps)
    }
}
