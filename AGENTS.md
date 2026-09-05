# AGENTS.md

## Codex 自动执行路由

- 默认由 GPT-5.6 Sol Medium 作为工作型协调者，收到任务后立即做最小充分检查并继续执行；模型与 effort 在内部静默选择，不先向用户展示推荐、要求切换或等待确认。
- 首次修改前必须在内部选择 `DIRECT`、`DELEGATED`、`PARALLEL` 或 `HANDOFF`。一个代理可以连续完成窄范围定位、实施和目标验证，且没有真实并行收益、上下文噪声隔离或独立复核需求时，默认 `DIRECT`；不要为了形式编排把同一条链拆给多个代理。
- 只有委派能实质缩短墙钟时间、隔离大量搜索/日志/测试输出、提供独立证据，或把明确且可逆的实现交给更便宜模型时，才使用 `.codex/agents/` 中的窄职责代理。协调者保留需求澄清、scope/共享状态、接口裁决、diff 审查、最终验证、合并和发布责任，不做只派单不判断的纯 Organizer。
- 原因明确时可直接派 `cretas_executor`，不要固定先开 Scout；只有真实未知可以独立调查时才使用 `cretas_scout`。高噪声且可独立运行的确定性测试交给 `cretas_test_runner`，高风险终审使用 `cretas_risk_reviewer`；短小目标测试留在当前代理，避免额外交接成本。
- `.agents/skills/model-effort-router/SKILL.md` 仅作静默路由；只有用户明确询问模型/effort 或要求证据比较时才给一次简短建议，并在同一轮继续执行。

This file provides guidance to Codex when working with this repository.

## Project Overview

**白垩纪食品溯源系统 (Cretas Food Traceability System)**

| 组件 | 技术栈 |
|------|--------|
| **后端** | Java 21 + Spring Boot 3.2.12 + PostgreSQL + JPA (Hibernate 6) |
| **前端** | Expo 53+ + TypeScript + React Navigation 7+ |
| **AI服务** | Python + LLM API |

---

## Quick Reference

### 端口配置

| Service | Port | URL |
|---------|------|-----|
| React Native | 3010 | `http://localhost:3010` |
| Cretas 后端 (Java) | 10010 / 10020 | prod 蓝绿槽交替使用；开发机经 SSH tunnel 访问，public direct access is closed |
| Python 服务 | 8083 | `http://localhost:8083` |
| Embedding 服务 | 9090 | gRPC |
| Mall 后端 | 8080 | `http://139.196.165.140:8080` |
| PostgreSQL | 5432 | `localhost:5432` |
| Redis | 6379 | `localhost:6379` |
| FRP | 7501 | 内网穿透 |

### 测试账号 / 凭证
真值**不进 tracked 文件**。无论当前仓库可见性如何，所有 tracked 内容一律按公开仓库标准处理。真值保存在主工作区的以下 gitignored 文件；新 worktree 中通常不存在，应从主工作区只读使用，禁止复制到 tracked 文件：
- `.codex/rules/aliyun-credentials.md` — 阿里云 AK/SK、服务器 SSH/宝塔、安全组操作
- `.codex/rules/db-credentials.md` — 数据库 / 服务密码
- `.codex/rules/test-credentials.md` — 测试账号 + E2E 约定

格式占位见 `.env.test.example`。**禁止把这些真值复制进任何会提交的文件。**

---

## Development Commands

### 前端 (React Native)
```bash
cd frontend/CretasFoodTrace
npm start                    # Start Expo
npx expo start --clear      # Clear cache
```

### 后端 (Java - Spring Boot)
```bash
cd backend/java/cretas-api
mvn clean package -DskipTests    # Build
mvn spring-boot:run              # Run locally
```

### 后端 (Python - FastAPI)
```bash
cd backend/python
pip install -r requirements.txt  # 安装依赖
uvicorn main:app --port 8083     # 启动服务
```

### 部署到服务器

GitHub 不可用时按 `.codex/rules/worktree-and-main-only-deploy.md` 的离线协议执行（本地 `main` 汇合、detached worktree + `SKIP_GIT_CHECK=1` 部署、恢复后各分支走 PR）；GitHub 可用时下方常规规则生效。

