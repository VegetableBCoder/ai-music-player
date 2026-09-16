
## 构建环境：Gradle 走代理会握手失败（已定位，反复踩过）

本机 `~/.gradle/gradle.properties` 配了代理 `127.0.0.1:7890`，而 shell 里**没有**代理环境变量。
后果：新依赖下载失败，报错却是 `Remote host terminated the handshake` /
`The server may not support the client's requested TLS protocol versions` ——
**看起来像 artifact 不存在，实际是代理断了**。诊断方法：同一条 URL 用 curl 测，
直连返回 `HTTP/2 200` 就证明 artifact 存在、问题在网络路径。

**规矩：本仓库凡是需要联网解析依赖的 Gradle 命令，都加这两个参数：**

```bash
./gradlew -Dhttp.nonProxyHosts='*' -Dhttps.nonProxyHosts='*' <task>
```

不要改 `~/.gradle/gradle.properties`（那是本机用户环境，不是仓库的事）。
历史教训：Phase 4 之前曾把 `androidx.security:security-crypto:1.1.0` 误记为"artifact 不存在"，
实为同一根因；该 artifact 一直存在于 Maven Central 的稳定版 1.1.0（minCompileSdk=34，兼容 compileSdk 36）。

## 本机设备

- 真机通过 USB 连接（`CYIBNFVSNBHICAJB`）。**跑 androidTest 前先唤醒设备**：
  `adb shell input keyevent KEYCODE_WAKEUP`，否则 MIUI 会报 `INSTALL_FAILED_USER_RESTRICTED`，
  那看着像权限问题，其实只是屏幕黑着。
- 无线调试的端口每次重启都变，别把端口写死。
