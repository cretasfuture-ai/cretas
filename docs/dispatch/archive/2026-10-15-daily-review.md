# 日常入口与追溯/分析联合体验

用户纠正：视觉SOP是长期日常功能，不属于平台对话建厂。用户要求准备数据追溯、数据分析的测试账号/样本/入口，复用已有功能；不合并、不部署。

Base fbe3449f711658ca22cac72995923fc2840d03f0；独立分支 codex/trace-analysis-vision-daily-review-20261015，worktree C:/Users/Steve/cretas-analysis-provenance-20261015。root保留唯一协调者/台账写Owner，原平台Owner工作区不动。

Root scope：vision API/组件/日常页面及既有菜单/路由最小挂载；移除自己上轮加在factory-onboarding的import+组件；FactoryVisionTrainingController及既有PythonSmartBIClient有限vision方法/目标测试；现有平台代理保留但不作为日常用户入口；本轮不改共享鉴权、Entity/Repository/Flyway、模型/图片真值/训练任务，不开启生产。私有运行时及账号仅代码外。前端遵循现有Vue设计规范。

DAILY-VISION-UI：Owner daily_vision_ui；Base fbe3449f711658ca22cac72995923fc2840d03f0。独占web-admin/src/api/visionTraining.ts及其transport测试、src/views/quality/vision-training/index.vue及目标测试、新日常路由/菜单（router/index.ts、components/layout/menuConfig.ts）、自己上轮的factory-onboarding组件import+挂载两处删除；现有FactoryVisionTrainingSetup组件仅必要通用文案修正，不重写流程/新建训练器。角色factory_super_admin、quality_manager，当前厂来自authStore不可从query切厂；/quality/vision-training，后端/api/mobile/{factoryId}/vision-training。验收目标Vue/API/路由测试及typecheck由该Owner执行，root统一构建/实际UI。

DAILY-TRACE-SEED：Owner daily_trace_seed；Base同上；唯一代码scope scripts/e2e/daily-review/seed_trace.py。允许本机127.0.0.1:55447/vision_entry_probe_20261015内VISION-ENTRY-A/B的新增合成追溯样本，复用现有seed/helper与业务结构，禁止DDL/禁用约束/改历史行或现有SOP；不改users/密码/Java/Web/ACTIVE。验收为该脚本目标guard+SQL前后计数/真实关系读回；root负责实际UI/API验收。

DAILY-ANALYSIS-SEED：Owner daily_analysis_seed；Base同上；唯一代码scope scripts/e2e/daily-review/seed_analysis.py。允许上述私有DB内ANALYSIS-REVIEW-A/B的新合成分析租户/Gold数据/指标定义；复用已有Indicator桥接fixture和Gold表，禁止DDL/放宽RLS/改共享或他人数据、users/密码/Java/Web/ACTIVE。可先给出精确所需租户类型/表依赖，root统一账号与运行时。验收为脚本loopback/DB/factory防护、数据来源明确、已知正值/真实0/NO_DATA及跨厂对照与实际SQL读回；root做真实登录/页面/重算链路。

分析Owner追加同文件scope：VISION-ENTRY-A/B仅新增工厂指标定义/计算配置，与trace Owner的业务样本表不重叠；复用现有Java指标计算器，对实际生产/报工数据给出有依据的指标，不使用餐饮公式伪装工厂指标。root负责应用私有库所缺既有SmartBI迁移，Owner不DDL。

源码和样本冻结后：目标Java权限/传输合同，Web路由/API/组件目标测试、typecheck/build；实际工厂登录→日常侧栏→视觉保存/刷新/本厂回执及拒绝平台/错厂，实际追溯批次→来源图/明细，实际分析指标→来源口径/重算/历史版本；截图、DB读回、阴性对照留在Documents/cretas-connect-20261015/daily-review。仅隔离测试服务可重启，最终保留给用户登录；不push/合并/部署。