```bash
# 正常 Java/Web 发布优先使用统一入口；Base SHA 必须来自 dispatch 登记
# 候选 worktree 只构建可信制品：
./scripts/deploy/release-cretas.sh \
  --phase build \
  --base-sha '<登记的 Base SHA>' \
  --tests '<本次目标测试>' \
  --stage-backend YES-STAGE  # 仅当用户要求合并后部署；只预上传，不安装/重启/切流

# 合入后【先预热】，不能省：把 CI 制品的跨境运输挪出发布窗口。幂等，可丢后台。
# 不预热则构建段 204s(本地 fallback) vs 25s(命中 CI 制品)，且 200s+ 构建期间常有
# 并发 session 推进 origin/main → 构建全成功却被 exact-main 复检整体作废。
# --tests 与下一步同选择器或更窄（判据是集合包含）。看到 PREWARM=done / already-warm 再往下。
./scripts/deploy/prewarm-main-artifact.sh \
  --tests '<本次目标测试>' \
  --wait 420

# 合入后在 clean exact origin/main worktree 自动检测范围、复用 manifest 并安全串行发布：
./scripts/deploy/release-cretas.sh \
  --phase deploy \
  --base-sha '<登记的 Base SHA>' \
  --tests '<本次目标测试>' \
  --confirm-prod YES-PROD

# 成败判据只看这一条：DEPLOY_EXIT=0 且日志里 RELEASE_FINAL_STATUS 恰好出现 1 次。
# RELEASE_FINAL_STATUS 不出现本身就是失败信号（收尾 printf 在 set -e 下被掐掉）；
# 后台任务通知里的 exit code 不可信。被 exact-main 闸拒绝时是【静默】的：
# 构建 BUILD SUCCESS、测试全绿、然后戛然而止，回执里 deploy_mode=none。
# 处理：git fetch && git checkout --detach origin/main 拉最新重跑即可。

# `release-jar-manifest.sh`、`deploy-backend.sh`、`deploy-web-admin.sh`
# 保留为明确的单组件发布和故障排查入口；不要由 Agent 自行拼接成普通全栈发布流程。

# 或使用 `deploy-backend` skill
```

**发布输出里这两条是预期的，不是异常**（2026-07-30 起）：

- `Compiling 1 source file` — 测试编译只编 `--tests` 命中的类（原先编全部 1146 个测试源，耗 41.4s 只为跑一个类）。不传 `-Dcretas.testIncludes` 时仍是全编，即旧行为。若命中不到目标类，surefire 找不到测试会**直接失败**，不会产出跳过测试的制品。
- `切换后健康轮次 1/3` — 切流后稳定观察由 5 轮改为 3 轮（覆盖 ~36s）。**auto-rollback 未改动**：任一轮非 200/active 仍会切回旧 upstream 并从备份恢复 jar。

**从 GitHub 取制品送上服务器**（`GitHub → 东京 Lightsail → 上海 OSS → ECS 内网`，制品字节完全不经过本机）由 `scripts/deploy/Publish-GitHubArtifactViaLightsailOss.ps1` 编排。⚠️ **2026-07-31 起它已是常规发布的默认路径，不再是旁路**（此处原写「不加速常规发布」，已随 PR#2061 作废）：`release-cretas.sh` 在 `--prefer-ci-artifact`（默认开）下调用 `release-ci-artifact.sh`，后者调用这个编排器复用 CI 已构建的 JAR，命中时 Java 侧**零次 Maven**（实测构建段 204s → 25s）；Web 侧同理走 `release-web-ci-artifact.sh`。命中的前提是制品已产出，所以合并后先跑 `prewarm-main-artifact.sh`（发布与 E2E 去重复 第 1 条）。未命中才回退本地 Maven 构建 + rsync delta（那条路 delta 实测 9.6×、只传 ~20MB，而 OSS PUT 无 delta 必传全量 —— 两条路各自占优的场景不同，不要据此认为本地构建总是更快）。手工传 `-AssetId` / `-ArtifactId` 直接调 ps1 仍是排查与人工回退用途。输出里 `transport_verified` 与 `deployable_trust_verified` 是两件事，后者只在 manifest 的 tree 与测试选择器都对得上时才为 true。详见 `scripts/deploy/RELEASE-ARTIFACT-TRANSPORT.md`。

---

## Server Operations

- `47.100.235.168`：Java、Python、Embedding、PostgreSQL、Redis。
- `139.196.165.140`：Nginx 网关、Web Admin、Showcase、Mall。
- 生产 Java 使用 `10010`/`cretas-backend` 与 `10020`/`cretas-backend-green` 蓝绿交替；任何检查或操作前先读取真实 upstream，禁止按固定槽位判断 active。

### 只读状态检查
```bash
ssh root@139.196.165.140 "cat /www/server/panel/vhost/nginx/_upstream_cretas.conf"
ssh root@47.100.235.168 "systemctl status cretas-backend cretas-backend-green cretas-python cretas-embedding --no-pager"
ssh root@139.196.165.140 "systemctl status mall-backend --no-pager"
```

