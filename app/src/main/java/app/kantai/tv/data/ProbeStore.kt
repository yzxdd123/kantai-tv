package app.kantai.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File

/** 测速结果（按播放地址记，直播源重新排序也不会对错频道）、测速进度、排序方式。 */
object ProbeStore {
    /** 同时测几个频道：太多会挤占正在看的节目带宽 */
    private const val PARALLEL = 6
    /** 结果超过 7 天就不再使用 */
    private const val MAX_AGE = 7 * 24 * 3_600_000L

    data class Progress(val done: Int, val total: Int)

    private lateinit var file: File
    private lateinit var settings: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    val results = MutableStateFlow<Map<String, Prober.Result>>(emptyMap())
    val progress = MutableStateFlow<Progress?>(null)
    val sortBySpeed = MutableStateFlow(false)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "probe.json")
        settings = File(ctx.filesDir, "probe_settings.json")
        results.value = load()
        sortBySpeed.value = runCatching { JSONObject(settings.readText()).optBoolean("sortBySpeed") }.getOrDefault(false)
    }

    fun result(ch: Channel): Prober.Result? = results.value[ch.url]

    fun setSortBySpeed(on: Boolean) {
        sortBySpeed.value = on
        runCatching { settings.writeText(JSONObject().put("sortBySpeed", on).toString()) }
    }

    fun isRunning() = job?.isActive == true

    /** 测一批频道；正在测时再按一次就停止 */
    fun toggle(channels: List<Channel>) {
        if (isRunning()) {
            job?.cancel()
            progress.value = null
            return
        }
        val targets = channels.distinctBy { it.url }
        if (targets.isEmpty()) return
        progress.value = Progress(0, targets.size)
        job = scope.launch {
            val gate = Semaphore(PARALLEL)
            try {
                targets.map { ch ->
                    async {
                        gate.withPermit {
                            val r = Prober.probe(ch.url)
                            results.update { it + (ch.url to r) }
                            progress.update { p -> p?.copy(done = p.done + 1) }
                        }
                    }
                }.awaitAll()
            } finally {
                save()
                progress.value = null
            }
        }
    }

    /** 列表顺序或按测速排序：流畅（响应快的在前）→ 可能卡顿 → 没测过 → 打不开 */
    fun sort(channels: List<Channel>, bySpeed: Boolean, map: Map<String, Prober.Result>): List<Channel> =
        if (!bySpeed) channels
        else channels.withIndex().sortedWith(compareBy({ Prober.sortKey(map[it.value.url]).first }, { Prober.sortKey(map[it.value.url]).second }, { it.index }))
            .map { it.value }

    private fun load(): Map<String, Prober.Result> = runCatching {
        val o = JSONObject(file.readText())
        val now = System.currentTimeMillis()
        o.keys().asSequence().mapNotNull { url ->
            val r = o.getJSONObject(url)
            val at = r.optLong("at")
            if (now - at > MAX_AGE) return@mapNotNull null
            url to Prober.Result(
                state = Prober.State.valueOf(r.getString("state")),
                ttfbMs = r.optLong("ttfb", -1).takeIf { it >= 0 },
                speedMbps = r.optDouble("speed").takeIf { !it.isNaN() },
                needMbps = r.optDouble("need").takeIf { !it.isNaN() },
                height = r.optInt("height", -1).takeIf { it > 0 },
                reason = r.optString("reason"),
                testedAt = at,
            )
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun save() {
        val o = JSONObject()
        results.value.forEach { (url, r) ->
            o.put(url, JSONObject().apply {
                put("state", r.state.name)
                r.ttfbMs?.let { put("ttfb", it) }
                r.speedMbps?.let { put("speed", it) }
                r.needMbps?.let { put("need", it) }
                r.height?.let { put("height", it) }
                put("reason", r.reason)
                put("at", r.testedAt)
            })
        }
        runCatching { file.writeText(o.toString()) }
    }
}
