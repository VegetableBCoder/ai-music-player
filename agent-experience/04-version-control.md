# 版本控制与提交规范

## 1. 提交信息

- **语言一律中文**（注释、提交信息、文档同此约定）。
- **末尾必须带尾注**：

  ```
  Co-authored-by: CommandCodeBot <noreply@commandcode.ai>
  ```

  漏了要补 —— 用 `git commit --amend` 追加：

  ```bash
  git log -1 --format=%B > /tmp/cm.txt
  printf '\nCo-authored-by: CommandCodeBot <noreply@commandcode.ai>\n' >> /tmp/cm.txt
  git commit --amend -F /tmp/cm.txt --quiet
  ```

- 提交信息要写**为什么**，尤其是"看起来该这样做、实际是那样"的判断过程。
  本项目的历史提交就是这么写的 —— 那是给后来人看的，不是流水账。

## 2. 文件模式：Windows 不记录可执行位

**症状**：`git status` 里 `gradlew` 反复显示 `old mode 100755 / new mode 100644`，
或脚本提交后在别的机器上因缺 x 位跑不起来。

**根因**：Windows 文件系统不保留 Unix 权限位，Git 看到的是 `100644`。

**做法**（两条都要）：

```bash
git config core.fileMode false      # 本机关掉模式比对，免得反复漂移
git update-index --chmod=+x <file>  # 需要执行位的文件，显式写进 index
```

**复核**：`git ls-files -s <file>` 应显示 `100755`。

**推论**：仓库里**别依赖 `./<脚本>`**，用 `bash <脚本>` —— 否则在 Windows 上会失败。

## 3. 行尾符：CRLF 警告是无害的

**症状**：每次 `git add` 都刷一堆

```
warning: in the working copy of 'X', LF will be replaced by CRLF the next time Git touches it
```

**判定**：这是**提示不是错误**，仓库按 LF 存储、检出时按本机配置转换，不影响内容。
不需要"修"，也不用加 `.gitattributes` 去消除它（除非真有二进制文件被误转换）。

## 4. 不该入库的东西

| 文件 | 原因 |
| --- | --- |
| `local.properties` | 本机 SDK 路径，机器间不同 |
| `.env` / `.env.*` | 含真实 API Key，**绝不入库** |
| `.idea/`、`build/`、`.gradle/` | IDE 与构建产物 |

**提交前自查**（两个都该无输出）：

```bash
git ls-files local.properties
git ls-files .env
```

**注意**：`.env` 曾被误提交过（有提交 `0e5d941` 专门把它重新挡回去）。涉及密钥时
**先查 `git ls-files`，再提交** —— 一旦进了历史，清理成本远高于当时多看一眼。
