package app.kantai.tv

import app.kantai.tv.data.Channel
import app.kantai.tv.data.ProbeStore
import app.kantai.tv.data.Prober
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket

/** 用本机临时 HTTP 服务模拟各种直播源，验证测速的判断和排序 */
class ProberTest {
    private lateinit var server: NanoHTTPD
    private lateinit var base: String
    private val segment = ByteArray(2 * 1024 * 1024) { if (it % 188 == 0) 0x47 else 0 }

    @Before
    fun start() {
        val pages = mapOf(
            "/master.m3u8" to (200 to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080\nhigh.m3u8\n"),
            "/high.m3u8" to (200 to "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg1.ts\n#EXTINF:6.0,\nseg2.ts\n"),
            "/huge.m3u8" to (200 to "#EXTM3U\n#EXTINF:1.0,\nseg2.ts\n"), // 2 MB / 1 秒 ≈ 16.8 Mbps
            "/forbidden.m3u8" to (403 to "no"),
            "/page.m3u8" to (200 to "<html><body>not available in your area</body></html>"),
            "/empty.m3u8" to (200 to "#EXTM3U\n#EXT-X-ENDLIST\n"),
        )
        val port = ServerSocket(0).use { it.localPort }
        server = object : NanoHTTPD("127.0.0.1", port) {
            override fun serve(session: IHTTPSession): Response {
                if (session.uri == "/seg2.ts") {
                    return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "video/mp2t", ByteArrayInputStream(segment), segment.size.toLong())
                        .apply { addHeader("Content-Range", "bytes 0-${segment.size - 1}/${segment.size}") }
                }
                val (code, body) = pages[session.uri] ?: (404 to "")
                val st = Response.Status.values().first { it.requestStatus == code }
                return newFixedLengthResponse(st, "text/plain", body)
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        base = "http://127.0.0.1:$port"
    }

    @After
    fun stop() = server.stop()

    @Test
    fun goodMultiBitrateStream() {
        val r = Prober.probe("$base/master.m3u8")
        assertEquals(Prober.State.GOOD, r.state)
        assertEquals(4.0, r.needMbps!!, 0.01) // 按最高一档测
        assertEquals(1080, r.height)
        assertTrue(r.ttfbMs != null && r.speedMbps!! > 0)
    }

    @Test
    fun failures() {
        assertEquals("HTTP 403 拒绝访问（多为限制地区）", Prober.probe("$base/forbidden.m3u8").reason)
        assertEquals(Prober.State.FAIL, Prober.probe("$base/page.m3u8").state)
        assertTrue(Prober.probe("$base/empty.m3u8").reason.contains("没有视频分片"))
        assertEquals("连接被拒绝", Prober.probe("http://127.0.0.1:1/x.m3u8").reason)
    }

    @Test
    fun needEstimatedFromSegmentSize() {
        // 列表没写码率：用 Content-Range 总大小 ÷ 时长估算
        val r = Prober.probe("$base/huge.m3u8")
        assertEquals(16.8, r.needMbps!!, 0.1)
    }

    @Test
    fun judgeAndSort() {
        assertEquals(Prober.State.GOOD, Prober.judge(10.0, 8.5))
        assertEquals(Prober.State.GOOD, Prober.judge(6.5, 8.5)) // 取样会低估，7 成以内不算慢
        assertEquals(Prober.State.SLOW, Prober.judge(5.0, 8.5))
        assertEquals(Prober.State.GOOD, Prober.judge(1.0, null))

        val chs = (0..4).map { Channel("s", it, "c$it", "http://x/$it") }
        val map = mapOf(
            "http://x/0" to Prober.Result(Prober.State.FAIL, reason = "HTTP 404"),
            "http://x/1" to Prober.Result(Prober.State.GOOD, ttfbMs = 1500),
            "http://x/2" to Prober.Result(Prober.State.SLOW, ttfbMs = 300),
            // 3 没测过
            "http://x/4" to Prober.Result(Prober.State.GOOD, ttfbMs = 400),
        )
        assertEquals(listOf("c4", "c1", "c2", "c3", "c0"), ProbeStore.sort(chs, true, map).map { it.name })
        assertEquals(chs, ProbeStore.sort(chs, false, map))
    }
}
