# 跨平台代码陷阱

> 本项目的 Android 代码在**开发机（Windows）**上跑 JVM 单测，在**真机（Android）**上跑
> 业务逻辑。任何依赖「宿主操作系统」的 API，都会让同一条规则在两处表现不同。
> 这类 bug 在 Linux 开发机上**不会出现**，换机才暴露 —— 所以极易被误判成"新引入的回归"。

## 判定方法

1. **先复现**，别急着改代码。
2. 用 `--rerun-tasks` 排除构建缓存干扰：
   `./gradlew :xx:testDebugUnitTest --rerun-tasks`
3. **对比历史**：同样的代码在别的平台上绿过吗？
   - 绿过 → **跨平台差异**（环境事实变了，不是新 bug）
   - 从没绿过 → 经验/实现本身有问题
4. 查这个类是否有平台相关的调用（`File`、路径分隔符、`canonicalPath`、行尾符、权限位）。

## 陷阱 1：`File.getCanonicalPath()` 对不存在的路径**不抛异常**

**症状**（`PathNormalizerTest` 6 条失败）：

```
expected: /storage/emulated/0/Music/a.mp3
but was : D:\storage\emulated\0\Music\a.mp3
```

**根因**：`getCanonicalPath()` 对**不存在**的路径不会抛 `IOException`，而是把整串当作
**相对于当前工作目录**解析。于是在 Windows 上 `/storage/...` 被拼成 `D:\storage\...`；
在 Linux 上它"看起来正常"（因为 `/` 开头的路径本来就存在一个 `/` 根）。

**为什么危险**：返回值仍然是个"像样的绝对路径"，不会报错 —— 只是**悄悄换了个文件**。

**做法**：软链解析只对**磁盘上确实存在**的路径做：

```kotlin
if (!p.startsWith("/")) return p
if (p == "/") return p          // 根路径没有软链可解，宿主会把它映射成盘根
val file = File(p)
if (!file.exists()) return p    // ← 关键：不存在就别 canonicalize
return runCatching { file.canonicalPath }.getOrDefault(p)
```

这与设计意图一致：`PathNormalizer` 的注释本就写着「root 参数化是为了能在 JVM 上测」，
而"软链解析"本就只对真实存在的路径成立。

**在 Android 上这是恒等替换**：媒体库路径必然存在，该判定恒为真 —— 所以这不是行为变更，
真正被修好的是"这条规则终于能在 JVM 上测"。

## 陷阱 2：路径分隔符是**宿主平台相关**的

**症状**（`FileStorageSourceTest` 2 条失败）：

```
expected: [media]          # 期望 Android/data、Android/obb 被黑名单挡掉
but was : [data, media, obb]
```

**根因**：黑名单字面是正斜杠（`"/Android/data"`），而 `File.absolutePath` 在 Windows 上
给反斜杠 —— `path.endsWith("/Android/data")` **永不命中**。

**做法**：比较前统一成正斜杠。注意区分场合：

```kotlin
// 只做"前缀归类"，不产生写库用的路径 → 就地归一即可
val path = dir.absolutePath.replace('\\', '/')
return SYSTEM_DIR_SUFFIXES.any { path.endsWith(it) }
```

**写库用的路径**（`music_file.path`）必须走 `PathNormalizer`，别在业务代码里散落 `.replace`。

## 陷阱 3：测试断言写死了某个平台的形式

**症状**（`FileStorageSourceTest` 1 条失败）：

```
value of: getPath()
expected to end with: Music/a.mp3
but was: D:\Temp\...\Music\a.mp3
```

**做法**：断言前归一化分隔符，或直接断言"规范化后同键"（更贴近真实契约）：

```kotlin
assertThat(ref.path.replace('\\', '/')).endsWith("Music/a.mp3")
// 更好：断言两条路径经 PathNormalizer 后相等
assertThat(PathNormalizer.normalize(ref.path, root))
    .isEqualTo(PathNormalizer.normalize(file.absolutePath, root))
```

## 通用原则

- **规则类**（路径、文本归一、排序）的输入应**参数化**，不要在类内部读 `Environment`
  之类的平台 API —— 否则规则永远无法在 JVM 上测。
- 需要平台能力时，**抽成窄接口**由调用方注入（本项目已有先例：`MmrRetriever`
  用窄缝让元数据读取逻辑可在 JVM 上测；`AnalysisTrigger` / `LlmCache` 同理）。
- 遇到"换了机器才红"的测试，**先怀疑平台差异**，别急着改断言让测试变绿 ——
  那样会把真实 bug 一起盖住。
- **改完要在两端都复核**：Windows 上 JVM 单测绿 + Android 上真机测试绿，才算真的修好
  （只在一个平台验证过等于没验证 —— 本条经验的由来正是如此）。
- 这三处修法都属 README 说的**跨平台差异**：旧结论（在 Linux 上正确）保留在文字里，
  新增的是"在非 Unix 宿主上要这么处理"，而不是把旧结论推翻。