常规发布和切流必须使用本仓库发布入口；手工重启仅用于明确的故障处置，并先确认 active 槽位。

---

## LIUSHANMEN VisionLab 双机计算规则

1. **固定分工**：加拿大 `canada-mini`（Apple M4 Pro 64GB）负责历史原图去重、候选检测、困难样本挖掘和批量推理；上海 Windows/RTX 3060 负责正式 YOLO 训练、ONNX 导出、保护集与独立正常集验证，以及模型上线判断。不要因 Mac 统一内存更大就把正式训练默认迁过去。
2. **主机身份必须先验明**：加拿大计算机只使用 SSH alias `canada-mini`；`codex-mac` 当前指向旧 M1 MacBook Pro，不得作为 LIUSHANMEN 图库或挖掘目标。每次新建或恢复通道先核对硬件、可用磁盘和目标根目录，再传业务照片。
3. **原图一次驻留、派生物不跨洋复制**：保留上海 `D:\CretasVisionLab\raw` 原件不变，首次按内容哈希分片同步到 Mac；后续只增量同步新原图。不要传整个 `datasets/`、`runs/` 或其他可重建派生目录，目标机按清单重建。人工标注仍是真值，任何挖掘输出都不能覆盖原图或人工 JSON。
4. **传输默认直连且可恢复**：业务照片经 Tailscale SSH/SFTP 直连，使用可断点续传的 `reput` 或等价分片机制；每个分片传前后记录字节数和 SHA-256，断线只补缺失字节。不要用普通 `scp` 承担整库或长时间大包传输。2026-08-31 实测直连 RTT 约 223ms，补传 224MiB 约 86 秒；该读数只作容量规划，执行前仍需短探针复测。
5. **GitHub 只传代码和非敏感制品**：GitHub/Actions/Release 可用于版本化脚本、清单和约 19MB 模型权重，不得作为工厂历史原图、人工审核照片或长期 VisionLab 数据集的中转存储。跨洋链路异常时也不得通过提交原图到仓库来绕过。
6. **环境锁定后才能产出正式候选**：Mac 挖掘环境的 Python、PyTorch、Ultralytics、图像预处理参数、模型哈希和命令行必须写入回执，并与上海本轮认可的锁定版本一致；版本不一致时只能做速度基准或探索，不能直接比较训练指标、生成正式训练清单或进入推广门禁。
7. **Mac 输出不等于可上线模型**：Mac 只返回内容哈希清单、候选 JSON 和少量选中图片；上海必须重新验证输入哈希、任务/图片去重、人工 review 状态、保护集、独立正常集、ONNX parity 和延迟。任一门禁失败均保持生产模型不变。

---

## Architecture

### 后端目录结构
```
backend/
├── java/                          # Java 服务
│   ├── cretas-api/                # 主后端 (Spring Boot, prod 端口 10010/10020)
│   │   └── src/main/java/com/cretas/aims/
│   │       ├── controller/        # REST API
│   │       ├── entity/            # JPA 实体
│   │       ├── service/           # 业务逻辑
│   │       │   ├── impl/          # Service 实现
│   │       │   └── skill/         # Skill 编排层
│   │       ├── ai/                # AI 意图系统
│   │       │   ├── tool/          # Tool-Skill 架构，Spring 自动注册 ToolExecutor
│   │       │   │   ├── ToolExecutor.java
│   │       │   │   ├── AbstractBusinessTool.java
│   │       │   │   ├── ToolRegistry.java
│   │       │   │   └── impl/{domain}/  # 按领域分包
│   │       │   └── dto/           # AI DTO
│   │       ├── repository/        # 数据访问
│   │       └── config/            # 配置类
│   └── embedding-service/         # 向量嵌入服务 (gRPC, 端口 9090)
│
└── python/                        # Python 服务 (FastAPI, 端口 8083)
    ├── main.py                    # 统一入口
    ├── smartbi/                   # SmartBI 数据分析模块
    │   ├── api/                   # API 路由
    │   └── services/              # 业务逻辑
    └── efficiency_recognition/    # 人效识别模块
        ├── api/                   # API 路由
        └── services/              # VL 分析服务
```

### 前端结构
```
frontend/CretasFoodTrace/src/
├── screens/       # 页面组件
├── components/    # UI 组件
├── services/api/  # API 客户端
├── store/         # Zustand 状态
├── navigation/    # 路由配置
└── types/         # TypeScript 类型
```

### API 路径

