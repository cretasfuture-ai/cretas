# 常驻规则瘦身前原文（2026-11-13）

常驻规则从约 85KB 压到 40KB 以内（每个会话开场都加载，每轮重读，成本高）。下面是六份规则瘦身前的**完整原文，原样保留**；现行规则文件只留判据与硬约束，需要背景/事故叙事/完整命令时来这里查。发布命令与 fastlane 分档、离线部署另见 `.claude/skills/deploy-backend/references/main-only-deploy-details.md`。


---

<!-- BEGIN measurement-and-wiring.md (原文) -->

# 量对东西 / 接上没有 / 变异打在被守的行为上

**最后更新**: 2026-10-16（本文件只留硬约束与形态判据，每 session 常驻；全部实测案例原样存档在
`docs/rules-casebook/measurement-and-wiring-cases.md`，编号与本文件一致，代码注释里引用的
「硬约束 8」「形态 A¹⁰」等都能在那里找到。新增案例写案例库，新增约束改这里并同步编号。）

---

## ⚠️ 改这份文件之前先读这一句

这份规则被它自己描述的东西咬过三次：没登记进 `CLAUDE.md` 常驻清单（形态 B）、示例被转义吃掉（硬约束 6）、
加载的是落后 1424 个 commit 的旧副本（载体）。所以每次改完花 10 秒做两件事：

1. **载体**：确认它还在 `CLAUDE.md` 常驻清单里；动手前 `git rev-list --count HEAD..origin/main` 非 0 就停下。
2. **自指**：这次改的那一条，它自己有没有犯它自己说的错（转义、截断、编号撞名）。

---

## ⛔ 硬约束（九条，执行项，不是提醒）

### 硬约束 1：诊断输出一律落文件再查，禁止管道直接 `head` / `tail`

```bash
./cmd > /tmp/x.log 2>&1; rc=$?; echo "rc=$rc"; wc -l /tmp/x.log; grep -n -E "ERROR|FAIL|拒绝" /tmp/x.log
```

三个理由：管道会截掉你要看的那一段（一次 3 分半的白构建就是这么来的）；`cmd | tail` 的退出码是 **tail 的**，
失败被读成成功且无声；多步流水线用 `&&` 不用 `;`，闸拦住一步不代表后一步知道它被拦了。

### 硬约束 2：证明正确组件正在运行，业务路径按完成条件验证

`DEPLOY_EXIT=0`、`RELEASE_FINAL_STATUS=deployed` 不是充分证据：`release-cretas.sh` 只发 Java + web-admin，纯 Python 的 PR 会全绿而根本没上线；`no-op` 不能被读成「部署了」。必须证明本次组件、运行制品/来源和健康符合要求。

执行与复用边界见 `execution-and-verification.md`：同一轮完整发布回执已覆盖的 grep、upstream、systemd 或哈希不重复做；组件未覆盖、回执缺失/失败或状态变化时补缺失证据。下面仅是缺证据时的诊断示例，不是每次发布的额外必跑项：

```bash
ssh <host> "ps -o lstart= -p \$(systemctl show -p MainPID --value <svc>); stat -c '%y' <artifact>"   # 进程晚于制品
ssh <host> "unzip -p <jar> '<class>' | strings | grep -c '<ASCII 标记>'"   # strings 读不到中文, 中文标记恒为 0
```

找到标记不证明业务能跑：曾有单测、AST、三个标记全绿而探针出现 `NameError`。完成条件包含业务修复/验收时，还须按当前任务授权真跑对应路径并检查必然产出；不把服务健康算作业务或 UI 验收，也不自动扩展生产写入范围。

### 硬约束 3：跨样本批量读数，开跑前清缓存并贴出清了哪几个

```python
for fn in ("clear_semantic_plan_cache", "clear_route_cache", "clear_tenant_gate_cache"):
    getattr(ri, fn)()      # ⛔ 不要 getattr(..., None): 拼错的名字静默 no-op
```

参数化遍历要断言被参数化的那一维真的不同（19 个意图用同一句问句 = 路由缓存 replay 18 次）。清缓存拼错的属性名不会报错，只会静默失效（C⁵）。
计划缓存键不含 history，A/B「带 / 不带上下文」两侧会命中同一条目 —— 16 个样本里 16 个无信息。

### 硬约束 4：定时跑批的退出码是三态

`rc=0` 没问题 / `rc=1` 有问题（读数有效）/ `rc=2` 这次没量到（阳性对照没过、样本 0 条、仪器起不来）。
`rc=2` 单独告警、措辞与 `rc=1` 不同；**主动构造一次 `rc=2`**（库名指到不存在的库）证明它到得了，
否则一个连不上库的跑批会安静地天天绿。

### 硬约束 5 + 6：改写脚本不经过 shell、不用 `bytes` 字面量、先算好再开文件

- 文本模式 `open(p, "r"/"w", encoding="utf-8", newline="")`：不转换行尾（与二进制一样不污染 CRLF），又能写中文。
  `bytes` 字面量含中文当场 `SyntaxError`，一天栽四次。
