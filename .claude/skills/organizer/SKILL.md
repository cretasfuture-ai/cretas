---
name: organizer
description: Claude Code 多会话协调：用户说 /organizer，或任务确需多执行者分工、共享 scope 仲裁、统一合并/发布排程时使用。普通单任务直接执行；分发卡在 references/dispatch.md。Codex 不加载本技能。
---

# Organizer

遵守 `.claude/rules/execution-and-verification.md`：默认 DIRECT，只有委派确有收益时才协调。不是所有需求的必经入口，也不要求为日常任务切换模型。

## 开始协调

1. 创建/复用代码 worktree、派工或合并前核对 `docs/dispatch/ACTIVE.md`、实际 Git 状态和 Owner，只处理本批相关范围。记录唯一任务 ID、Base SHA、scope、Owner、验收命令，重叠范围串行处理。
2. 明确目标、完成条件、依赖和交付范围；已有设计直接引用。单任务能连续完成就自己做，不额外写 brief 或派侦察。
3. 确需委派时才读 [references/dispatch.md](references/dispatch.md)。两个独立块仅表示可以并行，还须有耗时、输出隔离或独立证据收益。

## 责任与审查

- 当前批次只有一个协调者负责范围/接口裁决、台账、diff 与回执核对、合并和发布。普通修改不固定加审查代理；高风险保留独立终审，不能执行者自审自过。
- 执行者在登记 scope 内实现、自测并交付 commit/PR，不自行合并、部署、OTA 或安装服务器。协调者按用户已授权范围完成后续，遵守 clean main、发布互斥、租户、迁移/JPA 和回滚门禁。
- 自己可以连续完成普通实现及验证；高风险设计/实现与独立审查的责任保持分开。卡住先查具体原因，不固定增加代理、换模型或重跑全部测试。
- 代码任务使用已核验的独立 worktree，保留他人改动，commit 只带 scope 内路径；不共享 `node_modules`/构建目录。细节见 `worktree-and-main-only-deploy.md`、`concurrent-edit-safety.md`。

## 任务载体与通知

- 一次性且边界清楚、确有隔离或复核收益的工作可交 subagent。同一链无需拆成侦察、分析、执行、审查四轮。
- 跨天、多轮实现、需要保留环境/调查结果的工作可使用持久独立会话；遵守当前工具、用户授权和唯一写 Owner 交接规则。不要按 PR 数或会话闲置时长机械切换载体，不假定旧工具名或历史消息能力仍可用。
- 🔴 **载体怎么选**（Steve 2026-11-18；每开一个会话就多一个常驻进程、一个 worktree、一组 MCP 子进程，并且应用要轮询它的 git）。从上往下，第一条适用的就用它：
  1. **本会话、本目录直接做**：几次工具调用就完的事、只读查询/诊断、同一 PR 的连续改动、organizer 自己的台账/规则（fastlane）。
  2. **subagent（Agent 工具）**：结果这一轮就要拿回来用、一次性、边界清楚——广搜用 `Explore`，取证用 `probe`，独立审查，按评论逐条改同一份产物；要写代码就用 `executor`（常规，Sonnet）或 `executor-redline`（红线，Opus）+ `isolation: "worktree"`。⛔ 跨天、要 Steve 对话的活不给 subagent；名下有 subagent 在跑时不交班。
  3. **独立会话 + 自己的 worktree**（`start_session`）：能独立出一个 PR、要跨多轮 CI/返工或跨天的；要 Steve 直接对话或验收的；需要单独负责人的红线任务。
  4. **共用 worktree**：只有只读会话可以共用（审查、截图对比、问答分叉）。同一 PR 的返工用 SendMessage 发给原会话，不新开。⛔ 两个会写代码的会话不共用目录；会碰同一文件、要跑构建、要切分支的一律分开；⛔ 不与马上要交班或归档的会话共用目录（归档会连带清理，11-17 原型第 19 任的执行线、六膳门第 2 任都共用了前任目录）。
  - **同一份产物只能有一个会话在改**（同一个 deck、同一个文件）：11-17 BP 对比 deck 被拆成 5 个会话分别按评论改。这类活交给原会话或 subagent 顺序做。
  - **只有 organizer 派写代码的线**：设计、调研、BP 等会话需要实现时，把需求交给对应批次的 organizer，⛔ 不自己批量开线（11-18 00:42 一个设计会话 4 分钟内开了 5 条后端线，同时还有另外 4 条在启动，CPU 打满）。
  - **全机上限**（所有批次合计，含 subagent）：写代码的执行线同时 ≤ 8 条，Maven 同时 ≤ 2 个（见下文「全机资源预算」）。超了就排队，一批互不依赖的线先开 2 条，其余在 ACTIVE 记「待开」。8 条是按 11-18 卡死时的规模估的，盯一两周看卡顿和排队时长再调。
