# 看台 Kantai TV

[中文](#中文) · [English](#english)

---

## 中文

一个给 Android TV 用的免费开源直播播放器：导入你自己的直播源，用遥控器看直播、翻节目单、回看。界面和操作参考了 TiviMate 这类电视播放器，但代码全部从头写，和 TiviMate 没有任何关系。

> **本项目不提供任何直播源。** 请自行添加你有权观看的直播源，并遵守当地法律和内容提供方的条款。

### 下载安装

到 [Releases](../../releases/latest) 下载 `Kantai-TV-*.apk`，用下面任一方式装到电视（需要 Android 8.0 及以上）：

- **U 盘**：APK 拷到 U 盘，插电视，用电视上的文件管理器打开安装（第一次需要允许“安装未知来源应用”）
- **局域网 adb**：电视打开开发者选项里的网络调试，然后在电脑上运行 `adb connect 电视IP:5555`、`adb install Kantai-TV-*.apk`

装好后打开 App，会显示一个二维码和配对码：手机连同一个 Wi-Fi 扫码，输入配对码，就能在手机上添加直播源。

### 功能

- **导入直播源**：M3U / M3U8，以及国内常见的 TXT 格式（`名字,地址` + `分组,#genre#`）；支持多个源
- **节目单网格**：频道 × 时间的节目单（XMLTV，支持 `.xml.gz`），红线标出当前时间，左上角显示节目详情和**实时预览小窗**
- **回看**
  - 直播源自带回看配置时（`catchup-source`、`playseek`、`shift`、`flussonic` 等），按源的方式回看
  - 咪咕类直播源：利用 CDN 上按时间命名的历史分片拼出点播列表，在节目单里选中已播完的节目即可回看（默认 3 天内）
  - 节目单不可用但支持回看的频道，按小时切块，可按时间回看
- **手机管理**：电视上开一个只限局域网的网页（`http://电视IP:8899`），手机扫码、输入电视上显示的配对码后，可以添加 / 修改 / 删除直播源，或把一个地址推送到电视直接播放
- **可播性测速与排序**：不测 ping（经过 Clash 等 Fake-IP 代理时所有地址都只有几毫秒，没有意义），而是按播放器的实际流程测——打开播放列表的响应时间、取样下载视频的速度、是否够这个频道的码率。频道旁显示绿色响应时间、黄色“慢”或红色“✕”，打不开的会写明原因（例如 HTTP 403 多为限制地区）；可切换为“按测速”排序：流畅 → 可能卡顿 → 没测过 → 打不开
- **节目自带字幕**：显示视频流里自带的字幕（美国频道常见的 CEA-608 隐藏字幕、HLS 里的 WebVTT 等），全屏时按屏显键开关
- **视频统计信息**：全屏时按菜单键或长按确认键，显示分辨率、编码、帧率、实测码率、下载速度、缓冲、丢帧、解码器、字幕、音频
- 播放失败时显示原因（服务器拒绝、返回的不是视频、连不上、电视不支持该格式等）

### 遥控器操作

| 位置 | 按键 | 作用 |
| --- | --- | --- |
| 节目单 | 上 / 下 | 换频道 |
| 节目单 | 左 / 右 | 前后选节目（往左最多到可回看的最早节目） |
| 节目单 | 确认 | 直播节目全屏观看；已播完的节目回看 |
| 节目单 | 返回 | 先回到“现在”，再按跳到左侧分组 |
| 节目单 | 菜单 | 测速当前分组（再按一次停止） |
| 分组栏 | “⚡ 测速全部频道” / “↕ 当前排序” | 测速所有频道；在列表顺序和按测速排序之间切换 |
| 分组 | 返回 | 再按一次退出 |
| 全屏直播 | 上 / 下 | 换台 |
| 全屏直播 | 确认 | 频道列表 |
| 全屏回看 | 左 / 右、上 / 下 | 快退快进 30 秒、5 分钟 |
| 全屏回看 | 确认 | 暂停 / 继续 |
| 全屏 | 屏显（或数字 0、字幕键） | 开关节目自带字幕 |
| 全屏 | 菜单 或 长按确认 | 视频统计信息 |

### 自己编译

需要 JDK 17 和 Android SDK（platform 35、build-tools 34）。

```bash
./gradlew assembleDebug
```

电视打开网络调试后，可以一步编译并安装：

```bash
./install-tv.sh <电视IP>
```

技术栈：Kotlin、Jetpack Compose、Media3 / ExoPlayer、NanoHTTPD、ZXing、Coil。

### 致谢

- [YanG-1989/m3u](https://github.com/YanG-1989/m3u)：开发过程中用它公开的直播源和节目单做测试，咪咕 CDN 回看的思路也来自对这类源的研究。感谢作者的公开分享。

### 作者

- Yan Zhanxi（[@yzxdd123](https://github.com/yzxdd123)）
- Claude（Anthropic 的 AI 助手）

### 许可证

[MIT](LICENSE)

---

## English

A free, open-source live TV player for Android TV. Bring your own playlists, then watch live channels, browse the TV guide and replay past programmes with your remote. The UI is inspired by TV players such as TiviMate, but all code is written from scratch and has no affiliation with TiviMate.

> **This project does not provide any streams or playlists.** Only add sources you have the right to watch, and follow your local laws and the content providers' terms.

### Download

Get `Kantai-TV-*.apk` from [Releases](../../releases/latest) and install it on your TV (Android 8.0+):

- **USB drive**: copy the APK to a USB drive, plug it into the TV and open it with a file manager (allow "install unknown apps" the first time)
- **adb over Wi-Fi**: enable network debugging in the TV's developer options, then run `adb connect <TV-IP>:5555` and `adb install Kantai-TV-*.apk`

On first launch the app shows a QR code and a pairing code. Scan it with a phone on the same Wi-Fi, enter the code, and add your playlists from the phone.

### Features

- **Playlists**: M3U / M3U8 and the common Chinese TXT format (`name,url` with `group,#genre#`); multiple sources
- **Grid TV guide**: channels × time (XMLTV, `.xml.gz` supported), a red "now" line, programme details and a **live preview window**
- **Catch-up / replay**
  - Uses the source's own catch-up settings when present (`catchup-source`, `playseek`, `shift`, `flussonic`, …)
  - For Migu-style sources, rebuilds a VOD playlist from the time-named history segments still on the CDN (last 3 days by default)
  - Channels with replay but no usable guide get hourly blocks you can replay by time
- **Manage from your phone**: a LAN-only web page on the TV (`http://<TV-IP>:8899`). After pairing with the code shown on the TV you can add, edit and delete sources, or push any stream URL to play on the TV
- **Playability speed test and sorting**: no ping (behind Fake-IP proxies such as Clash every host "responds" in a few milliseconds, which tells you nothing). Instead it follows what the player does: playlist response time, a sampled video download speed, and whether that speed covers the channel's bitrate. Channels show a green response time, a yellow "慢" (slow) or a red "✕" with the reason (for example HTTP 403, usually a regional block). Optional sorting by result: smooth → may stutter → untested → unreachable
- **Built-in subtitles**: shows subtitles carried in the stream (CEA-608 closed captions common on US channels, WebVTT in HLS, …); toggle with the Display (屏显) key in fullscreen
- **Stats for nerds**: in fullscreen press Menu or long-press OK to see resolution, codec, frame rate, measured bitrate, download speed, buffer, dropped frames, decoder, subtitles and audio
- Clear error messages when a stream fails (server refused, not a video, unreachable, unsupported format, …)

### Remote control

| Where | Key | Action |
| --- | --- | --- |
| Guide | Up / Down | Change channel |
| Guide | Left / Right | Previous / next programme (left stops at the earliest replayable one) |
| Guide | OK | Watch live in fullscreen, or replay a finished programme |
| Guide | Back | Jump back to "now", then to the group list |
| Guide | Menu | Speed-test the current group (press again to stop) |
| Group column | "⚡ 测速全部频道" / "↕ 当前排序" | Speed-test all channels; switch between playlist order and speed order |
| Groups | Back | Press again to exit |
| Fullscreen live | Up / Down | Change channel |
| Fullscreen live | OK | Channel list |
| Fullscreen replay | Left / Right, Up / Down | Seek 30 s, 5 min |
| Fullscreen replay | OK | Pause / resume |
| Fullscreen | Display / 屏显 (or 0, or the CC key) | Subtitles on / off |
| Fullscreen | Menu or long-press OK | Stats |

### Build from source

Requires JDK 17 and the Android SDK (platform 35, build-tools 34).

```bash
./gradlew assembleDebug
```

With network debugging enabled on the TV, build and install in one step:

```bash
./install-tv.sh <TV-IP>
```

Built with Kotlin, Jetpack Compose, Media3 / ExoPlayer, NanoHTTPD, ZXing and Coil.

### Thanks

- [YanG-1989/m3u](https://github.com/YanG-1989/m3u): its public playlists and guide data were used for testing during development, and studying such sources led to the Migu CDN replay approach. Thanks to the author for sharing.

### Authors

- Yan Zhanxi ([@yzxdd123](https://github.com/yzxdd123))
- Claude (an AI assistant by Anthropic)

### License

[MIT](LICENSE)
