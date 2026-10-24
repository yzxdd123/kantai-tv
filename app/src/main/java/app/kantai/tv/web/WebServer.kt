package app.kantai.tv.web

import android.content.Context
import app.kantai.tv.data.AppRepo
import app.kantai.tv.play.MiguReplay
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 电视上的局域网服务：
 * - `/`、`/api/` 手机管理直播源，需先用电视上显示的配对码配对
 * - `/replay/` 回看列表和分片跳转，只接受本机播放器访问
 */
class WebServer(private val ctx: Context) : NanoHTTPD(PORT) {
    companion object {
        const val PORT = 8899
    }

    val pin: String = (1000..9999).random().toString()
    private val prefs = ctx.getSharedPreferences("web", Context.MODE_PRIVATE)
    private var fails = 0
    private var lockUntil = 0L

    private val playlistRe = Regex("""/replay/migu/(\w+)\.m3u8""")
    private val segRe = Regex("""/replay/migu/seg/(\w+)/(\d{8})/(\d{6})/(\d+)\.ts""")

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: IllegalArgumentException) {
        json(Response.Status.BAD_REQUEST, err(e.message ?: "参数不对"))
    } catch (e: Exception) {
        json(Response.Status.INTERNAL_ERROR, err(e.message ?: "出错了"))
    }

    private fun route(s: IHTTPSession): Response {
        val uri = s.uri
        if (uri.startsWith("/replay/")) return replay(s)
        if (uri == "/" || uri == "/index.html") {
            val html = ctx.assets.open("index.html").use { it.readBytes().toString(Charsets.UTF_8) }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
        if (!uri.startsWith("/api/")) return json(Response.Status.NOT_FOUND, err("没有这个页面"))
        if (uri == "/api/pair") return pair(body(s))
        if (!authorized(s)) return json(Response.Status.UNAUTHORIZED, err("请先配对"))

        return when (uri) {
            "/api/status" -> status()
            "/api/sources/add" -> body(s).let { AppRepo.addSource(it.optString("name"), it.optString("url"), it.optString("epgUrl")); ok() }
            "/api/sources/update" -> body(s).let { AppRepo.updateSource(it.getString("id"), it.optString("name"), it.optString("url"), it.optString("epgUrl")); ok() }
            "/api/sources/delete" -> body(s).let { AppRepo.deleteSource(it.getString("id")); ok() }
            "/api/refresh" -> { AppRepo.refreshAsync(); ok() }
            "/api/play" -> body(s).let {
                val url = it.optString("url").trim()
                require(url.contains("://")) { "地址要以 http:// 或 https:// 开头" }
                AppRepo.requestPlay(url, it.optString("name"))
                ok()
            }
            else -> json(Response.Status.NOT_FOUND, err("没有这个接口"))
        }
    }

    private fun replay(s: IHTTPSession): Response {
        val ip = s.remoteIpAddress
        if (ip != "127.0.0.1" && ip != "::1" && ip != "0:0:0:0:0:0:0:1") return json(Response.Status.FORBIDDEN, err("仅限本机"))

        playlistRe.matchEntire(s.uri)?.let { m ->
            val q = s.parms
            val text = MiguReplay.playlist(m.groupValues[1], q["start"]?.toLongOrNull() ?: 0, q["end"]?.toLongOrNull() ?: 0)
                ?: return json(Response.Status.NOT_FOUND, err("回看已失效"))
            return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", text)
        }
        segRe.matchEntire(s.uri)?.let { m ->
            val (id, day, hms, n) = m.destructured
            val loc = MiguReplay.segmentUrl(id, day, hms, n) ?: return json(Response.Status.NOT_FOUND, err("回看已失效"))
            return newFixedLengthResponse(Response.Status.FOUND, "text/plain", "").apply { addHeader("Location", loc) }
        }
        return json(Response.Status.NOT_FOUND, err("没有这个地址"))
    }

    @Synchronized
    private fun pair(b: JSONObject): Response {
        val now = System.currentTimeMillis()
        if (now < lockUntil) return json(Response.Status.FORBIDDEN, err("错太多次了，请 1 分钟后再试"))
        if (b.optString("pin") != pin) {
            if (++fails >= 5) {
                fails = 0
                lockUntil = now + 60_000
            }
            return json(Response.Status.UNAUTHORIZED, err("配对码不对，请看电视上显示的数字"))
        }
        fails = 0
        val token = UUID.randomUUID().toString()
        prefs.edit().putStringSet("tokens", tokens() + token).apply()
        return json(Response.Status.OK, JSONObject().put("token", token))
    }

    private fun tokens(): Set<String> = prefs.getStringSet("tokens", emptySet()) ?: emptySet()

    private fun authorized(s: IHTTPSession): Boolean = s.headers["x-token"]?.let { it in tokens() } == true

    private fun status(): Response {
        val channels = AppRepo.channels.value
        val arr = JSONArray()
        AppRepo.sources.value.forEach { src ->
            arr.put(
                JSONObject().put("id", src.id).put("name", src.name).put("url", src.url).put("epgUrl", src.epgUrl)
                    .put("channels", channels.count { it.sourceId == src.id })
            )
        }
        return json(
            Response.Status.OK,
            JSONObject().put("sources", arr).put("status", AppRepo.status.value).put("loading", AppRepo.loading.value)
        )
    }

    /** 请求体必须带 charset=utf-8，否则 NanoHTTPD 按 ASCII 解码中文会乱码 */
    private fun body(s: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        s.parseBody(files)
        return JSONObject(files["postData"]?.ifBlank { null } ?: "{}")
    }

    private fun ok() = json(Response.Status.OK, JSONObject().put("ok", true))
    private fun err(msg: String) = JSONObject().put("error", msg)
    private fun json(st: Response.Status, o: JSONObject) =
        newFixedLengthResponse(st, "application/json; charset=utf-8", o.toString())
}