**Java 后端 (prod 10010/10020)**
- 基础路径: `/api/mobile/*`
- 认证: `/api/mobile/auth/*`
- 业务: `/api/mobile/{factoryId}/*`

**Python 服务 (8083)**
- 路由真值以 `backend/python/main.py` 注册结果和运行时 OpenAPI 为准；不要在 AGENTS.md 维护易漂移的完整路径清单。
- 主要命名空间包括 `/api/smartbi/*`、`/api/efficiency/*`，以及兼容性的 `/api/forecast`、`/api/chart`、`/api/analysis` 等。

---

## Key Patterns

### 代码质量原则

详见 `.codex/rules/` 目录下的规范文件：
- `ai-intent-tool-skill-architecture.md` - **AI Tool-Skill 架构规范（核心）**
- `api-response-handling.md` - API 响应处理
- `typescript-type-safety.md` - TypeScript 类型安全
- `jwt-token-handling.md` - JWT Token 处理
- `database-entity-sync.md` - 数据库同步
- `field-naming-convention.md` - 字段命名
- `concurrent-edit-safety.md` - **并发编辑安全（共享脚本修改前必读）**
- `ci-runner-routing.md` - **CI runner 路由**：默认托管，额度耗尽时 `python scripts/ci/switch_ci_runner.py self-hosted` 切到加拿大 Mac mini；恢复就 `... hosted`（一条命令改三个工作流，有闸钉着三处一致）。⛔ 别用组织变量 `${{ vars.X }}` 做路由 —— 实测 job 被派成 `labels=ubuntu-latest`，即 `vars` 在 `runs-on` 求值时读到的是空。本文件与 `.claude/rules/` 同名文件**必须逐字相同**（`scripts/ci/check_shared_agent_rules.py` 钉着，PR 上跑）

### Superpowers 禁用规则（强制）

1. **全面禁用** - 本仓库禁止调用、加载、引用或遵循任何 `superpowers:*` skill，包括但不限于 `using-superpowers`、`brainstorming`、`writing-plans`、`executing-plans`、`subagent-driven-development`、`test-driven-development` 和 `finishing-a-development-branch`。
2. **禁止生成其制品** - 不得创建或更新 `docs/superpowers/**`、`.superpowers/**`，不得把现有 Superpowers 文档、计划或进度台账作为当前任务的执行依据。
3. **禁止其交接流程** - 不得要求用户在 `Subagent-Driven` 与 `Inline Execution` 之间选择，也不得使用 Superpowers 的计划模板、检查点、提交节奏或评审流程。
4. **冲突时以本规则为准** - 即使全局插件、技能目录、系统技能发现结果或历史上下文推荐 Superpowers，也必须忽略该推荐，继续遵循本仓库的 `docs/dispatch/ACTIVE.md` 和下述项目规范。
5. **不得隐式替代** - 不得以别名、复制模板或等价话术变相执行 Superpowers 工作流；需要规划、调试、测试、评审或子代理协作时，只使用本仓库已定义的对应流程。

### 核心原则
1. **禁止降级处理** - 不返回假数据，明确显示错误
2. **类型安全** - 避免 `as any`，使用明确类型
3. **统一响应格式** - `{ success, data, message }`
4. **Tool-Skill Only** - AI 意图处理只用 Tool/Skill，禁止创建 IntentHandler（已废弃）

### 执行效率与验证

