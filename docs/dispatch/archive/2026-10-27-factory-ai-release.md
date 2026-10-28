# FADREL27 — 工厂 AI 发布与只读验收

2026-10-27 最终集成登记：成本恢复 PR1243 已合并2536e9d454b49751657b535db798e89851851082，八类27项测试及全部适用CI通过。最终 Java 发布 Base SHA 为9178dc7769b5211735e958d50443277cc5d0da5c，仅选择 Java；最终后端树3afe5abe1b168a017a447b22cd2d9ba52814467b需要一次集成构建。完整成功生产回执cretas-1793108236-61056.json已证明9178的Java409依赖在线，因此可先发无接口新增的Python共享上下文服务，再完成独立成本修复Java发布。原生OTA已发布且真机轴标签验收通过，不再重复发布。下文待执行状态是阶段历史，最终交付以随后完成的结构化回执与验收报告为准。

当前状态 in-progress；Owner Root DIRECT。以下新进度优先，后附早期记录仅作历史。仅F006只读验收，不自动填写手机凭据，不外发分享文件，不补造生产事实。

已发布基础：PR1230（b97c81ae6f37cbbe05f56ee637ac52d8ee5c4e13）可信问答/图表/明细/快照与跨表能力，PR1234（9c4888f011a571cac6e8b1cd83593b72c6bc7650）产品名/限制分类/原生摘要折叠，PR1232（0c893cc2d790de791da6c7c5c310a2761c024f4e）Web背景写隔离/产品与期间保留。全部适用CI通过；网页基线两场景PASS、blockedMutationAttempts=0、actualBusinessWrites=0。原生基线问答/质量追问/明细/Excel-PDF-PPT分享入口已通过，未选择收件人。旧成功和失败证据均保留。

PR1240已合并705d5fd1cece6607b193fdba372c382f410c1b13：双worker共享PG对话上下文、actor/RLS/TTL/lease/CAS/容量，409不触发共享熔断。全部适用CI、Python263、真实PG两进程2项（1deselected/0skip）、Java七类25项、独立风险复核通过。旧后端树a913a42ca6a2c9e03fc73a3cd55177175f132ea9、JAR SHA256 0e98187a4639d0f83a7d48b22abc33be232dd457d0678fda957b456cbc75de64；官方候选预上传成功（cretas-1793103050-50699.json、exit0/FINAL1）。Main推进后旧制品被树校验安全拒绝，不代表当前Java/Python已发布。

PR1241已合并5f1db65adc35b7f4f59427c12139df220d41f7da：原生value轴标签避让；Canvas8/TypeScript/CI通过。官方OTA source705d5fd、runtime1.0.5/production、timestamp1793104272185、updateId a8fbd5d4-005f-ec9e-6406-ccaafa4f5ac0，exit0。JS本地/清单/CDN解压SHA256均为b18bd69dd59c8ba947efc7aa8653db4511f33015f45129ae53215552803612f1；40项资源仅launch bundle逐字节校验，其他资源未逐项验字节。F006真机SKU产品名和数值轴不重叠已实际确认。

新依赖P1：PR1237成本不完整标记在联产未知→已知重算时未清，实体生命周期继续清空已知成本。Python保持NULL并显示缺口，不补0。FADCOSTREC27仅补known分支清false与同实体真实恢复测试，Base e20c39173039dcd53409e8a56b296f4a58fb941d，无Entity/Repository/迁移或生产数据写。独立源码复核通过；最终八类制品测试/CI/合并/服务发布待完成，见同日factory-cost-recovery归档。

发布边界：Java在Python共享上下文之前；Python官方入口执行明确版本V20261101_20（独立短期表，无业务回填）。发布锁占用时等待，不删活锁/终止其他任务。已按规则执行一次预热，运输不可达跳过，不重试。其他Owner成功同版本回执仅在source/tree/制品与完整健康证据相符时复用，不重复已覆盖的服务检查。

待复验：新服务发布后的Web/原生连续问答、产品澄清继续、明细和导出；canonical MCP单会话、业务写0。线上联合正向样例按最新F006实际合格数据判断；缺完整关联记UNVERIFIED_DATA_NOT_ELIGIBLE，不把缺口提示冒充正向通过。当前完整状态/截图在本机ignored .playwright-mcp/smartbi-factory-ai-20261027/与test-output/，初始未提交笔记另有ignored副本。

## 早期原始记录（历史）

状态：in-progress；Owner Root DIRECT。

集成 Base SHA：`9c4888f011a571cac6e8b1cd83593b72c6bc7650`；候选 `9447468a8731a881d5d8e03c7d34a03d746e2247`（PR1232 head a3bce29d8f 与该 Base 的 GitHub 合并候选）。本批统一构建/部署仅选择 Web，不能使用旧 Base 5338 扩大成发布后续他人的 Java/全租户迁移。

生产发布 Base 预登记：`8daedc655bade306a6c962e50e82ca16dbf974d1`。后续安全主线已由其 Owner 发布，官方回执 `cretas-1793092550-33040.json` 为 deployed/exit0：active blue10010、Java 树 9684758712a5238e89c46ff4763d7c813ba860b9、后端健康与网页四方哈希通过。本批只复用依赖证明；合入 PR1232 后再次核验实际父提交与 Web 树，再从 clean exact main 仅发 Web。

范围：PR1230 的已发布 Java 问答入口、PR1234 的 Python/原生修复、PR1232 的 Web 修复、官方发布与 F006 只读网页/真机验收。本档只记录发布证据，不修改发布脚本、权限、业务数据、Entity/Repository 或迁移。前置 WIP PR1233 的 Java/迁移由其发布 Owner 负责，本批只读取其完整回执，证明合并后的网页调用已有匹配后端；不占用、不终止其发布进程。

已确认：PR1234 全部适用 CI 通过并合并；Python 官方发布 exit0、7 个源码 SHA256 一致且服务启动晚于文件；原生官方 OTA exit0、清单与本地 bundle SHA256 一致，设备已观察到新摘要折叠控件。PR1232 最终适用 CI 全通过、目标门禁/父页 23 项通过；原分支 Web 候选构建通过，需重建含 WIP 两处网页改动的最终集成 Web 树。

验收：仅 F006，业务查询、明细与导出；不自动输入手机凭据，不选择分享收件人。网页只读 harness 必须实际业务写入 0、被阻止写入尝试 0。真实多表正向样例缺配套生产完工与质检数据时明确未验收，不补造事实。

收尾须记录准确 merge/source、Web 发布 Base、官方回执、四方哈希、Python 与 OTA 指纹、网页及原生实际覆盖和未验证项；文档归档与产品发布是独立事实。
