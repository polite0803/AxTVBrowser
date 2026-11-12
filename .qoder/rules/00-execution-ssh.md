---
description: Qoder 以本地工作区打开 Z:\AxTVBrowser 时，所有命令必须经 SSH 在 WSL Linux 侧执行；定义执行宿主、Gradle 9.9 / JDK21 toolchain、验收与 git 边界
alwaysApply: true
---

# 执行宿主：SSH → WSL Linux（AxTVBrowser，强制）

> **适用边界**：仅 Qoder 本地工作区（`Z:\AxTVBrowser`）会话生效。会话本身已在 WSL Linux 内时
> 直接本地执行，不要加 SSH 包装。
>
> 项目 `AGENTS.md` 里的 `On Windows use gradlew.bat / .\gradlew` **只适用于原生 Windows
> checkout**；本机 WSL / Qoder 一律 `./gradlew`，忽略该句。共享根 `../AGENTS.md` 有完整约定。

## 一、环境事实（单宿主）

| 项 | 值 |
|---|---|
| Qoder 工作区（编辑锚点） | `Z:\AxTVBrowser` |
| 实际执行宿主 | WSL Ubuntu-24.04，`/home/polit/projects/AxTVBrowser`（ext4） |
| 两者关系 | **同一份文件**；Z 盘是 SMB 映射，不是副本、不需同步 |
| SSH 别名 | `wsl`（`127.0.0.1:22` / `polit` / `id_ed25519`） |
| 技术栈 | Android / Gradle 9.9.0 (Kotlin DSL)，`buildSrc` 声明 `jvmToolchain(21)` → **JDK 21** |
| JDK 现状 | WSL 只装 JDK 17；Gradle 9.9 可用 17 启动，JDK 21 toolchain 由 `settings.gradle.kts` 的 `foojay-resolver-convention` 自动下载到 `~/.gradle/jdks`（首次构建需联网）。**不要**为此手动改 `sdk.dir` 或做 C:/D: 副本 |
| SDK | `local.properties` 的 `sdk.dir=/home/polit/android-sdk` 已是 WSL 路径，禁止改成 Windows 路径 |
| 跨项目入口 | `/home/polit/projects/scripts/wsl-project.sh AxTVBrowser <命令>`（该脚本强制 `JAVA_HOME=17`，对本仓库无害） |
| 模型 | 保持 Qoder **本地工作区**，用本地配置的 BYOK 第三方模型。**禁止**改用 Qoder SSH 工作区（只能使用内置模型） |

## 二、命令执行铁律

单条命令（**必须以裸 `ssh` 起头**，Qoder 白名单已放行 `ssh` / `ssh.exe`）：

    ssh -o BatchMode=yes wsl "cd /home/polit/projects/AxTVBrowser && <command>"

多条读写 / 校验用**一次 SSH 批量会话**：

    ssh -o BatchMode=yes wsl 'bash -s' <<'EOF'
    cd /home/polit/projects/AxTVBrowser
    ... 多个文件的读、写、校验一次做完 ...
    EOF

- 命令不要包 `powershell -Command` / `cmd /c`（白名单首词不命中）；PowerShell heredoc 先转 LF。
- 构建 / 测试 / adb / 全库搜索**一律经 SSH 在 ext4 执行**。禁止在 `Z:`（SMB）或 9P UNC 上跑
  `gradlew` / `gradlew.bat` / adb —— 两端共用 `build/`、`.gradle/` 会触发
  `java.nio.file.NotDirectoryException`。
- 禁止把项目复制 / 克隆到 `C:` / `D:` 做本地副本再编译；远端只用 Linux 路径。
- 读文件、改文件用工作区文件工具（走 `Z:\`）；精确替换 + sha256 校验走批量 SSH 用远端 `python3`。
- 若 JDK 21 toolchain 自动下载失败（无外网），先报告，**不要**自行 `apt install` 或改全局
  `JAVA_HOME`；可选方案是装 `openjdk-21-jdk` 后以
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew ...` 直连（需用户确认）。
- 中文日志乱码时改 ASCII 关键行输出，或用 Base64 回传后本地解码。

## 三、验收

| 目的 | 命令（经 SSH，在 `/home/polit/projects/AxTVBrowser` 下） |
|---|---|
| 环境自检 | `bash /home/polit/projects/scripts/wsl-project.sh env AxTVBrowser` |
| Debug 构建（GeckoView 变体） | `./gradlew assembleGenericGeckoIncludedDebug` |
| Release 构建（WebView 变体） | `./gradlew assembleGenericGeckoExcludedRelease` |
| 单测全量 | `./gradlew testGenericGeckoIncludedDebugUnitTest` |
| 单测单类 | `./gradlew :app:testGenericGeckoIncludedDebugUnitTest --tests "com.axtv.browser.utils.FaviconExtractorTest"` |
| 公共模块单测 | `./gradlew :app:common:testDebugUnitTest` |

`buildSrc` 的 `allWarningsAsErrors = true` —— 警告即失败。Release 签名需
`KEYSTORE_PATH` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 四个环境变量，
无 `KEYSTORE_PATH` 时产出未签名包。Room schema 变更需导出 schema 并补迁移测试。
失败读原始报错关键行，不复跑同一条失败命令。

## 四、git 边界

规范使用，不禁止。git 一律经 SSH 在远端执行（仓库在 ext4，避免 SMB 上 filemode / CRLF / index 损坏）。

- 提交前 `status` + `diff`，改过代码跑项目门禁，一次一件事，push 前确认分支与远端。
- 破坏性操作（`reset --hard`、`clean -fd`、`rebase`、`push --force`、已推送提交 `--amend`、`--no-verify`）默认不做，需逐条确认。
- 回滚用 `.bak` 文件备份或临时 commit，**不用 `git stash`**；不在 `.git` 内做手术。

## 五、输出

简体中文、结论先行、表格化；不复述规则与问题；状态复述 ≤3 行；长输出只摘关键行。
