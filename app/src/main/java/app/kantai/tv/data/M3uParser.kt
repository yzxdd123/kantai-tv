package app.kantai.tv.data

/** 解析 M3U/M3U8 频道列表，也兼容国内常见的 "名字,地址" + "分组,#genre#" TXT 格式。 */
object M3uParser {
    data class Result(val channels: List<Channel>, val epgUrl: String)

    private val attrRe = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(sourceId: String, text: String): Result {
        val body = text.removePrefix("﻿")
        return if (body.contains("#EXTINF") || body.trimStart().startsWith("#EXTM3U")) parseM3u(sourceId, body)
        else parseTxt(sourceId, body)
    }

    private fun attrs(line: String) = attrRe.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    private fun parseM3u(sourceId: String, text: String): Result {
        val out = mutableListOf<Channel>()
        var epg = ""
        var headerCatchup = emptyMap<String, String>()
        var info: Map<String, String>? = null
        var title = ""
        var extGroup = ""

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> {
                    val a = attrs(line)
                    epg = (a["x-tvg-url"] ?: a["url-tvg"] ?: "").split(',').first().trim()
                    headerCatchup = a
                }
                line.startsWith("#EXTINF") -> {
                    info = attrs(line)
                    val lastQuote = line.lastIndexOf('"')
                    val comma = line.indexOf(',', maxOf(lastQuote, 0))
                    title = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    extGroup = ""
                }
                line.startsWith("#EXTGRP:") -> extGroup = line.removePrefix("#EXTGRP:").trim()
                line.startsWith("#") -> Unit
                // 有的列表会放一条说明文档链接，不是视频
                line.substringBefore('?').let { it.endsWith(".txt") || it.endsWith(".md") } -> info = null
                else -> {
                    val a = info ?: emptyMap()
                    out += Channel(
                        sourceId = sourceId,
                        index = out.size,
                        name = title.ifBlank { a["tvg-name"] ?: line },
                        url = line,
                        group = a["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup,
                        logo = a["tvg-logo"] ?: "",
                        tvgId = a["tvg-id"] ?: "",
                        tvgName = a["tvg-name"] ?: "",
                        catchup = a["catchup"] ?: headerCatchup["catchup"] ?: "",
                        catchupSource = a["catchup-source"] ?: headerCatchup["catchup-source"] ?: "",
                        catchupDays = (a["catchup-days"] ?: headerCatchup["catchup-days"])?.toIntOrNull() ?: 0,
                    )
                    info = null
                    title = ""
                }
            }
        }
        return Result(out, epg)
    }

    private fun parseTxt(sourceId: String, text: String): Result {
        val out = mutableListOf<Channel>()
        var group = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            val comma = line.indexOf(',')
            if (comma < 0) continue
            val name = line.substring(0, comma).trim()
            val rest = line.substring(comma + 1).trim()
            if (rest.startsWith("#genre#")) { group = name; continue }
            // 一行多个地址用 # 分隔时取第一个
            val url = rest.split('#').first().trim()
            if (url.contains("://")) out += Channel(sourceId, out.size, name, url, group)
        }
        return Result(out, "")
    }
}
