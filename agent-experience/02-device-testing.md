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

### 3.1 手工验收：**必须换掉输入法**，否则 `input text` 写的值会被联想坏

**症状**：用 `adb shell input text "https://..."` 填表单，落进输入框的却是
`https：、、。从难道的。唉、provides、他` 这种中文联想垃圾 —— 界面看起来"填了"，值却是错的。

**根因**：设备默认输入法是 `com.iflytek.inputmethod/.FlyIME`（讯飞）或搜狗，
它们的**联想/候选**会改写 `input text` 注入的英文字符（`//` → `、`，`api` → `provides` 之类）。

**做法**：切到设备上装好的 AdbKeyboard（本项目真机已装），它不联想、逐字符直传：

```bash
adb shell ime list -a -s                      # 找 com.android.adbkeyboard/.AdbIME
adb shell ime enable com.android.adbkeyboard/.AdbIME
adb shell ime set   com.android.adbkeyboard/.AdbIME
adb shell settings get secure default_input_method   # 复核已切换

# 之后用广播输入（能正确处理 : / . - 等任意字符）
adb shell am broadcast -a ADB_INPUT_TEXT --es msg "https://example.com/"
```

**复核**：`adb shell uiautomator dump` 后 grep `EditText` 的 `text=`，**逐项核对**内容 ——
不要凭"按钮点下去了"就认为填对了。

### 3.2 手工验收：改已有文本要先全选清空

`input text` 是**追加**不是替换。清空用：

```bash
adb shell input keycombination 113 29   # Ctrl+A
adb shell input keyevent KEYCODE_DEL
```

### 3.3 手工验收：点「分析并添加」别点到「放弃」

扫描结果页底部两个并排按钮，几何靠近、极易点错：

```
[45,2040][528,2175]     「分析并添加」→ 中心 x≈286
[551,2040][1035,2175]   「放弃」       → 中心 x≈793
```

点错的表现很隐蔽：界面**重置回初始态**（提交按钮消失），库里 0 行 —— 看着像"提交了但没效果"。
**判据**：点完应跳到「最近分析记录」页并出现逐文件行；没跳就是点错了。

### 3.4 取证：设备上没有 `sqlite3`，要把库拉回本地查

`adb shell run-as <pkg> sqlite3 …` 会报 `Permission denied`（MIUI 不提供该二进制）。改拉文件：

```bash
mkdir -p /tmp/aimpull && cd /tmp/aimpull
for f in ai_music_player.db ai_music_player.db-wal ai_music_player.db-shm; do
  adb exec-out "run-as com.aimusic.player cat databases/$f" > "$f"
done
```

**关键**：查之前先 `PRAGMA wal_checkpoint(FULL)`。Room 开的是 WAL，刚写的行还在 `-wal` 里，
4096 字节的主库直接查会**什么都查不到**（这不是"没写入"，是没合并）。

```python
c = sqlite3.connect('ai_music_player.db'); c.execute('PRAGMA wal_checkpoint(FULL)')
```

## 4. 从 Windows 的 PowerShell 跑脚本：两个必踩的坑（2026-09-18 补）

**症状一**：`bash tools/run-device-tests.sh` 报

```
wsl: 检测到 localhost 代理配置，但未镜像到 WSL。NAT 模式下的 WSL 不支持 localhost 代理。
<3>WSL (10 - Relay) ERROR: CreateProcessCommon:818: execvpe(/bin/bash) failed: No such file or directory
```

**根因**：PowerShell 里裸写 `bash` 解析到的是 `C:\Windows\System32\bash.exe` —— 它是
**WSL 的入口**，不是 Git Bash。本机没装 WSL 发行版，于是 `execvpe(/bin/bash)` 找不到。
报错里那句「localhost 代理配置」是 WSL 自己的提示，**与代理无关**，别顺着它去改代理。

**做法**：用 Git Bash 的**绝对路径**（本机是 PortableGit）：

