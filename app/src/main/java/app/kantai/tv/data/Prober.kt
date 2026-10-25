package app.kantai.tv.data

import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 频道可播性测速。
 *
 * 不测 ping：经过 Fake-IP 模式的 Clash 时，所有域名都解析到路由器上的假地址，连接延迟一律只有几毫秒，没有意义。
 * 改为按播放器的实际流程测：打开播放列表（记首字节时间）→ 取最新一段视频的开头一小段（算下载速度）
 * → 和这个频道需要的码率比较。只取样几百 KB，避免一次测几百个频道就用掉大量流量。
 */
object Prober {
    private const val TIMEOUT = 8_000
    private const val SAMPLE_BYTES = 768 * 1024
    private const val MAX_SAMPLE_BYTES = 8 * 1024 * 1024

    enum class State(val rank: Int) { GOOD(0), SLOW(1), FAIL(3) }

    data class Result(
        val state: State,
        /** 播放列表首字节时间 */
        val ttfbMs: Long? = null,
        val speedMbps: Double? = null,
        val needMbps: Double? = null,
        val height: Int? = null,
        val reason: String = "",
        val testedAt: Long = System.currentTimeMillis(),
    )

    fun probe(url: String): Result {
        val t0 = System.nanoTime()
        val (c, finalUrl) = try {
            Http.connect(url, TIMEOUT)
        } catch (e: Exception) {
            return Result(State.FAIL, reason = describe(e))
        }
        val ttfb = (System.nanoTime() - t0) / 1_000_000
        val head: ByteArray
        try {
            head = c.inputStream.use { it.readUpTo(256 * 1024) }
        } catch (e: Exception) {
            return Result(State.FAIL, ttfb, reason = describe(e))
        } finally {
            c.disconnect()
        }

        // 直接是视频流（TS 同步字节 0x47）
        if (head.isNotEmpty() && head[0] == 0x47.toByte()) return Result(State.GOOD, ttfb, reason = "直接视频流")
        var playlist = head.toString(Charsets.UTF_8)
        if (!playlist.trimStart().startsWith("#EXTM3U")) return Result(State.FAIL, ttfb, reason = "返回的不是视频（可能限制地区或已失效）")

        var base = finalUrl
        var need: Double? = null
        var height: Int? = null
        // 多码率列表：按播放器会选到的最高一档来测
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val v = bestVariant(playlist) ?: return Result(State.FAIL, ttfb, reason = "播放列表格式异常")
            need = v.bandwidth.takeIf { it > 0 }?.let { it / 1e6 }
            height = v.height
            try {
                val (vc, vFinal) = Http.connect(URL(URL(base), v.uri).toString(), TIMEOUT)
                playlist = vc.inputStream.use { it.readUpTo(256 * 1024) }.toString(Charsets.UTF_8)
                vc.disconnect()
                base = vFinal
            } catch (e: Exception) {
                return Result(State.FAIL, ttfb, needMbps = need, height = height, reason = "子列表打不开：" + describe(e))
            }
        }

        val seg = playlist.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return Result(State.FAIL, ttfb, needMbps = need, height = height, reason = "播放列表里没有视频分片（可能已停播）")
        val segDuration = Regex("#EXTINF:([\\d.]+)").findAll(playlist).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()

        return try {
            val (sc, _) = Http.connect(URL(URL(base), seg).toString(), TIMEOUT, mapOf("Range" to "bytes=0-${MAX_SAMPLE_BYTES - 1}"))
            try {
                // 没写码率时，用整段大小 ÷ 时长估算（咪咕就是这样）
                if (need == null && segDuration != null && segDuration > 0) {
                    val total = sc.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    if (total != null) need = total * 8 / segDuration / 1e6
                }
                // 码率越高取样越多（约 1.5 秒的量），否则 4K 这类高码率频道测不准
                val limit = (need?.let { it * 1e6 / 8 * 1.5 }?.toInt() ?: 0).coerceIn(SAMPLE_BYTES, MAX_SAMPLE_BYTES)
                val (n, speed) = sc.inputStream.use { measure(it, limit) }
                if (n < 1000) return Result(State.FAIL, ttfb, needMbps = need, height = height, reason = "视频分片几乎是空的")
                Result(judge(speed, need), ttfb, speed, need, height)
            } finally {
                sc.disconnect()
            }
        } catch (e: Exception) {
            Result(State.FAIL, ttfb, needMbps = need, height = height, reason = "视频分片下载失败：" + describe(e))
        }
    }

    /** 取样很短，速度会被低估，所以低于所需码率 7 成才判为可能卡顿 */
    fun judge(speedMbps: Double, needMbps: Double?): State =
        if (needMbps != null && speedMbps < needMbps * 0.7) State.SLOW else State.GOOD

    /** 排序：流畅（响应快的在前）→ 可能卡顿 → 没测过 → 打不开 */
    fun sortKey(r: Result?): Pair<Int, Long> = when (r) {
        null -> 2 to Long.MAX_VALUE
        else -> r.state.rank to (r.ttfbMs ?: Long.MAX_VALUE)
    }

    fun describe(e: Exception): String = when (e) {
        is HttpStatusException -> when (e.code) {
            403 -> "HTTP 403 拒绝访问（多为限制地区）"
            404, 410 -> "HTTP ${e.code} 地址已失效"
            401 -> "HTTP 401 需要登录"
            in 500..599 -> "HTTP ${e.code} 服务器故障"
            else -> "HTTP ${e.code}"
        }
        is UnknownHostException -> "域名解析失败"
        is SocketTimeoutException -> "连接超时"
        is ConnectException -> "连接被拒绝"
        is SSLException -> "加密连接失败（可能被拦截或代理不通）"
        is SocketException -> if (e.message?.contains("reset", true) == true) "连接被重置" else "网络错误"
        is IOException -> e.message ?: "网络错误"
        else -> e.javaClass.simpleName
    }

    data class Variant(val bandwidth: Long, val height: Int?, val uri: String)

    fun bestVariant(master: String): Variant? {
        val lines = master.lines()
        var best: Variant? = null
        lines.forEachIndexed { i, l ->
            if (!l.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
            val bw = Regex("[^-]BANDWIDTH=(\\d+)").find(",$l")?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val h = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull()
            val uri = lines.drop(i + 1).map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
            if (best == null || bw > best!!.bandwidth) best = Variant(bw, h, uri)
        }
        return best
    }

    /**
     * 读最多 limit 字节，返回（读到的字节数, Mbps）。
     * 速度只算前四分之一之后的部分：连接刚建立时 TCP 还在慢启动，算进去会明显低估。
     */
    private fun measure(input: InputStream, limit: Int): Pair<Int, Double> {
        val buf = ByteArray(64 * 1024)
        var n = 0
        var warm = -1
        var tWarm = System.nanoTime()
        val t0 = tWarm
        while (n < limit) {
            val r = input.read(buf, 0, minOf(buf.size, limit - n))
            if (r < 0) break
            n += r
            if (warm < 0 && n >= limit / 4) {
                warm = n
                tWarm = System.nanoTime()
            }
        }
        val tEnd = System.nanoTime()
        val (bytes, secs) = if (warm in 1 until n) (n - warm) to (tEnd - tWarm) / 1e9 else n to (tEnd - t0) / 1e9
        return n to bytes * 8 / maxOf(secs, 0.02) / 1e6
    }

    private fun InputStream.readUpTo(limit: Int): ByteArray {
        val buf = ByteArray(limit)
        var n = 0
        while (n < limit) {
            val r = read(buf, n, limit - n)
            if (r < 0) break
            n += r
        }
        return buf.copyOf(n)
    }
}