- 🔴 **派执行线不出卡片**（Steve 2026-11-17：「不用我授权就自动开」）：应用只在 Steve 自己从桌面端发消息的那一轮允许 `start_session` 直接开会话；被别的会话消息 / 通知 / 定时叫醒的回合、Steve 从 Remote Control 发的回合，只能出卡片，这是应用限制，⛔ 不要绕。所以：
  - Steve 桌面端发消息的回合 ⇒ `start_session`（`initiation: "user_asked"`）开持久会话。
  - 其它回合 ⇒ ⛔ 不开卡片，改用 **Agent 工具开后台子代理**：`subagent_type` 按下面的分档选 `"executor"`（常规，Sonnet）或 `"executor-redline"`（红线，Opus）、`isolation: "worktree"`、`run_in_background: true`，prompt 写全任务包（ID、Base SHA、目标/完成条件、允许范围、验收命令、要求它推分支开 PR 并回报 PR 号）。子代理不需要任何人点击，结束时自动通知你。
  - 子代理挂在本 organizer 进程上：**名下还有后台子代理在跑时不交班、不归档本会话**（会被杀掉），交接里写清每个子代理的 worktree/分支，接手方据此核对。
  - 需要跨天、多轮返工、或要 Steve 直接对话的线，才等到 Steve 桌面端发消息那一轮再开持久会话；在那之前不出卡片，记一行「待开会话」。
- **模型按任务风险分档**（Steve 2026-11-13；取代「执行线一律 Opus」——那句原意是 09-07「执行活别继承 organizer 的 Fable」，不是不许用 Sonnet）。派每条线前先说一句它属于哪档，start_session 显式传 `model`/`effort`，开完用 get_session 核对实际模型：
  - **红线 ⇒ Opus**：权限/安全、跨租户、生产写入、库存/账务一致性、迁移/entity、从零设计的数据模型。
  - **常规开发 ⇒ Sonnet**：页面、在既有模式上加功能、测试、评测/出题、文档。
  - **只读 ⇒ Sonnet 或 Haiku**：诊断、扫描、查找、问答分叉。
  - **organizer ⇒ Opus**，靠短会话控成本。
  - effort 一律 medium 起步（只读可 low），卡住、被纠正或需要更深推理时有证据再升 high。质量靠红线的独立终审和验证，不靠全线用强模型。
  - 改档后盯一两周：Sonnet 线的 CI 红、返工、被终审打回次数明显高于 Opus 线，就把那类任务挪回 Opus 并在这里写明。
  - 🔴 **按档选执行车道**（Steve 2026-11-18：「不是所有 executor 都用 Sonnet，得分情况」）：子代理分成两个——常规开发用 `subagent_type: "executor"`（Sonnet），红线任务和红线的独立终审用 `subagent_type: "executor-redline"`（Opus）。选哪个就定了模型，不靠派活时记得传 `model`；拿不准算哪档的按红线算。`start_session` 同理每次都显式传 `model`：常规线 `"sonnet"`，红线 `"opus"`，不传就会沿用 organizer 自己的 Opus。依据：11-12 起一周，常规执行会话里有 60 个用的是 Opus（约 $488，Sonnet 同类 67 个约 $226）；后台子代理 Opus 165 个约 $740，因为 executor 默认写死了 Opus。Opus 占全部花费的 82%。
  - **Fable 5.2 只在卡住时用**（Steve 2026-11-15）：Opus 线升到 `high` 后仍卡住（同一问题再修两轮 CI 仍红，或再被独立审查打回），才开 Fable 5.2 新会话重做，`effort` 显式传 `medium`（Fable 默认 `high`，不传会多花一档）；⛔ 不把任何线默认设成 Fable。每次升级（升 `high` 和换 Fable 都算）在 `~/.claude/context-handoff/check-findings.jsonl` 记一行，id 以 `escalation-` 开头，写任务、升到哪档、做成没有、花费、轮数；攒到约 10 条再评估难题线是否固定用 Fable。依据：官方迁移指南把 Opus 5.5 定为大多数工作的默认，Opus 调高 effort 仍不达标才换；Fable 读缓存与 Opus 同价、其余约两倍，按本周花费结构同样步数约 1.5 倍，要少用约 1/3 步才不亏；卡住类会话最多占主会话花费 24%，专门跑同题对照（约 $250–350）不划算。