- 含转义的内容用 Write/Edit **直接落盘**；heredoc 写出来的脚本转义会再被吃一层，症状不是报错而是 `count()` 返回 0。
  非过 shell 不可时 `BS = chr(92); pattern = BS + "x1f"` 显式构造。
- `open(p, "wb").write(expr)`：`open` 在 `expr` 求值之前就截断了文件，表达式一炸文件就是 0 字节。先算好再开（C⁷）。会写文件的命令不与其它命令同格。

### 硬约束 7：squash 合并之后，本地分支必须重开

```bash
git fetch origin main
git worktree add -b codex/claude-<task>-<n> ../cretas-<task>-<n> origin/main
git cherry-pick <已写好的 commit>
```

原分支的 commit 不再是 main 的祖先，继续追加再开 PR 直接冲突（一晚作废两个 PR）。
⛔ 不用 `git merge origin/main` 救旧分支。`safe-commit.sh` 按 **PR 状态**（不是 hash，squash 让 hash 对不上）
拒绝在已 MERGED 的分支上提交；拿不到状态一律放行。

### 硬约束 8：改共享结构前先数，改完再数，两个数必须相等

```bash
git grep -c '<那个结构的标志串>' -- <文件>      # BEFORE=N；已改处 + 显式登记「故意不改」处 == N
```

同一晚三次「只改一处」：拒答从一道闸挪到下一道、毛利对了毛利率还是两个数、meta 出口 4 处改 1 处。
「故意不改」要登记并写理由。**数之前先确认数的是那个东西**：第三次 `BEFORE=4/AFTER=4` 完全正确，
而真因是探针自己没传 role —— 机械约束保证你不会忘了数，形态 A 保证你数对了东西，两层都要。

### 硬约束 9：验证「X 没发生」时，必须同时有一个「Y 一定会发生」的读数

```bash
alerts_before=$(wc -l < alerts.log); ledger_before=$(wc -l < ledger.jsonl); run_it
# ledger 每次必追加一行 —— 它没涨就是根本没跑完, 那么 alerts 没涨的读数无意义
```

「没发生」是最容易伪造的读数：探针崩在写告警文件之前，`[ -s ]` 里「文件不存在」和「决定不喊」一模一样，
表象正是「修好了」。Y 必须是**设计上的必然**，「日志里通常会有」不算。

---

## 形态（识别用；括号里是案例库章节）

### 形态 A：我量的这个数，不是我真正想知道的那个数

动手前把两句并排写下来读一遍：`我想知道的是 X：__ / 我实际在量的是 Y：__`。X ≠ Y 就停下重新设计仪器。常见马甲：

- **我看到的是全部吗**：截断、分页、`head`、`[:N]`、少一个锚点，都把「没有」变成「我没看到」；
  结论标签必须由实际值算出来，⛔ 不无条件打印「（空=零新增）」（A⁗）。
- **仪器自己的会话上下文**：RLS 下 asyncpg `set_config(..., true)` 只在那一条语句里有效，每租户独立会话并贴出
  `current_setting`；阳性对照要跨样本，只在某一个样本上成立的对照证明不了别的样本（A⁶）。
- **问活进程要真相**：`/proc/<pid>/environ`、`fd/`、`cwd`、`cmdline`（`exe` 会把 symlink 解析掉）；从监听端口反查 pid
  而不是 `pgrep -f`（会抓到你自己的探针）；只 export 需要的前缀，别 eval 整份 environ（A′、A¹²）。
- **基线当场重跑同一条命令**，不引用记忆里的数（记忆里 8 failed，重跑 68 failed）（A⁷）。
- **每条读数带来源标记**（缓存 / 重试 / 降级 / 预热）并逐条贴：一个 `source_tier` 字段把「16 条没差异」变成「16 条无信息」；
  换了样本单位不等于换来了样本，改方法后先估有效率再定样本量（A¹³、A¹⁴）。
- **兜底默认值把「不知道」翻译成 0**：写 `.get(k, 0)` / `COALESCE(x, 0)` / `or 0` 前问一句这个 0 会不会被当真实读数往下传；
  会就让它炸（A¹⁰）。
- **空是不是合法状态**：一次性接入的类只问「接了没有」，⛔ 不算填充率（A¹¹）。
- 一个原因解释 N 个症状时逐个验证机制（A⁵）；判 A 对不对，分母用产生 A 的那个基准（A⁸）；
  承诺「补 X 就变准」之前先问补齐后它会不会仍然是估的（A⁹）。
- **报数同时报口径**：两轮都是「9 条」可能是两个集合，仪器改过时数字可比性为零。
- **日志取数的窗口**：daily rotate + copytruncate，第二天 grep 活文件只数得到第二天，用 `zgrep` 覆盖 `.gz`，把文件清单和字节数一起贴出来（A″）。
- **判据会在你手上开火**：数字出处闸第一次量到中位命中率 5.6%，按「近 0% 或近 100% 都是仪器问题」的判据先查仪器，揪出正则把日期切成三个数字、源池常年为 0，修完 52.7%（A‴）。

### 形态 B：机制在，只是没接上

