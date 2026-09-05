# CLAUDE.md / AGENTS.md / Rules / Skills 清理修订方案

审阅日期 2026-09-05。范围：`~/.claude`（全局）+ `my-prototype-logistics` 项目（`CLAUDE.md`、`AGENTS.md`、`.claude/rules`、`.claude/skills`、`.claude/agents`、`.agents/skills`、`.codex/rules`）。

原则（Anthropic 对 Fable 5.1 的说明）：为前代模型写的逐步指令、脚本化措辞、大量"必须/禁止"会压低输出质量。清理方向是**保留意图、边界、验证钩子；删掉步骤级 micromanage、历史叙事、已失效内容**。真正的安全护栏（prod 部署口令、F006 写入范围、凭证、迁移/RLS 红线、只从 main 部署、锁文件、stash）全部保留。

状态：Phase 1 已在 PR #595 落地（2026-09-05）。Phase 2/3 待做，决策项见第 8 节。

---

## 0. 全局体量与常驻成本（先看这个）

每个 session 无条件加载的内容：

| 文件 | 大小 | 说明 |
|---|---|---|
| 项目 `CLAUDE.md` | 4.2 KB | 合理 |
| `rules/measurement-and-wiring.md` | **66.2 KB / 1265 行** | 常驻，占常驻总量 66% |
| `rules/worktree-and-main-only-deploy.md` | 15.9 KB | 常驻 |
| `rules/concurrent-edit-safety.md` | 11.3 KB | 常驻 |
| `rules/CREDENTIAL-MANAGEMENT.md` | 2.1 KB | 常驻 |
| superpowers SessionStart hook 注入 | ~3 KB | 全局插件 |
| **合计** | **≈ 100 KB** | 中英混排约 40–55K token，每轮都在 |

目标：常驻降到 ≈ 30 KB（削减 70%），其余按需加载。方法见 R-1 / R-2 / R-3。

按路径加载的 rule 里最重的是 `python-java-port.md`（37.7 KB），而它的 `paths:` 覆盖整个 `backend/java/**` 和 `backend/python/**`，任何后端改动都会把它拉进来（见 R-8）。

---

## 1. 全局层（`~/.claude`）

### G-1 superpowers 插件 + SessionStart hook 与项目 AGENTS.md 直接冲突

- 文件：`~/.claude/settings.json`（`"superpowers@claude-plugins-official": true`）；注入文本见 session 开头 `using-superpowers`
- 原文（hook 注入）：「If you think there is even a 1% chance a skill might apply … you ABSOLUTELY MUST invoke the skill … BEFORE any response or action — including clarifying questions」「Before entering plan mode: … invoke the brainstorming skill first」
- 原文（AGENTS.md §Superpowers 禁用规则）：「本仓库禁止调用、加载、引用或遵循任何 `superpowers:*` skill … 冲突时以本规则为准」
- 影响：
  - 这是整套配置里**最典型的 micromanage 文本**：强制在回答任何问题前先跑 skill，逼模型在简单问题上绕一圈 brainstorming/plan；对 Fable 5.1 是净负担。
  - 同一个仓库里 Claude 被要求"必须用 superpowers"，Codex 被要求"禁止 superpowers"，而 `ux-flow`、`design/SKILL.md`、`depth-first-e2e` 又都用 superpowers 的词汇（`propose approaches`、`writing-plans`、`superpowers:brainstorming 先行`、`superpowers:code-reviewer`）。三方互相打架。
  - 本 session 就是例子：system prompt 说"不要用 subagent 除非被要求"，hook 说"1% 可能就必须 invoke skill"。
- 建议（需要你拍板，二选一）：
  - **A（推荐）**：在项目 `.claude/settings.json` 的 `enabledPlugins` 里加 `"superpowers@claude-plugins-official": false`，本仓库彻底关掉；全局保留给别的项目。然后把 CLAUDE.md / ux-flow / design / depth-first-e2e 里的 superpowers 词汇改成中性说法（见 P-3、S-8）。
  - **B**：全局关掉，同时删掉 AGENTS.md 那 5 条禁用规则（它们在没有插件时就是死文字）。
  - 无论选哪个，`~/.claude/skills-whitelist.md` 里"13 个 superpowers 核心 skill"那节都要同步（见 G-4）。

### G-2 PreToolUse 派发闸 hook：每次 Agent 调用都注入一段 300 字的路由说教

- 文件：`~/.claude/settings.json` → `hooks.PreToolUse[matcher=Agent|SendMessage]`
- 原文：「派发闸 — 先量并发形状再派: 独立工作面 =1 → inline 自己做, 不要派; >=2 → 一次性 fan out … 若形状=1 仍决定派, 必须先说明理由。同时先定 model 与 effort: … effort 派发时【设不了】…」
- 影响：
  - 内容与 `organizer` skill「派前先量并发形状」一节逐字重叠，等于每次派 agent 都把同一段规则再读一遍。
  - "必须先说明理由"是一个人为停顿点：模型要在派之前写一段自我辩护。
  - "effort 派发时设不了" 是 harness 细节，且已被 `.claude/agents/*.md` 的 `effort:` frontmatter 解决。
- 建议：删掉这条 hook，或压成一句：「派 subagent 前先数独立工作面：=1 inline，≥2 一次 fan out。」（≤40 字）。model/effort 由 `.claude/agents/executor|probe|recon.md` 决定，不在 hook 里重复。

### G-3 全局 `CLAUDE.md`（goal setting prompt 规则）

- 文件：`~/.claude/CLAUDE.md`
- 评价：触发窄（只在用户要 goal prompt 时）、判据可机械验证（`wc -m` + grep markdown 标记），是写得对的规则。保留。
- 微调建议：把「⚠️ 这条只管 prompt 正文本身」和「⛔ 不要发一版超长的然后说太长了我再改」两句合并进判据行即可，可省 6 行。非必需。

### G-4 `~/.claude/skills-whitelist.md` 已与 settings.json 脱节

- 文件：`~/.claude/skills-whitelist.md`（最后更新 2026-06-07）
- 原文：「Superpowers（14 个）… `superpowers:organizer`」「PR & Code Review（5 个）pr-review-toolkit:*」「Feature & Debug（3 个）feature-dev:*」「新 session 启动 → 自动加载此白名单（`.claude/settings.json` 已配置）」「e2e-miniprogram # 小程序测试（项目无小程序）」
- 事实：`settings.json` 里 `pr-review-toolkit`、`feature-dev`、`code-review` 全是 `false`；`superpowers:organizer` 不存在（organizer 是项目 skill）；`MallCenter/mall_miniprogram` 存在，项目有小程序。
- 影响：文件不会被自动加载，直接危害小；但它自称"单一真值"，会误导下一次维护（形态 D）。
- 建议：删除，或压成 10 行：「skill 启停以 `~/.claude/settings.json` 的 `enabledPlugins` 为准；项目 skill 在 `.claude/skills/`；本文件不再维护清单。」

