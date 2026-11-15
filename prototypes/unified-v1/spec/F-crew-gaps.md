# F 线 · 缺口清单（交付物 5，审计退回 F-3 后重写，2026-11-13）

原型里画了、但还没打通的地方。每条分两层说清：
- **正式后端**：去仓库只读核实的结果，路径都以 `my-prototype-logistics/backend/java/cretas-api/src/main/java/com/cretas/aims/` 为根，下文简写 `aims/`。
- **原型现状**：本原型（`accounts/unified-v1/src/crew/*`，19063）现在怎么处理。

结论分四类：
- **后端已有，原型未接**：排期只是对接。
- **后端部分有**：要补一段。
- **后端没有**：需新建。查证范围写在依据里，没查到不等于确定没有。
- **数据未配置**：后端有，六膳门没填。

11-12 初稿只在原型目录里查，就写了「系统没有产能、要新建」，这个结论不对，本次已逐条改正（F-3）。

优先级：
- **P0**：不补就讲不了「AI 办事、按办成的事收费」。
- **P1**：三条演示流程里露出来的。
- **P2**：体验和扩展。

## A. 计费、留痕与权限（P0）
| # | 原型画了 | 结论 | 正式后端查证 | 原型现状 | 建议 |
|---|---|---|---|---|---|
| A1 | 工作记录与额度：已办成扣 1 个额度；已交办、没办成、结果不明、原型演示都不扣（R26、R29） | **后端部分有** | 有 AI 调用配额 `controller/AIQuotaConfigController.java`、`service/AIQuotaRuleService.java`（按调用次数计），还有智能分析的计费配置 `entity/smartbi/SmartBiBillingConfig.java`。没查到「按办成的业务事项」记账的实体或接口（查过 `grep -r "Billing\|Quota\|Usage.*Controller"`） | 记在数字班组自己的保存区 `unified-v1/private/f-crew/<工作区>/<会话>.json` | 在现有配额 / 计费底座上加「办成事项账本」：事项类型、业务对象、执行接口、回读凭证、状态（已交办 / 已办成 / 没办成 / 结果不明）、额度 |
| A2 | 「已办成」必须回读核实；交给人办的先记「已交办」，任务完成才算（R29） | **原型已做；后端无对应** | 后端没有「交办 → 完成」回写数字员工记录的机制 | F 后端读 E 保存区：任务「已完成」才转已办成，「已取消」转没办成（`f-api.cjs` 的 `syncRecords`） | 协同任务完成时回调办成账本 |
| A3 | 凭证要绑定本次请求，别的任务号不能拿来充数（F-9） | **后端部分有** | AI 工具网关有幂等键：`ai/tool/gateway/DefaultToolExecutionGateway.java:113`（idempotencyPolicy / idempotencyKey）。协同任务原型（E 线 `tasks/save`）没有幂等键 | 已补：建任务时写 `origin.ref = "F:<事项 id>"`；登记结果时 F 后端核对，不匹配就记结果不明 | 写接口统一返回「凭证 = 记录 id + 请求幂等键」 |
| A4 | 确认人要有权限，计费记录记真人（F-9、F-14） | **后端已有，原型未接** | 正式接口用 `@RequirePermission` 校验，例如 `controller/UrgentInsertController.java:44`、`:68`（`scheduling:read_write`、`production:read_write`） | 权限只在页面判断；F 后端 `inbox/confirm` 只看页面传来的 `perm`，**服务端不校验**。确认人已改为记登录人：`users/current` 的姓名加账号；用演示身份时标「演示身份」 | 确认接口在服务端按登录人校验模块权限，确认人从会话里取，不信页面传值 |

