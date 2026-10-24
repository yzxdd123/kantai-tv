package app.kantai.tv.play

import app.kantai.tv.data.Http
import app.kantai.tv.web.WebServer
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * 咪咕 CDN 回看。
 *
 * 直播列表只给最近几段，但 CDN 仍保留历史分片，文件名形如
 * `..._20261024_193000_1.ts`：日期、10 分钟时段（北京时间）、段号 1..60，每段 10 秒。
 * 先请求一次直播地址拿到当前鉴权，再把文件名里的时间换掉，就能拼出点播列表。
 * 列表和分片由本机 [WebServer] 提供，分片请求 302 到 CDN，视频不经过本机转发。
 */
object MiguReplay {
    private val SEG = Regex("""_(\d{8})_(\d{6})_(\d+)\.ts""")
    private val BJ = ZoneId.of("Asia/Shanghai")
    private const val AUTH_TTL = 5 * 60_000L
    const val DAYS = 3

    private val lives = ConcurrentHashMap<String, String>()
    private val templates = ConcurrentHashMap<String, Pair<String, Long>>()

    fun isMigu(url: String): Boolean =
        runCatching { URL(url).host.endsWith("cmvideo.cn") }.getOrDefault(false) && url.contains("livemode=1")

    fun playlistUrl(liveUrl: String, start: Long, stop: Long): String {
        val id = MessageDigest.getInstance("SHA-1").digest(liveUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)
        lives[id] = liveUrl
        // 最近半分钟的分片可能还没生成
        val end = minOf(stop, System.currentTimeMillis() - 30_000)
        return "http://127.0.0.1:${WebServer.PORT}/replay/migu/$id.m3u8?start=${start / 1000}&end=${end / 1000}"
    }

    fun playlist(id: String, startSec: Long, endSec: Long): String? {
        if (!lives.containsKey(id) || endSec <= startSec) return null
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MEDIA-SEQUENCE:0\n")
        var t = startSec / 10 * 10
        while (t < endSec) {
            val z = Instant.ofEpochSecond(t).atZone(BJ)
            val bucket = z.minute / 10 * 10
            val n = ((z.minute - bucket) * 60 + z.second) / 10 + 1
            sb.append("#EXTINF:10.0,\n")
                .append("/replay/migu/seg/$id/%04d%02d%02d/%02d%02d00/$n.ts\n".format(z.year, z.monthValue, z.dayOfMonth, z.hour, bucket))
            t += 10
        }
        return sb.append("#EXT-X-ENDLIST\n").toString()
    }

    fun segmentUrl(id: String, day: String, hms: String, n: String): String? {
        val live = lives[id] ?: return null
        val tpl = template(id, live)
        val m = SEG.find(tpl) ?: return null
        return tpl.substring(0, m.range.first) + "_${day}_${hms}_$n.ts" + tpl.substring(m.range.last + 1)
    }

    /** 当前直播的一个分片绝对地址（含有效鉴权），定期刷新 */
    private fun template(id: String, live: String): String {
        fresh(id)?.let { return it }
        synchronized(this) {
            fresh(id)?.let { return it }
            val (body, finalUrl) = Http.text(live)
            val seg = body.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: throw IOException("直播列表为空")
            val abs = URL(URL(finalUrl), seg).toString()
            templates[id] = abs to System.currentTimeMillis()
            return abs
        }
    }

    private fun fresh(id: String): String? =
        templates[id]?.takeIf { System.currentTimeMillis() - it.second < AUTH_TTL }?.first
}
