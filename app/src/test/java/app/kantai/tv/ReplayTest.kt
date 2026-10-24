package app.kantai.tv

import app.kantai.tv.data.Channel
import app.kantai.tv.data.EpgParser
import app.kantai.tv.data.M3uParser
import app.kantai.tv.data.Programme
import app.kantai.tv.play.Catchup
import app.kantai.tv.play.MiguReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReplayTest {
    private val sample = """
        #EXTM3U x-tvg-url="https://example.com/epg.xml.gz"
        #EXTINF:-1 tvg-id="说明" group-title="其他",使用说明
        https://example.com/readme.txt
        #EXTINF:-1 tvg-id="体育1" tvg-name="体育1" tvg-logo="https://example.com/1.png" group-title="体育",体育一台
        http://live.example.com/index.m3u8?channel-id=a&Contentid=1&livemode=1
        #EXTINF:-1 tvg-id="体育2" catchup="append" catchup-source="?playseek=${'$'}{(b)yyyyMMddHHmmss}-${'$'}{(e)yyyyMMddHHmmss}" group-title="体育",体育二台
        http://live.example.com/2.m3u8
    """.trimIndent()

    @Test
    fun parsesM3u() {
        val r = M3uParser.parse("s", sample)
        assertEquals("https://example.com/epg.xml.gz", r.epgUrl)
        // 说明文档链接不算频道
        assertEquals(listOf("体育一台", "体育二台"), r.channels.map { it.name })
        assertEquals("体育", r.channels[0].group)
        assertEquals("https://example.com/1.png", r.channels[0].logo)
        assertTrue(Catchup.supports(r.channels[1]))
    }

    @Test
    fun parsesTxtFormat() {
        val r = M3uParser.parse("s", "体育,#genre#\nCCTV5,http://a/b.m3u8\nCCTV5+,http://c/d.m3u8#http://e\n")
        assertEquals(listOf("CCTV5", "CCTV5+"), r.channels.map { it.name })
        assertEquals("http://c/d.m3u8", r.channels[1].url)
        assertEquals("体育", r.channels[0].group)
    }

    @Test
    fun genericCatchupTemplates() {
        val ch = Channel("s", 0, "x", "http://h/live.m3u8?a=1", catchup = "append",
            catchupSource = "?playseek=\${(b)yyyyMMddHHmmss}-\${(e)yyyyMMddHHmmss}")
        val zone = ZoneId.systemDefault()
        val s = LocalDateTime.of(2026, 10, 24, 19, 0).atZone(zone).toInstant().toEpochMilli()
        val e = LocalDateTime.of(2026, 10, 24, 21, 40).atZone(zone).toInstant().toEpochMilli()
        assertEquals("http://h/live.m3u8?a=1&playseek=20261024190000-20261024214000", Catchup.buildUrl(ch, s, e))
        val shift = ch.copy(catchup = "shift", catchupSource = "")
        assertTrue(Catchup.buildUrl(shift, s, e)!!.startsWith("http://h/live.m3u8?a=1&utc=${s / 1000}&lutc="))
    }

    @Test
    fun miguReplayPlaylist() {
        val bj = ZoneId.of("Asia/Shanghai")
        val start = LocalDateTime.of(2026, 10, 24, 19, 0, 5).atZone(bj).toInstant().toEpochMilli()
        val stop = LocalDateTime.of(2026, 10, 24, 21, 40).atZone(bj).toInstant().toEpochMilli()
        val url = MiguReplay.playlistUrl("http://x.cmvideo.cn/index.m3u8?livemode=1", start, stop)
        val id = url.substringAfter("/replay/migu/").substringBefore(".m3u8")
        val segs = MiguReplay.playlist(id, start / 1000, stop / 1000)!!.lines().filter { it.startsWith("/replay/") }
        // 19:00:00 起每 10 秒一段，到 21:40 共 960 段；10 分钟一个时段，段号 1..60
        assertEquals(960, segs.size)
        assertEquals("/replay/migu/seg/$id/20261024/190000/1.ts", segs.first())
        assertEquals("/replay/migu/seg/$id/20261024/191000/1.ts", segs[60])
        assertEquals("/replay/migu/seg/$id/20261024/213000/60.ts", segs.last())
    }

    @Test
    fun detectsMergedSchedules() {
        val h = 3_600_000L
        // 正常频道：首尾相接，偶尔一处重叠
        val normal = (0 until 20).map { Programme(it * h, (it + 1) * h, "p$it") } + Programme(5 * h, 6 * h, "dup")
        assertEquals(false, EpgParser.isMergedList(normal.sortedBy { it.start }))
        // 合并的赛事列表：每 15 分钟开一场 2 小时的比赛
        val merged = (0 until 20).map { Programme(it * h / 4, it * h / 4 + 2 * h, "m$it") }
        assertEquals(true, EpgParser.isMergedList(merged))
    }
}
