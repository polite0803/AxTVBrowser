#!/usr/bin/env bash
# AxTVBrowser 开发环境统一入口 —— 只在 WSL 原生 ext4 路径运行。
#
# Windows 侧调用（一次 SSH 会话批量执行，禁止逐条 ssh）:
#   ssh -o BatchMode=yes wsl 'bash /home/polit/projects/AxTVBrowser/scripts/axtvbrowser-dev.sh <cmd> [args]'
# WSL 侧调用:
#   /home/polit/projects/AxTVBrowser/scripts/axtvbrowser-dev.sh <cmd> [args]
#
# 唯一真相: /home/polit/projects/AxTVBrowser (ext4)。\\wsl.localhost / Z: 是 SMB 视图,
# 禁止在其上执行 gradlew / adb / 全库搜索。
#
# 【JDK 锁定 —— 两层，缺一不可】
#   1. 启动器（Gradle launcher）锁 17：本脚本显式 export JAVA_HOME 到 JDK 17，
#      不继承调用方环境（机器全局 JAVA_HOME 指向 Corretto 21），并在启动前断言
#      java.specification.version == 17；同时剥离 JAVA_TOOL_OPTIONS / _JAVA_OPTIONS /
#      GRADLE_OPTS，防止外部 JVM 参数把启动器换掉。
#   2. 构建 JVM 由仓库内 gradle/gradle-daemon-jvm.properties 锁定（toolchainVersion=21,
#      vendor=JETBRAINS）：Gradle 会忽略启动器，强制 fork 到匹配的 JDK 21 上执行构建。
#      这是检出即生效、对 CI 同样成立的锁；本脚本的 env 子命令会把 Gradle 实际 fork
#      出来的 java 路径抓出来复检，而不是只相信配置文件。
#   因此「谁改了 JAVA_HOME 就会用错 JDK」在 AxTVBrowser 上不成立：
#     启动器 17（与 AxTV 一致） -> 构建 JVM 21（仓库声明，与 CI 一致）。
set -euo pipefail

ROOT=/home/polit/projects/AxTVBrowser
SDK=/home/polit/android-sdk
JDK=/usr/lib/jvm/java-17-openjdk-amd64
REQUIRED_LAUNCHER_MAJOR=17

if [ "$(uname -s)" != "Linux" ]; then
  echo "错误：只能在 WSL/Linux 执行。请改用：" >&2
  echo "  ssh -o BatchMode=yes wsl 'bash $ROOT/scripts/axtvbrowser-dev.sh <cmd>'" >&2
  exit 2
