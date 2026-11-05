# 餐饮运行栈清理

用户于 2026-11-05 明确要求删除全部餐饮专属代码和入口，食品工厂复用逻辑先迁出，完成测试后交付 PR。Owner：01a1a954-e8fa-7a10-8eda-3f5ba0872102。

本次独立分支 `codex/restaurant-complete-removal-20261105`，主线 Base `41f58a822cf3a739db8b9c2a8c1ea281522d4913`，叠加既有平台安全候选。Java、Python、Web/RN 和 ops 四个独立 scope 已登记；跨目录集成及 PR 由根协调负责。

## 旧待办退役

`UX-RN-RESTAURANT-PROCUREMENT-DELIVERY-20260802` 的餐饮 OTA/设备验收按用户全删除指令终止，已从 ACTIVE 移除。终止不等于曾完成线上验收，不恢复其功能或发布任务。

## 边界

- 工厂 BOM、工厂领料、供应商到货和历史损耗凭证读取继续保留；共享代码迁入工厂或中性模块。
- 不删除真实工厂、业务数据、既有备份或已应用的 Flyway 迁移。持久化旧枚举仅作读取兼容与拒绝墓碑，不再提供餐饮业务入口。
- 历史报告、截图及官网已经退役的旧 URL 跳转不代表运行能力；其他 AI 配置不在修改范围。
- 食品 DAG、缺料排程及验收组当前实现保留各自 Owner，不把清理扩大为这些业务的重写。

## 当前交付状态

已删除独立餐饮演示应用及专属 bootstrap/seed、根目录餐饮测试入口，里程碑提交 `3f7195b2b3`。其余迁出、入口和专属实现删除正在实施，尚未宣称全部完成；最终测试、PR、部署及业务验收分别记录，不相互替代。

## UX Flow Analysis（ux-flow 门控产出，不可删除）

### 用户画像

仓管员 — 以单号、供应商、实物数量核对收货，减少角色试错和记忆路径。采购、财务人员 — 按既有业务权限查看供货与处理价格异常，本次不扩大创建或审批权限。

### 用户旅程

| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|------|---------|---------|---------|
| 1 | 工厂工作台或仓库中的供货入口 | 打开待验收入库 | 进入迁出的工厂供货列表，显示单号、供应商及状态 |
| 2 | 当前已加载数量和总数 | 查看详情或继续加载 | 页面与服务端1-based页码一致，第51条以后仍可访问 |
| 3 | 当前账号可执行的操作 | 有仓库写权限时创建或识别供货单 | 路由、按钮与后端权限一致；采购只读上下文不提供无法执行的创建操作 |
| 4 | 单号、供应商、数量及单位 | 核对后提交 | 复用既有边界校验、等待态与重复提交防护；失败保留输入并给出可恢复操作 |
| 5 | 价格异常单、审批状态和原因 | 有审批权限时处理 | 精确匹配工厂角色和后端权限，原因通过 `comment` 完整送达审计 |

### 摩擦点清单

| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|-----------|---------|---------|
| F1 | 迁出后仍调用旧域或不可解析的页面 | HIGH | Rule 5 / 错误恢复 |
| F2 | 只读采购上下文显示无法创建的按钮 | HIGH | Rule 1 / 当前步骤信息 |
| F3 | 审批驳回原因字段错配导致审计缺失 | HIGH | Rule 2 / Rule 3 |
| F4 | 列表只有前50条且无后续入口 | HIGH | Rule 5 / 信息密度 |
| F5 | 页面残留餐饮角色与场所文案 | MED | Rule 2 / 上下文 |

### 每个摩擦点的设计回应

- F1 → 供货专用客户端绑定真实 `/warehouse/supplier-delivery-notes`，以请求契约及导航解析验证接线，其他库存接口保持各自域。
- F2 → 按真实仓库写权限控制创建入口，保留只读列表/详情，后端继续独立校验。
- F3 → 保留既有原因控件与业务校验，修正字段传递并测试完整审计文本；不自行放宽审批条件。
- F4 → 最小续载使用服务端 `totalElements`，显示真实加载状态和重试操作，避免空白或虚假“全部加载”。
- F5 → 显示工厂单号、供应商及明确角色名称；复用现有 Paper 交互和安全区，检查44pt触摸目标及相邻间距。

## 原安全链交付及发布窗口释放