单测直接调被测函数，绕过了「谁调它」。每个 PR：至少一条断言跑在**产品真实入口**；新增开关 / 闸 / 清洗要能答
「生产上谁保证它被调用」；**产出端有 ≠ 消费端收得到**，中间每层留一个可观测点二分定位，别一次改三层。

- 断言可能是恒真式：左右同源、mock 没打桩无论走哪条路都没按钮（B′）。
- 闸在你的验收环境里恒真：MOCK_REST 是唯一验收租户，每道闸单独回答「这个租户里有没有能让它红的输入」，
  没有就自己构造，⛔ 不因为只剩它就省掉这一步（B⁴）。
- 桩的形状要能在生产上出现：`COUNT` 空集给 0 不是 NULL，三态里的 `no_data` 永远到不了（B‴）。
- 从一句注释推行为之前先跑一次让它自己说（B⁷）；注释挡不住的写成闸，棘轮只禁增长（B⁶）；
  删掉或改变一句承诺时同一 commit 里 grep 它的关键词（B⁵）。

### 形态 C：变异要打在被守的行为上，不是某一行实现上

问「这条断言在守什么行为」然后破坏那个行为。变异不红先证明**变异真的生效了**（打印一个可观测量），再下结论 ——
三种成因症状不同：恒真式不红（B′）、变异没生效不红（C″：`from mod import NAME` 让 `monkeypatch.setattr(mod, ...)` 够不着；变异打在正文看不见的地方）、断言守着**已经不想要的旧行为**会红得理直气壮（C‴）。
第三种改断言要从「字面」抬到「性质」并补阴性对照，⛔ 不是删断言；去黑话只动黑话。

- 失败不许 `continue`，要计数并逐条贴，信息量最大的样本往往就是失败那条（C′）。
- 修复按**最不显眼的那个**验收：毛利对了不算，毛利率对了才算（C⁶）。
- 变异脚本自身会造缺陷：`write_text` 转 CRLF、`mv` 带回旧 mtime、尾注释吃掉闭括号（C⁗）。
- 这类闸一律走 AST，不再收窄正则（同形第三次）（C⁸）。

### 形态 D：同一个东西有两份，它一定会漂

它能跑，只是跑的是旧的那份，不报错不变红。复制粘贴时问「哪一份是真的」，答不上来就抽成一份；
抽不动就加一道钉「**两份一致**」的闸（一份是源，另一份从它生成或对它校验），「各自都对」是两条独立断言。
一个会误报的提示比没有更糟：它先打在成本率最低的米饭上，店长查完卡没问题从此不再信（D′）。

### 形态 E：闸的完备性和闸的存活是矛盾的

宁可窄而可信，不要宽而被关掉（扩面后 9 条里 6 条误报）。代理判据要标出来并可替换，⛔ 不靠加启发式逼近「面向用户」。
收窄告警时别把「没能做决定」一起收掉：`[ ! -f ]` 仪器死了 / `[ -s ]` 决定要喊 / 在且为空 决定不喊，三态（E′）。

---

## 改 `.claude/` 之前

```bash
git rev-list --count HEAD..origin/main      # 非 0 就停下 —— 曾在落后 1251 / 1303 / 1424 个 commit 的分支上读旧规则
git log --oneline -1 origin/main
```

Git Bash 下 `git show origin/main:<path>` 要 `MSYS_NO_PATHCONV=1`，否则冒号被转成 `;` 加反斜杠、一律报「不存在」。
新增一条规则文件之后，必须同时登记进 `CLAUDE.md` 的常驻清单 —— 那份清单是手工维护的。

## ✅ 正面：读数变了，先分清「我改的」还是「数据动了」

同一棵树、同一个冻结窗口（`PROBE_DATE=昨天`）重跑一次就分得开；这一步 1 分钟，省下的是一条写进报告的错误归因。

<!-- END measurement-and-wiring.md -->


---

<!-- BEGIN worktree-and-main-only-deploy.md (原文) -->

# Worktree 隔离 + 只从 main 部署 prod (HARD RULE)

**最后更新**: 2026-11-11（Steve：worktree 上限 9 → 30；此前 2026-10-16 复用已隔离的任务 worktree；离线期与 CI 判读叙事存档在 `docs/rules-casebook/worktree-and-main-only-deploy-cases.md`）
**触发事故**: 2026-05-30 青花椒 RBAC 修复在 prod 被并发 session 的 Java 部署覆盖, 总营收回归 ¥0。

## ⛔ 四条铁律

### 1. 永远不在主工作目录直接干活

主目录 `C:\Users\Steve\my-prototype-logistics` 被多个并发 session 共享，不能在其中混入代码改动。代码任务先核验现有 worktree 的路径、分支/HEAD、scope 与唯一 Owner，复用合格的本任务副本；无可复用副本时才新建，**永远 off `origin/main`**（off 别的 feature 分支会夹带 sister 的 commit）。只读/调研不新建。⛔ 主目录不混入任何改动（含文档、规则、台账）——要改就在本任务的 worktree 里改，已有合格副本就复用，不因为是纯文档而重建。

```bash
git worktree add -b codex/claude-<task> ../cretas-<task> origin/main
```

### 2. 完成 → 推上 origin/main（不在 feature 分支上累积部署）

