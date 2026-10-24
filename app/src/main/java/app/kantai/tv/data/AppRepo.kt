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

    fun refreshAsync() {
        scope.launch { refresh(network = true) }
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

    private suspend fun refresh(network: Boolean) = mutex.withLock {
        loading.value = true
        try {
            val all = mutableListOf<Channel>()
            val epgUrls = LinkedHashSet<String>()
            val errors = mutableListOf<String>()

            for (s in sources.value) {
                val cache = File(dir, "pl_${s.id}.txt")
                val text = if (network) {
                    try {
                        Http.text(s.url).first.also { cache.writeText(it) }
                    } catch (e: Exception) {
                        errors += "${s.name}：${e.message}"
                        cache.takeIf { it.exists() }?.readText()
                    }
                } else cache.takeIf { it.exists() }?.readText()
                text ?: continue
                val r = M3uParser.parse(s.id, text)
                all += r.channels
                s.epgUrl.ifBlank { r.epgUrl }.takeIf { it.isNotBlank() }?.let(epgUrls::add)
            }
            channels.value = all
            status.value = buildString {
                append("共 ${all.size} 个频道")
                if (errors.isNotEmpty()) append("；更新失败：").append(errors.joinToString("；"))
            }

            val keys = all.flatMap { it.epgKeys }.toSet()
            val now = System.currentTimeMillis()
            var merged = Epg.EMPTY
            for (u in epgUrls) {
                val f = File(dir, "epg_${Integer.toHexString(u.hashCode())}.xml")
                if (network && (!f.exists() || now - f.lastModified() > 3 * HOUR)) {
                    runCatching { Http.download(u, f) }.onFailure { errors += "节目单：${it.message}" }
                }
                if (f.exists()) runCatching { EpgParser.parse(f, keys, now - 8 * DAY, now + 2 * DAY) }.onSuccess { merged += it }
            }
            if (merged.programmes.isNotEmpty() || epgUrls.isEmpty()) epg.value = merged
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