PR #1451、#1457、#1438、#1475 已合并；原安全链部署 commit ecfb04cbac6283ac4e1b70592f4643be5feb44b8，Python 官方部署及 Java/Web 统一入口均成功。Java/Web 回执 `cretas-1793867215-605.json`：DEPLOY_EXIT=0、RELEASE_FINAL_STATUS 恰好一次、build reused。平台 AI 正向200、F006普通工厂阴性403、业务/删除/确认写入均0。完整餐饮删除仍在独立分支，不能把这次安全发布说成全量删除已上线。main/发布准备窗口已于16:35释放；本批无生产数据删除。

归档已完成的旧锁行：

- `SEC-PLAN-AUDIT-MAIN-FREEZE-20261105` — `in-progress` — Owner 01a1a954-e8fa-7a10-8eda-3f5ba0872102 — Base 0b2bdd6b8844ff865f898bcea1cea763d6442d76 — main合并冻结；1438纳入新增main后最终全量/构建/发布中。其他Owner可分支开发测试，暂不合并/推main或发布；已有有效发布锁/原子操作优先。解除条件：本组发布回执或明确交接。
- `SEC-PLAN-AUDIT-1438-20261105` — `review` — Owner 01a1a954-e8fa-7a10-8eda-3f5ba0872102 — Base a294f1078d02d33d76a3065a4a81fcf2e34790d1 — #1438迁移升V20261105_91、平台AI账号限流；scope grant迁移/平台AI controller及ContractTest/保密文档；独立PG/Maven+Python；不写生产。

主线汇合保留验收组在飞任务，补充归档已发布 #1475 后台退役旧登记（原安全链 release receipt 同上）：

- `SEC-RESTAURANT-BACKGROUND-20261105` — `review` — Owner 01a1a954-e8fa-7a10-8eda-3f5ba0872102 — Base e40ee26673ae6df68f503aecc0664f903186dd9b — scope Python main lifespan/专项tests；删除餐饮预约模拟与默认ETL写入任务，保留其他工厂生命周期。25目标通过；Python CI后供原安全发布前置，不清生产数据。

## 汇合检查点

主线 `91bfa2c9b42ac5e98ca67b02e0de6686339b5913` 已合入清理分支。合并保留两个独立 PG 选择器：PurchaseAutoSuggestion 与 SupplierDeliveryNativeQuery。下列已冻结子切片由 root 统一持有最终验证/PR scope，细节迁出 ACTIVE（状态仍 review，未声称已合并）：

- `SEC-RESTAURANT-CHAT-20261105` — `claimed` — Owner restaurant_chat/root — Base 41f58a822cf — scope smartbi/api/chat.py及专属回归已冻，续food_kb全目录及其专属tests；清餐饮RAG/guard/分类/入库入口，保留factory/OCR/通用食品知识。Python Owner只管其余闭包/registry；四代码执行者隔离。
- `SEC-RESTAURANT-FACTORY-COMPAT-20261105` — `review` — Owner factory_compat/root — Base 41f58a822cf — scope FactoryKpiPanel及其测试(当前)、Finding/Navigation、平台菜单/角色模块/Interview目录、PermissionService仅模块注册、Attachment权限及对应tests；移除餐饮注册并保留工厂能力。root串行Maven权限/启动；禁改Orch/SSE/Supplier/SmartBI/Canvas，详见归档。
- `SEC-RESTAURANT-FACTORY-STORE-20261105` — `claimed` — Owner restaurant_ops/root — Base 41f58a822cf — scope Python eval/store.py、validation.py、__init__.py及agent_eval旧trace/service/factory/http tests；清餐饮corpus/旧trace，恢复factory ACL/503正向，pytest相关集合。禁止service/runner/legacy/PG测试；独立目录产物，冻结释放。
- `SEC-RESTAURANT-PYTHON-SURFACES-20261105` — `claimed` — Owner restaurant_chat/root — Base 41f58a822cf — scope POS/twodfire闭包、canonical/dish_*、共享队列dish分支、shared诊断及chart-insight API/service/相关tests；保留工厂上传/通用队列/finance/租户。main注册由root移除；禁止AgentOps/legacy/CI/Java/UI。
- `SEC-RESTAURANT-PYTHON-DOMAINS-20261105` — `claimed` — Owner restaurant_ops/root — Base 41f58a822cf — scope canonical/entity_resolution、knowledge/loader、shared除diagnostics及smartbi_compat/_rbac_strip.py的餐饮分支与相关tests；先核factory消费者，删dish/restaurant配置入口，保留通用实体/工厂配置/ACL；禁改main/AgentOps；pytest对应集合。

## PR 1479 修复与跨组边界检查点

