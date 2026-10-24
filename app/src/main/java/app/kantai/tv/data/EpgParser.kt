package app.kantai.tv.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

/** XMLTV 节目单解析；只保留播放列表里用得到的频道和时间窗口内的节目，省内存。 */
object EpgParser {
    private val fmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun parse(file: File, keys: Set<String>, from: Long, to: Long): Epg =
        open(file).use { parse(it, keys, from, to) }

    private fun open(file: File): InputStream {
        val input = BufferedInputStream(file.inputStream())
        input.mark(2)
        val gz = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        return if (gz) BufferedInputStream(GZIPInputStream(input, 64 * 1024)) else input
    }

    fun parse(input: InputStream, keys: Set<String>, from: Long, to: Long): Epg {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)

        val alias = HashMap<String, String>()
        val wantedIds = HashSet<String>()
        val progs = HashMap<String, MutableList<Programme>>()
        var curChannel: String? = null
        var pChannel: String? = null
        var pStart = 0L
        var pStop = 0L
        var pTitle = ""

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> {
                        curChannel = p.getAttributeValue(null, "id")
                        if (curChannel != null && curChannel in keys) wantedIds += curChannel
                    }
                    "display-name" -> curChannel?.let { id ->
                        val n = p.nextText().trim()
                        if (n in keys) {
                            wantedIds += id
                            alias.putIfAbsent(n, id)
                        }
                    }
                    "programme" -> {
                        val id = p.getAttributeValue(null, "channel")
                        pStart = time(p.getAttributeValue(null, "start"))
                        pStop = time(p.getAttributeValue(null, "stop"))
                        pTitle = ""
                        pChannel = if (id != null && (id in wantedIds || id in keys) && pStop > from && pStart < to) id else null
                    }
                    "title" -> if (pChannel != null && pTitle.isEmpty()) pTitle = p.nextText().trim()
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "channel" -> curChannel = null
                    "programme" -> {
                        pChannel?.let { progs.getOrPut(it) { mutableListOf() } += Programme(pStart, pStop, pTitle) }
                        pChannel = null
                    }
                }
            }
            ev = p.next()
        }
        progs.values.forEach { list -> list.sortBy { it.start } }
        return Epg(progs, alias, progs.filterValues { isMergedList(it) }.keys)
    }

    /**
     * 一个频道同一时间只能播一个节目。超过 2 成节目和前面的重叠，
     * 说明这是多个频道合并出来的赛事列表（例如 YanG 源的“咪咕体育”），
     * 偶尔一两处重叠（节目单小错误）不算。
     */
    fun isMergedList(sorted: List<Programme>): Boolean {
        if (sorted.size < 5) return false
        var maxStop = Long.MIN_VALUE
        var overlaps = 0
        for (p in sorted) {
            if (p.start < maxStop - 60_000) overlaps++
            maxStop = maxOf(maxStop, p.stop)
        }
        return overlaps > sorted.size / 5
    }

    /** "20261024190000 +0800"；没写时区时按北京时间 */
    private fun time(s: String?): Long {
        if (s == null || s.length < 14) return 0
        val ldt = LocalDateTime.parse(s.substring(0, 14), fmt)
        val off = s.substring(14).trim()
        val zone = if (off.length >= 5) ZoneOffset.of(off.substring(0, 3) + ":" + off.substring(3, 5)) else ZoneOffset.ofHours(8)
        return ldt.toInstant(zone).toEpochMilli()
    }
}
