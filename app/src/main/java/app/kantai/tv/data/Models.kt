package app.kantai.tv.data

data class Source(
    val id: String,
    val name: String,
    val url: String,
    val epgUrl: String = "",
)

data class Channel(
    val sourceId: String,
    val index: Int,
    val name: String,
    val url: String,
    val group: String = "",
    val logo: String = "",
    val tvgId: String = "",
    val tvgName: String = "",
    val catchup: String = "",
    val catchupSource: String = "",
    val catchupDays: Int = 0,
) {
    /** 在所有源里唯一，同一个地址出现在多个分组也不会冲突 */
    val key: String get() = "$sourceId#$index"

    /** 用来匹配节目单的候选名字，按优先级排列 */
    val epgKeys: List<String> get() = listOf(tvgId, tvgName, name).filter { it.isNotBlank() }
}

/** 时间都是 epoch 毫秒 */
data class Programme(val start: Long, val stop: Long, val title: String)

class Epg(
    val programmes: Map<String, List<Programme>>,
    /** display-name → channel id */
    val alias: Map<String, String>,
    /** 节目大量重叠的 id：是多个频道合并的赛事列表，不是某个频道自己的节目单 */
    val merged: Set<String> = emptySet(),
    /** 时间格式错误、被跳过的节目数 */
    val skipped: Int = 0,
) {
    private fun resolve(ch: Channel): String? {
        for (k in ch.epgKeys) {
            if (k in programmes) return k
            alias[k]?.takeIf { it in programmes }?.let { return it }
        }
        return null
    }

    fun forChannel(ch: Channel): List<Programme> =
        resolve(ch)?.takeIf { it !in merged }?.let { programmes[it] } ?: emptyList()

    fun isMerged(ch: Channel): Boolean = resolve(ch) in merged

    /** 合并赛事列表里和 [start, stop) 时间段重叠的赛事；不是合并列表时为空 */
    fun mergedDuring(ch: Channel, start: Long, stop: Long): List<Programme> {
        val id = resolve(ch)?.takeIf { it in merged } ?: return emptyList()
        return programmes[id].orEmpty().filter { it.start < stop && it.stop > start }
    }

    fun nowPlaying(ch: Channel, now: Long): Programme? =
        forChannel(ch).firstOrNull { now >= it.start && now < it.stop }

    operator fun plus(other: Epg) = Epg(programmes + other.programmes, alias + other.alias, merged + other.merged, skipped + other.skipped)

    companion object {
        val EMPTY = Epg(emptyMap(), emptyMap())
    }
}