## B. 写接口（P1）
| # | 原型画了 | 结论 | 正式后端查证 | 原型现状 | 建议 |
|---|---|---|---|---|---|
| B1 | 急单：核对物料、产能、原有安排，确认后生效 | **后端已有，原型未接** | 急单插单整套接口 `controller/UrgentInsertController.java`，挂在 `/api/mobile/{factoryId}/urgent-insert`：`POST /slots`（找插单时段）、`POST /slots/{slotId}/analyze`（影响分析）、`POST /confirm`、`POST /force`、`/slots/{slotId}/select`、`/release`、`/statistics` | 六膳门工单在黑湖开（R23），原型只建协同任务交生产主管，记「已交办」 | 对接方向：数字计划员调 `slots` 加 `analyze` 出安排草案和影响说明，人确认后调 `confirm`。六膳门开单仍在黑湖，所以这条先在味美或 Cretas 自有工单上落地 |
| B2 | 一线称重报工 | **原型已有接口，F 未接** | — | A 线 `a-api.cjs` 提供 `POST weighins/save`；手机端只出预览，确认后记「原型演示，未执行」，不扣额度 | 直接接 `weighins/save`，回读用 `weighins` |
| B3 | 仓管建入库批次 | **原型已有接口，F 未接** | — | C 线 `supply-batch.cjs`；F 只做了「核实原料库存」协同任务 | 同上 |
| B4 | 质检员记检验单 | **原型已有接口，F 未接** | — | E 线 `inspections/save`；F 只做了「质量异常」协同任务 | 同上 |
| B5 | 任务负责人是「数字员工」 | **原型没有** | — | E 线 `e-api.cjs` 的 `cleanAssignee` 遇到 kind=agent 报「指派给 Agent 本轮只做占位，不能保存」 | 协同任务支持数字员工当负责人 |
| B6 | 入职确认后工序写进工艺 | **后端已有，原型未接** | 对话建厂：`blueprint/interview`、`blueprint/workflowassembly`、`blueprint/canonical/FactoryReadinessService.java`、`FactoryBusinessReadinessReader.java` | 只保存班组配置和入职记录；工序在「工序配置」页由人保存 | 入职页接 blueprint interview 和 readiness，上岗条件直接用 readiness 报告 |
| B7 | 协同任务类型用「急单」「费料核对」（F-16） | **原型没有对应类型** | — | E 线任务类型固定 5 类：质量异常、首件确认、设备故障、缺料跟进、其他（`task-types.cjs`）。急单、费料核对只能记「其他」；仓管核库存用「缺料跟进」，质检用「质量异常」 | 请 E 线增加「急单安排」「费料核对」两类，或开放类型配置 |

## C. 数据与计算（P1）
| # | 原型画了 | 结论 | 正式后端查证 | 原型现状 | 建议 |
|---|---|---|---|---|---|
| C1 | 急单核对「产能」 | **后端已有；六膳门未配置，原型未接** | 产品日产能 `entity/ProductType.java:77-86`（`dailyCapacity` / `dailyCapacityUnit`，APS v2 人工日产能）；产线产能配置 `entity/ProductionLineCapacityConfig.java`（表 `production_line_capacity_configs`），接口 `controller/LineCapacityConfigController.java`（`/scheduling/line-capacity/capacity`、`/equipment`）；排产接口 `SchedulingController` 里用到 `DailyCapacityRequiredDTO` | 页面写「六膳门没有配置这款产品的日产能（正式系统有产能配置和急单插单分析，原型还没接）」，只给近 14 天实际单日产出作参考（按快照日，最多 6,507.07 kg，11-05） | 六膳门在正式系统里补日产能，原型接 `line-capacity` 和 `urgent-insert/analyze` |
| C2 | 急单核对「物料」 | **数据问题** | — | 缓化料 BOM 7 项原料在黑湖库存快照里全是 0，但近 14 天有 11 天在生产，判「不能判断，交仓库核实」 | Cretas 入库批次上线后再核；库存补充按来源原则由人填 |
| C3 | BOM 多投入的替代料 | **后端已有；黑湖数据没有** | 替代料 `controller/BomRecipeController.java:119`（`GET /{recipeId}/substitutes`），服务 `service/bom/BomItemSubstituteService` | 黑湖 BOM 只有用量和损耗，没有替代组；7 项都是 1:1、损耗 0，识别为「疑似替代料」，不相加 | 六膳门在 Cretas 配置替代组（补充来源），原型接 substitutes |
| C4 | 出成（费料）的依据 | **数据问题** | — | 用黑湖报工良品算「后道 ÷ 前道」。R30 起先排除数据疑点：出成 ≥ 99.5% 的像没称重直接录入，> 105% 或 ≤ 30% 的不像同一物料，单位填错的不算，相邻两单跳变超过 15 个百分点的也算疑点。疑点单列「数据疑点 · 找车间核」，不上首页头条 | 称重报工上线后改用称重的投入和良品 |
| C5 | 费料金额 | **后端未核实** | 没查到工序级材料成本（未逐个查成本服务，待 B 线确认） | 只算多用多少 kg（7.86 kg），不算钱。成本快照 218 张工单：材料成本 > 0 的 137 张；为 0 的 49 张（其中 48 张是来源单价为 0），按未知处理；未算出 28 张 | 领料按工序记，才能做工序级材料成本 |
| C6 | 工序超时 | **数据未配置** | — | 不判断，只说明「没有标准工时」 | 在「在制品与效率」补标准工时；称重报工记起止时间 |
| C7 | 卤门腔出成标准 | **数据未配置** | — | 黑湖没有这款产品的工艺路线；入职时由人填一个标准，只进入职记录 | 在「出成标准」页维护 |