### G-5 全局 `design/SKILL.md`（路由枢纽，15 KB）

- 文件：`~/.claude/skills/design/SKILL.md`
- 问题点：
  1. 原文「| 不确定 | — | 问用户："你要做业务页面还是创意展示页？" |」—— 停顿点。路径/组件名几乎总能推断；推断不出时按业务页面处理并说明假设即可。
  2. 原文「需求还没定、要做的是新功能 | `superpowers:brainstorming` 先行」—— 与 G-1 冲突。
  3. 「v2.1 (2026-07-29) 变更 … v2 (2026-07-28) 变更 …」两段 changelog 放在正文开头（10 行），对执行无用。
  4. 「Route 2 fallback: 创意页设计原则（委派不可用时的最低标准）」—— 与 `taste`/`impeccable` 重复，且"委派不可用"在本机不成立。
  5. `description` 长达 400 字，每个 session 都在 skill 列表里占位。
- 建议：删 1、2、3、4（约 -35%）；description 压到 2 句：「设计工作路由入口：业务页面按三端规范；创意页/打磨/审查/动效/品牌分别路由到 taste/impeccable/gsap/promax。用户说做页面、设计 UI、美化、landing page、加动画、原型时触发。」
- 保留：意图路由表、外部委派地图、边界铁律 1–6、Route 0、Route A 槽位化写法（这是真正非显然的知识）。

### G-6 第三方设计 skills 的触发面互相重叠

- 文件：`impeccable`、`taste`、`ui-ux-pro-max`、`web-design-guidelines`、`frontend-design`（.agents）、`emil-design-eng`、`apple-design`
- 现象：`impeccable` 的 description 覆盖「design, redesign, shape, critique, audit, polish, … or otherwise improve a frontend interface」，与 `design` 路由、`taste`、`web-design-guidelines`、`ui-ux-pro-max` 的触发词几乎全集重叠。一句「美化一下这个页面」会同时命中 4–5 个 skill。
- 影响：触发过宽；模型要在多个 skill 间自行仲裁，而 `design/SKILL.md` 明明已经是声明的唯一入口。
- 建议：不改第三方 body（它们与 Codex 侧同步，`taste` 87 KB 的 pre-flight 清单是它自己的风格，不动）。只做两件事：
  1. 在 `design/SKILL.md` 顶部加一句「设计类请求只从本 skill 进入，其余设计 skill 由本 skill 路由，不自触发」。
  2. 若 Claude Code 版本支持 `disable-model-invocation: true`（`review-animations` 已在用），给 `taste`、`impeccable`、`ui-ux-pro-max`、`emil-design-eng`、`apple-design` 加上，由 design 路由显式调用。这是唯一能真正收窄触发面的机制。
- 与 Cretas 无关的全局 skill（`bp-creator`、`notebooklm`、`brand`、`design-system`、`promax-design`）：每个 description 在列表里占 ~100 token；不强求删，但可考虑移到 `_disabled/`。

### G-7 全局 `settings.json` 的 `"model": "sonnet"`

- 事实：全局默认模型是 sonnet，但本 session 跑的是 Fable 5.1；`organizer` skill 全篇假设"本体是 Opus"。
- 建议：不改设置，但 `organizer`/`multi-model-dispatch` 里所有"你是 Opus"的措辞改成模型无关（见 S-1）。

---

## 2. 项目 `CLAUDE.md`

总体：4 KB，结构对。只有 4 处要动。

### P-1 死链

- 原文：「- [QUICK_START.md](./QUICK_START.md)」
- 事实：文件不存在。
- 建议：删这一行。

### P-2 端口表与蓝绿槽位冲突

- 原文：「| Cretas 后端 (Java) | 10010 | `http://47.100.235.168:10010` |」
- 事实：AGENTS.md、server-operations、deploy-backend 都写明 prod Java 是 `10010/10020` 蓝绿交替，且 2026-08-19 有实测误判记录（照 10010 curl 读到 000 差点报"生产挂了"）。CLAUDE.md 是每 session 必读的那份，却是唯一写死 10010 的。
- 建议：改成「10010 / 10020 蓝绿交替；哪槽 active 只看 139 网关 `_upstream_cretas.conf`」。这条在 AGENTS.md 出现了 4 次、server-operations 2 次、deploy-backend 2 次，其余各处可删到只留一处。

### P-3 UX Flow Gate 用了 superpowers 词汇

- 原文：「**brainstorming 阶段必须在 propose approaches 之前先 invoke `ux-flow` skill** … 缺失不进入 writing-plans」
- 影响：若按 G-1 关掉 superpowers，"propose approaches / writing-plans" 就没有对应物；门本身是对的（有客户原话支撑）。
- 建议：改成「设计或改动这些屏幕之前，先用 `ux-flow` 产出「UX Flow Analysis」章节；没有这一节不进入实现。」

### P-4 常驻 rules 清单需要随 R-1/R-2/R-3 更新

- 原文：「**常驻**（每 session 加载）：… `measurement-and-wiring.md` - 量对东西 …」
- 建议：拆分后清单改为指向精简版；案例库放 `docs/` 不进常驻。另外补一句「`.codex/rules/` 是 Codex 侧副本，Claude 以 `.claude/rules/` 为准」（见 X-1）。

---

## 3. `AGENTS.md`（Codex 主文件，35.7 KB）

这份是给 Codex 的，但它是仓库里 micromanage 密度最高的文件（"必须/禁止/不得"出现 49 次），且部分内容 Claude 侧 skill 直接引用。

### A-1 「GitHub 封控期间离线发布（临时最高优先级）」仍挂在文件顶部

- 位置：第 73–84 行
- 原文：「#### GitHub 封控期间的离线合入与发布（临时最高优先级，2026-08-08 起）… 本节与下方所有"必须先推 origin/main"条款冲突时，仅在 Steve 已确认的 GitHub 封控窗口内以本节为准」
- 事实：8 月中下旬起 PR #2663/#2666/#2668… 已正常合并，GitHub 已恢复；同一套离线协议在 `worktree-and-main-only-deploy.md §GitHub 不可用时` 已有更完整的版本。
- 影响：一条"临时最高优先级"长期放在最前面，每次读都要先判断它是否生效；两份副本还会漂。
- 建议：整节删除，替换为一句「GitHub 不可用时按 `worktree-and-main-only-deploy.md` 的离线协议执行」。

