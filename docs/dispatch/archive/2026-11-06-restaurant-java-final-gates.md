# 餐饮退役 Java 最终门禁修复

任务 SEC-RESTAURANT-JAVA-20261105，PR #1479；Owner 为方案审计组。代码在独立 codex/restaurant-complete-removal-20261105 worktree，未写主仓工作树。生产发布与合入 main 仍 HOLD，不持 release lock。

## 实现

- 工厂库存汇总与异常行动建议沿用直接接口 inventory 权限；库存工具、生产 runtime policy、descriptor 三方精确一致。真实 Registry 阳性与 warehouse-only 用户拒绝测试覆盖接线。
- 工厂保留的 SupplierDeliveryNote/WastageRecord 增加实体级 SQLDelete；两个实体无 Version，删除 SQL 各只绑定一个 ID。保留实体级 Where。新 PG 测试实际 remove、原生回读 deleted_at，并清空 JPA 缓存后验证 Repository 不可见；子项历史级联行为未更改，未宣称子项保留。
- 原餐饮审批审计测试迁为 FactoryWorkflowAuditRepositoryQueryValidationTest，保留真实 Hibernate 查询启动、审批审计持久化及八张业务表无变化断言。
- 删除已不存在的餐饮类、字段与角色清册登记，不吸收新软删除缺陷。保留历史演示身份只读禁写 fence，断言餐饮 demo 登录配置不存在。精确 override 数量随新增权限声明从 177 到 178，其他计数和安全阈值不变。
- Supplier Hint 单独交付源为 323f1db35c50dcbcd4dbf4d11179e5206749d8f6；本分支已精确集成 19923121a6。仅补下一步 hint 与拒写分支断言，锁序/409/code 未变。
- V20261105_92 已放弃并释放。本批无 DDL、生产删除、真实业务审批或新设备验收。

## 验证与限制

证据根目录为本机 cretas-restaurant-removal-evidence-20261105；完整日志不入 Git。

- v20 34 项中 33 过/1 Demo 旧断言失败；失败未记通过。修正后 Demo 五项于 v22 通过。
- v21 新 Mockito 重载匹配编译失败、零测试；独立 PG stopExitCode=0。改为显式 anyString 后复验。
- v22 九类 48 项、4 个 Gateway 描述/fixture 失败。真实 PG 软删除一项通过，commandExitCode=1/stopExitCode=0/productionAccess=false；不将此轮整体称为成功。
- v23 三个受影响 Gateway 类 19 项全部通过，退出码 0、零失败/错误/跳过。结合未变内容 v20/v22 XML，本轮 14 类 65 项取得最终目标通过证据。
- Supplier 独立 Spec 单测 15 + 真实 PG 11 + 409 Hint 契约 5，共 31 项通过、独立 PG 正常停止。食品可精确取入冻结源，不等待本 PR。
- 前候选 7e704084fc 的远端 PG 门禁 342 项零失败/错误、2 既有跳过；全量 Java 有 12 个归一化失败，本轮按根因修复，不扩失败基线。最终提交仍须重新取得必需 CI，通过前保持 draft。

本批实现、目标测试、commit/push/PR、部署、线上验证和业务验收是独立状态。尚未合入 main、部署或进行本批线上业务验收。

## 后续 main 整合

main 后续合入 #1477/eacf526 的 F006 工厂演示沙箱，导致共享台账冲突。本组在本地 f70060f7e5 整合，保留 main 已交付代码原样，餐饮退役实现不变；仅移除已交付的 BACKGROUND 僵尸台账行，不更改其他 Owner 记录。新 main 的五个 Java 文件不由旧候选 ca3d 的 CI 代为背书。

本地 v24 五类 42 项全部通过、零失败/错误/跳过：SandboxGateway 23、真实 JWT/Sandbox 4、Demo 只读 5、平台角色边界 2、AI 数据权限 8。Node 只读入口/写请求拦截契约 4 项通过。两组测试均未访问生产。原 ca3d 的 PG 最终为 343 项、零失败/错误、2 既有跳过；其 full 作业取得自然终态后再集中推送整合候选并核验最终 CI，不取消在跑的全量。

ca3d 全量自然终态为 19,432 项观测：19,120 通过、2 失败、310 跳过。剩余两个失败均已按根因修复：ApprovedToolSourceMetadataTest 为库存汇总精确使用 inventory 两码，其他两工具四码不变；软删除唯一性分类登记保留照片身份不释放。后者先用生产 `BEGIN READ ONLY` 查询 pg_index，确认 supplier_delivery_notes_photo_hash_key 为有效全量 UNIQUE(photo_hash)、无部分谓词，再在独立 PG 验证软删后跨厂同 hash 仍报 23505。本次不放宽索引、不恢复 V92、不改失败基线。

v25 三类五项（两个失败契约及新增 PG 断言）全部通过、零失败/错误/跳过，commandExitCode=0/stopExitCode=0；生产业务写和 DDL 均为零。完整失败 artifact 与只读索引证据已保存到外部证据目录。

## SOP main 整合与权限前置修复

推送 b120 后 main 合入 #1485/095558 的 SOP 兼容层，PR 冲突使该 head 未触发 CI，不能称其 CI 已运行。本地 561a585e44 整合：保留 main PG 选择器完整前缀及两个 SOP 闸，再追加本组三个 PG 闸；保留餐饮拒绝和 SOP 默认禁写。候选继承 main 的 V94，餐饮清理自身没有新增 DDL/V92；后续发布须遵守该迁移及兼容回滚门禁。

v26 整合验证 102 项观测、零失败/错误，1 项真实 OSS opt-in 因明确关闭而跳过；自有 PG 正常停止。其中 V94 真实 PG 迁移、Attachment JPA 启动/持久化以及照片身份保留均实际执行，其余 Spring/JVM 断言不冒充真实 PG。

独立复核发现 incoming main 的 listByEntity 在动态权限/工艺保密检查前查询附件，已改为先授权、后查询，返回映射与原策略不变。v27 Controller 13 + 真实 Spring/H2 21 共 34 项全部通过、零跳过；新断言覆盖跨模块权限通过粗 OR 后被动态实体权限拒绝时零查询、受保护 SOP 拒绝时零查询及允许路径查询一次。源码终审无残余 P0/P1，未新增或恢复餐饮入口。

## 1fa2 完整通过与最终 main 整合

PR #1479 的 1fa2d237587ef9d6c6157a6009602f3775af67c8 已取得十个工作流成功。Java run 42081341602：full-suite 共 19490 项（19177 通过、313 跳过、零失败/错误，失败基线零），PG 346 项、零失败/错误、2 既有跳过，随后迁移和 committed JDBC 校验成功；fast-contracts 因 full-ready 跳过，不计作通过。完整 artifact 已保留。

最新 main 的 #1488 成本修复、#1491 验收归档、#1492 Web 演示及 #1493 已完成后台任务归档已经正常整合。v28 成本三类目标测试 60 项全部通过，零失败/错误/跳过，退出码零；成本生产源码和新增契约与 main 一致。餐饮退役源码未再修改，验收 S6-10 本人签署 blocked 记录原样保留，未代签。

新整合 head 按实际 CI 重新取得必需门禁，不把 1fa2 通过冒充新 head 通过。03:23 已释放本轮 main 短窗口，未持 release lock，未部署或进行本批线上/业务验收。全部临时 PG 已正常停止；V92 仍放弃，组合 main 的 V94 门禁保留。