merge 前 `git diff origin/main...HEAD --stat` 确认只有你的文件；夹带 sister 文件就 cherry-pick 到干净的 origin/main worktree 重做。squash 合并之后本地分支必须重开（measurement-and-wiring 硬约束 7）。

**合入通道双轨**（2026-07-28 Steve 拍板）：

| 变更 | 通道 |
|---|---|
| `docs/**.md`、`.claude/**`、根 `*.md`、`.gitignore` | **fastlane**：`./scripts/deploy/publish-main-fastlane.sh --base-sha <完整 40 位 SHA> --confirm YES-DIRECT-MAIN`（先 `--dry-run`）。2026-11-13 起 main 设了必需检查、GitHub 拒收直推，脚本默认改为「推分支 → 开 PR → 等检查全部结束无红 → `--match-head-commit` 合并 → 等 MERGED」，文档类 PR 上多数闸判为跳过，通常几分钟；禁 force push、开始前证明 origin/main 未前进；分支名必须 `codex/*`。`FASTLANE_PUBLISH_MODE=direct` 只给本地夹具/离线 main |
| 其余（含 `docs/dispatch/**`、配置文件）| fastlane 须带 `--task-id <本批任务ID>`（ID 必须以词边界出现在 `docs/dispatch/` 下），否则门禁按「全局零未完成任务」判定而恒拒 |
| backend / web-admin 代码 | **PR**（Python Gate / Web Admin Gate / Secret regression 按 paths 跑；PR 号是台账锚点） |
| AGENTS.md / `.agents/skills/*` / `.codex/rules/*` / workflows / `scripts/deploy/*` / 迁移 / entity / repository / security | **强制 PR**（fastlane 列为高风险自动拒绝；`.claude/` 豁免而 `.agents/`、`.codex/` 高风险，三个目录名很像） |

分档是脚本说了算，拿真实 commit 跑一次 `--dry-run` 看 `FASTLANE_READY` 还是 `FASTLANE REJECTED: <原因>`。底线：**任何通道都必须推上 origin/main 后才可部署**，省的是 PR 仪式往返，不是 push 本身。

看 CI 结论：`gh` 时间戳是 UTC；PR 上的 `failure` 先查是不是 `cancelled`（`concurrency: cancel-in-progress` 让 push 与 pull_request 互相取消）；判「某个闸还在不在」看 yml 文件，不看 `gh workflow list`。

### 3. prod 永远从 main 构建 / 部署，绝不从 feature 分支

```bash
# 合并【前】在候选 worktree: 先 build + 预传 (硬规则 2026-09-15; manifest 按 backend tree 复用, 合并后部署 ≈1 min)
./scripts/deploy/release-cretas.sh --phase build --base-sha '<Base SHA>' --tests '<tests>' --stage-backend YES-STAGE
# 合并后:
git fetch origin main && git checkout --detach origin/main      # 或 clean 的 main worktree
./scripts/deploy/release-cretas.sh --phase deploy --base-sha '<Base SHA>' --tests '<同上选择器>' --no-prefer-ci-artifact --confirm-prod YES-PROD
# ⛔ 不跑 prewarm-main-artifact.sh: CI 制品车道在本组织不通 (无 attestation + 制品存储配额), 等 420s 纯浪费
# 判据: DEPLOY_EXIT=0 且日志里 RELEASE_FINAL_STATUS 恰好 1 次 (不出现本身就是失败信号)
```

`deploy-backend.sh` 上传到**固定的共享 jar 路径**，多个 session 各自从 feature 分支部署 = last-write-wins 互相覆盖（5/30 事故原形）。根治：prod = main，所有工作 merge 进 main 再部署。`deploy-backend.sh` 的 idle readiness 三态：`UP:*` 健康 / `TIMEOUT`、`RESTART_LIMIT:*` 真失败 / 空串是 ssh 断线**没量到**（脚本先重试再独立核验；2026-09-02 曾把断线判死回滚）。单组件入口与逃生路见 `deploy-backend` skill。

### 4. worktree 总数 ≤ 30，开新的之前先体检，做完顺手收自己的

（2026-11-11 Steve 把上限从 9 调到 30：多会话并行批次常态下同时在用的目录就有二十多个，9 个会逼着删别人正在用的目录。）

2026-10-07 清理前仓库挂着 76 个 worktree（大半是已合并的、一批是没合并却没人管的），目录占盘、`node_modules` 删起来按分钟算，还有 junction 穿透的风险。铁律 1 要求「每个任务一个 worktree」，没有对应的收口，数量只会涨。