### A-2 §发布与 E2E 去重复 第 1/6/7/10/11 条：把发布脚本的内部实现写成了代理规则

- 位置：第 265–276 行，每条 500–900 字
- 原文（节选第 1 条）：「manifest 格式合法且 `success=true`、记录的 `target_tests` 与本次选择器完全一致、记录的 backend tree 等于当前 HEAD backend tree、缓存 JAR 存在且通过归档完整性与 SHA-256 校验时，`release-jar-manifest.sh build` 直接复用并运行零次 Maven；任一条件不满足才执行一次 clean package … 部署阶段的 manifest/制品复用还必须证明记录的 build commit 可由 Git 解析，且该 commit 的后端 tree、manifest 记录的后端 tree、当前 origin/main 后端 tree 三者一致 …」
- 影响：这些是 `release-cretas.sh` 已经在强制执行的校验逻辑。代理不需要背诵脚本内部的分支条件才能正确调用它；背诵反而让模型去"手工复核"脚本已做的事（AGENTS.md 第 5 条自己也说"回执完整时不得再手工重复"）。这 5 条合计约 4 KB，是文件里最该删的部分。
- 建议：压成一段（≤15 行）：
  - 入口：build / prewarm / deploy 三条命令（已在 §部署到服务器 给出，不重复）
  - 判据：`DEPLOY_EXIT=0` 且 `RELEASE_FINAL_STATUS` 恰好 1 次；`build="reused"`/`build_count=0` 是正常成功
  - 三个不要：不要手工拼单组件脚本当全栈发布；不要反射性 `rm` 锁文件（先 `kill -0` PID）；不要为等 CI 制品原地空等（用 `prewarm --wait`）
  - 脚本内部校验逻辑移到 `scripts/deploy/RELEASE-ARTIFACT-TRANSPORT.md` 或脚本头注释。

### A-3 子代理 WIP 上限与"临时扩容登记"

- 原文：「**WIP 上限** - 同一仓库默认最多同时运行 2 个代码执行子代理和 1 个只读/测试子代理」「**临时扩容例外** - … 可临时将上限提高到最多 4 个 … 扩容必须在 `docs/dispatch/ACTIVE.md` 记录理由、参与任务与释放条件」
- 影响：数字上限是为前代模型的并发失控设的；与 `organizer`「≥2 独立块一次 fan out 全部」方向相反；"扩容前先写台账"是额外停顿。真正防撞的是 scope 锁 disjoint，不是数字。
- 建议：删数字上限和扩容登记。保留「派前查 scope 锁，文件重叠不并行」+「结构化回执」。

### A-4 派工前置条件对小改动过重

- 原文：「每个代码任务必须先登记唯一任务 ID、`Base SHA`、允许修改的文件/目录 scope 锁、Owner 与验收命令；没有 scope 锁不得启动代码子代理」
- 影响：一个 2 文件的修复用 `executor` 也要先写台账；这是"每次都要过的仪式"。
- 建议：改为「**并行派发**（≥2 个代码子代理同时在飞）时才要求 scope 锁登记；单线程执行只需在回执里报 base SHA 与改动文件」。

### A-5 F006 生产写入特例三处逐字重复

- 位置：AGENTS.md 第 13 条（900 字）、`.agents/skills/e2e-web-admin/SKILL.md §F006 Production-Write Exception`、`.agents/skills/project-playwright-e2e/SKILL.md §F006 Production-Write Exception`
- 影响：同一段 900 字的规则三份，已经开始各自措辞不同（形态 D）。
- 建议：`e2e-web-admin` 为唯一定义；AGENTS.md 与 project-playwright-e2e 各留一行指向。规则内容本身**不削弱**（这是护栏）。

### A-6 保留并轻微压缩的部分

- 「Optional Model and Effort Advice」（第 3–7 行）：写得对，是反 micromanage 的范例，原样保留。
- §执行效率与验证 第 1–4 条（避免过度审计 / 同批一次完成 / 验证分层 / 例外要说明）：保留；第 3 条那句 80 字长句改成两句。
- 第 7 条（闸的命名 `*ContractTest` / `*StartupGuardTest`）与第 8 条（别拿 CI 绿当证据）：真正的验证钩子，保留。
- 第 14 条（Goal 授权，不重复确认）：保留，它正是在解决"不必要停下来确认"。
- 第 15 条（锁互斥判读）：保留，压到 3 行。

### A-7 蓝绿槽位规则出现 4 次

- 位置：第 30、135、266、304 行
- 建议：§Server Operations 保留一次，其余删。

预计 AGENTS.md 从 35.7 KB 压到 ≈ 14 KB。

---

## 4. `.claude/rules/`

### R-1 `measurement-and-wiring.md`：66 KB 常驻，需要拆成"硬约束"+"案例库"

- 文件：`.claude/rules/measurement-and-wiring.md`（1265 行，无 `paths:` ⇒ 每 session 加载）
- 结构现状：硬约束 1–9（约 250 行）+ 形态 A–E 及 A′…A¹⁴、B′…B⁷、C′…C⁸、D′、E′ 共 30 余个带实测表格的案例 + 三条"载体教训"自述。
- 影响：
  - 它一个文件就是常驻上下文的 2/3。Fable 5.1 不需要 40 个 worked example 才能理解"量对东西 / 接上没有 / 变异打在被守的行为上"。
  - 文件自己写着「读到形态会点头，读到硬约束才会动手」——它已经承认真正起约束作用的是硬约束那一章。
  - 部分硬约束是特定工具风格的细节（硬约束 5「变异脚本一律文本模式 + `newline=''`」、硬约束 6 的 `chr(92)` 写法），对当前模型是噪音。
