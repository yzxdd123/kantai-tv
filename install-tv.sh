#!/bin/zsh
# 编译并通过局域网 adb 安装到电视：./install-tv.sh <电视IP>
# 需要 JDK 17 和 Android SDK（ANDROID_HOME），电视需打开开发者选项里的网络调试。
set -e
cd "$(dirname "$0")"
TV=${1:?用法：./install-tv.sh <电视IP>}

./gradlew assembleDebug --console=plain -q
adb connect "$TV:5555"
adb -s "$TV:5555" wait-for-device
adb -s "$TV:5555" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$TV:5555" shell am start -n app.kantai.tv/.MainActivity
