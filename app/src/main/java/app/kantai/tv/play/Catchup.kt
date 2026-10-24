package app.kantai.tv.play

import app.kantai.tv.data.Channel
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 回看地址生成：咪咕走 CDN 历史分片，其他源用直播源自带的 catchup 配置。 */
object Catchup {
    private val fmtRe = Regex("""\$?\{\((b|e)\)([^}:]+)(:utc)?\}""")

    fun supports(ch: Channel): Boolean =
        MiguReplay.isMigu(ch.url) || ch.catchupSource.isNotBlank() || ch.catchup.lowercase() in setOf("shift", "timeshift", "flussonic", "fs")

    fun days(ch: Channel): Int = when {
        ch.catchupDays > 0 -> ch.catchupDays
        MiguReplay.isMigu(ch.url) -> MiguReplay.DAYS
        else -> 7
    }

    fun buildUrl(ch: Channel, start: Long, stop: Long): String? {
        val mode = ch.catchup.lowercase()
        return when {
            // 实测咪咕服务器忽略 playseek，固定走 CDN 分片
            MiguReplay.isMigu(ch.url) -> MiguReplay.playlistUrl(ch.url, start, stop)
            ch.catchupSource.isNotBlank() -> {
                val filled = fill(ch.catchupSource, start, stop)
                if (filled.contains("://")) filled else join(ch.url, filled)
            }
            mode == "shift" || mode == "timeshift" ->
                join(ch.url, "?utc=${start / 1000}&lutc=${System.currentTimeMillis() / 1000}")
            mode == "flussonic" || mode == "fs" -> flussonic(ch.url, start, stop)
            else -> null
        }
    }

    /** 支持 TiviMate/Kodi 常见占位符 */
    fun fill(template: String, start: Long, stop: Long): String {
        val now = System.currentTimeMillis()
        var s = fmtRe.replace(template) { m ->
            val ms = if (m.groupValues[1] == "b") start else stop
            val zone = if (m.groupValues[3].isNotEmpty()) ZoneOffset.UTC else ZoneId.systemDefault()
            DateTimeFormatter.ofPattern(m.groupValues[2]).format(Instant.ofEpochMilli(ms).atZone(zone))
        }
        val z = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())
        val values = linkedMapOf(
            "{utcend}" to stop / 1000, "{utc}" to start / 1000,
            "\${start}" to start / 1000, "\${end}" to stop / 1000,
            "{start}" to start / 1000, "{end}" to stop / 1000,
            "{lutc}" to now / 1000, "\${now}" to now / 1000, "\${timestamp}" to now / 1000, "{timestamp}" to now / 1000,
            "{duration}" to (stop - start) / 1000, "\${duration}" to (stop - start) / 1000,
            "{offset}" to (now - start) / 1000, "\${offset}" to (now - start) / 1000,
        )
        values.forEach { (k, v) -> s = s.replace(k, v.toString()) }
        return s.replace("{Y}", "%04d".format(z.year))
            .replace("{m}", "%02d".format(z.monthValue))
            .replace("{d}", "%02d".format(z.dayOfMonth))
            .replace("{H}", "%02d".format(z.hour))
            .replace("{M}", "%02d".format(z.minute))
            .replace("{S}", "%02d".format(z.second))
    }

    private fun join(url: String, suffix: String): String = when {
        suffix.startsWith("?") && url.contains("?") -> url + "&" + suffix.drop(1)
        suffix.startsWith("&") && !url.contains("?") -> url + "?" + suffix.drop(1)
        else -> url + suffix
    }

    private fun flussonic(url: String, start: Long, stop: Long): String? {
        val st = start / 1000
        val dur = (stop - start) / 1000
        val hls = Regex("""/(index|video|mono)\.m3u8""")
        return when {
            hls.containsMatchIn(url) -> hls.replace(url) { "/${it.groupValues[1]}-$st-$dur.m3u8" }
            url.contains("/mpegts") -> url.replace("/mpegts", "/timeshift_abs-$st.ts")
            else -> null
        }
    }
}
