# Dispatch 台账 — ACTIVE


旧页眉见 archive/2026-10-25-smartbi-catalog-closure.md。

**当前真值**：代码与合并读 origin/main；本页仅在飞摘要（每条≤300字、全页≤30000 bytes，check_dispatch_active.py校验）；历史/判据/增量见 archive/。

## 在飞任务




- `SMARTBI-F006-QA-20261028` — `in-progress` — Root；Base `96bf2707b860bb5892ee0735fc01e4faa1f11f68`；PR1267已部署；34场景与QA修复见archive/2026-10-28-smartbi-acceptance-fixes.md；F006 only。

- `ERP-S0S3-ACCEPTANCE-20261023` — `in-progress` — Owner Root/原QA — Base eb299396dc7c0a27ca22f24efe73112c2e130289 — PR1131；集成Base/范围/验收见archive/2026-10-24-s3-combined-release.md。


- `ERP-STAGES-20261019` — `in-progress` — Root/原QA — Base eb299396dc7c0a27ca22f24efe73112c2e130289 — S1 10/10；S0/S3续行，见archive/2026-10-23-s0-s3-acceptance.md。


- `F006-MOBILE-R33-20261004` — `queued` — Owner root — Base `a98b634d0936` — WHInboundDetail空价伪0/无闸；无仓库调用点，先核可达，再测权限/零空/owner；private同因扫描已记。
- `F006-MOBILE-R44-20261005` — `queued` — Owner root/只读追踪 — Base `3db04cc0a13a573a0c2646475b9645da0215acf3` — F006状态参数缺省/available/AVAILABLE三GET同200行含77已耗尽/用完。PageRequest有status但列表服务未用；库位/临期实际UI未复现；临期需保留EXPIRED，后续明确状态集及查询/JPA门禁。0变更。

- `F006-MOBILE-CAMPAIGN-20261003` — `in-progress` — Owner root/手机测试 — Base `b2c884402b` — #921/#923已合；18角色首页与162只读GET/日志核验，BOM跨收料人通过。APK18及老板Mini正常/忙碌/恢复已验；rex Canada永久sudo通过。设备QA900新增编辑已保存；R64参数/错误提示/详情修复本PR归档，待新APK实测。完整业务/QC/AI异常未全验。

- `CRETAS-ORG-20261003-ORGANIZER` — `review` — Owner: `01a10269-8fb3-7432-a2bf-89fde67ac88e` — Base `71b4e430cba9405a9a2d270214ea62e796d6d3f3` — 独占本批ACTIVE/archive；四修复合并、旧业务FAIL/pending保留；35e固定制品及新两题预算见archive。

- `CRETAS-ORG-20261003-G1` — `blocked` — Owner: `01a10277-0b68-7cb3-88ee-f221c9fce4ba` — Base `35e017dc526255c39956195a11b06b6c93916a31` — Java/Web固定包已验SHA，Web795资产一致；未安装，真实issuer/期限/身份与新QA资格仍待验。 详细scope见archive/2026-10-03-local-organizer-seven-sessions.md。
- `CRETAS-ORG-20261003-G2` — `blocked` — Owner: `01a10277-1213-7a60-b72d-55524e160840` — Base `e89e75057c2eab8d961010ae9a53f02442132aa6` — CUSTOMER03已提交不重放；原FAIL/pending/预算保留，身份与精确恢复入口缺失。 详细scope见archive/2026-10-03-local-organizer-seven-sessions.md。
- `CRETAS-ORG-20261003-G3` — `blocked` — Owner: `01a10277-13d1-7a82-a27d-1624f6538ddd` — Base `e89e75057c2eab8d961010ae9a53f02442132aa6` — 原RAW403/pending保留；身份与收货附件范围未绑定。 详细scope见archive/2026-10-03-local-organizer-seven-sessions.md。
- `CRETAS-ORG-20261003-G4` — `blocked` — Owner: `01a10277-1615-7932-b97e-883356d0c96a` — Base `e89e75057c2eab8d961010ae9a53f02442132aa6` — PR881已合并、代码scope已释放；BOMv2真链未验。 详细scope见archive/2026-10-03-local-organizer-seven-sessions.md。
- `CRETAS-ORG-20261003-G5` — `blocked` — Owner: `01a10277-181f-7f71-83ce-ea033d6d2876` — Base `e89e75057c2eab8d961010ae9a53f02442132aa6` — PR884已合并、代码scope已释放；标准22过，新版本真样本未验。 详细scope见archive/2026-10-03-local-organizer-seven-sessions.md。
- `CRETAS-ORG-20261003-G6` — `blocked` — Owner: `01a10277-199d-7fa2-b130-369d12f7e030` — Base `e89e75057c2eab8d961010ae9a53f02442132aa6` — PR882已合并、代码scope已释放；41目标过，无设备/业务新验收。 详细scope见archive/2026-10-03-local-organizer-seven-sessions.md。
- `CRETAS-ORG-20261003-G7` — `blocked` — Owner: `01a10277-1af9-7203-b775-96ff673b87f8` — Base `e89e75057c2eab8d961010ae9a53f02442132aa6` — PR885草稿hold；新MASS合同0/1/名称漂移，UNKNOWN未发、剩1暂停；原FAIL保留，来源/UI未验。 详细scope见archive/2026-10-03-local-organizer-seven-sessions.md。