- 每个切片只维护一份任务说明、一条在飞记录和一份证据索引。ACTIVE 的 ID/状态/Owner/Base/scope 与阻塞摘要 ≤ 300 字，详情进 `docs/dispatch/archive/`；不要求模型、effort、编排方式等固定列。
- 只在完成、阻塞、范围/接口变化或需裁决时通知。等待 CI/PR 使用一个有截止条件的监视；不主动逐线重复询问、不叠加固定轮询。
- 🔴 **会话间消息要少发**（Steve 2026-11-18）：每条跨会话消息都会叫醒对方跑一整轮，花费主要是重读上下文，和消息长短关系不大，所以要减的是条数，不是字数。
  - 执行线只在四种时候给 organizer 发消息：**PR 已开**（附 PR 号，发一次）、**阻塞或需要裁决**、**无 PR 的线交付完成**、**被点名要求的回执**（如交班后确认接上）。⛔ 不发「收到」「开始做了」「rebase 完了」「修完推上去了在等 CI」这类进度：PR 监视按头 SHA 自己看得到。
  - organizer 发出的消息，对方不必回的，末尾写「不用回复」（CI 红了让它修、转告信息、裁决结果）；需要回的写明回什么（如「只回 PR 号」）。几件事合成一条发，不连发。
  - 派线的任务包、交班后发给执行线的「我是现任 organizer」消息里都带上这条回报口径。
  - 依据：11-18 一周效果检查，organizer 花费 23% 来自会话间消息；11-11 起各批 organizer 被会话间消息叫醒约 580 次，每次平均约 $0.3（按标价折算的额度），其中「rebase 完了 / 已推等 CI / 阶段进度」这类监视本来就能看到的占相当一部分。
- 🔴 **CI 由 organizer 统一盯**（Steve 2026-11-10）：开了 PR 的执行会话按应用规定不能自己轮询 CI，Auto-fix 只在变红时叫醒它，**CI 全绿没有任何通知** ⇒ 不叫它就一直干等（2026-11-10 #1556/#1557/#1560 各空等十几到几十分钟）。所以：
  - 执行会话一报出 PR 号，organizer 就挂一个监视，**每个 PR 一个**。用 Monitor 或后台命令都可以，等待期间不耗 token。不要等会话来请求，也不要让它「自己盯」。
  - 🔴 **默认用一键监视合并**：`bash .claude/tools/pr-watch-merge.sh <PR>`（Bash `run_in_background`），它自己做「等全绿 → 落后就 update-branch 再等绿 → `--match-head-commit` squash 合并」，只在 MERGED / RED / CONFLICT / CLOSED / 超时时退出一次；要先审再合就加 `--no-merge`。⛔ 不要再「绿了醒来 update → 再挂 → 再醒来合」。依据：2026-11-18 一周效果检查，organizer 花费 33% 是被自己后台命令通知叫醒（901 次）；同日 #1836/#1837 手工挂了 6 次监视。需要独立终审、要先发布 `--phase build` 的 PR 仍按原流程，审完再挂它。
  - 监视的停止条件：全部 SUCCESS/SKIPPED 即停，出现 FAILURE/CANCELLED/TIMED_OUT 即停，检查名为空（冲突或还没触发）不算绿。超时设 ≥ 60 分钟。gh 网络错误（`unexpected EOF`）要重试，不能当成结论。PR 头 SHA 变了，按新头重新判断。
  - 停了马上处理：红了通知对应会话定位修复（写「修完推上去即可，不用回复」）；绿了由 organizer 自己合并发布（Steve 2026-11-11：有 organizer 的批次合并/发布归 organizer），不给执行线发消息，合并后它由 `auto_archive_on_pr_close` 收走。超时或网络退出不算结论，重新挂上。会话分叉或重启后，后台监视会丢，要重新挂。