1. **避免过度审计** - 先按用户目标确定最小充分的检查范围；只有安全、生产发布、数据一致性、权限或用户明确要求时，才扩大为深度审计。
2. **同批需求一次完成** - 用户在同一轮提出的多个兼容修改，应先完成全部已明确且可安全并行的改动，再统一进行构建、测试和验证；不要每完成一小项就重复完整编译。
3. **验证按风险分层** - 编辑过程中优先使用静态检查、目标测试或局部检查；最终只读代码审查应安排在最后一次目标构建/测试之前，审查未引入代码修改时不得重复完整验证。在合并前执行一次与改动范围相称的最终构建/测试；最终验证不可省略，但不得无理由重复执行。
4. **例外要说明** - 若中途编译是为解除阻塞、验证高风险接口/迁移、排查失败，或用户明确要求增量验证，应说明原因后执行。
5. **Repository 查询启动门禁** - 修改 Spring Data Repository 方法、`@Query`/JPQL/HQL、Entity 字段或枚举映射时，必须新增或更新真实 JPA Context 测试（优先命名为 `*RepositoryQueryValidationTest`）并运行通过；Mockito、纯编译、`-DskipTests` 或仅 Service 单测不能证明查询可在 Hibernate 启动期解析。
6. **禁止绕过未完成的 JPA 门禁** - 涉及上述范围的 PR，CI 中 `JPA repository query startup gate` 必须完成并通过后才可合并。CI 因基础设施异常无法完成时，只有在同一 commit 本地运行等价真实 JPA Context 测试并取得成功证据后，协调者才可说明原因并请求/执行管理员合并；没有该证据不得以紧急发布为由绕过。
7. **闸的命名约定（决定它跑不跑）** - CI 在 push 上只执行一个窄选择器（`ci.yml` 的 `TARGET_TESTS`）。**新写的闸必须以 `*ContractTest` 或 `*StartupGuardTest` 结尾**，否则它只会被编译、不会被执行 —— 「CI 绿」对它等于没有背书。判据是机械的：*凡是会去读 `src/main` 下源码的测试，类名必须落在选择器的匹配面里*，由 `GateSelectorCoverageContractTest` 钉住（存量 29 个已冻结成棘轮，只许变短，⛔ 不许把新的塞进那份名单）。
8. **别拿 CI 绿当未执行测试的证据** - 说「这条测试进 CI 了」之前，先找到会执行它的那一行 `run:`，并确认它的 `if:` 与触发事件。仓里多数 workflow 是 `workflow_dispatch`（不会自动跑），`daily-integration.yml` 亦然；Java 侧除选择器命中的那些之外，**没有任何定时全量**。需要背书就用 `release-cretas.sh --tests` 把它挂进制品。

### 开发流程去浪费

1. **Worktree 生命周期** - 代码改动前先检查 `git worktree list` 和 `docs/dispatch/ACTIVE.md`；只读、调研、纯文档任务不新建 worktree。默认不自动删除；用户明确要求清理时，仅删除路径已核验、工作区干净且已由祖先关系、补丁等价或 merged PR 证明进入 `origin/main` 的 worktree。
2. **窄范围排查** - 先读取当前 handoff、目标模块和已有测试 helper；无命中才扩大搜索。禁止默认全仓递归扫描、整段 CI 日志倾倒或重建已有 E2E 脚手架。
3. **真值与交接** - `origin/main` 和精简 ACTIVE 是当前状态真值；本地旧文档、旧会话和 feature worktree 不可覆盖它们。完成项进入带日期归档，ACTIVE 不累积历史。
4. **测试与浏览器纪律** - 同一 worktree 的 Maven 目标测试顺序执行；优先复用 helper。浏览器/设备验证须隔离配置并限制并发；没有文件、截图或断言进展时停止或切到更快的 Expo/Web 路径。
5. **发布证据复用** - 统一入口已经调用 `scripts/deploy/verify-release.sh` 并把只读证据写入结构化回执；回执完整且成功时不得再手工重复相同 upstream、systemd、健康和 Web 哈希检查，只补任务特有且回执未覆盖的断言。回执缺失或失败时才单独运行验证脚本；不得以脚本退出码替代真实线上验证。
6. **受控无 PR 快速通道** - PR 仍是默认路径。只有用户明确要求“不做 PR/直接发 main”、本批只有一个协调者且已在 clean `codex/*` worktree 完成 scope 审查和目标验证时，才可使用 `scripts/deploy/publish-main-fastlane.sh` 快进推送。必须锁定登记的 Base SHA、在同一最终 commit 归档 ACTIVE 并释放 scope、推送前重新 fetch 且证明 `origin/main` 未前进；禁止 force push。非 docs 批次必须传准确的 `--task-id <本批任务 ID>`：ACTIVE 常驻数十条他人在飞任务，不传时门禁要求「全局零未完成任务」，该条件实际上永远不成立，会把每一次合法直推都拒成 PR；`--task-id` 只检查在飞区是否仍有匹配该 ID 的未完成行，使无关任务不再阻塞。脚本会要求该 ID 以词边界形式真实出现在 `docs/dispatch/`（ACTIVE 或归档），拼错或不存在的 ID 会被拒绝而非静默放行（PR #1962）；但脚本只能证明 ID 存在，不能证明归档内容正确，调用方仍须在同一 commit 归档自己的批次并释放 scope。脚本任一门禁失败即回退为一次 PR，不得手工绕过。迁移、Entity/Repository/Security、共享发布脚本等高风险 scope 默认仍走 PR；只有用户明确授权且必需深度门禁已通过时才可传入高风险覆盖口令。合入 main 与生产部署仍是两个独立状态。

### 子代理协作约束