Root必要接线补充：scripts/e2e/daily-review/bootstrap_smartbi.py仅对精确本机私有库复用既有canonical Gold SQL，保留RLS，排除其他租户backfill；私有库schema-only缺L1数据，仅代码外helper补既有canonical权限默认catalog，不改变生产权限或schema。经营指标finance_manager入口同步guards.ts窄路径白名单及目标行为测试。独立审查发现raw Python JWT会绕过Java实时成员检查，因此仅生产main.py挂载通过新增require_factory_bridge_context，要求internal认证、FACTORY_USER及两管理角色；通用SOP/service/CLI不重写。scope追加vision_training/router.py、main.py及既有entry contract测试。复用既有runner结果，不重训，不启用模型。

交付状态（2026-10-15）：实现与本机验收完成，用户体验待验；不push/PR/合并/部署。DAILY-TRACE-SEED与DAILY-ANALYSIS-SEED子Owner已交回root，只有root继续持有本分支scope。trace样本仅修自有客户NORMAL→真实RECURRING枚举；已真实Java fulltrace读回、跨厂403；A/B关系计数与幂等通过。分析样本仅写输入/定义，不直接写计算快照；Java真实重算写入indicator_versions，工厂100%/40kg，Python75%/0%/NO_DATA及跨厂403通过，NO_DATA无伪造数值版本。私有5账号凭据只在代码外文件，不入Git。

Web验收复用真实Vue登录/页面/Java/Python/PG，无route mock：工厂追溯保存地址刷新；菜单进入经营指标并重算；Assembly开关关闭时日常视觉可用、URL query不能切厂；质量负责人显式预览保存刷新同一项目；既有collect回执读回、未登记dataset/train按钮禁用；B厂无A草稿/专用模板、查询A批次显示未找到；平台日常接口403。质量角色无analytics授权时不显示经营指标。餐饮hub概览由已有GoldMaterializer从同一Silver样本汇总，营收300/3单；75%与B厂真实0值均UI确认，概览错误0。修复隔离Vite harness遗漏/smartbi-api代理；仅私有库补齐已有Gold读取依赖，不改生产迁移、RLS、客户数据或模型真值。

验证证据（代码外 Documents/cretas-connect-20261015/daily-review）：backend/vision-contract.log，Maven选择器PythonVisionTrainingPlatformContractTest,PlatformVisionTrainingControllerContractTest,PlatformVisionTrainingMvcContractTest,PythonFactoryVisionTrainingContractTest,FactoryVisionTrainingContractTest，17/17，rc0；backend/python-vision-final.log，pytest tests/test_vision_training_{entry_contract,worker_contract,router,server_registration,queue,sop}.py，76/76，rc0；ui/final-target-tests.log，Vitest vision API/setup/page及真实router guard，29/29，rc0；ui/final-typecheck.log，vue-tsc -b，rc0；ui/final-build.log，npm run build，rc0。源码/测试冻结后不因本档或SOP说明变化重跑；diff-check及dispatch checker通过。独立只读安全复核修复后无阻断。

主链UI回执 ui/browser-result-1792069438941.json PASS；补充质量保存/菜单/B厂隔离见ui/browser-result-1792069694822.json的8条成功checks（该轮餐饮概览因测试库缺依赖FAIL，未冒充整轮通过）；补齐canonical依赖后仅复验失败分析片段，ui/browser-result-1792069767483.json PASS，概览错误0/营收300/75%/真实0截图。api-controls.json保存6个计算正负对照和真实DB版本。保留已修复的环境/样本/locator失败证据；不以HTTP健康代替业务验收。

限制：本轮未训练客户模型/未跑GPU效果或启用验收；通用模板可以定义、保存，未登记本厂runner和输入则执行阻塞。六膳门仍仅白名单专用。整库从其他私有schema-only快照复用且Flyway关闭，因此不宣称通过新库Flyway或生产发布门禁；AI自由问答不在本轮本地验收范围。用户反馈前保留worktree、分支、样本库、SOP状态与10060/18090/31761本地服务。