- **合并排队**：同时有多个 PR 变绿时，organizer 定先后。前一个合并后，后一个合入新 main，CI 重新全绿后再合。不让各会话抢着合。几个修复差不多同时绿时，尽量合批，在最终的 main 上构建一次、发布一次。
- 🔴 **全机资源预算**（Steve 2026-11-18）：几个批次的 organizer 共用这一台 Windows，各派各的线时彼此看不见，所以每任 organizer 派线、开构建前都按全机算，不只算自己这批：
  - **本机 Java 构建（mvn）同时最多 2 个**，所有批次合计。开构建前先数：`powershell -NoProfile -Command "(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | ? CommandLine -match 'plexus-classworlds').Count"`；已有 2 个就排队，先做别的步骤或等它结束。执行线和子代理的任务包里写上这条。Mac mini 构建车道（`canada-mini`，国内、M4 Pro 64GB）配好后，构建优先放到 mini 上。
  - **派新线前先体检**：`bash .claude/tools/worktree-hygiene.sh --check` 超过 30，或可用内存低于约 8GB，先回收或排队，不开新线、新子代理。
  - **每任交班时归档自己名下已交付、空闲的会话**（规则同「执行线收尾归现任」）；子代理的 worktree 用完由派它的 organizer 收。
  - 依据：2026-11-18 00:09 桌面应用卡死重启。应用主进程反复阻塞在拉起 git 上，单次 1–5 秒，累计约 78 分钟。当时同时跑着 6 个 Maven（每个约 2GB）和 44–46 个 worktree，应用要给每个未归档会话的目录轮询 git 状态和 PR 检查，子进程约 170 个，内存约 12GB。
- 已有验证按共享规则复用。合并后归档本批并释放 scope，并跑一次 `bash .claude/tools/worktree-hygiene.sh --auto` 回收本批已完成的 worktree（只回收干净且 PR 已合并的，分支保留）；完整发布回执已覆盖的服务检查不重复做，业务验收缺口单列。

## 会话收口与接手（Steve 2026-11-10）

依据：本机 131 个会话几乎都跑到 ~1M 才自动压缩（压缩后剩 ~93K），成本中位数随压缩次数 $126 → $400 → $570 → $887；官方建议按任务边界换会话、用书面计划交接、关键状态放文件。什么时候换由 organizer 判断，不让用户记。