```powershell
& "D:\Program Files\PortableGit\bin\bash.exe" tools/run-device-tests.sh :core:ui SongRowTest
```

> 别硬编码路径，先定位：
> `Get-ChildItem "D:\Program Files\PortableGit\bin\bash.exe","C:\Program Files\Git\bin\bash.exe" -ErrorAction SilentlyContinue`

**症状二**：脚本第一步就报 `没有可用设备（adb devices 为空）`，但 PowerShell 里 `adb devices` 正常。

**根因**：`adb` 在 PowerShell 的 PATH 里（来自 Android SDK），而 **Git Bash 继承的是它自己的
PATH**，不含 `platform-tools`。脚本内部直接调 `adb`，于是全部失败 —— 那句提示指向"设备问题"，
实际是**找不到 adb 命令**（脚本把 `adb shell getprop` 的失败当成了"没有设备"）。

**做法**：跑脚本前把 `platform-tools` 塞进本次会话的 PATH：

```powershell
$env:PATH = "C:\Users\huwansong\AppData\Local\Android\Sdk\platform-tools;" + $env:PATH
& "D:\Program Files\PortableGit\bin\bash.exe" tools/run-device-tests.sh :core:ui SongRowTest
```

**复核**（先确认 bash 与 adb 都真找得到，再跑整套）：

```powershell
& "D:\Program Files\PortableGit\bin\bash.exe" -c "which adb && adb devices"
# 期望：/c/.../platform-tools/adb  +  83695e  device
```

**环境**：Windows + PowerShell 7 + PortableGit + 无 WSL 发行版。
在 Linux / macOS / WSL 下这两个坑都不存在（那时裸写 `bash` 就是对的）。

### ⚠️ 2026-09-18 修正：脚本的模块表与类名补全（已改脚本，不再需要手工绕）

两个原以为要手工处理、现已修进 `tools/run-device-tests.sh` 的点：

1. **模块 → namespace 曾是一张硬编码表**，新增可测模块（如给 `:core:ui` 加 androidTest）时
   会以「未知模块 :core:ui（请在脚本里补 namespace）」**静默 `continue`** ——
   看起来像"跑过了、没问题"。现改为 `sed` 读该模块 `build.gradle.kts` 的 `namespace`。
2. **类名过滤曾假定测试类在 namespace 根包下**（`-e class "${NS}.${CLASS_FILTER}"`）。
   `:core:ui` 的测试在 `com.aimusic.player.ui.component` 子包，于是
   `SongRowTest` 被拼成 `com.aimusic.player.ui.SongRowTest` → `ClassNotFoundException`。
   现改为：**`CLASS_FILTER` 含点号即视为完整类名**，否则才补 namespace 前缀。

```bash
# 两种写法都支持
bash tools/run-device-tests.sh :core:ui SongRowTest
bash tools/run-device-tests.sh :core:ui com.aimusic.player.ui.component.SongRowTest
```

## 5. 两个只有真机才暴露的**测试自身**缺陷（2026-09-18 补）

这两条都不是产品代码的问题，而是**测试写错了还一路绿**。共同特征是：**编译通过、跑起来也不报错**，
只有「真机 + 变异抽查」才能发现。

### 5.1 `RoomDatabase.QueryCallback` 会把 Room 自己的内部语句也算进来

**症状**：用查询回调数「业务查询条数」来防 N+1，在真机上得到
`forOne=34` vs `forTwenty=48` —— 看起来像 N+1，其实不是。

**根因**：回调报上来的**不只有业务 SQL**，还有 Room 失效追踪的基础设施：

```
CREATE TEMP TRIGGER IF NOT EXISTS `room_table_modification_trigger_music_file_IN…`
DROP TRIGGER IF EXISTS `room_table_modification_trigger_song_artist_INSERT`
INSERT OR IGNORE INTO room_table_modification_log VALUES(…)
BEGIN IMMEDIATE TRANSACTION / END TRANSACTION
```