- **开新 worktree 之前**：`bash .claude/tools/worktree-hygiene.sh --check`。总数 > 30（脚本返回 3）就先 `--apply`，再开。先用 `list_sessions` 把 `isRunning` 的 session 的 cwd 用 `--keep <路径>` 传进去。
- **任务做完 / PR 合并后**：把**自己**这个 worktree 收掉（从主仓库目录执行，不要在要删的目录里执行）：`bash .claude/tools/worktree-hygiene.sh --apply`；它会 WIP commit 脏改动、摘 junction、不加 `--force` 地移除，分支保留。
- 脚本保留：主仓库 / 当前所在 / `.codex/worktrees` / `--keep` / locked / 近 120 分钟有动静的。没合并的分支**不删**，分支本身就是备份，最后汇报「分支名 / 领先几个提交 / 有没有开着的 PR」。
- **自动回收（2026-11-11，根治「只涨不收」）**：上限数字不是根因——创建不受控（桌面应用/Codex 每会话自动建）、回收靠人想起来、squash 合并后 git 看不出已合并、Codex 的 worktree 被整体豁免，所以会一直涨（当天 90 个，约六成对应的 PR 早已合并）。现在回收按「活干完了」触发，与总数无关：`worktree-hygiene.sh --auto` 挂在 SessionStart hook（后台跑，日志 `<git-common-dir>/worktree-hygiene.log`），只回收**干净且已完成**的 worktree（分支 PR 已 MERGED 且 PR 头 == worktree HEAD，或 HEAD 已是 origin/main 祖先；Codex 的同样适用；有任何改动、locked、当前所在、`--keep` 指定的、**会话活动标记在近 6 小时内的**一律不动（标记由 `worktree-touch.sh` 在 SessionStart/每条消息/每轮结束时更新；不用提交时间判断——已合并的 worktree 提交时间必然是旧的，2026-11-11 实测靠它的缺位把当前会话自己的目录删了）；会话停用 6 小时后才回收；没有标记的（Codex 会话不跑 Claude hook、hook 上线前的旧会话）改看 HEAD/index 的 mtime，同一 6 小时窗；每次回收还会清「空壳」（git 删完内容却因目录被占用删不掉留下的未登记空目录，只删完全为空的）；本会话执行 `gh pr merge` / `publish-main-fastlane.sh`（非 dry-run）后由 PostToolUse hook（`worktree-after-merge.sh`）立刻触发一次回收，GitHub 网页上合并的则等下一个会话启动。已知不覆盖：无 PR 但内容已被别的 PR 吸收的 detached worktree、合并后又加了提交的分支，这两类要人看；gh 不可用则不回收；分支保留）。organizer 合并 PR 后也跑一次。上限 30 退为兜底：超限时会话开头会提示。
- 定时任务 `cretas-worktree-cleanup` 已停用（每次要起一个 Claude 会话，成本高于 hook），回收以 SessionStart hook 为准。
- ⛔ 不对带 junction 的目录用 `git worktree remove --force`；⛔ 不替 detached 且有改动的 worktree 擅自建分支（脚本会跳过并报告）。

## 必须在 prod 验证 feature 时

测试环境的当前分配见 `server-operations` skill 的服务器地图（YOLO 只在本机 Windows/RTX）。若必须在 prod 验 feature 分支：验完**立即 merge 到 main + 从 main 重部**，然后核对运行中的 jar 含你的修复：

```bash
ssh root@47.100.235.168 "unzip -p /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar 'BOOT-INF/classes/.../YourClass.class' | strings | grep -c '<ASCII 修复标记>'"
```

## 🔌 GitHub 不可用时：本地 `main` 是唯一汇合点

铁律 3 的意图（prod = 所有人都汇合过的点）照样成立，载体从 `origin/main` 换成本地 `main`。所有 worktree 共享同一个 `.git`，本地 merge 不需要网络。

1. 汇合点固定用本地 `main`，不新造整合分支；只有占着 `main` 的那个 worktree 能往上合（天然串行）。动它之前**单独跑一次** `git status --porcelain` 看结果再决定，别和 merge 压进同一条命令。要看「我要动哪些文件」问自己的 commit（`git show --name-only --format= <分支>`），⛔ 不用 `git diff --name-only main..<分支>`（答案取决于基底，会把本地 main 领先的文件全算成你的）。
2. 部署从 detached worktree：`git worktree add --detach ../cretas-deploy-offline $(git rev-parse main)`，然后 `SKIP_GIT_CHECK=1 ./scripts/deploy/deploy-backend.sh --env prod`（web-admin 同理）。逃生门只在「已合进本地 main + 从 main 部署」前提下用；`check_git_sync` 离线时只 WARN，「部署入口全被闸挡住」是不完整的结论。
3. 合并后、部署前跑迁移撞号闸：`mvn -o test -Dtest='FlywayVersionUniquenessTest,*RepositoryQueryValidationTest'`（git 对 Flyway 撞号一个字不报）。
4. 每次离线部署记账：部署了哪个本地 commit、含哪些分支、跑了哪些迁移，写进当天 handoff。
5. 恢复后清单**现算**：`bash scripts/offline-recovery-manifest.sh --commands`，各 feature 分支分别 push → PR → CI；全部合完再 `git checkout main && git reset --hard origin/main`。⛔ 不 `git push origin main`（绕过 CI 一次推十几个 commit）；⛔ 不用 `%(ahead-behind:origin/main)` 筛分支（squash 让老分支永远 ahead，实测筛出 869 条而真动过的 28 条），判据是提交时间晚于 origin/main 冻结时刻。

## 速查