- 建议：
  1. **常驻版** `measurement-and-wiring.md` ≤ 220 行：
     - 顶部「改这份文件之前先读这一句」保留（载体/自指两条），压到 6 行
     - 硬约束 1–9 各保留：一句规则 + 一句为什么 + 一个 ≤5 行的代码判据。删掉每条下面的"为什么是硬约束：同一天栽了四次…"叙事。硬约束 5、6 合并成一条「改写脚本不经过 shell、不用 bytes 字面量」。
     - 形态 A–E 各保留一段 3–5 行的判据（「我想知道 X / 我实际在量 Y」、「产出端有 ≠ 消费端收得到」、「变异先证明打进去了」、「两份必漂，钉一致性」、「闸窄而可信」）。
     - 「改 `.claude/` 之前先 `git rev-list --count HEAD..origin/main`」保留。
  2. **案例库** 移到 `docs/rules-casebook/measurement-and-wiring-cases.md`（不自动加载），常驻版末尾一行链接。所有 A′–A¹⁴、B′–B⁷、C′–C⁸、D′、E′ 的实测表格整体搬过去，一字不删。
  3. `.claude/agents/probe.md` / `recon.md` / `executor.md` 已经各自摘了它们需要的 3–5 条，这个拆分不影响 agent 定义。
- 风险提示：这个文件的案例是用事故换来的，拆分是"搬家"不是"删除"。但常驻 66 KB 对每一轮都是税，且这份文件在 8-15 就被证明"加载的是过期副本"——体积越大越难保证加载对。

### R-2 `worktree-and-main-only-deploy.md`：16 KB，40% 是离线期协议与事故叙事

- 保留：三条铁律、§2b 合入通道双轨表（含 `--task-id` 那段，它是真判据）、速查表。
- 压缩：
  - §2b-1「docs 批次的确切定义」那张表 + 「判据：直接跑 `--dry-run`」—— 把表删掉只留 dry-run 那句（脚本说了算，表只是让你知道会被拒，正文自己也这么说）。
  - 「⚠️ 2026-08-07 订正 … `jpa-gate-pr.yml` 已删」「看 CI 结论的两个坑」—— 移到案例库。
  - §GitHub 不可用时（约 90 行）—— 保留协议骨架（本地 main 汇合、detached worktree 部署、恢复后现算清单 3 条），删除「🔴 这个检查要单独跑一次…我就是一把梭」「两点式 diff 假警报」等叙事到案例库。
  - 「验证边缘情况」保留。
- 目标 ≈ 6 KB。顺带：`.codex/rules/` 的同名文件已经是 6 KB 版本，可对照。

### R-3 `concurrent-edit-safety.md`：11 KB，规则 1 过细，规则 7/8 叙事长

- 原文（规则 1）：「任何连续 3+ Edit 的同一文件, 或 15-30 分钟工作量, **立即 commit**」
- 影响：按 Edit 次数计数是 micromanage；意图是"里程碑 commit"。
- 建议：
  - 规则 1 改为「长任务按里程碑 WIP commit，不攒到最后」。
  - 规则 5b（`git commit -- F1 F2` / `safe-commit.sh`）保留，它是真机制。
  - 规则 7（junction）压到 6 行：禁 `mklink /J`、原因一句、用 `npm install --prefer-offline`、清理用 `safe-worktree-remove.sh`。「2026-08-10 首次全盘扫描实测结果」段落删。
  - 规则 8（stash）压到 3 行；harness 的 worktree 提示已经每 session 讲一遍同样的事。
  - 「优先级组合」表保留。
- 目标 ≈ 4 KB。

### R-4 `playwright-headed-mode.md`：与 E2E canonical 冲突 + `paths` 过宽

- 文件：`.claude/rules/playwright-headed-mode.md`
- 冲突：
  - 原文：「任何 Cretas web-admin 或 RN Expo Web Playwright spec 跑 prod / staging UI E2E 时, **MUST** `headless: false`」「PR review 时如发现 UI E2E spec headless: true → 阻 merge」
  - 而 canonical 的 `.agents/skills/e2e-web-admin/SKILL.md` 与 `project-playwright-e2e/SKILL.md` 示例代码都是 `chromium.launch({ headless: true })`，生产只读验收走 `scripts/e2e/production-readonly/`（CI drift gate 里跑，无头）。两边都是"权威"。
- 触发过宽：`paths: "**/*.spec.ts"` 会命中所有 Vitest/Jest 单测（如 `AppSidebar.reactivity.spec.ts`），改一个组件单测就把 Playwright 窗口纪律拉进上下文。
- micromanage：「多 chat 共存纪律」6 条是给人的操作提示（别 alt-tab、别让电脑 sleep）；「Audit doc 必含 verification block」要求每次粘贴 10 行模板。
- 建议：
  - 意图收窄为：「面向客户的截图/演示/人工审阅用 headed + `--lang=zh-CN` + 1920×1080；自动化验收、CI、production-readonly harness 可 headless。」
  - 删多 chat 纪律、删 verification block 模板、删「Steve 屏占用」权衡表。配置范例保留（它是可复制的资产）。
  - `paths` 改为 `tests/e2e*/**`、`scripts/e2e/**`、`**/playwright.config.*`。
- 目标 ≈ 1.5 KB。

### R-5 `python-java-port.md`：37 KB，触发面覆盖整个后端

- 文件：`.claude/rules/python-java-port.md`
- 原文（frontmatter）：`paths: backend/python/** , backend/java/**, tests/fixtures/java-smartbi-golden/**, scripts/record-java-golden.sh`
- 影响：这份规则只关乎 SmartBI Phase 2A 的 Java→Python parity port（2026-05），但任何 Java Controller / Python 路由改动都会加载 37 KB。12 条规则每条都带「Why（背景 + 修复历史）」「Audit 来源」「跟 Rule N 的关系」，正文里还有 PR 号、commit hash、chat 编号。
- 建议：
  - `paths` 收窄到 `backend/python/smartbi_compat/**`、`tests/fixtures/java-smartbi-golden/**`、`scripts/record-java-golden.sh`。
  - 每条规则只留「反 pattern / 正 pattern / 何时适用」三段；删「Why 修复历史」「Audit 来源」「跟 Rule N 的关系」「Audit 历史」表、「Phase 2A dict-eq gate」那 60 行（压成一段：Phase 2A 用 dict-eq，Pattern A/A2 是预期差异）。
  - 「工具 + 配置 reference」「何时新增 rule」保留。
- 目标 ≈ 14 KB，且只在真正做 parity port 时加载。

### R-6 `fool-proof-design.md`：内容对，尾部三节过期

- 保留：核心原则、5 大规则、4 位一体、适用场景速查、客户原话。
- 删：「Cretas 现状 Audit」（Sprint 3/4 状态）、「Sprint 4 W2 + Wave 1 9 chat dispatch 必须按本规范设计」、「何时 audit 本规范」（与 4 位一体重复）。
- 「PR review 时如发现违反, 阻塞 merge 直到修」改成「PR review 按本规范检查」——阻塞是人的决定。

