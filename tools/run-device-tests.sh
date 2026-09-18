#!/usr/bin/env bash
# 在 MIUI 真机上跑 androidTest 的封装。
#
# 为什么需要它（2026-09 换到 小米 24117RK2CC / Android 16 后踩到）：
#
# Compose UI 测试（`createComposeRule`）要启动宿主 Activity
# `androidx.activity.ComponentActivity`（由 `ui-test-manifest` 提供）。在 Android 16 上
# 这属于**后台启动 Activity**（BAL），MIUI 用专有 appops `MIUIOP(10021)["后台弹出界面"]`
# 拦截它 —— 现象是测试进程活着、EspressoLink idling resource 已注册，却**永远卡在
# 0/N completed**，前台始终是 launcher；logcat 里 `ActivityManagerWrapper` 每 1~3 秒
# 重试一次启动那个 ComponentActivity，一直失败。整套 23 条 compose 用例会像挂死一样。
#
# 而 `connectedDebugAndroidTest` **每次都会重装 APK**，MIUI 会把该包的 appops 重置回
# `ignore` —— 所以「先设权限再跑 Gradle」没有用；必须在 APK 装好之后、**不过 Gradle**地
# 直接 `am instrument`。
#
# 另外，`connectedAndroidTest` 不支持 `--tests`（那是 JVM 单测的选项），要过滤用例得用
# `-e class <全限定类名>` 传给 instrumentation。
#
# 用法：
#   tools/run-device-tests.sh                        # 跑全部模块的 androidTest
#   tools/run-device-tests.sh :feature:mine          # 只跑某个模块
#   tools/run-device-tests.sh :feature:mine MineScreenTest    # 再按类名过滤
set -uo pipefail

MODULE="${1:-}"
CLASS_FILTER="${2:-}"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 1. 唤醒设备：屏幕黑着时 MIUI 会连带影响安装与启动
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
adb shell wm dismiss-keyguard >/dev/null 2>&1
# 保持屏幕常亮 + 超时拉到最大，免得长跑中途熄屏
adb shell svc power stayon true >/dev/null 2>&1
adb shell settings put system screen_off_timeout 2147483647 >/dev/null 2>&1

if ! adb shell getprop ro.product.model >/dev/null 2>&1; then
  echo "没有可用设备（adb devices 为空）" >&2
  exit 1
fi

MODULES=("${MODULE:-:core:storage}" "${MODULE:-:core:data}" "${MODULE:-:core:ui}" "${MODULE:-:feature:mine}")
# 去重（单模块时上面会重复若干次）
if [[ -n "$MODULE" ]]; then MODULES=("$MODULE"); fi

# 2. 先让 Gradle 把 APK 装好（这一步会重置 appops，所以权限要在它之后设）
echo "== 构建并安装测试 APK =="
GRADLE_TASKS=()
for m in "${MODULES[@]}"; do GRADLE_TASKS+=("${m}:installDebug" "${m}:installDebugAndroidTest"); done
./gradlew "${GRADLE_TASKS[@]}" --console=plain || { echo "安装失败" >&2; exit 1; }

FAILED=0
for m in "${MODULES[@]}"; do
  # namespace 即测试包名前缀：<namespace>.test
  # **从模块自己的 build.gradle.kts 里读**，而不是维护一张硬编码表 ——
  # 表的缺点是每新增一个（可测的）模块都要回来补一行，否则会以「未知模块」静默跳过，
  # 看起来像「跑过了、没问题」。Task 2.1 给 :core:ui 加 androidTest 时就撞上了这一点。
  mod_path="${m#:}"; mod_path="${mod_path//://}"
  gradle_file="${mod_path}/build.gradle.kts"
  NS="$(sed -nE 's/^[[:space:]]*namespace[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$gradle_file" 2>/dev/null | head -1)"
  if [[ -z "$NS" ]]; then
    echo "未知模块 $m（读不到 $gradle_file 里的 namespace）" >&2
    continue
  fi
  TEST_PKG="${NS}.test"

  # 3. 关键一步：放开 MIUI 的后台弹窗拦截（10021）。必须紧挨着 instrument，
  #    中间不能再有 Gradle 重装。
  for op in 10020 10021 10022; do
    adb shell cmd appops set "$TEST_PKG" $op allow >/dev/null 2>&1 || true
    adb shell cmd appops set "$NS" $op allow >/dev/null 2>&1 || true
  done

  echo "== $m（测试包 $TEST_PKG）=="
  ARGS=(-w -r)
  if [[ -n "$CLASS_FILTER" ]]; then
    # 类名可能不在 namespace 的根包下（如 :core:ui 的测试在 .component 子包）。
    # 含点号即视为**完整类名**，否则按根包下的类名补全。
    case "$CLASS_FILTER" in
      *.*) ARGS+=(-e class "$CLASS_FILTER") ;;
      *)   ARGS+=(-e class "${NS}.${CLASS_FILTER}") ;;
    esac
  fi
  adb shell am instrument "${ARGS[@]}" "${TEST_PKG}/${RUNNER}" 2>&1 \
    | grep -aE "OK \(|FAILURES|Tests run|Error|INSTRUMENTATION_CODE|junit\." || true

  # 复核权限是否被中途重置：被重置就意味着这个模块的结果不可信
  STATE="$(adb shell cmd appops get "$TEST_PKG" 10021 2>/dev/null | tr -d '\r')"
  echo "  appops 10021: $STATE"
  case "$STATE" in
    *allow*) ;;
    *) echo "  !! 权限被重置，该模块结果不可信" >&2; FAILED=1 ;;
  esac
done

exit $FAILED
