package app.kantai.tv

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import app.kantai.tv.data.Channel
import app.kantai.tv.data.EpgParser
import app.kantai.tv.data.M3uParser
import app.kantai.tv.play.Players
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReliabilityTest {
    @Test
    fun epgSkipsOnlyBrokenProgrammes() {
        val xml = """
            <tv>
              <channel id="体育1"><display-name>体育1</display-name></channel>
              <programme start="20261024190000 +0800" stop="20261024200000 +0800" channel="体育1"><title>正常一</title></programme>
              <programme start="2026-10-24 20:00" stop="20261024210000 +0800" channel="体育1"><title>时间写错</title></programme>
              <programme start="20261024210000 +0800" stop="20261024220000 +0800" channel="体育1"><title>正常二</title></programme>
            </tv>
        """.trimIndent()
        val epg = EpgParser.parse(xml.byteInputStream(), setOf("体育1"), 0, Long.MAX_VALUE)
        assertEquals(listOf("正常一", "正常二"), epg.forChannel(Channel("s", 0, "体育1", "http://x", tvgId = "体育1")).map { it.title })
        assertEquals(1, epg.skipped)
    }

    @Test
    fun errorPageIsNotAPlaylist() {
        // 服务器 HTTP 200 却返回错误页：解析出 0 个频道，AppRepo 据此保留旧缓存
        val html = "<!DOCTYPE html><html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>"
        assertTrue(M3uParser.parse("s", html).channels.isEmpty())
        assertTrue(M3uParser.parse("s", "").channels.isEmpty())
        assertTrue(M3uParser.parse("s", "#EXTM3U\n").channels.isEmpty())
    }

    private fun httpError(code: Int) = PlaybackException(
        "x",
        HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), DataSpec.Builder().setUri("http://x").build(), ByteArray(0)),
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    )

    @Test
    fun retriesOnlyWhenItCanHelp() {
        assertTrue(Players.isRetryable(PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        assertTrue(Players.isRetryable(PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)))
        assertTrue(Players.isRetryable(httpError(503)))
        assertTrue(Players.isRetryable(httpError(429)))
        assertFalse(Players.isRetryable(httpError(403)))
        assertFalse(Players.isRetryable(httpError(404)))
        assertFalse(Players.isRetryable(PlaybackException("x", null, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)))
        assertFalse(Players.isRetryable(PlaybackException("x", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)))
    }
}