## D. 主动汇报、对话与入口（P2）
| # | 原型画了 | 结论 | 正式后端查证 | 原型现状 | 建议 |
|---|---|---|---|---|---|
| D1 | 定时工作、主动推送 | **后端已有底座，原型未接** | 通知 `controller/NotificationController.java`（`/api/mobile/{factoryId}/notifications`、`/unread-count`、`/recent`）、`InternalNotificationController.java`；定时任务 `config/SchedulingConfig.java`（全应用唯一的 `@EnableScheduling`）、`service/AIReportScheduler.java` | 打开页面时按快照现算，没有推送 | 把出成、库存扫描挂到定时任务，结果走通知推到对应岗位 |
| D2 | 自由对话理解 | **后端已有，原型未接** | 能力包匹配 `controller/ai/FactoryCapabilityPackController.java`（`/ai/capability-pack/match`），AI 工具网关 `ai/tool/gateway/*` | 规则识别（急单、出成、库存、进度），不调模型 | 按员工能力加权限过滤意图，接能力包匹配 |
| D3 | 语音口述、按住说话 | **后端已有，原型未接** | `controller/VoiceRecognitionController.java`（`POST /api/mobile/{factoryId}/voice/recognize`） | 文字输入；麦克风按钮不录音 | 手机端和入职口述接语音识别 |
| D4 | 按登录人权限过滤；岗位只给一个入口 | **后端部分有** | 权限由正式后端 `@RequirePermission` 管；没查到「岗位 → 数字员工」的配置 | 当前账号读真实 `permissions/current`；岗位 → 数字员工的对应是代码常量（`crew-config.ts` 的 `DEMO_ROLES[*].mobile`），不是工厂配置（F-17） | 「岗位 → 数字员工」做成工厂级配置，放在设置下的数字班组配置页（R27） |
| D5 | 嵌入现有页面「交给数字 X」 | **原型组件已有，未接入** | — | `crew-entry.ts`，接入说明见 `F-crew-embed.md` | A–E 页面按说明接入 |
| D6 | 一线手机端 | **网页演示** | — | 375 宽网页；真实窄屏只显示手机屏本身。在统一外壳里打开，左侧菜单仍会挤占宽度（外壳是 D 的） | 落到 RN App |
| D7 | 味美工作区的演示 | **数据问题** | — | 味美只有本机练习数据；所有数字班组页面在味美一律显示「无数据」，不借六膳门的数（F-2） | 不需要后端 |