| 场景 | 做法 |
|---|---|
| 开始代码任务 | 核验并复用本任务隔离副本；没有合格副本才从 `origin/main` 新建 |
| 任务完成 | PR 或 fastlane → origin/main；先 `git diff origin/main...HEAD --stat` 确认 scope |
| 部署 prod | 合并前候选树 `--phase build --stage-backend` → 合并 → clean 的 origin/main 树 `--phase deploy`（绝不从 feature 分支；不跑 prewarm） |
| 必须 prod 验 feature | 验完立即 merge main + 从 main 重部 + 核对运行 jar 含修复 |
| 开新 worktree 前 / 任务做完 | `bash .claude/tools/worktree-hygiene.sh --check`（> 30 先 `--apply`）；做完收掉自己的（铁律 4） |
| 清理 | `scripts/safe-worktree-remove.sh`（⛔ 不 `mklink /J` 共享 node_modules，见 concurrent-edit-safety） |
| GitHub 不可用 | 合进本地 `main` → detached worktree + `SKIP_GIT_CHECK=1` → 恢复后各分支走 PR |

关联: `concurrent-edit-safety.md`、`deploy-backend` skill、memory `feedback_concurrent_deploy_r2_path_collision`。

<!-- END worktree-and-main-only-deploy.md -->


---

<!-- BEGIN standing-authorization-and-visual-acceptance.md (原文) -->

# 常设授权：直接做完，不设人肉闸门（HARD RULE）

**最后更新**: 2026-10-16
**触发事故**: 2026-08-27 夜，Owner 已在同一轮里授权多次，我仍然逐步请示 ——
四项裁定问了两轮、非-F006 写入问了一轮、发布前又问了一轮。Owner：
「我授权你没问题你继续做，你却把所有东西……都问，说了好多遍了。」

---

## 1. 默认动作是「做」，不是「问」

Owner 给出方向之后，**范围内的一切直接执行到底**：读、写、改、删、配置、
生产写入（含非-F006 租户）、合并、发布。

⛔ **不要**为下面这些再开一次口：

| 不要问 | 直接做 |
|---|---|
| 「要不要我执行？」 | 执行 |
| 「你授权我写生产吗？」 | 写（Owner 点名了任务就是授权） |
| 「这个要不要合并/部署？」 | 验证绿就合并、就部署 |
| 「我可以删掉它吗？」 | 删（可逆的直接删；不可逆的见 §3） |
| 「要我继续吗？」 | 继续 |

📌 已有先例同源：`feedback_deploy_without_asking`（2026-08-21 Owner 裁定
「常规部署不要再问，验证绿就直接部署」）。本规则把它从「部署」推广到**全部动作**。

---

## 2. 🔴 「不问」≠「不验」——判据一条都不能省

这是本规则最容易被读错的一句。Owner 要去掉的是**请示**，不是**证据**。

省掉人肉闸门之后，**唯一**能保证做对的就是判据本身，所以判据反而更重要：

- 写入类：写前写后各计一次数 + **阴性对照**（不该动的东西必须一行没动）
- 部署类：`DEPLOY_EXIT=0` **且** `RELEASE_FINAL_STATUS` 恰好 1 次，并有覆盖本次组件的真实线上制品/健康回执；按 `execution-and-verification.md` 复用已覆盖证据，仅补缺失项与任务特有断言
- 闸/测试：新增之后去 CI 日志里 grep 类名，确认它**真的执行了**
- 任何「没发生」的读数，必须配一个「一定会发生」的读数（见
  [[measurement-and-wiring]] 硬约束 9）

⇒ **交付物是「做完了 + 我怎么证明它对」，不是「做完了 + 你看行不行」。**

---

## 3. 宣告，而不是请示

不可逆或影响面大的动作（删数据、改生产配置、force push、清库），
**不要问，但要在动手前用一句话说清楚你要做什么**，然后就做：

```
❌ 「我要删掉这 3 个 worktree，可以吗？」        <- 请示, 违规
✅ 「删掉本轮建的 3 个 worktree。」+ 直接删       <- 宣告, 正确
```

区别在于：宣告留下痕迹让 Owner 能在事后（或过程中）叫停，
而请示把决定权推回给了 Owner —— 那正是他不要的。

⚠️ 唯一仍然应当停下来的情况：**继续做下去会产生你无法证明其正确性的结果**
（例：拿不到租户证明、判据自相矛盾、阳性对照不通过）。
那不是「请求许可」，那是「仪器坏了」，停下来修仪器再继续。

---

## 4. 视觉验收优先

验收范围与证据复用遵守 `execution-and-verification.md`。涉及 UI 行为的完成条件保留下面的视觉证据；纯后端、规则或文档改动不自动扩展为整套 UI 验收。生产业务验收与写入以当前任务范围和对应 agent 的授权边界为准。

能用**界面跑一遍**证明的，就不要只报数据库读数。

数据库读数证明「数据长这样」，**证明不了「用户点得动」**。本仓已有多次教训：
92 条 web 路由用户走不到而 E2E 是绿的、报表接口一直返数据而工厂用户一个报表都打不开。

**做法**：用浏览器自动化真跑那条业务链，截图留痕，覆盖两侧：