fi
case "$ROOT" in
  /mnt/*|/z/*|/Z/*) echo "错误：项目位于 Windows 挂载盘，拒绝构建" >&2; exit 2 ;;
esac
[ -x "$JDK/bin/java" ] || { echo "错误：JDK 17 不存在: $JDK" >&2; exit 2; }

# 取大版本用 java.specification.version，避免解析 "17.0.21.1+2-1-24.04.1-Ubuntu" 这类串
jdk_major() {
  "$1" -XshowSettings:properties -version 2>&1 \
    | awk -F' = ' '/java\.specification\.version/{print $2; exit}' \
    | tr -d '[:space:]'
}
LAUNCHER_MAJOR=$(jdk_major "$JDK/bin/java")
[ "$LAUNCHER_MAJOR" = "$REQUIRED_LAUNCHER_MAJOR" ] || \
  { echo "错误：$JDK 不是 JDK $REQUIRED_LAUNCHER_MAJOR（实际 ${LAUNCHER_MAJOR:-未知}）" >&2; exit 2; }

export JAVA_HOME="$JDK"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$JAVA_HOME/bin:$SDK/platform-tools:$PATH"
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS GRADLE_OPTS 2>/dev/null || true

# 导出后复检：PATH 上的 java 必须就是被锁定的那个 JDK
[ "$(command -v java)" = "$JDK/bin/java" ] || \
  { echo "错误：PATH 上的 java 不是 $JDK/bin/java（实际 $(command -v java)）" >&2; exit 2; }

# 【构建 JVM 断言】启动器锁 17 不代表构建跑在 17：本仓库的构建 JVM 由
# gradle/gradle-daemon-jvm.properties 锁定为 21。门禁开跑前用临时 init 脚本
# 在 Gradle 进程内部读 java.specification.version 实测一次，不等于 21 直接 exit 1。
REQUIRED_BUILD_JVM=21
build_jvm_probe() {  # $1 = Gradle 根目录
  local probe out
  probe=$(mktemp /tmp/axtvb-jvmprobe.XXXXXX.gradle)
  cat > "$probe" <<'GRADLE'
gradle.rootProject { p ->
  p.tasks.register("gateJvmProbe") {
    doLast {
      println "GATE_BUILD_JVM_VER=" + System.getProperty("java.specification.version")
      println "GATE_BUILD_JVM_HOME=" + System.getProperty("java.home")
    }
  }
}
GRADLE
  out=$(cd "$1" && ./gradlew --no-daemon -q --console=plain -I "$probe" gateJvmProbe 2>&1)
  rm -f "$probe"
  echo "$out" | sed -n 's/^GATE_BUILD_JVM_VER=//p' | head -1
}
assert_build_jvm() {  # $1 = Gradle 根目录, $2 = 期望大版本
  local ver
  echo "启动器 JDK: $(java -version 2>&1 | head -1)"
  ver=$(build_jvm_probe "$1")
  [ -n "$ver" ] || { echo "错误：无法取得构建 JVM 版本 —— 门禁中止" >&2; exit 1; }
  echo "构建 JVM  : $ver  (期望 $2, criteria $(sed -n 's/^toolchainVersion=//p' "$1/gradle/gradle-daemon-jvm.properties" 2>/dev/null || echo 无))"
  [ "$ver" = "$2" ] || \
    { echo "错误：构建 JVM 是 $ver，不是 $2 —— 门禁不可信，已中止" >&2; exit 1; }
}

# 平台检查跟着 gradle/libs.versions.toml 的 compileSdk / compileSdkMinor 走
toml_value() { awk -F'"' -v k="$1" '$1 ~ "^"k" *=" {print $2; exit}' "$ROOT/gradle/libs.versions.toml"; }
API_LEVEL=$(toml_value android-compileSdk)
API_MINOR=$(toml_value android-compileSdkMinor)
AGP_VERSION=$(toml_value agp)
[ -n "$API_LEVEL" ] || { echo "错误：无法从 libs.versions.toml 解析 android-compileSdk" >&2; exit 2; }
PLATFORM_OK=0
for d in "$SDK/platforms/android-$API_LEVEL" "$SDK/platforms/android-$API_LEVEL".*; do
  [ -d "$d" ] && { PLATFORM_OK=1; break; }
done
[ "$PLATFORM_OK" = 1 ] || \
  { echo "错误：缺少 android-$API_LEVEL${API_MINOR:+.x} 平台（compileSdk=$API_LEVEL${API_MINOR:+.$API_MINOR}）" >&2; exit 2; }

# 统一入口：先断言实际构建 JVM，再走项目自带 wrapper（--no-daemon 便于逐次复检）
gradle() { assert_build_jvm "$ROOT" "$REQUIRED_BUILD_JVM"; cd "$ROOT" && ./gradlew --no-daemon "$@"; }

usage() {
  cat <<'USAGE'
用法: axtvbrowser-dev.sh <命令> [参数]
  env                 环境自检（启动器 JDK / 实际构建 JVM / SDK / adb / 文件系统）
  test                全量 JVM 单元测试（与 CI pr.yml 两个矩阵一致）
  lint                Android lint（lintGenericGeckoExcludedDebug，与 CI 一致）
  assembleDebug       debug APK：generic × geckoExcluded + geckoIncluded
  install             装机：adb install -r genericGeckoExcluded debug APK
  adb <args>          adb 透传，例如: adb devices
  gradle <args>       任意 Gradle 任务（仍在 JDK 锁定下）
  sh '<批量命令>'      在本环境内一次执行任意 shell（Agent 单会话批量用）
  clean               清理构建产物（不动源码 / local.properties）
USAGE
}

cmd=${1:-env}; shift || true
case "$cmd" in
  env)
    echo "ROOT      = $ROOT"
    echo "FS        = $(df -T "$ROOT" | awk 'NR==2{print $2" on "$1}')"
    echo "uname     = $(uname -sr)"
    echo "JAVA_HOME = $JAVA_HOME"
    java -version 2>&1 | head -3 | sed 's/^/  /'
    GRADLE_VERSION=$(cd "$ROOT" && ./gradlew --version 2>/dev/null | awk '/^Gradle /{print $2; exit}')
    echo "Gradle    = ${GRADLE_VERSION:-未知}"
    # 在 Gradle 进程内部实测构建 JVM，而不是只读配置/推断
    echo "构建 JVM  = $(build_jvm_probe "$ROOT")  (期望 $REQUIRED_BUILD_JVM)"
    echo "criteria  = $(sed -n 's/^toolchainVersion=//p' "$ROOT/gradle/gradle-daemon-jvm.properties" 2>/dev/null || echo 无)"
    echo "SDK       = $ANDROID_HOME"
    echo "platforms = $(ls -1 "$ANDROID_HOME/platforms" | tr '\n' ' ')"
    echo "compileSdk= $API_LEVEL${API_MINOR:+.$API_MINOR} (AGP ${AGP_VERSION:-?})"
    echo "adb       = $(command -v adb || echo MISSING)"
    echo "devices   :"; adb devices 2>&1 | sed 's/^/  /'
    echo "wrapper   = $(ls -l "$ROOT/gradlew" | awk '{print $1}')"
    echo "NOTE      = 构建/测试/调试一律在 WSL ext4；Z:/\\wsl.localhost 仅只读镜像"
    ;;
  test)
    # 与 .github/workflows/pr.yml 的两个矩阵一致：5 个模块的单元测试一次跑完
    gradle :app:testGenericGeckoExcludedDebugUnitTest \
           :app:testGenericGeckoIncludedDebugUnitTest \
           :app:common:testDebugUnitTest \
           :app:gecko:testDebugUnitTest \
           :adblock:testDebugUnitTest "$@"
    ;;
  lint)
    # 与 CI 同一任务（lintGenericGeckoExcludedDebug）；库内无 baseline，且 CI 该 job 是
    # continue-on-error，所以既存 findings 一直没暴露。这里不掩盖：失败即非 0 退出。
    gradle lintGenericGeckoExcludedDebug "$@" || {
      echo "提示：lint 失败来自既存 findings（仓库无 lint baseline）。报告：app/build/reports/lint-results-*.html" >&2
      exit 1
    }
    ;;
  assembleDebug|build)
               gradle assembleGenericGeckoExcludedDebug assembleGenericGeckoIncludedDebug "$@" ;;
  install)
    APK="$ROOT/app/build/outputs/apk/genericGeckoExcluded/debug/app-generic-geckoExcluded-debug.apk"
    [ -f "$APK" ] || { echo "错误：APK 不存在，先跑 assembleDebug: $APK" >&2; exit 2; }
    adb install -r "$APK" "$@"
    ;;
  adb)         adb "$@" ;;
  gradle)      gradle "$@" ;;
  sh)
    [ "$#" -gt 0 ] || { echo "用法: sh '<批量命令>'" >&2; exit 1; }
    cd "$ROOT" && bash -c "$*"
    ;;
  clean)
    echo "清理构建产物（不影响源码与 local.properties）"
    rm -rf "$ROOT/app/build" "$ROOT/build" "$ROOT/adblock/build" "$ROOT/buildSrc/build" "$ROOT/.gradle"
    ;;
  -h|--help|help) usage ;;
  *) echo "未知命令: $cmd" >&2; usage >&2; exit 1 ;;
esac