### R-7 小文件：`coding-conventions.md`、`python-services-architecture.md`、`database-entity-sync.md`、`ai-intent-tool-skill-architecture.md`、`CREDENTIAL-MANAGEMENT.md`

- `coding-conventions.md`（1.6 KB，四条规则合一）：这是全仓写得最好的一份，作为其他 rule 的压缩样板。不动。
- `ai-intent-tool-skill-architecture.md`：「337+ Tool，16 内置 Skill」数字会过期，删数字。其余不动。
- `database-entity-sync.md`：「修复历史」三行删；「推荐配置」保留。可选：把 memory 里已确认的「原生 SQL 无类型参数第 5 次执行必炸（pgjdbc prepareThreshold）」加一行到「could not determine data type」旁边，它和现有条目是同族。
- `python-services-architecture.md`：不动。
- `CREDENTIAL-MANAGEMENT.md`：「见 `.claude/skills/server-operations/db-credentials.md` (gitignored)」重复 4 次，表格里合并成表头一句。不动其余。

---

## 5. `.claude/skills/`

### S-1 `organizer`（28 KB）+ `multi-model-dispatch`（30 KB）：Fable 作废内容、重复、停顿点、模型硬编码

这两份是问题最集中的地方。

**(a) Fable 5 内容"作废但不删"**
- 原文（multi-model-dispatch 顶部横幅）：「Fable 5 退出路由，以后不再考虑 … 共 33 处提及，**没有逐处删除**—— 逐处删会把这份文件的历史推理也一起删掉」
- 原文（organizer 引导层，横幅之后仍在）：「## fable 提醒（model 轴破玻璃顶层 — 别过省）- fable = 2x Opus，稀缺，但**稀缺 ≠ 攒着不用**。earned 车道清楚命中就**主动派**」
- 事实：organizer 29 处、multi-model-dispatch 38 处 Fable 提及；organizer 的 description 仍写「Fable 5 门槛」；两文件 60% 篇幅在讲一个已被裁定作废的三档阶梯。
- 影响：模型要在"读到请跳过"和"主动派 fable 别过省"之间自己判断哪个生效；而现在执行者本身可能就是 Fable 5.1。
- 建议：**全部删除**。git 历史保存推理；横幅本身留 3 行（「模型轴两档：Sonnet → Opus。卡住换手段不换模型。earned-not-predicted 保留：默认 Sonnet」）。

**(b) 两份文件互相重复**
- 「两条派发通道」「红线四类表」「隔离铁律」「审查分层」「速查判断树」在两份里各出现一次，措辞已经不同。
- 建议：
  - `organizer`（≤120 行）：角色定义（模型无关）、派前并发形状表、台账协议、brief-vs-do、红线（指向 dispatch）。
  - `multi-model-dispatch`（≤150 行）：路由启发式表、effort/orchestration 表、分发卡 + brief 卡模板、红线四类表、隔离铁律 4 条。
  - 删两份里所有「**最后更新**/触发：2026-06-10 晚 v2 Steve 拍板…」的版本叙事段（合计约 40 行）。

**(c) 停顿点**
- 原文（organizer 触发后立即做）：「3. 向用户确认角色 + 汇报台账状态」「## 引导完成后向用户汇报 - "✅ Organizer 已就位（Opus / high effort）"」
- 原文（effort 提醒）：「你不能自己中途升 effort。遇到 gnarly 判断，提醒 Steve 在那条消息加 `ultrathink`」
- 原文（multi-model-dispatch）：「**触发**: 我(Claude/Opus)每次出**计划 / 设计**, 末尾**默认**追加一段分发卡。」
- 影响：第一条是仪式性确认；第二条是过期的 harness 细节（现在有 effort 参数与 agents frontmatter）；第三条让每个单人计划末尾都长出一张给 Codex/Composer 的分发卡。
- 建议：改成「触发后读台账、报状态、直接接任务」；删 effort 提醒；分发卡改为「计划含 ≥2 个可并行块或用户要派活时才产」。

**(d) 模型/价格硬编码**
- 原文：「Sonnet 5 … $3/$15 且 8-31 前引入价 $2/$10」「organizer 本体永远是 Opus 4.8 + high」「GPT-5.5·Codex」「Composer 2.5」
- 建议：价格全删；模型名收进一张表，正文用"执行层 / 判断层"。「你是 Opus Organizer」改成「你是 Organizer」。

**(e) 断链**
- 原文：`[multi-model-dispatch.md](./multi-model-dispatch.md)`、`.claude/rules/organizer-protocol.md`、`.claude/rules/parallel-work-analysis.md`
- 事实：三个路径都不存在（已并入 skill）。改为 skill 名引用。

**(f) 台账依赖**
- 原文：「2. 读 `docs/dispatch/ACTIVE.md`（当前在飞任务 + scope 锁）」；AGENTS.md：「`origin/main` 和精简 ACTIVE 是当前状态真值」
- 事实：ACTIVE.md 最后提交 2026-08-23（13 天前）；单条任务长达 8000 字（`REST-AI-READ-PATH` 一条就是一整屏）。"精简"已不成立。
- 建议（组织决策，不在本次文档改动里）：organizer 只在派活时读台账；台账条目限制 300 字，历史进 archive。写进 organizer 一句「台账超过 7 天未更新或单条超 300 字时先整理再派活」。

### S-2 `server-operations`（24 KB）：下线 7 周的 test 环境仍占 1/3 篇幅

- 原文：「> ⚠️ **2026-07-13 更新: 47 上的 test 环境已下线** … **下方"双环境"「test」相关内容暂留作历史参考**」，随后仍有 §双环境、§启动命令 `restart.sh test`、§日志 `cretas-test.log`、§systemd 表 `cretas-backend-test`、§测试环境自动重启验证、§双环境部署最佳实践（「先部 test 再部 prod」）。
- 影响：12 处 10011/8084 引用；一个新 session 读到「推荐工作流：先部 test」会去做一件不存在的事。deploy-backend 里同样有 10 处。
- 建议：
  - 删所有 test 环境段落，只留一行「test 环境 2026-07-13 下线；Mac mini 测试环境见 memory `project_macmini_test_env_is_live_with_f006`」。
  - 「2026-08-19 快照（磁盘普遍偏紧）」表删——它上一节自己写着"规格是快照会过期，用量当场重测"。
  - R2/OSS/GitHub 镜像叙事（注意事项 7/7b/12，合计约 1.5 KB）压成两行：主通道 rsync，CI 制品走 `Publish-GitHubArtifactViaLightsailOss.ps1`，细节见 `RELEASE-ARTIFACT-TRANSPORT.md`。
  - 保留：全局服务器地图（含"5/6 在跑 VPN 不是空闲机"）、蓝绿读 upstream、内容分布表、目录结构、systemd 表、Smartbi migration HARD RULE、凭证位置。
