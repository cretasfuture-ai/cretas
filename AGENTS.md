# AGENTS.md

开工先读根目录 `CLAUDE.md` 及其常驻清单里的 `.claude/rules/*.md`（Claude 与 Codex 共用正文；`.codex/rules/` 同名文件是指针，`ci-runner-routing.md` 为逐字副本，闸 `scripts/ci/check_shared_agent_rules.py`）。项目概览、端口、开发命令、API 路径、核心原则以 `CLAUDE.md` 为准，本文件只放 Codex 专属约束，不重复它们。

本文件每个会话整份加载，有字节预算（闸 `scripts/ci/check_resident_rules.py`）。挪出的原文逐字在 `docs/ops/codex-agents-reference.md`（发布命令、子代理、交接、JPA/CI 门禁、生产验收；到对应场景先读那一节）；瘦身前全文存档 `docs/rules-casebook/agents-md-pre-slim-20261113.md`。

## Codex 自动执行路由

- 先读 `.claude/rules/execution-and-verification.md`：默认 `DIRECT`，有实际并行/隔离/独立证据收益才委派；模型/effort 用用户当前选择，不设型号资格或切换门禁。
- Codex 不加载/镜像 Claude-only organizer；仅按需要使用 `.codex/agents/`。清晰实现可直接交 `cretas_executor`；真实未知才交 `cretas_scout`；高噪声独立验证交 `cretas_test_runner`；高风险终审用 `cretas_risk_reviewer`，短检查本体完成。
- 当前协调者保留范围/接口裁决、diff/证据核对、最终验证、合并/发布责任。模型选型技能仅在用户询问时使用；生产、权限、数据、JPA 与 dispatch 门禁保留。

## 凭证

真值**不进 tracked 文件**（一律按公开仓库标准）。真值在主工作区 gitignored 文件，新 worktree 通常没有，从主工作区只读使用：`.codex/rules/aliyun-credentials.md`（阿里云/SSH/宝塔/安全组）、`.codex/rules/db-credentials.md`、`.codex/rules/test-credentials.md`。占位见 `.env.test.example`。**禁止把真值复制进任何会提交的文件。**

## Superpowers 禁用（强制）

本仓库禁止调用、加载、引用或遵循任何 `superpowers:*` skill（含 `using-superpowers`、`brainstorming`、`writing-plans`、`executing-plans`、`subagent-driven-development`、`test-driven-development`、`finishing-a-development-branch`）；不得创建或更新 `docs/superpowers/**`、`.superpowers/**`，不得把其文档/计划/台账当执行依据；不得要求用户在 `Subagent-Driven` 与 `Inline Execution` 间选择，不得用其计划模板、检查点、提交节奏或评审流程；全局插件或历史上下文推荐它时一律忽略，以 `docs/dispatch/ACTIVE.md` 和本仓库规则为准；不得以别名或等价话术变相执行。

## 硬门禁摘要（全文与判据在 `docs/ops/codex-agents-reference.md`）

