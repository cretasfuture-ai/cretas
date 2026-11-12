# Windows 上的 shell 与路径：空格才是根因，中文不是

**最后更新**: 2026-11-13（瘦身；实测对照与细节在 `docs/rules-casebook/resident-rules-pre-slim-20261113.md`）

实测分离两个变量：纯 ASCII 带空格不加引号会失败，纯中文无空格不加引号成功。**会让命令失败的是空格，中文是安全的**；不加引号读「中文+空格」路径时 bash 会输出乱码转义报错，看起来像编码问题——看到这种乱码先数空格，不要查编码。中文侧（文件名/内容/全角标点/rg/git）已是对的，⛔ 不要为此开 Windows UTF-8 Beta（ACP 65001，系统级会弄坏依赖 GBK 的旧程序）。

1. ⛔ **新建的文件和目录不带空格**，中文随便用，空格用 `-` 或 `_`。存量约 625 个带空格的 tracked 文件（E2E 截图）靠引号活着，⛔ 不批量改名。
2. ⛔ **路径变量一律加引号**：`cat "$file"`、`cd "$dir"`（`C:\Program Files` 改不掉，PATH 上 26/61 条带空格）。
3. **命令发给对的 shell**：提示符 `PS C:\>` 收 PowerShell，`MINGW64 ~ $` 收 bash；Windows 原生的事（注册表、环境变量、`Get-*`）走 PowerShell，POSIX 脚本、git 管道、`sed`/`awk` 走 Git Bash。
4. **需要精确字符的内容不经过 shell**：含转义、引号、多行的直接用 Write/Edit 写文件，⛔ 不用 heredoc（转义被吃掉不报错，只是行为不对）。
5. Git Bash 下 `git show origin/main:path` 要 `MSYS_NO_PATHCONV=1`，否则冒号被改写、`cat-file -e` 一律报不存在。
6. ⛔ Windows 子进程 `subprocess(text=True)` 经 ssh stdin 传远端脚本会变 CRLF（`set -e` 失效），用二进制 `input`。