每条 ≤ 300 字：ID / 状态 / Owner / Base / 一句话目标与当前阻塞点。详情锚点 = `archive/2026-09-05-active-entries.md#<小写 id>`。

- `OPEN-CODEX-PRS-20261003` — `queued` — Owner: Codex/owner — #862 五张旧销售报表 400/403 显示真实错误（web-admin，Draft，等 owner 发布）；#856 托盘训练排除照片不回流（只改 tools/，CI 只跑 2 道闸不覆盖 tools/，未训练未上线）。
- `FEAT-SEED-CONSUME-LIVE-CATTLE-20260915` — `in-progress` — Owner: sister(executor/Opus) — C1 种子缺口: 从不领用 6 个活牛批次 ⇒ 无 RAW_TO_PRODUCTION 边, 溯源无上游。加领用步骤(幂等) + SPECIES 改「牛」+ 终态断言(count=6/最远层全带个体/公开侧脱敏) + 本机两遍验收 + 变异。只到 PR。🔒 Scope: scripts/e2e/shenglong-demo/seed-cattle-demo.py
- `OPS-OTA-DEMO-BUNDLE-20260915` — `in-progress` — Owner: organizer — 🔒 两推均已核: 第 1 推 06:41 ts=1789425596125(#797); 第 2 推 07:05 ts=1789427030082(main `ef32c456ce`, 含 #798 RN), ota 域 manifest createdAt 23:05:44Z 含该 ts, CDN `index-41faa7f0….hbc` GET 6,764,861B/200。仅剩真机行为读数, 等 Steve 周二手机。
- `OPS-LINEAGE-FINISHED-ALIGN-AMBIGUOUS-20260915` — `queued` — Owner: 无 — 回填对不齐 7 条(F006 计划 8cf3fa56/ffeb2d34/40b47b4b 各多批次), 对齐器拒绝猜 ⇒ 这些成品批次无 PROD→FIN 边。要按批次号/时间二次对齐或人工映射, ⛔ 不猜。
- `DEMO-SHENGLONG-TENANT-SEED-20260915` — `in-progress` — Owner: Line B(executor) — Base `origin/main`(含 #791/#793) — 纯 API 演示租户种子脚本(建厂→默认用户→原料/产品/工序→族图 draft+publish→6 头个体+期初批次→计划/报工/成品→标签 traceCode→公开溯源 200), 幂等可重跑, 只在本地跑通; prod 执行归 organizer(🔒 prod 写)。
- `OPS-LLM-ROUTER-STALE-CHAIN-COMMENT-20260915` — `queued` — Owner: 无 — `llm_router.py` L1029 一带 `# Chain — 5 providers, all free:` 仍描述已不存在的链(#793 只订正了两处); 照它推理会得到自洽但错误的链形状。判据=注释与 `_build_chain` 实际输出一致。
- `OPS-LLM-REGISTRY-AUDIT-STALE-20260915` — `queued` — Owner: 等 Steve — `test_registry_audit_date_is_not_stale` 自 09-04 起红(`_REGISTRY_AUDIT_DATE`=08-13 + 21 天), main 的 Python Gate 因此常红; 修它需要一次真正的注册表复审(逐 (账号,模型) 探针), ⛔ 不许只推日期。
- `OPS-VL-SLOT-HAS-NO-PROVIDER-GATE-20260915` — `queued` — Owner: 无 — prod `llm_router` `SLOT.VL: []`(owner 08-09 拍板)而产品代码仍调 VL ⇒ 500; DashScope key 仅免费额度。要一道「被产品调用的 SLOT 必须有非空候选链」的闸, 并订正 `llm_router.py` 过期的 `aliyun_c→b→a` 注释。同根 `LLM-PROVIDER-CLIFF-20260815`。

- `FIX-LSM-SUPPLIER-PRICE-REQUIRED-20260901` — `in-progress` — Owner: `lucid-nobel-4a3795-7c` — 缺陷 16+17：`SupplierDetailDrawer.vue` 价格字段三处（关系表单/内联规格/独立规格）；`required` 是装饰，校验手写在 `saveRelation`/`savePurchaseSpec`。Owner 裁定：新增必填，编辑仅价格被改动时拦（存量 4 条空价）。未核后端/导入路径，需改后端 → 🔒 回协调者。
- `OBS-PRODUCTION-MANAGER-FALLBACK-DRIFT-20260901` — `queued` — Owner: 无 — `V20261030_11` 把 production_manager 的 analytics/report/procurement 收窄为 `-`，前端 store 与 Java fallback 仍 `r`（3×2=6 条陈旧），与 #441 同形但不在其 KNOWN_DRIFT 名单；只在 DB 不可达的 fallback 窗口显形。
- `FIX-QC-VERSION-OTA-APK-20260829` — `in-progress` — Owner: `/root` — Base `0f11d9a91b` — 质检员「关于/设置」版本号改读 APK 原生真值并补一致性闸；合入后向仍报 1.0.3 的设备发兼容 OTA，重建 1.0.4 签名 APK（保留最低版本门禁）。
- `HOTFIX-LIUSHANMEN-QC-CAPTURE-UPLOAD-20260829` — `in-progress` — Owner: `/root` — Base `0ed3f10da8` — Label QC 拍照本地压缩后立即添加；远程清晰度检查只作后台建议，5s 超时/断网/失败/拒绝均不删照片不阻塞提交，不伪称「已自动检查」。验收含 Android OTA 与真机上传回读。
- `ML-LIUSHANMEN-WORK-AREA-ROI-20260811` — `blocked` — Owner: `/root` — 正式 audit 因 `trayal_0e5a259f_030` unjudgeable fail closed；计划任务已迁到 clean exact-main 树（LastResult=0）。剩余仅该 ROI 人工裁决；模型/人工真值/原图/生产不改。
- `LLM-PROVIDER-CLIFF-20260815` — `blocked` — Owner: 等 Steve — 🔴 09-13 aistore 三条硬到期, 之后 7 个槽全只剩 `[deepseek, zhipu]` —— 而**这两个现在都是坏的**(401/429)。⚠️ 09-09 04:40 生产上**已真发生过一次** `All providers exhausted for review` ⇒ 09-13 后那次偶发会变成七槽常态。详见 `archive/2026-09-07-new-tickets-detail.md`
- `UX-RN-RESTAURANT-PROCUREMENT-DELIVERY-20260802` — `blocked` — Owner: `/root` — 实现已入 main，Expo Web 验收可核销；餐饮 RN OTA runtime 与真机验收未确认。实现 scope 已释放，如继续只新建 exact-commit 的 OTA/设备验收项。
- `SEC-CREDENTIAL-ROTATION-20260719` — `blocked` — Owner: `/root` — 仓内/服务器副本/DB/JWT/internal/BaoTa/OSS RAM 已轮换；仍需各控制台吊销旧 Aliyun 主账号 AK、旧模型供应商 key、微信长期凭证并验证失效。⚠️ 09-05 新增：47 宝塔面板口令曾在 tracked 文件与 git 历史，待轮换。
- `ENH-F006-MATERIAL-TAXONOMY-SUGGESTION-001` — `blocked` — Owner: `/root` — 分类建议契约测试已补（`MaterialTaxonomySuggestionContractTest` 10 条，在 CI 选择器内）。「Excel preview 复用」实测是未实现而非未证明（`suggestFields` 唯一调用点是 Controller），需 owner 裁定是否要接。无执行者。
- `ARCH-F006-UNIFIED-MATERIAL-RECEIPT-SOURCE-001` — `blocked` — Owner: `/root` — 两个真缺口已清零（`TransferServiceImpl` 补 `TRANSFER_IN` 来源；并行入库端点 `POST material-receipt` 已下线）并上闸 `MaterialBatchSourceDocContractTest`。待 owner 裁定 `InboundType` 死字段：接真实消费方还是删除；裁定前不扩枚举。
- `BUG-F006-PURCHASE-AP-PAYMENT-BOUNDARY-001` — `blocked` — Owner: `/root` — AP 核心与付款核销已在 main；「四维状态」疑即 `PayablePaymentStatus` 四值，需 owner 确认本条描述是否过期。付款条款里程碑/付款计划、三单匹配确实未实现（全仓 0 命中）。
- `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001` — `blocked` — Owner: `/root` — 净需求/客户来料/幂等/直接收料 fail closed 已有；未核销：严格同批 identity/单位/日期/QC/cost 匹配与独立库存事务验收。⚠️ `MATERIAL_REQUISITION`/`PRODUCTION_BATCH`/`CUSTOMER_MATERIAL_ARRIVAL` 不在 `validateSourceDoc` 的 switch 里，直接落库绕开守卫。
- `ENH-F006-SUPPLIER-IMPORT-001` — `blocked` — Owner: `/root` — 实现已在 main；两个测试类已改名进 CI 选择器并补 4 条幂等出口断言。未核销：状态生命周期、采购门禁、详情信息架构、Web 侧。⚠️ `suppliers` 成对重复列只有一半是活的（`contact_*`/`business_license` 全空），按字段名直觉写会写进死列。
- `BUG-F006-R2-SKU-UNIT-001` — `blocked` — Owner: `/root/supplier_frontend` — Web canonical 化已成熟且有 CI 背书；Java 净含量契约已补 `ProductTypeNetContentContractTest` 13 条（容量型绝不伪装为克重）。剩余未核销：历史回填。
- `FEATURE-F006-R2-WORKPROCESS-DEDUP-001` — `blocked` — Owner: `/root/workprocess_governance` — Java 治理契约已补 11 条；生产审计表 0 行、F006 重名 3 组 8 行疑似按产线拆分而非重复录入，治理前需 owner 判定。剩余：Web 治理界面、排序/单位移除、Flyway/JPA 验收。
- `FEATURE-F006-R2-BOM-SUBSTITUTE-001` — `queued` — Owner: `/root` — Java CORE 已归档；父任务的 Web/计划/领料/报工/成本/追溯快照未证明完成，由统一 Workflow/BOM 任务串行吸收，`NOT_DEPLOYED`。
- `QA-WAREHOUSE-STORAGE-UI-VERIFY-20260825` — `review` — Owner: `claude-code/warehouse-storage-ui-verify` — 第一批 PR #87 已部署复验；第二批（`codex/claude-warehouse-approval-closeout-20260826`）：App 端仓库地图入口、数量审批走通（凭证发号器无类型参数第 5 次必炸已修）、位置脏值清洗。未决：`ST-202608-C20566F3` 待上线后点「应用差异」。
- `LSM-WALKTHROUGH-ORGANIZER-LINE-20260902` — `in-progress` — Owner: `liushanmen-organizer-handoff-fa5fb5-0c` — 生产 = main（发 49 = #683）；OTA 推 #687/#688（标记 `common:pleaseSelect` 基线 0）。待 owner：lsm001 登权限矩阵页、zhipu 余额、DeepSeek key、prod 切 validate。
- `YIELD-STANDARD-LINE-20260905` — `blocked` — Owner: 无（f006 收工） — #594/#599/#600/#601 + **#612（发 36）** 已上线：头级出成率现在真的生效（此前被行级 100.00 哨兵门拦死，#599 三周一次没算过）。剩下卡数据：六膳门 8 配方出成率 0/8（张权填，填了即生效）、主料份额 1/12。F006 黄油鸡 100.00 已由迁移置 NULL，回滚项撤销。
- `OPS-CRON-ALERT-FILES-HAVE-NO-CONSUMER-20260906` — `blocked` — Owner: 无(owner 09-06 裁定暂不做, 留票) — 47 上 13 个 cron 脚本写 11 个 `*alert*` 文件零消费方。🔴 **09-10 兑现**: `llm-provider-health-daily.sh`(crontab `25 5 * * *`)09-09/09-10 连打 `LLM HEALTH DEGRADED` + 3 条 `PROVIDER EXPIRY DUE`, 全进没人读的文件 ⇒ 悬崖预警响两天没到人眼前。
- `ARCH-PERMISSION-ADMIN-L1-EDITOR-20260906` — `in-progress` — Owner: Line A — #672 发 48、#683（登录路径一次预取两张 map；hasPermission 逐字未动）发 49 上线，运行 jar 有 `PermissionServiceImpl$LayerSnapshot`（阴性对照 0）。剩：lsm001 登页面 200；测试环境真登录读 modulePermissions 在跑。🔒 权限。
- `ARCH-MODULE-LISTS-CONVERGENCE-20260906` — `in-progress` — Owner: Line A — A #690 / B #695 / D #715 已合，棘轮 27→21→**19**（#703）。✅ 刀 C 解冻，Steve 授权 organizer 代定：⛔ 不静默兜底成零权限（那是假数据，与「确实无权限」不可区分，违核心原则 1）⇒ 改三态（读到 / **读不到显式报错** / 确实无权限）；兜底矩阵仍从 L1 快照生成但只作离线默认，⛔ 不冒充实时权限。
- `OPS-PROD-VALIDATE-FEASIBILITY-20260906` — `blocked` — Owner: 等 owner 裁定 — #669 决定书已合：发 47 后 prod 同款 jar 在 prod schema 副本零 fixture 起 validate ⇒ STARTED 71s、剩余失败 0；阳性对照删列即点名；none/validate 耗时无差。推荐切（先把 smartbi 单元写死的 `hbm2ddl.auto=update` 改 none，再一行 property 随发布切）。
- `ARCH-SQLDELETE-RATCHET-BATCHES-20260906` — `in-progress` — Owner: Line B — 批 1/2/3-A/3-B + @Version + 删用户级联 + prod-only 索引(#741 V20261101_40) + 唯一性推回写时(#742 V20261101_41) + 注解形式枚举闸(#744) **全部已合**。剩 R68 本体：`@Where` 有而 `@SQLDelete` 无的实体(读起来像软删、delete() 是物理删)——数要当场重取, ⛔ 不引用 342/23。🔒 实体。
- `UX-ALERT-ALERT-NOOP-ON-WEB-20260906` — `review` — Owner: Line A — #678 + #687 + #697（QIScan testID 漏进 main 的标记 / 清完 21 个未用 Alert import / 做成闸）已合。🔴 宿主栈行为证明拿到（Expo Web × `f006_warehouse_mgr`）：正向与「离开自带宿主屏后再触发」都 0→1，变异回单槽后回归红。剩：Snackbar 方案待定。
- `OPS-CRON-SCRIPTS-PATH-AUDIT-20260906` — `in-progress` — Owner: Line C — 批 1–3 + canary + #679 已合装；#681 alert-digest 骨架（14 组测试 / 9 种 rc=2 / 游标只在送达后前进）与 #686（用例级 `# SCRIPT_TEST_TIMEOUT`）已合，#681 按 `# NOT_SCHEDULED` 不装。剩：#679 释放侧后续。🔒 cron。
- `OPS-CI-ARTIFACT-LANE-DEAD-IN-THIS-ORG-20260907` — `blocked` — Owner: Line C — #698 已合：`release-ci-artifact.sh` 默认 repo 指对 + 签不上时立刻退 `rc=3`（⛔ 不复用 2，那是用法错误码）而不是空等 7 分钟。剩余是**能力边界**：私有仓 GitHub 不给 attestation，ECS 按设计拒未签名制品 ⇒ 这条车道在本组织**永远**走不通，只能明说并退 3。⛔ 不放宽 ECS 信任。🔒 deploy。
- `OPS-SCHEMA-PARITY-ROOT-FIX-20260907` — `queued` — Owner: Line C — bootstrap 已落地(#739), 剩「从迁移建库 → 导 schema → 与 checked-in prod 快照 diff」那道闸。⚠️ 豁免清单必须能表达**第三种**不一致：prod **刻意删掉**的(补回去=制造回归, `uk_report_worker_batch_date` 那个 409)。⛔ Hibernate validate 不是 schema 对账(看不见唯一约束/索引/FK/NOT NULL)。
- `CI-NINE-OPTIN-PG-TESTS-RUN-NOWHERE-20260907` — `queued` — Owner: Line C — 15 个 opt-in 真 PG 测试只有 6 个被 `java-gate-pr.yml` 点名，其余 9 个任何 CI 都不跑。⚠️ 障碍**不是**「自建 runner 没 Docker」——那条车道用 runner 上原生 homebrew postgresql@17 ⇒ 接进来只是加名字。⚠️ 接之前每个先答「它当初为什么是 opt-in」，耗时长的进 PG 车道而非 fast-contracts。
- `CI-NO-GATE-GUARDS-SUITE-SIZE-20260907` — `queued` — Owner: Line C — 本仓无任何闸守套件规模（`Tests run` 只在日志里，从未被断言）。守的是「测试静默不跑」族（选择器 / profile / 聚合模块 / 批量 `@Disabled`），#722 三读数对这一族完全不可见。⛔ 不写死 `>= N`（并行线会天天误报）：只在下降超阈值时红 + 无条件打出当次数。⚠️ 抓不到「车道整个 skip」，那一半归 GUARDS 票。
- `OPS-DECISION-GRADE-PROBES-MUST-BE-COMMITTED-20260907` — `queued` — Owner: Line C — 产出决策数字的普查脚本不在版本控制里（prod-only 索引那次是临时脚本，`git grep` 找不到），⇒ 它的 v1（名字出现过）/ v2（区分 CREATE 与 DROP，漏 RENAME TO）各错一次，而下一个人会照 v1 重写。要求：凡产出进决策/PR/台账的读数，脚本必须进仓并带阳性对照。
- `OPS-BARE-FK-COLUMNS-POINT-AT-SOFT-DELETED-ROWS-20260907` — `queued` — Owner: Line C — 1064 个裸 `_id` 列（无 FK），319 对父子非空中 20 对指向已软删父行、10 对子行仍活。✅ Steve 裁定（organizer 代定）：指向已软删父行**合法，⛔ 不告警**；缺陷是取名字被 `@Where` 过滤成空白 ⇒ 显示路径要能读已删父行，取不到显示「已删除」。收窄为：10 对逐对判「显示 vs 查当前」。
- `OPS-DEEPSEEK-KEY-REJECTED-SINCE-0826-20260907` — `blocked` — Owner: 等 Steve — `LLM_DEEPSEEK_API_KEY`(`.env.prod:73`) 对 `api.deepseek.com` 自 2026-08-26 04:40 起 **100% 401**(此前 352 次成功, 断点干净)。**len=35 格式正确 ⇒ 被拒, ⛔ 不是没配** ⇒ 控制台先查旧 key 状态。详情 [archive](archive/2026-09-07-batch-close.md)。
- `OPS-LLM-REGISTRY-PROBE-NOT-IN-CRONTAB-20260910` — `queued` — Owner: 运维 — `scripts/cron/probe-llm-registry.sh` 存在可跑(自带日志/alerts/退出码语义), 但 crontab 命中 **0** ⇒ 从没定时跑过。09-10 手动跑: **6/6 全部「注册表说活、实测不可用」**。⚠️ aistore 三条报 `LocalProtocolError` 而生产日志该错误 **0 次** ⇒ 探针侧, ⛔ 探针失败 ≠ 生产失败。
- `OPS-PURGED-TENANT-SCHEMA-STILL-HAS-LIVE-ROWS-20260907` — `blocked` — Owner: 等 Steve — `tenant_purge_68` 182 表 / **347,661 行**(刻意的删前备份, 迁移注释写「确认无碍后手动 DROP」)。dump 已验证可还原(182/347,661 逐位相同), ⚠️ 依赖外部前提(pgvector 镜像 + 先建扩展)。⛔ 未 DROP、未删 dump。
- `OPS-BACKUP-RESTORE-NEVER-VERIFIED-20260907` — `queued` — Owner: Line C — prod `backup-db.sh` 唯一「验证」是 `[ ! -s ]`(只判非空) ⇒ 与今晚那份**还原不出来**的 dump 同样是绿的。**RESTORE_EVER_VERIFIED=no**。⚠️ 仓 2062 / prod 5571 已漂移 ⇒ 判据只能取部署那一份。
- `OPS-FLYWAY-HISTORY-TABLE-WAS-HAND-EDITED-20260907` — `queued` — Owner: Line C — prod `flyway_schema_history` 有 **9 个重复 version**(同一版本两行 success)且 **5 个 version 的文件已不存在**。Flyway 不会重复应用同一版本 ⇒ **台账本身被人手工动过**。⚠️ checksum 691/691 **相符, MISMATCH=0** ⇒ 「编辑已应用迁移」有书面许可但**零实际使用**。⛔ 只登记, 不动 prod 台账。
- `CI-WORKTREE-PATH-CAN-BE-A-LIE-20260907` — `queued` — Owner: organizer — 系统告知的 worktree 路径可能是**主仓里的空目录**(无 `.git`、`git worktree list` 里没有) ⇒ 在其中跑 git 会操作**主检出**(别人的分支 + 别人未提交的改动)。本轮 Line C 因此**整轮零 commit**(正确结果)。⇒ 开工前三验: `.git` 存在 / `worktree list` 命中 / 当前分支名。
- `OPS-MUTATION-GUARD-HAS-NO-CALLER-20260907` — `queued` — Owner: Line C — `scripts/mutation-guard.sh` 可执行但全仓 **0 调用点** ⇒ 那条 memory 的「已机制化」前提已变。⛔ 判据不是 `grep -r mutation-guard`。详见 `archive/2026-09-07-new-tickets-detail.md`
- `CI-SIX-WORKFLOWS-STILL-PINNED-TO-HOSTED-20260907` — `queued` — Owner: 迁移任务 — 自建 `windows-wsl-ci` 已恢复，#758 的 Java/Web/扫描真实运行通过；Ops/扫描两条已接入统一路由。托管 billing 未调整，剩余定时及 Docker 专用工作流仍待迁移核验；不得将 runner online 等同于全部工作流可运行。证据：`archive/2026-09-09-ci-recovery.md`。
- `CI-WEB-ADMIN-VITEST-IS-FLAKY-ON-SELF-HOSTED-20260909` — `queued` — Owner: 迁移任务 — 🔴 同一个 commit 跑两次, 都是 3 files/4 tests failed、都 4691 passed, 而**红的文件一个都不重合** ⇒ 浮动, ⛔ 不是「稳定复现」。本地(串行)main 与 PR 全量都绿; CI 多 worker 并行(注释实测 CPU 350%)。⚠️ 这道闸现在绿和红都只携带有限信息。详见 `archive/2026-09-07-new-tickets-detail.md`

## Scope 锁地图

- `SMARTBI-F006-QA-20261028`：Root；本批代码冻结、子任务回执归档，合入时释放产品scope；仅发布协调与F006私有业务验收/证据。发现新代码问题须重新登记明确scope与Base；禁Entity/迁移/权限矩阵。


- `FEAT-LINEAGE-SOURCE-INDIVIDUAL-NODE-20260915`：`service/lineage/LineageGraphAssembler` 与 lineage DTO 加法式、RN `screens/traceability/**`；⛔ 不碰 SourceIndividual 实体/迁移/vision/recorder。
- `DEMO-SHENGLONG-TENANT-SEED-20260915`：仅新建 `scripts/e2e/shenglong-demo/**`；⛔ 不改后端/前端代码、不 SQL 直插、凭证不入库。
- `FIX-LSM-SUPPLIER-PRICE-REQUIRED-20260901`：仅 `web-admin/src/views/procurement/suppliers/SupplierDetailDrawer.vue` 与对应目标测试。⛔ 不改 `permission.ts` 权限矩阵；若需改后端/导入路径对空价格的行为 → 🔒 停在 PR 交协调者。
- `FIX-QC-VERSION-OTA-APK-20260829`：`frontend/CretasFoodTrace/src/screens/quality-inspector/QIProfileScreen.tsx`、`QISettingsScreen.tsx`、`src/i18n/locales/{zh-CN,en-US}/quality.json`、`src/__tests__/gates/nativeVersionConsistencyContract.test.ts`、ACTIVE 与当日归档；发布阶段只读使用官方 OTA/APK 脚本。
- `ML-LIUSHANMEN-WORK-AREA-ROI-20260811`：仅 `tools/vision-lab/work_area*.py`、`mine_tray_queue.py`、`tray_workflow.py`、`evaluate_tray_candidate.py`、`tools/vision-lab/tests/`、`docs/ops/liushanmen-vision-lab.md`、ACTIVE 与当日归档；不得改写人工真值、保护集、原图、现有模型或生产。
- `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001`：销售需求、BOM净需求、采购建议、客户来料、仓储来源任务及对应 Java/Web/测试/dispatch；涉及 Entity/Repository/Flyway 时必须重登记最新 Base 并通过真实 JPA/迁移门禁。不得开启普通手工收料写入口或修改生产数据。
- `ENH-F006-SUPPLIER-IMPORT-001`：供应商 Java Entity/DTO/Controller/Service/Repository、采购门禁、共享 Excel 导入与目标测试；Web 供应商列表/详情/新增编辑/导入及 API/types/tests；必须真实 JPA Context（如触及查询），生产业务写入为 0。
- `BUG-F006-R2-SKU-UNIT-001`：仅 Web SKU 新建/编辑基础规格、displayUnit/净含量单位 helper 与目标测试；Java 净含量契约须另行登记不重叠 scope。
- `FEATURE-F006-R2-WORKPROCESS-DEDUP-001`：Java WorkProcess/Workflow/SKU 关联、DTO/Repository/Service/Controller、Flyway/JPA tests；Web 工序治理及排序/单位字段移除与目标测试；不得迁移既有业务引用或治理生产重复组。
- `FEATURE-F006-R2-BOM-SUBSTITUTE-001`：BOM 替代关系的 Web、版本/计划/领料/报工/成本/追溯快照与目标测试；Java CORE 已完成，父任务不得重复修改 CORE Flyway/Entity，必须与统一 Workflow/BOM 任务串行。

## 阻塞项

- LIUSHANMEN：21 条空 `storage_type` 等待 Owner 向客户取得真实储存类型；结论到达前保持零写入，不猜填。
- VisionLab：正式 ROI audit 仍有 1 个 unjudgeable；计划任务迁移与运行失败已核销。
- LLM 运营：2026-09-13 aistore 续期换 key，以及 2026-09-04/2026-11-15 复审需要 Owner/供应商控制台动作。
- 餐饮 RN：实现已入 main，但当前 OTA runtime 与真实设备验收未确认。
- 凭证：旧 Aliyun 主账号 AK、旧模型供应商 key、Mall 微信长期凭证仍需各控制台吊销/重置并验证失效。
- F006 产品/架构：taxonomy suggestion、统一收料来源、AP 付款完整边界仍是无执行者 backlog；需求驱动收料与客户渐进创建保留 scope，后者须先裁定必填规则。

## 维护规则

- 按 AGENTS.md 的唯一协调者、结构化回执与弹性并发规则执行；创建 worktree、派工和合并前核对本表及 `git worktree list`。
- 仅在飞状态；merged 当次归档释放 scope。每条≤300字、全文≤30000 bytes，不开三级增量段；详细证据进 archive/。PR/push/fastlane 保留 `python scripts/ci/check_dispatch_active.py` 门禁。
- 原维护规则全文保留在 archive/2026-10-22-s6-b3b-review.md。