1. **单一台账写者** - 只有当前协调者可以修改 `docs/dispatch/ACTIVE.md`；子代理不得直接修改台账，只能回传结构化回执。
2. **派工前置条件** - 每个交给代码子代理的任务必须先登记唯一任务 ID、`Base SHA`、允许修改的文件/目录 scope 锁、Owner 与验收命令；没有 scope 锁不得启动代码子代理。`DIRECT` 任务不为形式登记子任务，但仍须遵守现有 worktree、scope 和验证门禁。
3. **固定状态机** - 任务状态只使用 `queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。合并完成时，协调者必须在同一收尾中将任务归档并释放 scope 锁。
4. **结构化回执** - 子代理完成或阻塞时必须返回：任务 ID、base SHA、实际修改文件、验证命令与结果、commit/PR、阻塞原因、下一动作。协调者在 commit、合并或发布前核对适用回执；缺少这些字段不得进入 review，也不得用口头“已完成”替代证据。
5. **弹性 WIP** - 不限制一个任务累计使用的代理数量，默认并发为最多 2 个代码执行代理和 2 个只读/测试代理，并按波次回收已完成线程。实际并发不得超过当前运行时可用槽位，也不得为了占满槽位拆分没有独立价值的工作。
6. **临时扩容** - 当任务具备明确且互不重叠的文件、生成物、构建目录和命令资源 scope，且已登记任务 ID、Base SHA、Owner、验收命令、扩容理由和释放条件时，可提高到最多 4 个并发代码执行代理；只读/测试代理使用剩余运行时槽位。出现共享文件、lockfile、迁移版本、同一 Maven `target/`、接口未定或合并冲突风险时立即串行化。
7. **单层编排** - 子代理不得生成孙代理。发现新的独立工作流时返回 `EXPANSION_REQUEST`，由协调者静默决定是否开启下一代理或下一波次，无需用户重复确认。
8. **上下文节制** - 默认使用 `fork_turns="none"` 加热上下文包；强耦合任务最多携带最近少量回合。完整历史只允许一个接管/诊断代理使用，且不得多个完整历史代理并行。任务包至少包含目标、已确认事实、Base SHA、scope、禁止范围、入口、验收命令和停止条件。
9. **避免交接浪费** - 清晰任务由一个执行者连续完成窄范围定位、实施和目标验证；不得固定执行“Scout→Analyst→Critic→Integrator”链。只有歧义、独立证据或高风险审查真实存在时才增加角色。
10. **验证责任分层** - 执行子代理只跑改动范围内的目标测试；协调者核对 scope、diff、commit/PR 和证据。普通任务不固定增加 Reviewer；只有权限、迁移、生产、数据一致性或跨模块高风险任务才追加深度终审。

### 跨 Chat 与长上下文

1. 遵循全局 `~/.codex/AGENTS.md` 的 compaction 安全交接规则；当前 Chat 是阶段协调者，不是永久 Organizer。持久真值放在 `AGENTS.md`、精简 `ACTIVE.md`、Git 和结构化测试/发布回执，不依赖长对话记忆。
2. 同一原子操作继续留在当前 Chat；阶段转换、独立结果或长上下文在安全检查点使用桌面版新 Chat。上下文重置优先创建新 Chat 并发送精简 handoff，不用复制完整历史的 fork 来节省 token。
3. 用户已对当前 Goal/任务明确授权直接交接时，协调者可在安全检查点创建并发送新 Chat；没有该授权时只提供可复制 prompt。创建仍在准备时不得把 `clientThreadId` 当作可用 `threadId`。
4. handoff 必须包含目标/完成标准、阶段、已确认事实、仓库/worktree/branch/Base SHA、实际修改、验证结果、未完成项、权限边界、禁止重复的昂贵操作和下一动作。新 Chat 必须重新核验仓库与运行时真值。
5. 新 Chat 接管后，旧 Chat 不得继续写同一 scope。测试、构建、CI 等待、部署/切流、迁移、事务、生产写入、回滚、合并中间态或仍在写共享 scope 时不得交接，先取得明确结果或安全停止点。

### Worktree 跨 Chat 交接登记（强制）

1. 跨 Chat 转移 worktree 写 Owner 时，源协调者必须维护本机登记表 `C:\Users\Steve\.codex\worktree-handoffs.json`，记录源/目标 `threadId`、任务名、worktree 绝对路径、branch、HEAD/Base SHA、PR/CI 状态、保留证据与处置状态；登记内容不得包含账号、令牌、密码等凭据。
2. 仅取得 `clientThreadId` 不算完成交接。必须等目标 Chat 取得真实 `threadId`，并回读确认目标 worktree、branch 和唯一写 Owner 后，才能把目标标为 `KEEP_ACTIVE`，再将源标为 `REVIEW_AFTER_MERGE`、`REVIEW_EMPTY_PATH` 或继续保护。源 Chat 随后不得继续写同一 scope。
3. 目标 Chat 完成或再次交接时，必须更新最终 HEAD、PR/merge/CI 状态、证据路径和 disposition。编辑登记表时先读取并保留其他任务条目，写后用 `ConvertFrom-Json` 或等价方式验证，禁止整文件盲写覆盖并发记录。
4. 登记表只表示主机上的交接与保护状态，不替代 `origin/main`、Git、PR/CI 或 `docs/dispatch/ACTIVE.md`，也不构成删除授权。`KEEP_ACTIVE` 是硬保护信号；`REVIEW_*` 只允许清理任务优先审计。真正清理仍须实时核验绝对路径、worktree/branch/HEAD、工作区无修改和未跟踪文件、无进程/锁/ACTIVE/open PR/受保护证据，并以 fresh `origin/main` 的祖先关系、merged PR 或补丁等价证明改动已入主线；任一项不成立即保留。

### 发布与 E2E 去重复

1. **统一发布入口** - 预期合并后立即部署时，合并前在候选 worktree 跑 `./scripts/deploy/release-cretas.sh --phase build --base-sha '<dispatch Base SHA>' --tests '<tests>' --stage-backend YES-STAGE`（只构建 + 预上传，不安装、不重启、不切流；ACTIVE 归档与 scope 释放进同一个最终 commit）；合并后先 `./scripts/deploy/prewarm-main-artifact.sh --tests '<同一选择器>' --wait 420`，看到 `PREWARM=done`/`already-warm` 再从 clean exact `origin/main` 跑 `--phase deploy --confirm-prod YES-PROD`。成败判据只看 `DEPLOY_EXIT=0` 且 `RELEASE_FINAL_STATUS` 恰好 1 次；回执 `build="reused"` / `build_count=0` 是正常成功；deploy 阶段不比对测试选择器，宣称「本次 selector 已门禁」前读 manifest 的 `target_tests`。开始生产发布后不再合入无关 PR，直到回执完成。
2. **不要手工拼发布** - manifest / tree-cache 复用、SHA-256 与 JAR 完整性、exact-main 复检、Web dist 缓存与四方哈希、蓝绿切流观察与自动回滚都由脚本强制：不复制、不削弱、不手工重做。`deploy-backend.sh` / `deploy-web-admin.sh` / `release-jar-manifest.sh` / `release-web-manifest.sh` 只作单组件与排查入口；缓存或校验失败只回退一次本地构建，不 retry；禁止为等 CI 制品原地空等（prewarm 只轮询不触发 CI）。默认安全串行；并行发布仅限两侧任意切换顺序均 API 兼容的独立发布，且显式 `--parallel-if-independent YES-INDEPENDENT-SERVICES`（或 `deploy-cretas-parallel.sh --confirm-prod YES-PROD --confirm-independent-services YES-INDEPENDENT-SERVICES`）；跨端接口、迁移、认证契约一律串行。
3. **蓝绿槽位** - 见 Server Operations 节：部署 / 核对前读真实 upstream，禁止假设某槽永久停用；相同制品 no-op 仍要读 upstream 并验证 active systemd 与健康。
4. **问题前移** - SKU、Workflow、报工单位或历史快照改动，合并前必须覆盖生产形态数据，包括旧 `g/box/case`、中文基本单位和多包装规格。
5. **生产验收责任** - Codex 常规发布默认完成服务级验收（真实 upstream、systemd/端口、直连健康、网关 HTTP、Web 四方哈希；`verify-release.sh` 回执完整且成功时不重复手工检查）。完整线上只读业务验收与 F006 只读 UI E2E 由用户/QA 执行，用户明确要求时才由 Codex 运行，且生产只读模式的业务写请求必须为 0。脚本/选择器失败与产品失败分开记录；共享前置条件未变时只重跑失败场景。
6. **全量 CI 分层** - 改动相关目标测试和必需门禁必须通过；已确认的全量测试基线噪声单独治理，不用重复本地全量构建代替，也不隐瞒其状态。
7. **生产 Web Playwright 唯一入口** - 生产只读验收用 Codex 直接集成的 Playwright MCP 以 filename 方式加载 `scripts/e2e/production-readonly/mcp-entry.js`，同一次验收复用一个干净 UI 登录会话；MCP entry、Node CLI、本地 fixture 与 CI drift gate 共享同一套 `core/`、`scenarios/`、before-send mutation guard、证据 schema 与脱敏规则。禁止临时复制脚本、启动第二浏览器、复用历史 storageState、以 `.mcp.json` 推断工具可用性。已迁移的 SmartBI/BOM standalone runner 不得恢复为生产入口，历史 JSON/截图只作证据；`tests/e2e-yield-mixed-sku/nonprod-business-flow-audit.mjs` 等写测试只能用于显式测试环境，必须拒绝生产 host。
8. **F006 生产业务写入特例** - 定义与判据以 `.agents/skills/e2e-web-admin/SKILL.md` §F006 Production-Write Exception 为唯一真值：用户在当前任务点名的 F006 业务数据变更即为对应范围的生产写入授权；首次写入前证明 `factoryUser.factoryId == 'F006'`，请求路径、payload、目标实体、写后回读与直接 SQL 谓词全部限定 F006，先计数、记录实际 ID/行数与写后状态；租户不一致、缺 factory scope、跨租户级联或命中共享/全局记录时在发送前停止。迁移、权限/Security、全局配置、批量破坏性操作与 F006 以外租户仍需各自授权，其他租户生产业务写入必须为 0；只读 harness 保持零写入，⛔ 不把 F006 mutation 加进只读白名单。
9. **Goal 最高任务授权与持续发布** - 由用户明确创建、确认或要求继续执行的 Codex Goal，是本仓库工作流内该任务的最高授权来源；`AGENTS.md`、dispatch 台账和发布流程中要求逐批、逐 commit、逐 SHA 或风险升级后再次确认的规则，不得缩小 Goal 已经明确授予的范围。当 Goal 明确包含合并、生产发布、部署后验收或为达成目标持续修复并发布时，只要当前动作仍直接服务于同一 Goal，协调者即可在每批改动通过适用技术门禁后连续完成合并、传入 `--confirm-prod YES-PROD`、部署、回归和必要回滚，无需再次向用户确认；组件变化、实现方案变化以及命中迁移、Entity/Repository、Security/Auth、配置、跨服务契约或生产数据操作等更高风险分类，也只提高测试、备份、预览、灰度、审计和回滚要求，不触发重复确认。Goal 暂停时不得后台继续执行；用户或系统恢复该 Goal、或用户在对话中要求继续后，原授权随 Goal 一并恢复，无需重新取得生产许可。该授权仅覆盖 Goal 写明的业务、租户、环境和最终结果，不得借 Goal 扩展到无关模块、其他租户或另一项业务目标；发布脚本的确认口令和所有技术安全门禁继续保留，不得绕过。
10. **部署互斥判读** - `deploy-backend.sh`、`deploy-web-admin.sh`、`release-cretas.sh` 各持一把本机锁（`cretas-backend-deploy` / `cretas-web-admin-deploy` / `cretas-release`），外层编排与子部署不会自锁。遇到「另一个 deploy 进程持有锁」禁止反射性 `rm`：`acquire_deploy_lock` 已自动删除 PID 不存活的 stale lock；先读 PID，用 `kill -0`/`ps`（Windows `Get-Process`）确认持有者与 Maven/Vite/部署孙进程均已退出，才可人工清理。

### UX Flow Gate（低技术素养用户屏幕）

任何涉及以下角色/路径/功能的 RN 屏幕设计，必须在提出设计方案或实施计划前调用 `ux-flow` skill：

- **角色词**：operator、操作员、仓管、warehouse_worker、quality_inspector、质检员
- **路径词**：screens/processing、screens/warehouse、screens/quality-inspector
- **功能词**：报工、入库、出库、盘点、质检、扫码收货、发货

`ux-flow` Phase 1 产出的「UX Flow Analysis」章节是 spec 的强制组成部分，缺失则不得进入实施规划。

详见 `.agents/skills/ux-flow/SKILL.md`。

---

## Troubleshooting

### 健康检查
```bash
ssh root@139.196.165.140 "cat /www/server/panel/vhost/nginx/_upstream_cretas.conf"
ssh root@47.100.235.168 "curl -s http://localhost:10010/api/mobile/health; curl -s http://localhost:10020/api/mobile/health"
ssh root@47.100.235.168 "curl -s http://localhost:8083/health"
```

开发机访问 Java 后端请先读取 upstream，再把本地 `10010` 映射到当前 active 槽；例如 active 为 `10020`：
```bash
ssh -L 10010:localhost:10020 root@47.100.235.168
```

### 缓存问题
```bash
npx expo start --clear
```
