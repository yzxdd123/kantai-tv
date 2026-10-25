package app.kantai.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 直播源、频道、节目单的唯一数据来源。电视界面和手机网页都通过它读写。 */
object AppRepo {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    val sources = MutableStateFlow<List<Source>>(emptyList())
    val channels = MutableStateFlow<List<Channel>>(emptyList())
    val epg = MutableStateFlow(Epg.EMPTY)
    val status = MutableStateFlow("")
    val loading = MutableStateFlow(false)
    /** 手机“推送到电视播放” */
    val playRequests = MutableSharedFlow<Channel>(extraBufferCapacity = 4)

    fun init(ctx: Context) {
        dir = ctx.filesDir
        sources.value = loadSources()
        scope.launch {
            refresh(network = false) // 先用缓存秒开
            refresh(network = true)
        }
    }

    /** force：手动“立即刷新”，节目单也不看缓存有效期，重新下载 */
    fun refreshAsync(force: Boolean = false) {
        scope.launch { refresh(network = true, force = force) }
    }

    fun addSource(name: String, url: String, epgUrl: String) {
        val u = url.trim()
        require(u.contains("://")) { "地址要以 http:// 或 https:// 开头" }
        val s = Source(UUID.randomUUID().toString().take(8), name.trim().ifBlank { guessName(u) }, u, epgUrl.trim())
        sources.update { it + s }
        saveSources()
        refreshAsync()
    }

    fun updateSource(id: String, name: String, url: String, epgUrl: String) {
        require(url.trim().contains("://")) { "地址要以 http:// 或 https:// 开头" }
        sources.update { list ->
            list.map { if (it.id == id) it.copy(name = name.trim().ifBlank { guessName(url) }, url = url.trim(), epgUrl = epgUrl.trim()) else it }
        }
        saveSources()
        refreshAsync()
    }

    fun deleteSource(id: String) {
        sources.update { list -> list.filterNot { it.id == id } }
        File(dir, "pl_$id.txt").delete()
        saveSources()
        refreshAsync()
    }

    fun requestPlay(url: String, name: String) {
        playRequests.tryEmit(Channel("push", 0, name.ifBlank { "手机推送" }, url.trim(), "推送"))
    }

    private suspend fun refresh(network: Boolean, force: Boolean = false) = mutex.withLock {
        loading.value = true
        try {
            val all = mutableListOf<Channel>()
            val epgUrls = LinkedHashSet<String>()
            val errors = mutableListOf<String>()

            for (s in sources.value) {
                val cache = File(dir, "pl_${s.id}.txt")
                val cached = { cache.takeIf { it.exists() }?.readText()?.let { M3uParser.parse(s.id, it) } }
                // 先解析新下载的内容，确认真的是直播列表再替换缓存；否则继续用上次的
                val r = if (network) {
                    try {
                        val text = Http.text(s.url).first
                        val fresh = M3uParser.parse(s.id, text)
                        if (fresh.channels.isEmpty()) {
                            errors += "${s.name}：下载到的内容里没有频道" + if (cache.exists()) "，继续用上次的列表" else ""
                            cached()
                        } else {
                            cache.writeText(text)
                            fresh
                        }
                    } catch (e: Exception) {
                        errors += "${s.name}：${e.message}" + if (cache.exists()) "，继续用上次的列表" else ""
                        cached()
                    }
                } else cached()
                r ?: continue
                all += r.channels
                s.epgUrl.ifBlank { r.epgUrl }.takeIf { it.isNotBlank() }?.let(epgUrls::add)
            }
            channels.value = all
            fun report() {
                status.value = buildString {
                    append("共 ${all.size} 个频道")
                    if (errors.isNotEmpty()) append("；").append(errors.joinToString("；"))
                }
            }
            report()

            val keys = all.flatMap { it.epgKeys }.toSet()
            val now = System.currentTimeMillis()
            var merged = Epg.EMPTY
            for (u in epgUrls) {
                val f = File(dir, "epg_${Integer.toHexString(u.hashCode())}.xml")
                if (network && (force || !f.exists() || now - f.lastModified() > 3 * HOUR)) {
                    // 下载到新文件，能解析才替换旧节目单
                    val fresh = File(dir, f.name + ".new")
                    try {
                        Http.download(u, fresh)
                        EpgParser.parse(fresh, keys, now - 8 * DAY, now + 2 * DAY)
                        if (!fresh.renameTo(f)) throw java.io.IOException("保存失败")
                    } catch (e: Exception) {
                        fresh.delete()
                        errors += "节目单更新失败（${e.message ?: e.javaClass.simpleName}）" + if (f.exists()) "，继续用上次的" else ""
                    }
                }
                if (f.exists()) {
                    runCatching { EpgParser.parse(f, keys, now - 8 * DAY, now + 2 * DAY) }
                        .onSuccess {
                            merged += it
                            if (it.programmes.isEmpty()) errors += "节目单里没有匹配到这些频道"
                            if (it.skipped > 0) errors += "节目单有 ${it.skipped} 条时间格式错误，已跳过"
                        }
                        .onFailure { errors += "节目单无法解析（${it.message ?: it.javaClass.simpleName}）" }
                }
            }
            if (merged.programmes.isNotEmpty() || epgUrls.isEmpty()) epg.value = merged
            report()
        } finally {
            loading.value = false
        }
    }

    private fun guessName(url: String) = url.substringAfter("://").substringBefore('/').ifBlank { "直播源" }

    private fun loadSources(): List<Source> {
        val f = File(dir, "sources.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Source(o.getString("id"), o.getString("name"), o.getString("url"), o.optString("epgUrl"))
            }
        }.getOrDefault(emptyList())
    }

    private fun saveSources() {
        val arr = JSONArray()
        sources.value.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("epgUrl", it.epgUrl)) }
        File(dir, "sources.json").writeText(arr.toString())
    }
}