- **正向**：该成功的路径成功了
- **阴性对照**：该被拦的路径**真的被拦了**（且是被**那道闸**拦的，看措辞/状态码）

相关 skill：`e2e-web-admin` / `smartbi-test-e2e` / `e2e-native`，
浏览器工具见 `claude-in-chrome`。

⚠️ 视觉验收**不替代**判据，是加在判据之上的一层：
截图证明「用户看得见」，判据证明「数字是对的」。两者都要。

---

## 5. 仍然生效的硬边界（这些不是「闸门」，是「正确性」）

去掉请示不等于去掉这些 —— 它们不需要 Owner 点头，是**做事的方式**：

- **worktree 隔离**、prod 只从 `main` 部署（见 [[worktree-and-main-only-deploy]]）
- **commit 范围保护**：`git commit -- <paths>` / `safe-commit.sh`
  （见 [[concurrent-edit-safety]]）
- **租户约束**：写生产数据前用实时登录响应证明租户，SQL 必须带
  `factory_id = ...` 约束；**跨租户命中一律停手**
  ⇒ 注意：这是**证明写对了地方**，不是**请求批准去写**
- ⛔ 不要 `mklink /J` 共享 `node_modules`

---

## 判据（本规则自身）

一轮工作结束时回头看：

```
我这一轮问了 Owner 几次「要不要 / 可不可以」？
```

**> 0 就是没照做**（澄清事实、二选一的产品口径除外 —— 那是要信息，不是要许可）。

⚠️ 与 [[measurement-and-wiring]] 顶部那条「载体」教训同源：
**本文件必须登记进 `CLAUDE.md` 的常驻清单**，否则它不会每 session 加载，
写了也等于没写。

<!-- END standing-authorization-and-visual-acceptance.md -->


---

<!-- BEGIN concurrent-edit-safety.md (原文) -->

# 并发编辑安全规范

**最后更新**: 2026-09-05（压缩；事故叙事存档在 `docs/rules-casebook/concurrent-edit-safety-cases.md`）

多个 Claude Code session / Cursor / VSCode 同时开同一仓库时会互相覆盖编辑，**commit 阶段也会被并发 session 的文件串到一起**（husky / lint-staged auto-stage）。两次原型事故：4/8 重做 deploy 脚本写了 150 行被并发 session 覆盖只剩 29 行；4/11 一个只想提交 2 个脚本的 commit 带进了别人的 5 个无关文件并推上 main。

## 规则

1. **里程碑 commit**：长任务按阶段 WIP commit，不要「全部做完再 commit」；关键 edit 后 `git diff --stat <file>` 对比预期，diff 远小于预期就是被覆盖了（`git reflog` 找回）。
2. **长任务用 worktree 物理隔离**（见 `worktree-and-main-only-deploy.md`）。
3. **改共享文件前 `git status --short <file>`**（`scripts/deploy/*.sh`、`.claude/rules|skills/*`、`pom.xml`、`package.json`）：有你不记得的 unstaged 改动 → 停手先确认。
4. **commit 只带列出的路径**（默认习惯，不只是怀疑并发时）：
   ```bash
   git commit -m "feat: my change" -- backend/foo.py backend/bar.py     # --only 模式, 别人 staged 的文件不进来
   ./scripts/safe-commit.sh "feat: my change" backend/foo.py backend/bar.py   # 前后各 verify 一次, 且按 PR 状态拒绝在已 squash-merge 的分支上提交
   ```
   ⚠️ `git commit -- <paths>` **不包含未列出的删除**：删了文件却没列出，HEAD 里旧文件还在而工作树没有，症状是本机绿 / CI 红。commit 前再 `git status --short` 看一眼 staged 区；已推上去的 scope creep 只能补 follow-up commit，未推的 `git reset --soft HEAD~1` 拆开。
5. **不要同时打开同一文件**：IDE auto-save 会覆盖外部修改；两个 chat 不并行 edit 同一文件，必须并行就 worktree 隔离或明确分工。
6. ⛔ **Subagent worktree 禁止 `mklink /J` 共享 `node_modules`**（也别用于 `target/`、`.next/`）：Windows 下 `git worktree remove --force` 会穿透 junction 把主仓 `node_modules` 掏空（2026-05-18 事故，阻断 prod deploy）。各自 `cd web-admin && npm install --prefer-offline --legacy-peer-deps`（cache 命中，接近 junction 的速度）。扫描 / 摘除：`pwsh -File scripts/check-worktree-junctions.ps1 [-Fix]`；移除 worktree 用 `./scripts/safe-worktree-remove.sh <path>` 或 `--all-merged`，摘链接只能非递归删除。maven `.m2` 天然跨 worktree 共享，不受影响。
7. ⛔ **干净工作树上 `git stash` 什么都不存，随后的 `pop` 弹出的是别人的 stash**（栈不是抽屉）。量基线用 worktree 或 `git checkout -q <sha> -- <文件>`；一定要 stash 就用 `git stash push -u -m "<tag>"`、记 SHA、`git stash apply <sha>`、事后按 tag 找到再 drop，并先 `git stash list | wc -l` 确认真的新建了一条。

## 速查

