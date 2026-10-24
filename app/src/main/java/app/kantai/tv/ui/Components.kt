package app.kantai.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Palette {
    val bg = Color(0xFF0E1116)
    val panel = Color(0xFF161B22)
    val focus = Color(0xFF1F6FEB)
    val selected = Color(0x33FFFFFF)
    val text = Color(0xFFE6EDF3)
    val muted = Color(0xFF8B949E)
    val accent = Color(0xFF4C8DFF)
    val live = Color(0xFFFF6B6B)
}

/** 电视遥控器可聚焦的行：聚焦时高亮；onClick 为空时仍可聚焦，方便滚动查看 */
@Composable
fun TvItem(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onFocus: () -> Unit = {},
    onClick: (() -> Unit)? = null,
    compact: Boolean = false,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val base = modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(if (focused) Palette.focus else if (selected) Palette.selected else Color.Transparent)
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocus()
        }
    Row(
        (if (onClick != null) base.clickable(onClick = onClick) else base.focusable())
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 7.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(focused) }
}

@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

@Composable
fun Tag(text: String, color: Color = Palette.accent) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

private val hm = SimpleDateFormat("HH:mm", Locale.CHINA)
private val mdhm = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
private val day = SimpleDateFormat("yyyyMMdd", Locale.CHINA)

fun clock(ms: Long): String = hm.format(Date(ms))

/** 今天只显示时间，其他日子带上日期 */
fun shortTime(ms: Long, now: Long): String =
    if (day.format(Date(ms)) == day.format(Date(now))) hm.format(Date(ms)) else mdhm.format(Date(ms))

fun lanIp(): String = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }?.hostAddress
}.getOrNull() ?: "电视IP"

fun qrBitmap(text: String, size: Int): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, size, 0, 0, size, size) }.asImageBitmap()
}
