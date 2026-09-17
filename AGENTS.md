
## 构建环境（2026-09 换机后重写）

**本机网络路径：直连拿不到大文件，走代理。** 代理在 `127.0.0.1:7890`，
已配进 `~/.gradle/gradle.properties` 的 `systemProp.http(s).proxyHost/Port`。

排障顺序：先确认代理端口在监听、且真的能出网，再去怀疑 artifact 不存在。

```bash
curl -x http://127.0.0.1:7890 -o /dev/null -w '%{http_code}\n' https://services.gradle.org/distributions/
# 期望 200/307；连不上就是代理没开或端口变了
```

典型症状（换了根因，症状不变）：新依赖下载失败，报 `Remote host terminated the handshake` /
`The server may not support the client's requested TLS protocol versions` ——
**看起来像 artifact 不存在，实际是网络路径断了**。

> **旧规矩已作废**：以前那台 Linux 机是「直连通、代理坏」，所以要以
> `-Dhttp.nonProxyHosts='*' -Dhttps.nonProxyHosts='*'` 开头绕过代理。**本机正好相反** ——
> 直连在 `307` 之后下不动（实测 0 字节），代理 1.6 MB/s。依赖未缓存时加那两个参数**会直接失败**，
> 别再当默认前缀用了。（历史教训：曾把 `androidx.security:security-crypto:1.1.0` 误记为
> "artifact 不存在"，实为网络路径问题；该 artifact 一直存在于 Maven Central 稳定版 1.1.0，
> minCompileSdk=34，兼容 compileSdk 36。）

## 本机 JDK / Android SDK（换机后）

- JDK 全在 `D:/JDK/`（非标准位置）：`jdk-25.0.3.9-hotspot` 是 Gradle daemon 用的
  （`gradle/gradle-daemon-jvm.properties` 锁 `toolchainVersion=25`），`jdk-17.0.20.1+1-hotspot`
  是编译目标（Java 17），另有 `jdk-21` / `jdk-8`。
  因为不在标准位置，`~/.gradle/gradle.properties` 里用 `org.gradle.java.installations.paths`
  显式指过去；不指的话 Gradle 会去 foojay 下一份（慢且依赖网络）。
- Android SDK：`C:/Users/huwansong/AppData/Local/Android/Sdk`，写在 `local.properties` 的 `sdk.dir`。
  **路径必须用正斜杠** —— Java properties 会把 `\U` / `\A` 这类无效转义的反斜杠吞掉，
  写成 `C\:\Users\...` 会被解析成 `C:Usershuwansong...`，构建报「文件名、目录名或卷标语法不正确」。
  已装 `platforms;android-36`（项目 `compileSdk = 36`；SDK 里另有 android-37.0）与 `build-tools;36.0.0`。
- Gradle wrapper 发行包：`~/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1-bin.zip`。
  换机时该目录若只剩 0 字节 `.part`，续传补全即可（wrapper 首次运行会自己解压）：

  ```bash
  cd ~/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj
  curl -L -C - -x http://127.0.0.1:7890 --retry 8 --retry-all-errors -o gradle-9.4.1-bin.zip \
    https://services.gradle.org/distributions/gradle-9.4.1-bin.zip
  ```

## 本机设备

- 真机通过 USB 连接。**跑 androidTest 前先唤醒设备**：
  `adb shell input keyevent KEYCODE_WAKEUP`，否则 MIUI 会报 `INSTALL_FAILED_USER_RESTRICTED`，
  那看着像权限问题，其实只是屏幕黑着。
- 无线调试的端口每次重启都变，别把端口写死。
