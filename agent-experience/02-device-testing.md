# 真机测试（androidTest）

> 适用前提：MIUI/HyperOS 设备（当前为小米 `24117RK2CC` / zorn，Android 16 / SDK 36）。
> 换设备或升系统后**先复核本文件**。

## 0. 先用脚本，别直接跑 Gradle

```bash
bash tools/run-device-tests.sh                                 # 全部模块
bash tools/run-device-tests.sh :feature:mine                   # 单模块
bash tools/run-device-tests.sh :feature:mine MineScreenTest    # 再按类名过滤
```

> 用 `bash <脚本>` 而非 `./<脚本>`：仓库在 Windows 上 `core.fileMode=false`，
> 不记录可执行位，`./` 形式会因缺 x 位失败。

脚本做的事：唤醒设备 + 常亮 → Gradle 只负责 `installDebug`/`installDebugAndroidTest`
→ 放开 MIUI 后台弹窗 → `am instrument` → 复核权限未被重置。

**为什么不能直接用 `connectedDebugAndroidTest`** —— 见下面第 2 节，那是本文件的重点。

## 1. 模块测试不会装主 APP（先确认这点，免得白查）

**症状**：想手工验收，发现设备上找不到 `com.aimusic.player`。

**根因**：模块级 `connectedDebugAndroidTest` 只装**该模块自己的测试 APK**
（`:feature:mine` → `com.aimusic.player.mine.test`），不装 `:app`。两个包相互独立。

**做法**：

```bash
adb shell pm list packages | grep -i aimusic     # 看装了什么
adb shell pm path com.aimusic.player            # 空输出 = 主 APP 没装
./gradlew :app:installDebug                     # 装上
```

## 2. Compose UI 测试在 MIUI 上「假死」★★★

**症状**：`0/N completed` **长时间不变**，不报错、不失败、不超时 —— 像挂死。
测试进程活着，`EspressoLink` idling resource 已注册，但**前台始终是 launcher**。
（`core:data` / `core:storage` 不受影响 —— 它们的测试不起 Activity。）

**根因**：`createComposeRule` 需要一个宿主 Activity（`ui-test-manifest` 提供的
`androidx.activity.ComponentActivity`）。Android 16 把这算作**后台启动 Activity（BAL）**，
MIUI 用专有 appops **`MIUIOP(10021)`［后台弹出界面］** 拦掉。

诊断（关键判据是 `rejectTime` 与测试时段吻合）：

```bash
adb shell cmd appops get com.aimusic.player.mine.test 10021
# ignore; rejectTime=+1m7s ago   ← 命中此问题
```

**证据链**（当初就是靠这几条定性的，别只看第一条就下结论）：

| 证据 | 说明 |
| --- | --- |
| 前台始终是 launcher，不是测试 Activity | 宿主 Activity 没起来 |
| logcat 里 `ActivityManagerWrapper` 每 1~3 秒重试启动 `androidx.activity.ComponentActivity` 且一直失败 | 启动被拦，不是用例逻辑卡住 |
| **手动 `am start` 同一个 Activity 能成功拿到焦点** | 差别在"测试是从后台启动的" —— 这条是定性的关键 |
| `core:data`(143) / `core:storage`(6) 全过 | 反证：它们不起 Activity |

**做法**：放开权限后**直接 `am instrument`**：

```bash
adb shell cmd appops set com.aimusic.player.mine.test 10021 allow
adb shell am instrument -w -r com.aimusic.player.mine.test/androidx.test.runner.AndroidJUnitRunner
```

### ⚠️ 关键坑：为什么"先设权限、再跑 Gradle"没有用

`connectedDebugAndroidTest` **每次都会重装 APK**，而 MIUI 会把该包的 appops
**重置回 `ignore`**。所以必须在 APK 装好之后、**不过 Gradle** 地直接 `am instrument`
——这正是 `tools/run-device-tests.sh` 存在的理由。

**复核**（脚本内置这一步，手工跑时也要看）：

```bash
adb shell cmd appops get com.aimusic.player.mine.test 10021   # 期望 allow
```

权限被重置 = 该模块结果不可信（可能假绿，也可能又卡住）。

## 3. 其它易踩点

- **跑前唤醒设备**：`adb shell input keyevent KEYCODE_WAKEUP`，否则 MIUI 报
  `INSTALL_FAILED_USER_RESTRICTED` —— 那看着像权限问题，其实只是屏幕黑着。
- **屏幕常亮**（长测试别中途熄屏）：
  `adb shell svc power stayon true` + `adb shell settings put system screen_off_timeout 2147483647`。
- **`connectedAndroidTest` 不支持 `--tests`** —— 那是 JVM 单测的选项，会报
  `Unknown command-line option '--tests'`。按类名过滤要用
  `-Pandroid.testInstrumentationRunnerArguments.class=<全限定类名>`（脚本第二个参数即是）。
- **首次安装可能瞬时失败**：MIUI 首次安装会弹确认。重跑一次即可（实测第二次成功）。
- **无线调试端口每次重启都变**，别把端口写死。

## 4. 实测基线（2026-09）

| 模块 | 用例 | 结果 |
| --- | --- | --- |
| `:core:storage` | 6 | `OK (6 tests)` |
| `:core:data` | 143 | `OK (143 tests)` |
| `:feature:mine` | 23 | `OK (23 tests)` |

共 **172 条**全绿。类名过滤（`OK (1 test)`）亦通过。