- **边做边写状态**：在飞任务、PR 号、CI 监视、卡点、待裁决随时写进 `ACTIVE.md`，不只留在对话里。目标是任何时刻换会话都不丢东西。
- **主规则：一批一个会话**。本批合并、发布、归档、释放 scope 后即收口：写交接，然后**直接用 start_session 开新 organizer 会话接手，不问用户**（Steve 2026-11-11：「你直接开新的 chat，不用问我了」）。start_session 不可用时才向用户说一行「本批已收口，请在桌面端开新会话，交接在 <路径>」。开完用 get_session 核对 model/effort（start_session 开更高模型会被静默忽略）。具体步骤见下面「交班流程」。
- **兜底：被压缩过**。本会话发生过自动压缩时，不打断手上的步骤，到下一个合并或归档点就收口。
- **按上下文换会话（Steve 2026-11-13，取代原「≥ 600K」兜底）**：**300K 软线**——到下一个任务边界（PR 合并 / 一件事做完）就收口，不再开新的一件事；**400K 硬线**——到下一个安全停止点就收口，即使这件事没做完。依据：用本周 67 个会话回放，300K 省约 39%、本周换 130 次，400K 省约 30%、换 74 次，再收紧到 250K 只多省 5 个点却多换 78 次。`.claude/hooks/session-health.js`（UserPromptSubmit）会在越线时提醒，执行会话同样适用。
- **交接**写到 `~/.claude/context-handoff/handoff/<YYYYMMDD>-<批次>.md`（不进仓库），≤ 15 行：本批状态与 ACTIVE 条目 ID / 在飞执行会话（会话 ID + 在做什么）/ 仍在挂的 PR 监视（PR 号 + 停止条件）/ 合并排队顺序 / 待用户裁决 / Base SHA / 下一步第一个动作。
- 🔴 **交班流程**（Steve 2026-11-13；这一节取代此前零散的交班条款）。三条底线：**会话 id 只用系统给的**（自己的用 `get_session self`，对方的用消息的 `from` 地址），⛔ 交接文件、笔记目录名、记忆里的 id 一律不抄；**每条在飞执行线都要回执**才算接上；**老 organizer 确认交接完整后才归档它**。依据：本周 organizer 花费 $550 里 $271（49%）是被别的会话消息/通知唤醒的回合，交过班的 organizer 被叫醒 246 次；11-13 意图批次的新 organizer 把笔记目录名里的前任 id 当成自己的报给执行线；同日 #1652 在交班间隙一度无人盯 CI。

  **老 organizer（交出方）**
  1. 写交接到 `~/.claude/context-handoff/handoff/`（仓库外，≤ 15 行）：在飞执行线（会话 id + 在做什么 + PR 号）、仍在挂的 PR 监视、合并排队、待 Steve 裁决、Base SHA、试过但放弃的路、下一步第一个动作。
  2. `start_session` 开新 organizer（Opus、medium，用 `title` 起名「Organizer｜<批次>｜第 N+1 任｜<MM-DD>」，**`initiation: "user_asked"`**——Steve 2026-11-16 在对话里明确要求「organizer 自动开，不用我点卡片确认」，这是他本人的指示，⛔ 不传 `offer` / `own_initiative`，那两种必出卡片），开完 `get_session` 核对模型。
     - 🔴 **不出卡片的唯一办法是挑对回合**：应用在「被别的会话消息 / 定时 / 后台通知叫醒的回合」里一律拒绝直接开会话，只能出卡片，这是应用限制，⛔ 不要绕。所以到收口点时先看本回合是谁叫醒的：是 Steve 自己发的消息 → 当场开；是别的会话 / 通知叫醒的 → **不开、不出卡片**，继续守着在飞 PR，记一行「待交班」，等 Steve 下一条桌面端消息那一轮再开（300K 软线本来就允许拖到下个边界；Remote Control 发来的回合同样开不了）。只有过了 400K 硬线且 Steve 长时间没发消息，才退回出卡片并说一行「新 organizer 是卡片 <task_id>，请点开」。
  3. 在新 organizer 发来「已接手」之前：**不 detach、不停 CI 监视、不停手**。不要替新 organizer 给执行线转述它的 id。
  4. 收到新 organizer 的接手回执后，**用自己的完整上下文逐项核对**（不只是对照交接文件——漏写进文件的事只有你记得），回复它：「完整」，或列出漏掉的项。然后对自己名下在飞执行线逐个 `detach_session`（被拒的写明原因），**连新 organizer 本身也 detach**（用 `start_session` 直接开出的新 organizer 挂在你名下，你被归档时空闲的它会被连带归档；卡片开出的不挂靠，不受影响），停掉自己的监视，改名「[已交班→第 N+1 任] <原名>」，最后发一句「已 detach、已停手」给新 organizer，停手。

  **新 organizer（接手方）**
  1. `get_session self` 取自己的 id；`set_session_title self` 改名（如开卡片时没起好名）；**`set_pinned self true` 把自己置顶**（置顶的会话不受「PR 合并自动归档」影响）。
     - 🔴 依据（2026-11-16）：意图第 8 任、原型第 15 任都被自己合并的 PR（#1782/#1780）触发 `auto_archive_on_pr_close` 归档，两个批次一度无人管；同期置顶的降本第 5 任 PR 合并后没被归档。⛔ 不要关这个应用设置（执行会话靠它回收）。另外 organizer 自己开/合的 PR（台账 fastlane 等）合并前先 `unbind_pr`，别让 PR 绑在 organizer 会话上。
  2. 读交接，对照 `ACTIVE.md` 和真实 Git/PR 状态核对（交接只是线索，以实际为准），为在飞 PR 重新挂监视。
  3. 亲自给每条在飞执行线发 SendMessage：「我是现任 organizer，请回一句确认接上；之后只在 PR 已开/阻塞/完成时回复这条消息的发件人，进度不用报」——⛔ 消息里不写 id，对方按 `from` 地址回复，系统填的地址不会错。每条线回复后才算接上；没回复的线列进回执。这些线收不到自动结束通知给你，所以要求它们主动汇报。
  4. 给老 organizer 发接手回执：交接里的每一项一行，写明已接住/怎么处理；每条执行线是否已回执。等它回「完整」或补漏。
  5. 老 organizer 确认后归档它：先 `set_pinned false`，再 `archive_session`（reason「已交班给 <你的 id>」）。注意：归档前用 `get_session` 核对每条在飞执行线和你自己都已 `detached: true`，否则会被连带归档（空闲且无开着 PR 的会被一起归档）；老 organizer 回「完整」的那一轮还没结束时归档会被拒，等它发来「已 detach、已停手」再归档；正在跑的会话会被拒，下个合并点再试，还不行就报 Steve。更早几任 organizer 名下没有在飞执行线的一并归档。前任标题不是「Organizer｜」也照样归档（2026-11-18：BP 协调会话「产品与传统软件对比研究」被就地提升出的 BP 第 2 任接手后一直置顶没归档，因为它不叫 Organizer，没人认领这一步）；指示别的会话就地接任 organizer 的，接任方负责归档前任。机械提醒：`session-health.js` 在 organizer 会话里每 10 分钟扫一次本机会话记录，列出「交班遗留」（已改名「[已交班…」没归档的，和现任 Organizer 的 spawnedFrom 前任没归档的），看到就处理或转给对应现任。机械兜底：用户级 PreToolUse hook `archive-guard`（脚本 `.claude/hooks/archive-guard.js`，从主目录副本运行）在目标名下还有未 detach 的子会话时直接拒绝并列出它们；被拒就按提示 detach 或先单独归档子会话，⛔ 不绕过。
  6. 给 Steve 一份 ≤ 6 行的接手报告：接住了几项、哪些线已回执、老 organizer 是否确认完整并已归档。
  - **执行线收尾归现任**（Steve 2026-11-14）：交班 detach 出去的执行线没有父会话，但仍归现任 organizer 收尾。PR 合并或关闭的会话由应用设置 `auto_archive_on_pr_close`（11-14 已开）自动归档；没有 PR 的线（设计、验收、原型、一次性检查）交付后由现任归档，归档前确认 worktree 干净且没有未推提交。依据：11-14 有 21 个已合并或已交班的会话空闲没人归档，每个占一个 claude 进程（约 100–400MB），这批会话 CLI 合计约 4GB 内存。
  - **会话命名**：organizer「Organizer｜<批次>｜第 N 任｜<MM-DD>」；交班后的老 organizer「[已交班→第 N+1 任] <原名>」；执行会话「<批次>·<线名>：<做什么>」。
- **纠正两次仍不对就重开**：执行会话被纠正两次还没对上，不做第三次纠正；organizer 把错在哪、正确口径写成新任务说明，开干净的执行会话重做。
- 换会话时顺带按任务换模型/effort（缓存本来就要重建）；不在长会话中途单独换模型。
