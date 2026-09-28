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

## 6. 走 `git clone` 的工具代理是另一套，别只改 Gradle

**症状**：Gradle 依赖下得动，但用 `git clone` 拉 GitHub 的工具失败 ——
例如 `install_source` 装 Reasonix 插件包时报：

```
source is not readable: git clone failed: exit status 128: Cloning into '...'...
fatal: unable to access 'https://github.com/obra/superpowers.git/':
Failed to connect to github.com port 443 via 127.0.0.1 after 2094 ms: Could not connect to server
```

**根因**：git **不读** Gradle 的 `systemProp.*.proxy*`，**也不读** `HTTP_PROXY`/`HTTPS_PROXY`
环境变量；它只认 `git config` 里的代理。本机全局 `~/.gitconfig` 曾挂着两条**已失效**的条目：

```
http.https://github.com.proxy=http://127.0.0.1:7891
https.https://github.com.proxy=http://127.0.0.1:7891
```

`:7891` 早就没了（`curl -x http://127.0.0.1:7891 …` 返回 `000`），当前活着的是 `:7890`。
于是**任何走 git 的工具都连不上**，而报错只写"连不上"，压根不提代理端口 —— 极易误判成断网。
（2026-09 本机实测：改到 `7890` 后同一仓库 clone 立即成功。）

> ✅ **已修复（2026-09 用户自行处理）**：`~/.gitconfig` 两条现在都已是 `7890`。
> 上面的报错只会在**代理端口再次变化**时复现；下面的做法仍要留着，换机/换代理后照用。

**做法**：能加参数就临时覆盖，用完即弃：

```bash
git -c http.https://github.com.proxy=http://127.0.0.1:7890 \
    clone --depth 1 https://github.com/<owner>/<repo>.git
```

对**没法传 `-c` 的集成工具**（`install_source` 用内置 clone），改全局配置、装完**按原值还原**：

```bash
cp ~/.gitconfig ~/.gitconfig.reasonix-bak      # 先备份
git config --global http.https://github.com.proxy http://127.0.0.1:7890
# ... 执行安装 ...
cp ~/.gitconfig.reasonix-bak ~/.gitconfig      # 用备份整体还原，别手写"原值"
rm ~/.gitconfig.reasonix-bak
```

### ⚠️ 只有 `http.<url>.proxy` 生效，`https.<url>.proxy` 是噪声

`http.<url>.proxy` / `https.<url>.proxy` 两个 key 名字长得像「分别管 http 和 https」，
**其实不是**。git 只有 `http.*` 一族配置，`https.<url>.proxy` 落在**未知 section `https`** 里，
git 直接忽略（也不会报错）。控制实验（2026-09 本机，均 clone 同一公开仓库）：

| 只配这一条 | 结果 |
| --- | --- |
| `http.https://github.com.proxy=http://127.0.0.1:7890` | ✅ 成功 |
| `https.https://github.com.proxy=http://127.0.0.1:7890` | ❌ `Failed to connect to github.com port 443`（走了直连，21s 超时） |

→ 所以**真正起作用的是 `http.https://github.com.proxy` 那一条**。
留着 `https.*` 是历史遗留噪声，写新配置时**只写 `http.*`** 即可。

**复核**：**先分端口探测，再 clone** —— 别去猜是代理挂了还是仓库不存在：

```bash
for p in 7890 7891; do
  printf "%s: " "$p"
  curl -s -o /dev/null -w '%{http_code}\n' --max-time 6 -x "http://127.0.0.1:$p" https://github.com
done
# 200 = 活着；000 = 端口没人监听
```

> 📌 **历史记录（已作废）**：`7891` 那两条 `~/.gitconfig` 配置是本机**曾经**的失效值，
> 2026-09 已由用户改写为 `7890`。此处保留仅作历史，**不要照抄 `7891`**。

## 7. SSH 方式的 GitHub（`git@github.com:…`）不走 git 代理，本机也**不需要**配

**症状/疑问**：既然 HTTPS 要挂代理，那 `git@github.com:obra/superpowers.git` 这种
SSH 写法是不是也得配代理？

**结论：不用。** SSH 走自己的通路，**完全无视** `http.*.proxy` 与 `HTTPS_PROXY`；
本机 **22 端口直连 GitHub 就是通的**，直接 clone 即可。实测（2026-09 本机）：

```bash
ssh -T git@github.com
# Hi VegetableBCoder! You've successfully authenticated, but GitHub does not provide shell access.
# ↑ exit=1 是 GitHub 的正常行为（它不给 shell），不代表失败

git ls-remote git@github.com:obra/superpowers.git HEAD
# b36e0829c6d0140e93cfef2ca599b1b07d4a7797	HEAD   ← exit=0，通路正常
```

- `~/.ssh/config` **不存在**也能用（默认走 22 端口 + `~/.ssh/id_rsa`）。
- **不要**给 SSH 加 `http.proxy`：加了对 SSH 毫无作用，只会误导后来人。

### 只在「22 端口被挡、必须走代理」时才需要下面两条（本机**尚未**触发）

本机 22 与 `ssh.github.com:443` 都实测可通，所以**当前什么都不用加**。
若换网络环境后 22 被挡（表现为 `Connection timed out` / `Connection refused`），二选一：

```bash
# 方案 A（推荐）：走 GitHub 官方 443 端口的 SSH，不依赖本地工具
# ~/.ssh/config
Host github.com
  HostName ssh.github.com
  Port 443
  User git

# 方案 B：用 ProxyCommand 让 SSH 走本地 http 代理（本机有 connect）
Host github.com
  ProxyCommand connect -H 127.0.0.1:7890 %h %p
  User git
```

**复核**（先确认哪条通路断了再动手，别直接抄配置）：

```bash
ssh -T -p 443 git@ssh.github.com         # 方案 A 的备用通路是否可通
command -v connect ncat socat nc         # 方案 B 需要其中之一（本机只有 connect）
ssh -vT git@github.com 2>&1 | grep -i 'connect\|proxy'   # -v 看实际走了哪条路
```

> ⚠️ **别照搬**：`ncat`/`socat`/`nc` 本机**都没有**，只有 Git for Windows 自带的 `connect`。
> 照抄网上 `ProxyCommand nc -X connect …` 会直接报「找不到 nc」。
