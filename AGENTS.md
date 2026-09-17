# 项目约定

## 构建与验证

```bash
./gradlew :app:assembleDebug                    # 构建
./gradlew :xx:testDebugUnitTest                 # JVM 单测
bash tools/run-device-tests.sh                  # 真机测试（不要直接跑 connectedDebugAndroidTest）
```

- Kotlin/Java 17、`compileSdk 36`、`minSdk 26`；coroutines 版本必须显式钉住。
- **注释与提交信息一律中文**；提交尾注 `Co-authored-by: CommandCodeBot <noreply@commandcode.ai>`。
- **文案只在 `common/error/ErrorText.kt` 里取，不得新造词面**，也不得在代码里拼文案键。
- 不用通用 `Result` 包装：每个操作有自己的 sealed 结果，或直接返回领域类型。
- 文档（`docs/技术方案/*`）是锁定的契约；与实现不一致时**改文档**，不能默默偏离。

## 经验库（动手前先读）

本目录 `agent-experience/` 沉淀可复用的操作经验，**按任务类型查表，先读再动手**。
每条经验的「症状 / 根因 / 做法 / 复核」都写全了，含那些"看起来像 X、实际是 Y"的坑。

| 任务类型 | 读这个文件 |
| --- | --- |
| 依赖下载失败、代理、JDK / SDK 路径、wrapper | [`agent-experience/01-build-environment.md`](agent-experience/01-build-environment.md) |
| 跑真机 androidTest、UI 测试卡住、装不上 APP | [`agent-experience/02-device-testing.md`](agent-experience/02-device-testing.md) |
| 测试只在某平台红、路径 / 分隔符 / `canonicalPath` | [`agent-experience/03-cross-platform-code.md`](agent-experience/03-cross-platform-code.md) |
| 提交信息、文件模式、行尾符、别入库的东西 | [`agent-experience/04-version-control.md`](agent-experience/04-version-control.md) |

经验库的使用与维护规则见 [`agent-experience/README.md`](agent-experience/README.md)。
文件里的命令**可照抄执行**；其中的盘符、端口、设备型号是本机的，
换机或换 OS 后按 `01` 的排障顺序复核一遍再用。

### 发现经验不对时（**必须处理，不能不吭声**）

先**复现**再定性，然后按类型分别处理：

| 判定 | 处理 |
| --- | --- |
| **跨平台差异**（换了 OS / 设备 / 版本，事实变了） | **补全**：加分平台/分设备分支，**保留**旧结论 |
| **经验本身写错了**（当时的根因判断就错） | **修复**：改正并记下纠正原因，别只删原文 |
| **过时**（事实作废） | **标注作废** + 给替代做法，留一行历史 |

改完经验，回来核对上面的索引表是否还准；新增文件要登记进索引。

## 当前设备

真机为小米 `24117RK2CC`（zorn，**Android 16 / SDK 36**），USB 连接。
跑 androidTest 前先唤醒：`adb shell input keyevent KEYCODE_WAKEUP`。

> **注意**：这台机器上直接跑 `./gradlew :feature:mine:connectedDebugAndroidTest`
> 会**永远卡在 `0/N completed`**（MIUI 拦后台弹窗）。**用 `tools/run-device-tests.sh`**，
> 原因与排查见 [`agent-experience/02-device-testing.md`](agent-experience/02-device-testing.md)。