- 目标 ≈ 10 KB。同时把 `aliyun-operations` 并进来（见 S-9）。

### S-3 `deploy-backend`（22 KB）：一个不必要的 AskUserQuestion + 大段过期流程

- 停顿点：
  - 原文（Phase 0）：「如果判断不了，用 AskUserQuestion 确认：Java 后端 / Python 服务 / Web 前端 / 全部」
  - 同一表已写「仅说"部署" | 默认 Java 后端」；memory 里有 Steve 8-21 裁定「常规部署不要再问，验证绿就直接部署」。而且 `release-cretas.sh --phase deploy` 本来就自动检测范围。
  - 建议：删这句，改为「范围由 `release-cretas.sh` 自动检测；只动 Python 时走 `deploy-smartbi-python.sh`」。
- 过期/冲突：
  - Phase 1 方式 B「服务器自编译：git diff | ssh … git apply … mvn package … cp jar … systemctl restart」—— 与 AGENTS.md「旧 `--git` 服务器拉分支现场编译路径已删除；Java/Web 生产发布必须使用可信制品流程」直接冲突，且绕过蓝绿。删。
  - Phase 4「仅重启：`bash restart.sh all`（生产+测试）」、Phase 5 健康检查 curl `10010`/`10011`/`8084`/`47:8088`：test 已下线，直连 10010 违反蓝绿规则，web-admin 在 139:8086 不在 47:8088。删，改为「验证以 `verify-release.sh` 回执为准」。
  - 「本地 Java 启动方式」代码块里 `DB_PASSWORD=cretas_pass POSTGRES_SMARTBI_PASSWORD=smartbi_pass` 是写死的口令（CREDENTIAL-MANAGEMENT 明说 `smartbi_pass` 已废弃）。改为 `$DB_PASSWORD` 变量。
  - 「上传策略详情」「关键事实（cretas-media bucket 属账号 B）」「Private repo 让 GitHub 镜像失效」—— 历史考古，删。
- 保留：Phase -1 三条命令 + 判据；「为什么②不能省」压成 4 行（构建 204s→25s；构建期间 main 被推进会作废）；东京中转逃生路（含 ⛔ 不改 sshd/不 StrictHostKeyChecking=no）；锁不要 rm；单组件入口表；Web 部署三条注意（139 不是 47 / tar+scp 原子替换 / 别手动 scp dist）；`mvn spring-boot:run` 不要 `java -jar`。
- 目标 ≤ 120 行。`.agents/skills/deploy-backend/SKILL.md` 已是一版更干净的重写，可以作为底稿反向同步。

### S-4 `depth-first-e2e`（34 KB）：脚本化拒绝语 + 平台错位

- 脚本化拒绝（4 处）：
  - 原文：「**CANNOT skip**: Rule 2 … If user asks to skip depth work, respond: > "I cannot write another round of shallow tests. Spec §1.3 hard rules 3-4 require deep testing…"」以及 Rule 8/9/10 各一段「I cannot …」的固定台词。
  - 影响：预写的拒绝台词是对 Fable 影响最坏的一类文本——它让模型照本宣科而不是判断当前情况；且用户真要跳过时它应该说明后果然后照做（system prompt："user reaffirms → proceed"）。
  - 建议：删掉 4 段台词，改为「用户要跳过 Rule 2/8/9/10 时，说明将失去什么证据，然后按用户决定执行并在报告里标注」。
- 平台错位：
  - 原文：「R{N+1} backlog ticketed — each deferred item exists as a real ticket (GitHub issue / Linear / internal tracker)」；「reviewed by an independent agent (`superpowers:code-reviewer` …)」；「Agent-team skill is being used for E2E audit」。
  - 事实：本仓库的 backlog 是 `docs/dispatch/ACTIVE.md`；superpowers 在 AGENTS.md 被禁；agent-team 已在 2026-07-28 退役（本文件自己说的）。
  - 建议：ticket 改为「登记进 ACTIVE.md」；reviewer 改为「一个零上下文的独立 subagent（recon/Explore）」；删 agent-team 引用。
- 体量：`.agents/skills/depth-first-e2e/SKILL.md` 是同一意图的 6 KB 重写（11 条 → 9 条硬规则 + checklist + 审计问题 + 摘要 schema），案例已在 `references/`。建议**以 .agents 版为两侧共同的 SKILL.md**，把 Claude 版里独有的「Detection: Is this E2E suite compromised?」5 条 grep 和「Bug patterns 1–5」搬到 `references/anti-patterns.md`（那里已有同名内容）。
- 目标 ≈ 7 KB。

### S-5 `e2e-native`（14 KB）：报告模板占一半

- 保留：子命令表、配置表、测试账号表（含「F001 账号是死值 / LIUSHANMEN 是真客户只读」两条，这是关键钩子）、Phase 0 环境检查、关键约束 5 条、错误处理表。
- 删：scan/inject/gen/run/report 五个子命令各自的 markdown 输出模板（合计约 120 行）。Fable 会自己组织报告；模板只是把输出格式钉死。
- 目标 ≈ 7 KB。

### S-6 `e2e-miniprogram`（17 KB）：43 页清单与套件定义应进 references

- 保留（这些是真正非显然的知识）：`connect()` 而非 `launch()`（Windows 中文路径）、`safeNav()`、`page.data()` 优先、`mp.screenshot` 不是 `page.screenshot`、已知限制表、故障排查表。
- 移到 `references/pages.md` / `references/suites.md`：页面清单（43 行表）、TabBar、6 个测试套件定义、文件结构。
- 顺带修 G-4：白名单里「项目无小程序」是错的。
- 目标 ≈ 7 KB。

### S-7 `skill-creator`（18 KB）：与全局/插件版本三重复制

- 事实：`.claude/skills/skill-creator`、`.agents/skills/skill-creator`（把 Claude 改成 Codex）、`superpowers:writing-skills`、`anthropic-skills:skill-creator` 四份同源。Step 5「打包成 .skill 文件」与本仓库无关。
- 讽刺点：它自己写着「Keep SKILL.md body under 500 lines」「Only add context Claude doesn't already have」，本仓 5 个 skill 超过它的上限。
- 建议：删项目副本（`.claude` 与 `.agents` 两份），需要时用插件版。

