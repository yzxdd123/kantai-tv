package app.kantai.tv.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    const val UA = "okhttp/4.9.3"

    /** 手动跟随跳转（含 http↔https），返回连接和最终地址 */
    fun connect(url: String, timeoutMs: Int = 15_000): Pair<HttpURLConnection, String> {
        var u = url
        repeat(8) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            when {
                code in 300..399 -> {
                    val loc = c.getHeaderField("Location") ?: throw IOException("跳转缺少 Location")
                    c.disconnect()
                    u = URL(URL(u), loc).toString()
                }
                code >= 400 -> {
                    c.disconnect()
                    throw IOException("HTTP $code")
                }
                else -> return c to u
            }
        }
        throw IOException("跳转次数太多")
    }

    /** 返回 (内容, 最终地址) */
    fun text(url: String): Pair<String, String> {
        val (c, finalUrl) = connect(url)
        try {
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } to finalUrl
        } finally {
            c.disconnect()
        }
    }

    fun download(url: String, dest: File) {
        val (c, _) = connect(url, 30_000)
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        if (!tmp.renameTo(dest)) throw IOException("保存失败")
    }
}