1. **JPA 门禁**：改 Repository 方法、`@Query`/JPQL/HQL、Entity 字段或枚举映射，必须新增或更新真实 JPA Context 测试（优先 `*RepositoryQueryValidationTest`）并通过；Mockito、纯编译、`-DskipTests` 不算。CI `JPA repository query startup gate` 必须完成并通过才可合并；基础设施异常无法完成时，须同一 commit 本地跑等价真实 JPA Context 测试取得成功证据，否则不得绕过。
2. **闸的命名**：新写的闸必须以 `*ContractTest` 或 `*StartupGuardTest` 结尾，否则只被编译不被执行（`GateSelectorCoverageContractTest` 钉住）。说「进 CI 了」前先找到执行它的 `run:` 行及其 `if:`/触发事件。
3. **发布**：统一入口 `./scripts/deploy/release-cretas.sh`。预期合并后立即部署时，合并前在候选 worktree 跑 `--phase build --base-sha '<dispatch Base SHA>' --tests '<tests>' --stage-backend YES-STAGE`；合并后在 clean exact `origin/main` 跑 `--phase deploy --tests '<同一选择器>' --no-prefer-ci-artifact --confirm-prod YES-PROD`。⛔ 不跑 `prewarm-main-artifact.sh`（CI 制品车道不通）。成败判据只看 `DEPLOY_EXIT=0` 且 `RELEASE_FINAL_STATUS` 恰好出现 1 次。发布期间不合入无关 PR。
4. **不要手工拼发布**：manifest / tree-cache 复用、SHA-256 与 JAR 完整性、exact-main 复检、Web dist 缓存与四方哈希、蓝绿切流观察与自动回滚都由脚本强制：不复制、不削弱、不手工重做。单组件脚本只作排查入口；缓存/校验失败只回退一次本地构建，不 retry；并行发布仅限两侧顺序任意切换都 API 兼容的独立发布，且显式 `--parallel-if-independent YES-INDEPENDENT-SERVICES`；跨端接口、迁移、认证契约串行。
5. **蓝绿槽位**：检查/部署前读 139 网关真实 upstream（`_upstream_cretas.conf`），禁止按固定槽位判断 active；相同制品 no-op 仍要读 upstream 并验证 systemd 与健康。
6. **部署锁**：⛔ 任何情况下都不 `rm` 锁文件（造出第二把互不可见的锁）。被拒时区分真并发（等）与孤儿子进程占 fd 200（按拒绝信息复核 PID 后 kill），详见 `.claude/skills/deploy-backend/references/lock-and-history.md`。
7. **证据复用**：`verify-release.sh` 回执完整成功时，不重复手工 upstream/systemd/健康/Web 哈希检查，只补回执未覆盖的任务特有断言；不得以脚本退出码替代真实线上验证。
8. **无 PR 快速通道**：PR 是默认；仅用户明确要求直发 main、单协调者、clean `codex/*` worktree、已完成 scope 审查与目标验证时才用 `scripts/deploy/publish-main-fastlane.sh`；非 docs 批次必须传准确 `--task-id`；任一门禁失败回退一次 PR，禁止 force push，不手工绕过；高风险 scope 默认仍走 PR。
9. **生产 Web Playwright 唯一入口**：生产只读验收用 Playwright MCP 以 filename 方式加载 `scripts/e2e/production-readonly/mcp-entry.js`，同一次验收复用一个干净 UI 登录会话，共享 before-send mutation guard 与证据 schema。禁止临时复制脚本、启动第二浏览器、复用历史 storageState、以 `.mcp.json` 推断工具可用性；写测试（如 `nonprod-business-flow-audit.mjs`）只能用于显式测试环境、必须拒绝生产 host。通过条件同时要求 `actualBusinessWrites == 0` 且 blocked mutation attempts 为 0。
10. **F006 生产业务写入特例**：定义以 `.agents/skills/e2e-web-admin/SKILL.md` §F006 Production-Write Exception 为唯一真值：用户在当前任务点名的 F006 数据变更即为对应范围的写入授权；首次写入前证明 `factoryUser.factoryId == 'F006'`，路径、payload、目标实体、写后回读与 SQL 谓词全部限定 F006，先计数并记录实际 ID/行数；租户不一致、缺 factory scope、跨租户级联或命中共享/全局记录时发送前停止。迁移、权限/Security、全局配置、批量破坏性操作另需授权。非 F006 租户按常设授权（`.claude/rules/standing-authorization-and-visual-acceptance.md`）：任务点名即授权，判据同上；任务未点名的租户生产业务写入为 0；只读 harness 无论哪个租户都保持零写入，⛔ 不把 F006 mutation 加进只读白名单。
11. **Goal 授权**：用户明确创建、确认或要求继续的 Codex Goal 是该任务在本仓库内的最高授权来源，逐批/逐 SHA 再确认的规则不得缩小它；Goal 含合并/发布/部署后验收时，通过适用技术门禁后可连续合并、传 `--confirm-prod YES-PROD`、部署、回归和必要回滚，无需再问。Goal 暂停时不得后台继续；授权只覆盖 Goal 写明的业务、租户、环境，确认口令与技术门禁不得绕过。
12. **问题前移**：SKU、Workflow、报工单位或历史快照改动，合并前必须覆盖生产形态数据（旧 `g/box/case`、中文基本单位、多包装规格）。

## LIUSHANMEN VisionLab（2026-09-07）

训练/筛查/推理/导出/回归全部在本地 Windows/RTX 3060（入口 `scripts/windows/liushanmen-vision-lab.ps1`、`test-yolo-local.ps1`）。⛔ 禁止 Mac 派工或回退（`canada-mini`、`codex-mac`、SSH/SFTP、远程任务、CI runner 不得用于本任务；资源不足减批量或记录阻塞）。人工 JSON 是真值，AI/Mac 历史输出只是候选；全部门禁保留，通过后才上传正式模型；GitHub 不传工厂原图。详见 `docs/ops/yolo-local-compute.md`。