### S-8 `ux-flow`（4 KB）：缺 frontmatter + superpowers 词汇

- 事实：文件无 YAML frontmatter（skill 列表里描述显示为 H1「UX Flow Gate Skill」，是回退值），意味着触发靠 CLAUDE.md 的硬门，而不是 description 匹配。`.agents/skills/ux-flow/SKILL.md` 有完整 frontmatter，可直接复制。
- 原文：「**触发时机**: brainstorming 阶段 … 在 propose approaches 之前」「门控检查：进入 writing-plans 前」「缺失则拒绝进入 writing-plans 并提示补全」
- 建议：补 frontmatter（name/description 含角色词/路径词/功能词）；措辞改成「设计这些屏幕前先产出本章节；缺失则先补齐再实现」；删「扩展路线 P2/P3」表。内联 UX 规则与 spec 章节格式保留（这是它的价值）。

### S-9 `aliyun-operations`（4.6 KB）：🔴 tracked 文件里有宝塔面板口令

- 原文：「| 宝塔用户 | `user` / `<口令已移除>` |」「| 宝塔面板 | `https://47.100.235.168:8888/<面板路径已移除>` |」
- 事实：`.gitignore` 只忽略 `**/*-credentials.md`；这份 SKILL.md 是 tracked 的（`git ls-files` 可见）。AGENTS.md 明令「所有 tracked 内容一律按公开仓库标准处理」。
- 另有过期：47 上「Web 前端 8088」、DB 只列 `cretas_db/smartbi_db`（prod 是 `cretas_prod_db/smartbi_prod_db`）、`restart.sh` 重启全部、直连 10010 健康检查。
- 建议：
  1. 口令与面板路径移入 `server-operations/aliyun-credentials.md`（gitignored），并**轮换宝塔口令**（它已在 git 历史里；memory 记录仓库因历史中的阿里云 AK 已不能转 public，这是同一类问题）。
  2. 其余内容并入 `server-operations`（安全组命令、故障排查表各保留 10 行），删除本 skill。`.agents/skills/aliyun-operations` 一并处理。

### S-10 `smartbi-test-e2e`（6.5 KB）：逐步点击脚本

- 原文（Phase 1–2）：「1. `browser_navigate` → `{BASE_URL}/login` 2. `browser_snapshot` 获取页面结构 3. `browser_fill_form` 填写用户名 … 4. `browser_click` 点击登录按钮 5. `browser_wait_for` …」共 25 步。
- 影响：这是把 MCP 调用序列写成 skill，典型的前代写法。`allowed-tools` 钉死 `mcp__plugin_playwright_playwright__*` 工具名，本 session 实际可用的是 `playwright-rn` 与 `plugin:playwright:playwright` 两套，名字一变 skill 就废。
- 建议：压成 ≤40 行：目标（上传 → SSE 完成 → 图表/KPI/AI 三项断言）、验证判据表（保留）、文件定位表（保留）、错误处理表（保留）。删 Phase 1/2/5 步骤，删 `allowed-tools` 里的 MCP 具名。

### S-11 `smartbi-e2e-test`、`smartbi-test-data`、`field-consistency-check`、`build-android-apk`、`rules-maintenance`

- `smartbi-e2e-test`：原文「**生产环境测试**: `BASE_URL=http://47.100.235.168:8088 npm test`」—— host/port 都错（web-admin 在 139:8086 / admin.cretaceousfuture.com）。改掉即可，其余保留。
- `smartbi-test-data`：合格。「确认删除前列出将被删除的文件数量」保留（破坏性操作）。
- `field-consistency-check`：删「使用 Grep/Glob 工具而非 bash 命令进行检查」（工具选择不该由 skill 规定）。其余保留。
- `build-android-apk`：gradle 补丁 heredoc 是真正脆弱的流程，低自由度合理。保留原样。
- `rules-maintenance`（本次就是用它触发的）：指向的「统计现状」章节已在 2026-07-28 合并规则时删除；表里的 `server-operations.md`、`aliyun-credentials.md` 不在 rules 目录；memory 路径已过期。建议重写为 12 行：「检查 `.claude/rules` 每份文件的 `paths:` 是否过宽、引用路径是否存在、`最后更新` 是否 > 90 天；发现问题列清单不直接改。」

### S-12 `e2e-web-admin`（指针）

- 合格。但注意 canonical 在 `.agents/skills/e2e-web-admin/SKILL.md`，其中「Pass that checked-in file to Codex Playwright MCP's filename entry」「`browser_run_code_unsafe`」是 Codex 工具名。Claude 侧读到需要自行换算成 `playwright-rn` 的等价工具。建议在指针文件加一行说明。

### S-13 `.claude/agents/executor|probe|recon.md`

- 合格，是全仓最好的三份定义：每份 ≤20 行，只摘了自己需要的 4–5 条判据，说清工具边界。不动。
- 唯一注意：三份都 `effort: xhigh`；`multi-model-dispatch` 说 workers 该跑低 effort。二者取其一；建议 recon/probe 降到 `high`（它们是扫描/取证，不是判断）。

---

## 6. Codex 侧副本（`.agents/skills`、`.codex/rules`）

### X-1 `.codex/rules` 与 `.claude/rules` 9 份同名文件全部不同

| 文件 | .codex | .claude |
|---|---|---|
| worktree-and-main-only-deploy | 6.1 KB | 15.9 KB |
| ai-intent-tool-skill-architecture | 8.0 KB | 3.3 KB |
| python-services-architecture | 4.1 KB | 1.4 KB |
| concurrent-edit-safety | 8.2 KB | 11.3 KB |
| 其余 5 份 | 差异 < 5% |

- 影响：形态 D 的教科书案例。哪份是真值取决于谁最近改过哪边。Claude 的 `ai-intent` 是精简版，Codex 的是老版；Claude 的 `worktree` 是加长版，Codex 的是精简版——两边各有一半是新的。
- 建议（需要拍板）：
  - **A**：`.claude/rules` 为源，`.codex/rules` 改为 5 行指针文件（「以 `.claude/rules/<同名>.md` 为准」）。Codex 能读仓库任意路径，指针足够。
  - **B**：加一道 CI drift gate（`diff -q` 同名文件，不一致即红），两边手工同步。
  - A 更省；B 保留 Codex 自包含。

### X-2 `.agents/skills/agent-team`（28 KB）：已退役仍在

