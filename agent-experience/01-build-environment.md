# 构建环境：依赖下载、JDK / Android SDK、Gradle wrapper

> 适用前提：Windows 主机，代理在 `127.0.0.1:7890`，JDK 全在 `D:/JDK/`（非标准位置）。
> 换机或换 OS 后**先复核本文件**再动手。

## 1. 依赖下载：直连不通，必须走代理

**症状**：新依赖下载失败，报 `Remote host terminated the handshake` /
`The server may not support the client's requested TLS protocol versions`。

**根因**：这是**网络路径**问题，不是 artifact 不存在 —— 但报错完全指向后者，极易误判。
（历史教训：曾把 `androidx.security:security-crypto:1.1.0` 误记为"artifact 不存在"，
实际它一直在 Maven Central 稳定版 1.1.0，minCompileSdk=34，兼容 compileSdk 36。）

**做法**：代理配在 `~/.gradle/gradle.properties`（本机用户环境，不是仓库的事）：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7890
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7890
```

排障顺序：**先确认代理端口在监听且真能出网，再去怀疑 artifact**。

```bash
curl -x http://127.0.0.1:7890 -o /dev/null -w '%{http_code}\n' https://services.gradle.org/distributions/
# 期望 200/307。连不上 → 代理没开或端口变了
```

**实测速率（2026-09 本机）**：直连在 `307` 之后下不动（0 字节）；代理 1.6 MB/s。

### ⚠️ 旧规矩已作废（跨环境的典型例子）

以前那台 Linux 机是「**直连通、代理坏**」，所以规矩是给 Gradle 加
`-Dhttp.nonProxyHosts='*' -Dhttps.nonProxyHosts='*'` **绕开**代理。
**本机正好相反** —— 依赖未缓存时加那两个参数会**直接失败**。

→ 这就是 README 说的「跨平台差异」：**不要删掉旧结论，要分环境写**。

## 2. JDK 在非标准位置，要显式告知 Gradle

`gradle/gradle-daemon-jvm.properties` 锁了 `toolchainVersion=25`，而 Gradle 只自动探测
标准安装位置与 `~/.gradle/jdks`。本机 JDK 全在 `D:/JDK/` 下，不指就会去 foojay 下一份
（慢且依赖网络，断网直接失败）：

```properties
org.gradle.java.installations.paths=D:/JDK/jdk-25.0.3.9-hotspot,D:/JDK/jdk-17.0.20.1+1-hotspot,D:/JDK/jdk-21.0.11.10-hotspot
```

- `jdk-25.0.3.9-hotspot` → Gradle daemon
- `jdk-17.0.20.1+1-hotspot` → 编译目标（项目用 Java 17）

复核：`./gradlew --version` 应显示 `Daemon JVM: Compatible with Java 25`。

## 3. `local.properties` 的路径必须用正斜杠

**症状**：构建报 **「文件名、目录名或卷标语法不正确」**（这其实是 Windows 的中文报错，
在英文系统上会显示 *The filename, directory name, or volume label syntax is incorrect*）。

**根因**：Java properties 会**吞掉无效转义的反斜杠**。写成

```properties
sdk.dir=C\:\Users\huwansong\AppData\Local\Android\Sdk   # ✗
```

`\U`、`\A` 都不是合法转义，反斜杠被丢掉，解析成 `C:Usershuwansong...`。

**做法**：

```properties
sdk.dir=C:/Users/huwansong/AppData/Local/Android/Sdk    # ✓
```

（`local.properties` 在 `.gitignore` 里，属本机配置，不入版本控制。）

## 4. SDK platform 版本要够

项目 `compileSdk = 36`，SDK 里必须有 `platforms/android-36`（**只有 `android-37.0` 不够**）。
装的时候**必须走代理**，否则会卡在下载：

```bash
export JAVA_HOME="D:/JDK/jdk-17.0.20.1+1-hotspot"
"$SDK/cmdline-tools/latest/bin/sdkmanager.bat" \
  --proxy=http --proxy_host=127.0.0.1 --proxy_port=7890 "platforms;android-36"
```

**复核**（`package.xml` 存在才算装好，空目录是中断残留）：

```bash
ls "$SDK/platforms/android-36/package.xml"
```

## 5. Gradle wrapper 发行包

路径：`~/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1-bin.zip`。

**症状**：该目录只剩 0 字节的 `.part`（紧急迁移被清空），wrapper 反复重下且卡住。

**做法**：续传补全（wrapper 首次运行会自己解压）。删掉残留的 `.lck`/`.part` 再下：

```bash
D=~/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj
rm -f "$D"/*.part "$D"/*.lck
curl -L -x http://127.0.0.1:7890 --retry 8 --retry-delay 3 --retry-all-errors \
  -o "$D/gradle-9.4.1-bin.zip" https://services.gradle.org/distributions/gradle-9.4.1-bin.zip
```

**复核**（131.4 MiB；`unzip -t` 无错才算完整 —— 中途 `schannel: server closed abruptly`
会导致文件被截断，而大小看起来"像下完了"）：

```bash
unzip -tq "$D/gradle-9.4.1-bin.zip" && ls -l "$D/gradle-9.4.1-bin.zip"
```
