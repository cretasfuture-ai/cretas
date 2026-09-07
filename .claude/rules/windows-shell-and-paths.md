# Windows 上的 shell 与路径：空格才是根因，中文不是

**最后更新**: 2026-09-07（首版，来自一次全机器环境审计的实测）

---

## ▎一句话

**中文文件名/内容在这台机器上是安全的。会让命令失败的是空格。**
而中文会把空格造成的报错**伪装**成编码问题，所以这条一直被误诊。

---

## 实测对照（分离「中文」与「空格」两个变量）

```bash
# [1] 纯 ASCII + 有空格, 不加引号   ← 只有空格这一个变量
cat ascii dir/f.md
    cat: ascii: No such file or directory
    cat: dir/f.md: No such file or directory          ❌ 失败

# [2] 纯中文 + 无空格, 不加引号     ← 只有中文这一个变量
cat 纯中文目录/f.md
    B                                                  ✅ 成功

# [3] 中文 + 空格 + 引号
cat "中文目录 带空格/嵌套文件.md"
    第二层中文                                         ✅ 成功
```

**[1] 失败且 [2] 成功 ⇒ 根因是空格，与中文无关。**

⚠️ 不加引号读一个**中文且带空格**的路径时，bash 会把非 ASCII 转义成
`cat: '�'$'\255''�'$'\226\207'...: No such file or directory` ——
一个普通的「空格没加引号」长得像「中文编码炸了」。
**看到这种乱码报错，先数空格，不要去查编码。**

---

## 中文侧：已经是对的，不要再「修」

实测全通，无需任何改动：

| 项 | 实测 |
|---|---|
| PowerShell ↔ Git Bash 双向中文文件名/内容 | 正确 |
| 全角标点 `：、。「」` | 正确 |
| `rg` / `fd` / `git` 处理中文名 | 正确（git 有 1266 个非 ASCII 文件名） |
| 文件编码 | UTF-8 无 BOM |

已生效的配置：`core.quotepath=false`、`PYTHONUTF8=1`、PowerShell 控制台 UTF-8。

⛔ **不要为了「解决中文问题」去开 Windows 的 UTF-8 Beta（ACP 65001）。**
ACP 目前是 936，实测连走 `cmd.exe` 的输出都没有乱码；
那个开关是系统级的，会让依赖 GBK 的旧程序反过来出问题。

---

## ⛔ 硬约束 1：新建的文件和目录不带空格

`B1-异常流-超收防呆.png` 与 `B1-异常流- 超收防呆.png` 对人一样，对 shell 是两个参数。
**中文随便用，空格用 `-` 或 `_` 代替。**

存量 **625 / 30163** 个 tracked 文件带空格（基本是一批 E2E 截图，
命名模式 `异常流- 超收防呆`，短横后多一个空格）。
⛔ **不要批量改名** —— 会动 git 历史和引用它们的文档，收益远小于风险。存量靠引号活着。

---

## ⛔ 硬约束 2：路径变量一律加引号

这条**永远**需要，因为 `C:\Program Files` 改不掉 —— PATH 上 **26 / 61** 条带空格，
`node` 就在 `Program Files` 下。所以「全仓没有空格」这个前提在 Windows 上不成立。

```bash
cat "$file"           # ⛔ 不要写 cat $file
cd "$dir"             # ⛔ 不要写 cd $dir
rg pattern "$path"    # ⛔ 不要写 rg pattern $path
```

---

## ⛔ 硬约束 3：命令要发给对的 shell

实测踩过：把 PowerShell 命令粘进 MINGW64 窗口 →
`bash: syntax error near unexpected token`。

判据是**提示符**：

```
PS C:\>            收 PowerShell 命令
MINGW64 ~ $        收 bash 命令
```

Agent 侧同理：PowerShell 工具与 Bash 工具选错就是一次白失败。
Windows 原生的事（注册表、环境变量、`where.exe`、`Get-*`）走 PowerShell；
POSIX 脚本、git 管道、`sed`/`awk` 走 Git Bash。

---

## ⛔ 硬约束 4：需要精确字符的内容，不经过 shell

含转义、引号、多行的内容**直接写文件**（Write/Edit），⛔ 不要用 heredoc 传。
转义在 heredoc 里被吃掉时**不报错，只是行为不对**。

与 `measurement-and-wiring.md` 硬约束 6 同一条，此处只补 Windows 的理由：
跨 PowerShell / Git Bash 两层解释时，被吃掉的层数还会再多一层。

---

## Git Bash 特有：路径改写

`git show origin/main:path` 里的冒号会被 MSYS 转成 `;` 加反斜杠，
`git cat-file -e` 一律报「不存在」——一个反向的错误结论。

```bash
MSYS_NO_PATHCONV=1 git show origin/main:.claude/rules/x.md
```

---

## 关联

- `measurement-and-wiring.md` 硬约束 6（转义不经过 shell）
- `concurrent-edit-safety.md`（并发下的文件安全）