候选分支 `codex/restaurant-complete-removal-20261105`，推送检查点 `980ff20b28a22b83dced2e1a1c2f1d7183082e1b`，PR #1479 仍为 draft。Java/native PG、Python完整集合及RN/Web门禁修复未完成，不能将目标通过写成最终绿。先前 Web 根项目 `vue-tsc --noEmit` 未检查 references 下 app；以实际 CI `vue-tsc -b --force` 为准，其失败正在修复。

`V20261105_92` 候选已明确放弃：照片哈希继续保留原全局唯一约束，查询按厂隔离；不通过放宽DDL损害旧制品回滚兼容。当前候选和已汇合main均无92，本批不依赖92，不占用该迁移序号。93/94由相关Owner交付前按实际冻结版本/main/线上重新排序。

验收组e976 FULL-UI原子片段结束前，本组不合main、不发布；无release lock占用。食品组只读整合 Attachment 与供应物料接口，本组保留附件权限迁移及 SupplierMaterial 服务唯一写 Owner。真实 SupplierMaterialService/Impl 当前在 `service/supplier`，并无已落地 inventory 迁移；五个公开方法签名未变。食品拥有 `MaterialReferenceWriteGuard`/Repository/Purchase/Workflow，本组待其冻结接口后接Supplier create/update，在事务内且所有关系写入之前取得相同排序raw-material锁并复核active/mergedInto；不得各自实现不兼容锁序。无生产数据删除、无新生产业务写入。
## Python 冻结验收

2026-11-05 完整 CI 选择集合实际执行：5983 passed、0 failed、183 skipped、82 deselected，428.42秒，commandExit=0、own PostgreSQL stopExit=0。既有ci-gate-excludes及跳过条件未扩展。1264文件冻结快照SHA256 `80bda23a50c305d49a5d13e68008fa6554c0174971e7366493a8cb554dcd5da9`；证据 `python-full-retirement-v7.log/.xml`、`native-pg-20261105-201651-321-88576/result.json`（本机私有证据目录）。v6中断无最终结果，未当通过。

真实shared tenant解析拒绝缺失/NULL/未知metadata，四HTTP入口均503且handler零调用；明确FACTORY/HQ/CENTRAL保留。历史14个精确R_*_REAL与MOCK_REST/DEMO_REST在API/engine前拒绝，保留R_FACTORY_CUSTOM等合法工厂ID。KPI/保密fixture显式提供FACTORY metadata，保留admin价格可见、warehouse null、调用者Bearer及保密产品排除计数断言；周期比较[30,34]/实际参考线与缺值负向分别保留。上述为Python测试证据，不是Java/Web全绿、部署或线上业务验收。
## Supplier 冻结与餐饮 Java 收口

食品第二次冻结 `eb908e5550c653ab2aedeea938348377e95f6bcb` 的 Guard/RawMaterialTypeRepository 两文件已完整接入，签名 `lockActive(String, Collection<String>)` 不变。新增 native scalar fresh-state 查询实际在 PostgreSQL 执行，拒绝一级缓存仍active而数据库已inactive的物料。旧31a的6PG不作为新版SQL证据。

SupplierMaterialService create/update先锁raw；SupplierMaterialPurchaseSpecService create/update先捕获父引用、锁raw、悲观刷新父关系并核对factory/supplier/active/deleted/materialId，再读取/写入spec且核同ID。锁序raw→Supplier父关系→spec，不追加raw→PO/workflow。父实体消失转409 MATERIAL_REFERENCE_CONFLICT。独立冻结commit731c05ee09516f151dbf1306d5cd03565f6a4cb5，主PR精确取入070af768e7。

原生PG v2：11真实PG +15规格单测+15主关系单测，共41，0fail/error/skip，commandExit=0、own stopExit=0、productionAccess=false。覆盖新版原生查询字段/租户/删除过滤、一级缓存旧active、主关系和采购规格两入口等merge锁后拒绝写、旧报价不变、父关系重定向零新增。独立风险复核无新P0/P1，7文件内容哈希一致。

餐饮Java v11完整目标960项出现10fail/2error，不能报通过；生产源码终审无新P0/P1。失败为工厂夹具缺少新增准入metadata、旧角色遍历把退休角色当可授权，以及新测试无用stub，保持原业务/provider断言修复。仅重跑5失败类，完整结果与最终PR CI待回执；不扩基线。Python/UI已冻结证据复用，不因文档重跑。

当前main/生产HOLD未解除，无发布锁，不合main、不部署；本批未做线上验证或生产业务写入。旧制品回滚可能恢复餐饮入口，服务恢复和退役验收须分别判断。