| 场景 | 方案 |
|---|---|
| 单 session 改多个文件 | 里程碑 commit（1） |
| 2+ chat 要改同一文件 | worktree 隔离（2） |
| 不确定是否并发 | `git status` 防御（3）+ 关闭其他 editor（5） |
| commit 阶段保护 scope | `git commit -- F1 F2` / `safe-commit.sh`（4） |
| subagent worktree 的 node_modules | `npm install --prefer-offline --legacy-peer-deps`（6） |
| 量基线 | worktree / checkout 指定文件，不用 stash（7） |

<!-- END concurrent-edit-safety.md -->


---

<!-- BEGIN windows-shell-and-paths.md (原文) -->

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

<!-- END windows-shell-and-paths.md -->


---

<!-- BEGIN execution-and-verification.md (原文) -->

# 执行、验证与交接（Claude Code / Codex 共用）

**最后更新**: 2026-10-16

本文件不扩大用户授权，不替代安全、租户、权限、迁移、JPA、发布及专用 UX 门禁。Claude-only organizer 不加载或镜像到 Codex。

## 执行

- 默认 DIRECT：当前执行者连续完成定位、实现、目标验证和已授权交付。先明确目标、scope、完成条件和下一次必要验证，小任务几句话即可。
- 仅为实际耗时收益、输出隔离或必要独立证据委派；两个独立小改动不自动并行，不固定走侦察→分析→执行→审查流水线。
- 普通改动自查 diff 并过适用检查，不固定增加 Reviewer。权限/安全、迁移、生产、金额/库存一致性、跨模块契约保留独立终审，由协调者核对并收尾。审查绑定 reviewed diff/HEAD/scope；后续源码、测试、迁移或权限契约改动影响结论时补审，仅未变化且覆盖本次风险的审查可复用。
- 尊重用户当前模型/effort 和实际工具能力；代理配置仅是默认，不设例行切换或型号资格门禁。

## 验证

- 开发中跑目标检查；同批兼容改动完成、源码/测试冻结后，做一次必要最终验证/构建。必需 CI、发布来源/制品门禁仍执行。
- 回执记受测源码/测试版本或哈希、命令/选择器、退出码、执行数（适用时）、日志；依赖/配置/环境/数据库状态影响结论时也记录。前提仍匹配、覆盖要求且成功才复用。
- 只读审查、仅文档/台账/PR 描述变化不重跑产品测试；新源码/测试/相关依赖或环境变化、失败、具体新风险才复验受影响项。缺失、跳过、零执行数、工具失败、未验证不能计为通过，不降断言/改期望或清失败证据。
- 行为修复有能暴露缺陷的回归断言；关键防护、源码闸及任务指定的变异验证保留并证明变异生效。文字、低风险样式、规则维护做对应检查，不机械补完整业务测试/变异链。
- Repository 查询、JPQL/HQL、Entity 字段/枚举映射变更保留真实 JPA Context 和适用 CI 启动门禁；Mockito、编译、跳过测试构建、健康检查均不能替代。

## 发布与验收

- 规范发布入口、clean exact-main、制品/测试 manifest、租户范围、发布互斥、蓝绿观察/回滚保留。服务成功须有正确组件的完整真实线上回执，退出码本身不足。
- 同一轮回执已覆盖的 upstream、运行服务、健康、制品/四方哈希不重测；组件未覆盖、回执缺失/失败或状态改变时补缺失/失效项，任务特有断言另验。
- 服务、业务 API/数据库、UI/真机分别记录。涉及用户操作的完成条件保留真实入口、截图、必要阴阳性对照；后端/规则改动不自动扩展整套 UI 验收。生产业务验收/写入限当前任务授权，只读 harness 零写入。

## 规划与交接

- 每切片一份任务说明、一条在飞记录（确需登记时）、一份证据索引；已有设计/规则/日志引用即可。DIRECT 不另写分发卡或登记形式子任务；必要 scope/worktree/派工/发布登记保留。
- 代码 worktree 先核对真实路径、分支/HEAD、ACTIVE、唯一写 Owner，复用合格的本任务隔离副本；只读/调研不新建。
- 真正委派/跨会话才给任务包：ID、Owner、Base SHA、worktree/分支/HEAD、目标/完成条件、允许/禁止范围、必要规则/入口、验收命令/已有证据、停止条件/下一动作。补接收方不可访问的关键约束，不重写设计。
- 回执补实际文件、验证命令/结果/证据、commit/PR、阻塞/下一动作。协调者仅维护本批，ACTIVE 每条 ≤ 300 字，完成后归档释放 scope；不因日期旧而清查无关历史。
- 仅完成、阻塞、范围/契约变化或需裁决时通知；等待 CI/PR 用一个有截止条件的监视，不叠加逐线追问/固定轮询，状态未变不反复汇报。
- 跨会话保留唯一写 Owner/交接登记；构建、测试、合并、迁移、部署/回滚先到安全停止点。规则须在新会话验证加载，不声称运行中会话已刷新。
- 已授权实现、必要验证和交付完成即报告，未完成/未验证单列，不自行扩阶段或打磨已通过切片。

<!-- END execution-and-verification.md -->