- 事实：`depth-first-e2e` 写明「the old agent-team skill's 4-phase flow was retired 2026-07-28」；文件引用 `.claude/agents/researcher.md`、`analyst.md`、`browser-explorer.md` 均不存在；Phase 0 有 6 组关键词检测表（`CODEBASE_GROUNDING`、`COMPETITOR_MODE`…）是最重的 micromanage。
- 建议：删除。

### X-3 `.agents/skills/performance-audit`、`debug-assistant`、`integration-testing`、`seo-audit`、`vercel-react-best-practices`、`frontend-design`

- `performance-audit`：原文「cd /Users/jietaoxie/my-prototype-logistics/…」（另一台 Mac 的路径）+ 按行号的问题清单（`MobileServiceImpl.java 990-995`）—— 行号早已漂移。删除。
- `debug-assistant` / `integration-testing`：Web 前端「8088 / 47.100.235.168 (Nginx)」错；`java -jar … --spring.profiles.active=pg,dev` 与 deploy-backend「不要用 java -jar」冲突；`JAVA_HOME=jdk-17` 而后端需要 21；DB 名用 test 库。要么修成指向 `server-operations`，要么删。
- `seo-audit`（8 KB）、`vercel-react-best-practices`（88 KB 含 AGENTS.md）、`frontend-design`：与 Vue/RN/Element Plus 技术栈无关，全局已有覆盖。删除。
- `code-review`（2.4 KB）：短，保留；「禁止使用的 API 客户端」表是真钩子。
- `model-effort-router`：写得对（明确"advisory only, never block"），保留。它的 hard rules 里仍有一段 Fable 5 升级条件，随 S-1 同步删。

---

## 7. 停顿点 / 限制自主执行 总表

这是用户特别关注的一类，单独汇总（含上面已提到的）：

| # | 位置 | 原文 | 处置 |
|---|---|---|---|
| 1 | superpowers SessionStart hook | 「1% chance … ABSOLUTELY MUST invoke the skill … BEFORE any response」 | G-1，本仓库关掉 |
| 2 | 全局 PreToolUse hook | 「若形状=1 仍决定派, 必须先说明理由」 | G-2，删或压成一句 |
| 3 | deploy-backend Phase 0 | 「如果判断不了，用 AskUserQuestion 确认」 | S-3，删 |
| 4 | organizer 引导 | 「向用户确认角色」「✅ Organizer 已就位」 | S-1(c)，改为报状态即接活 |
| 5 | organizer effort 提醒 | 「提醒 Steve 在那条消息加 ultrathink」 | S-1(c)，删 |
| 6 | design/SKILL.md | 「不确定 → 问用户」 | G-5，按业务页默认并说明假设 |
| 7 | depth-first-e2e | 4 段「I cannot …」拒绝台词 | S-4，删台词，说明后果后按用户决定 |
| 8 | multi-model-dispatch | 「每次出计划末尾默认追加分发卡」 | S-1(c)，仅并行/派活时产 |
| 9 | AGENTS.md 子代理 | WIP 上限 2+1，扩容先登记台账 | A-3，删数字 |
| 10 | AGENTS.md 派工前置 | 「没有 scope 锁不得启动代码子代理」 | A-4，仅并行时要求 |
| 11 | playwright-headed | 「如 spec 跑没满足任一条 → 不 ship」 | R-4，收窄到客户可见截图 |
| 12 | organizer 台账 | 「触发后立即读 ACTIVE.md」而台账 13 天未更新、单条 8000 字 | S-1(f)，派活时读 + 条目上限 |

**明确保留的护栏**（不在清理范围）：`--confirm-prod YES-PROD` 类口令；F006 写入前证明 `factoryId==='F006'` 与 0 跨租户写；prod 只从 origin/main 部署；🔒 红线（migration/Flyway/RLS/权限/架构）执行者只到 PR；凭证不进 tracked 文件；不 `rm` 部署锁；不 `StrictHostKeyChecking=no`；不 `mklink /J`；不裸 `git stash`；`ui-ux-pro-max` 的「不自行装 Python」；`smartbi-test-data clean` 前列清单。

---

## 8. 实施顺序与预期削减

### Phase 1 — 零判断、纯修正（可一次 fastlane 直推）
1. S-9：宝塔口令移出 tracked 文件 + 轮换（**最优先，与其余无关**）
2. P-1 死链、P-2 端口、S-11 错误 host/port
3. S-1(a) 删全部 Fable 段落；S-1(e) 修断链
4. S-2/S-3 删 test 环境段落
5. R-5 收窄 `python-java-port` 的 `paths`；R-4 收窄 `playwright-headed` 的 `paths`
6. S-8 补 ux-flow frontmatter
7. X-2/X-3 删 agent-team / performance-audit / seo-audit / vercel-react / frontend-design；S-7 删 skill-creator 副本
8. A-1 删 AGENTS.md 顶部离线段落；G-4 删/缩 skills-whitelist

### Phase 2 — 压缩重写（逐文件 PR，便于 review）
1. R-1 measurement-and-wiring 拆分（常驻 ≤220 行 + 案例库）
2. A-2 AGENTS.md 发布段压缩
3. S-1 organizer + multi-model-dispatch 重写去重
4. S-3 deploy-backend 重写（以 .agents 版为底）
5. S-4 depth-first-e2e 采用精简版
6. R-2/R-3 worktree / concurrent 压缩
7. S-2 server-operations 合并 aliyun-operations
8. S-5/S-6/S-10 e2e-native / miniprogram / smartbi-test-e2e 模板外移

### Phase 3 — 需要你拍板
1. G-1：superpowers 在本仓库关掉（A）还是全局关掉（B）
2. X-1：`.codex/rules` 改指针（A）还是加 drift gate（B）
3. S-1(f)：ACTIVE.md 条目上限与"派活时才读"

### 预期数字

| 指标 | 现状 | 目标 |
|---|---|---|
| 每 session 常驻加载 | ≈ 100 KB | ≈ 30 KB |
| `AGENTS.md` | 35.7 KB | ≈ 14 KB |
| `organizer` + `multi-model-dispatch` | 58 KB | ≈ 16 KB |
| `deploy-backend` + `server-operations` + `aliyun-operations` | 51 KB | ≈ 18 KB |
| `depth-first-e2e` SKILL.md | 34 KB | ≈ 7 KB |
| 项目 skills 总量（不含 references） | ≈ 200 KB | ≈ 80 KB |
| 停顿/确认点 | 12 处 | 0 处（护栏不算） |