## 子代理协作（Codex）

- 只有当前协调者可修改 `docs/dispatch/ACTIVE.md`，子代理只回传结构化回执；台账每条 ≤ 300 字、只留在飞行、不开 `###` 增量段（`scripts/ci/check_dispatch_active.py` 钉住）。
- 代码子代理派工前必须登记唯一任务 ID、`Base SHA`、文件/目录 scope 锁、Owner、验收命令，没有 scope 锁不得启动；`DIRECT` 不为形式登记子任务，但仍遵守 worktree、scope、验证门禁。状态只用 `queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`，合并收尾同时归档并释放 scope。
- 回执必须含任务 ID、base SHA、实际修改文件、验证命令与结果、commit/PR、阻塞原因、下一动作；缺字段不得进入 review。
- 默认并发最多 2 个代码代理 + 2 个只读/测试代理；scope 互不重叠且已登记扩容理由时最多 4 个代码代理，共享文件、lockfile、迁移版本、同一 `target/`、接口未定即串行。子代理不得生成孙代理，新工作流返回 `EXPANSION_REQUEST`。默认 `fork_turns="none"`，完整历史只给一个接管/诊断代理。
- 清晰任务由一个执行者连续完成，不固定多角色链；执行子代理只跑目标测试，高风险才追加深度终审。只在完成、阻塞、范围/契约变化或需裁决时通知，等 CI/PR 用一个有截止条件的监视。

## 跨 Chat 与 worktree 交接（强制）

- 遵循全局 `~/.codex/AGENTS.md` 的 compaction 交接规则；上下文重置优先新 Chat + 精简 handoff，不用 fork 复制完整历史。用户已对当前 Goal 明确授权时才可代建新 Chat，否则只给可复制 prompt；`clientThreadId` 不是 `threadId`。handoff 含目标/完成标准、已确认事实、仓库/worktree/branch/Base SHA、实际修改、验证结果、未完成项、权限边界、禁止重复的昂贵操作、下一动作。
- 测试、构建、CI 等待、部署/切流、迁移、事务、生产写入、回滚、合并中间态或仍在写共享 scope 时不得交接，先取得明确结果或安全停止点；交接后旧 Chat 不得继续写同一 scope。
- 转移 worktree 写 Owner 必须维护 `C:\Users\Steve\.codex\worktree-handoffs.json`（源/目标 `threadId`、任务名、绝对路径、branch、HEAD/Base SHA、PR/CI、证据、处置状态；不得含凭据）。目标 Chat 取得真实 `threadId` 并回读确认 worktree/branch/唯一写 Owner 后，才把目标标 `KEEP_ACTIVE`，再将源标 `REVIEW_AFTER_MERGE`、`REVIEW_EMPTY_PATH` 或继续保护。先读并保留他人条目，写后 `ConvertFrom-Json` 验证，禁止整文件盲写。
- 登记表不替代 `origin/main`、Git、PR/CI、`ACTIVE.md`，也不是删除授权；`KEEP_ACTIVE` 是硬保护。清理 worktree 须用户明确要求，并实时核验路径/HEAD、无修改与未跟踪文件、无进程/锁/ACTIVE/open PR，且以 fresh `origin/main` 祖先关系、merged PR 或补丁等价证明已入主线，任一项不成立即保留。

## 开发流程

- 代码改动前先 `git worktree list` 和读 `docs/dispatch/ACTIVE.md`；只读/调研/纯文档不新建 worktree。`origin/main` 与 ACTIVE 是状态真值，旧文档/会话/feature worktree 不得覆盖。
- 窄范围排查：先读 handoff、目标模块、已有 helper；禁止默认全仓扫描、整段 CI 日志倾倒、重建已有 E2E 脚手架。同一 worktree 的 Maven 目标测试顺序执行。

## UX Flow Gate

低技术素养角色（操作员/仓管/质检员）的 RN 屏幕、或报工/入库/出库/盘点/质检/扫码收货/发货功能，提出方案或实施计划前必须调用 `ux-flow` skill（触发词见 `.agents/skills/ux-flow/SKILL.md`），「UX Flow Analysis」缺失不得进入实施规划。