这些**随被观察的表集变化而增减**（列表从 1 首到 20 首，涉及的表不同 → trigger 重建次数不同），
与业务查询无关。真正的业务查询稳定是 3 条（Step 1 取实体 + Step 2 可播性 + Step 3 标签/歌手名）。

**做法**：只统计业务 `SELECT`，把内部机制过滤掉：

```kotlin
private fun isBusinessQuery(sql: String): Boolean {
    val s = sql.trimStart()
    if (!s.startsWith("SELECT", ignoreCase = true)) return false
    return !s.contains("room_table_modification", ignoreCase = true) &&
        !s.contains("room_master_table", ignoreCase = true)
}
```

**另加两条断言**（缺了任一条都可能假绿 / 假红）：
- **`forOne > 0`**：否则回调没生效时 `0 == 0` 恒真。
- **`recorded.containsNoDuplicates()`**：这才是 N+1 的**直接**特征（同一条 SQL 被执行 N 次）。
  不要比对 SQL 字面是否逐字相同 —— 批量查询的 `IN (?, ?, …)` 会随 id 数量变化，
  那是**参数个数**差异，不是 N+1（我第一版就是这么误判的，真机上红了才知道）。

**复核**：变异抽查 —— 把 `assemble()` 的批量可播性查询改成逐行
（`combine(ids.map { dao.observePlayableCounts(listOf(it)) })`），期望 `expected: 4 but was : 23`。

### 5.2 夹具漏建关联表 → 断言退化成「1 == 1」恒真

**症状**：歌手维度的防 N+1 用例**永远绿**，连变异抽查都抓不出来
（把批量改成逐行，它照样绿）。

**根因**：歌手维度查的是 **`song_artist` 关联表**
（`SongQueryBuilder`：`EXISTS (SELECT 1 FROM song_artist a WHERE a.entity_id = s.id AND a.artist_name = ?)`），
而 `TestDb.insertSong(artistsKey = …)` 只写 **`song_entity.artists_key`** 这一个字段。
夹具少了关联行 → 该维度查不到任何实体 → `assemble()` 在 `entities.isEmpty()` 处提前返回 →
只跑 1 条 SQL，`forOne == forTwenty == 1`，断言恒真。

**诊断依据**（`println` 打在测试里，从 logcat 捞）：

```bash
adb logcat -d | Select-String "DIAG-ARTIST"   # forOne=1 forTwenty=1 ← 装配压根没跑
```

**做法**：夹具显式插关联行，并**断言真的查到了数据**：

```kotlin
private fun insertLinkedSong(index: Int, artist: String = "歌手$index") {
    val songId = db.insertSong(title = "歌$index", artistsKey = artist)
    db.exec("INSERT INTO song_artist (entity_id, artist_name, position) VALUES ($songId, '$artist', 0)")
    …
}

// 用例里必须断言行数，否则「查不到」会伪装成「很稳定」
assertThat(one).hasSize(1)
assertThat(twenty).hasSize(20)
```

> **教训**：断言「两个值相等」时，先确认**这两个值真的反映了被测行为**。
> `1 == 1` 和 `0 == 0` 是同一类陷阱：把「什么都没发生」当成「没有退化」。
> 上面 `LibraryQueryTest` 里就有正确写法（`歌手维度只返回该歌手的歌`），照抄它即可。

## 6. 实测基线（2026-09）

| 模块 | 用例 | 结果 |
| --- | --- | --- |
| `:core:storage` | 6 | `OK (6 tests)` |
| `:core:data` | 145 | `OK (145 tests)`（2026-09-18 +2，`LibraryQueryCountTest` 防 N+1） |
| `:feature:mine` | 23 | `OK (23 tests)` |
| `:core:ui` | 4 | `OK (4 tests)`（2026-09-18 新增，`SongRowTest`） |
| `:feature:library` | 13 | `OK (13 tests)`（2026-09-18 新增，`LibrarySongsContentTest`） |

共 **191 条**全绿。类名过滤亦通过。
