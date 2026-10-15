# CONNECT-DATA-VISION-20261015

用户要求继续接通全部剩余项，先 Python 指标桥接并验收，再视觉 SOP 接入对话建厂及既有 runner。复用真实数据口径；各厂独立数据、训练、验收；六膳门模型不通用化。

Base SHA: a39038c68e36ff23d32eef5398bb87afe33fd746。

## AN2 scope lock

任务 ID CONNECT-DATA-VISION-20261015-AN2；Owner /root/implement_analysis_provenance。

允许：PythonSmartBIClient.java 仅指标方法及必要 private helper；IndicatorQueryServiceImpl.java 仅桥接能力/缓存按端点验证；对应 PythonIndicatorSourceContractTest 与新增桥接目标测试；新 backend/python/smartbi/api/indicator_bridge.py、新 backend/python/tests/test_indicator_bridge.py。

禁止：Entity/Repository/SQL/Flyway、其他客户端能力、main.py、auth_middleware.py、ACTIVE、视觉文件、平台建厂 scope、真实生产写入。Maven target 仅其独立 worktree 串行使用。

复用 smartbi.gold.restaurant.dish_margin.compute_dish_margins 的 date_range 与 avgRate 账上口径，不复制 SQL/业务公式，不纳入 avgRateWithEstimated。只登记实际支持的固定端点/别名，禁止任意 URL；未知来源不准复用旧缓存。真实零/无样本/错误分开，校验工厂、时间窗和有限数值；已有内部认证/安全 HttpClient 复用。HTTP 主服务挂载由 root 做。

验收：Python 新桥接目标测试、真实 ASGI/JWT 或 internal middleware 的跨厂/无身份对照；Java MockWebServer 实际请求含 factory/time + secret、zero/null、HTTP/协议/时间窗/工厂错配与非法端点拒绝；既有 indicator 目标单测及 transport security 不回归。先失败再修复的证据落 Documents/cretas-connect-20261015/indicator，不将 Mock HTTP 称为真实业务库验收。

## Root scope

root 唯一修改 main.py 路由挂载、docs/dispatch 与 docs/vision-training-sop.md，及后续新增 vision_training runner 接线/工厂限定配置/API；复用既有 tools/vision-lab 函数。本轮不动原图/人工 JSON/保护集/当前模型/计划任务，不自动训练真实客户数据或发布模型。

平台入口并行任务 PLATFORM-ASSEMBLY-20261015 位于 C:/Users/Steve/cretas-platform-assembly-20261015，当前独占建厂专属 Java/迁移/配置及 web-admin 平台入口；不接管其 worktree、不覆盖其未提交内容，先调查契约，集成按最终可复用入口收口。

用户澄清：视觉 SOP 接入正在建设的平台建厂入口，复用该任务成果；不另接旧独立版本。

Root 的 AN2 验收 scope 追加：新增 IndicatorPythonBridgePostgresAcceptanceTest.java、backend/python/tests/fixtures/indicator_bridge_app.py、scripts/windows/test-indicator-bridge.ps1。真实 Controller→QueryService→PythonSmartBIClient→真实 Python router/middleware/Gold function→私有 PostgreSQL，并回读 indicator_versions。Java 生产鉴权明确排除，Python 内部鉴权真实执行；测试只允许独占 loopback 55458 / cretas_workflow_verify_indicator_bridge_20261015，拒绝占用已有 10059/18085，不借用平台 PG55447。

已取得旧源红：1 test / 1 assertion failure / 0 errors / 0 skipped，期望 AVAILABLE 而实际 FAILED；日志 Documents/cretas-connect-20261015/indicator/java-python-pg-red.log。fixture 清理仅本专属 DB 的固定七张 Gold fixture 表，JPA 只扫描既有 indicator 包；不执行生产迁移或生产业务写。

Root 的指标展示 scope 追加：既有 IndicatorDetailDrawer.vue 及目标测试，显示 Python 菜品毛利的当前成本快照口径；只在相关 code/source 显示，不改变数值。新 Python wrapper 对部分成本覆盖不出全店 KPI（NO_DATA），不复制旧函数的计算逻辑。

独立复核补强：AN2 原 scope 包含唯一菜品毛利 code 与固定 endpoint 双向绑定（fresh/cache）、独立 wrong-period 控制、覆盖率精确等于 1 控制。Root 修复既有抽屉强制重算对 FAILED/NO_DATA 的假成功提示，真实 0 仍为成功；页面目标测试 7/7 通过，日志 indicator/web-recompute-green.log。真实链路 fixture 导入生产 main.app，关闭无关 lifespan，未替换真实 middleware/router/Gold 算法。

Root 私有 UI 验收 scope：web-admin/tests/e2e/indicator-bridge/{fixture.html,browser.mjs}；只准 127.0.0.1:31759→10059→18085→专属 PG55458，真实抽屉与 API 不 mock。Java fixture 可选 HOLD_FOR_UI 窗口追加本月真实零和错配 code 控制；生产 Java 鉴权排除，固定本地组件角色不算鉴权验收。命令 scripts/windows/test-indicator-bridge.ps1 -HoldForUi 与 web-admin 下 VITE_BACKEND_URL=http://127.0.0.1:10059 的独占 Vite31759，再运行 browser.mjs。不得访问生产 host。

VS2 实施设计约束：Linux API 只调度、持久化和验收回执；上海 Windows 本地 worker 仅调用命名函数，不调用 cycle/deploy，不接受客户端命令/URL/本地路径。任务、配方、数据、模型和独立验收必须绑定厂/项目/版本/哈希。租约超时不自动重跑训练；训练、评测、独立验收与启用分别留证。平台规划必须显式 PLATFORM_ADMIN namespace 与目标厂，不能伪装 FACTORY_USER。六膳门专用硬编码不可作为 generic/OCR 默认 runner。

## VS2 scope lock

任务 ID CONNECT-DATA-VISION-20261015-VS2；Base fa9b9e125348168d31a1c3d8d774462fd48e46aa；Owner /root/implement_vision_sop；独立完整 worktree C:/Users/Steve/cretas-vision-runner-connect-20261015。AN2 真实跨服务及本地 UI 验收已通过，可开始实施。

允许：backend/python/vision_training/**（复用已有 service/draft/adapter，不另建一套 SOP）、tests/test_vision_training*.py；新增 tools/vision-lab/sop_worker.py 及其目标测试、新 scripts/windows/vision-sop-worker.ps1。main.py、auth_middleware.py、PythonSmartBIClient、Java/Web、dispatch、现有 vision_lab/tray_workflow 函数文件不在 scope。不得改数据/模型/计划任务、启动客户训练或远程计算。

实现：持久 SQLite 调度与幂等/原子 claim、绑定厂/项目/版本/preview/config/input hash 的 worker 回执、复用现有命名函数的本地 worker、显式工厂和配方注册。租约超时冻结，禁止自动重复训练。普通用户不能提交 worker 回执。generic/OCR 无已登记真实 runner 时显式阻塞。平台专用规划 API 只接受内部已验证 PLATFORM_ADMIN 命名空间，不把平台管理员 ID 借成工厂用户；root 后续 Java proxy 负责真实平台身份与目标厂验证。

验收：原有 vision SOP 测试及新增队列并发/幂等、错厂/错版本/错哈希/过期 lease、worker 鉴权、generic 与专用模型隔离、无任意命令/URL/路径入口。可以使用临时 synthetic manifest 与 fake adapter 测编排；不能将其称为真实训练、模型准确率或独立工厂验收。原模型/原图/人工标注与生产写入均为 0。

AN2 验收回执：冻结源 fa9b9e125348168d31a1c3d8d774462fd48e46aa，真实主服务 middleware/router/Gold→私有 PG，Java 1 test/0 failure/error/skip；实际抽屉真实零、NO_DATA、FAILED 与重算提示均通过，console/page errors=0；匿名401、跨厂403；真实0/50/99.9和版本窗口回读、错code无快照。证据 Documents/cretas-connect-20261015/indicator/{cross-service-receipt.json,ui-result.json,ui-pg-readback.json,ui-*.png}。Java生产鉴权和无关Python lifespan明确排除，生产业务写入0。源码根因复核5项闭合，后续CI/发布分别留证。

AN2 单独进入 review/发布切片，代码 Owner 已释放该指标 scope，VS2 继续独立分支实施。私有 Python18085/Java10059/Vite31759/PG55458 已按启动PID与专属data目录核验并停止，数据和证据保留。发布目标 selector：PythonIndicatorSourceContractTest,IndicatorQueryServiceImplTest,PythonSmartBIClientTransportSecurityTest；真实 PG/UI 证据按上述冻结源码复用，不把 opt-in 未执行当发布测试通过。

AN2-CI 窄scope（Owner implement_analysis_provenance；Base bf42051bfa3e2e52d4b566e4dcaa9d7b7846a621）只登记 backend/python/pytest.ini 的 anyio marker，原14条目标测试（12桥接+2标记）通过；提交8fbd51e75a383fa69a86b02c65e4e49067fe3920。该scope已释放。CI原始单失败/9921pass日志保留 indicator/release/ci-python-failed.log，不把失败称全绿。

候选4b7725e613完成统一构建：Java发布目标37/37，Maven BUILD SUCCESS；Web构建成功。JAR f069f2ef3e452ca7176a55eaf0ea9ce47ff03140a034e857dbae0cb377397559 已预上传（未安装/切流）。marker登记及本台账不改变Java/Web树，复用该制品，不重新构建。真实manifest保存built-java.manifest/built-web.manifest；初次调用额外指定的manifest回执路径未作用于build，最终部署使用默认受校验cache路径，不以初次报告的空元数据代替manifest。

## VS3 root scope lock

任务 CONNECT-DATA-VISION-20261015-VS3；Owner root；Base 739b30272d（指标PR #988；最终接线在合入后以fresh main收口）。独占新增 controller/vision/PlatformVisionTrainingController.java 与目标ContractTest、PythonSmartBIClient.java 新命名vision bridge段及其新ContractTest、web-admin/src/api/visionTraining.ts、新 components/vision-training/** 及目标测试、main.py挂载、vision SOP文档和dispatch。平台建厂现有 index.vue/安全类型/迁移仍属 PLATFORM-ASSEMBLY-20261015，待其合入/释放后才挂载组件，禁止复制其未提交代码或另建建厂流程。

API固定平台前缀/有限操作，不允许任意Python URL。平台actor使用建厂任务提供的AuthenticatedActor namespace；正平台身份和目标工厂存在性均验证后才转发。模板/预览/保存/回读/阶段/配方/排队沿用VS2契约，无自动启用。验收：Java Client实际HTTP合同与平台/工厂同ID阴性；Vue状态/工厂切换/保存排队合同；实际新平台入口→Java→Python草稿/队列持久化读回，生产/真实客户训练0。缺建厂依赖不得假报已接通。

AN2 已于 2026-10-15 合入 PR #988，merge 4b0209edd95e7f854a7bec659c8d50eccc168b3c。适用9项CI通过：Java fast-contracts 2984/0fail/0error/3skip（目标新合同9条确实执行），Python 9922pass/166skip/153deselected/2xfail（self-hosted特定排除），Web5253pass/5skip；Java full suite明确skipped。源码树未变，复用已通过真实PG/UI证据与可信制品。

AN2 发布：clean exact-main release tree cretas-analysis-provenance-20261015；Python部署exit0，统一Java/Web部署exit0，RELEASE_FINAL_STATUS=deployed恰好1次，build均reused。回执 indicator/release/reports/cretas-1792062042-938.json；实际active cretas-backend-green:10020，JAR f069f2ef3e452ca7176a55eaf0ea9ce47ff03140a034e857dbae0cb377397559；Web四方哈希pass。任务特有runtime-feature.json确认新class标记存在、旧unsupported/阴性标记不存在，真实Python路由匿名401/跨厂403/非法窗口422/内部F006只读200，当前窗口NO_DATA。生产业务写入0，未跑生产UI业务验收。汇总delivery-an2.json，不重复标准健康核验。

VS3 root配置接线scope追加：新增vision_training/server_registration.py、目标test_vision_training_server_registration.py；main.py统一mount factory/platform/worker runtime，外部registry只读，worker密钥仅从命名环境变量取；默认无recipe/worker/专用工厂。修改既有test_vision_training_entry_contract.py以核对真实三命名空间挂载。VS2 owner继续独占queue/runtime/worker/models/router修复；根不并发修改这些文件。

VS2 Owner于73c459430d后释放代码scope；root接管sop_worker.py及worker_contract最后两项consumer修复：真实安全YAML语义/重复键拒绝，以及每个评估图片路径必须匹配manifest锁定的预登记hash。旧实现两条断言均红（consumer-mutation-red.log），恢复修复后跑冻结目标集；未执行真实模型训练。

VS-HTTP验收任务 CONNECT-DATA-VISION-20261015-HTTP；Owner implement_vision_sop；Base19860076ce（root冻结候选另记录内容hash）。只允许读取root树产品文件，在Documents/cretas-connect-20261015/vision/http-runner中创建非敏感脚本/回执，在D:/CretasVisionLab/acceptance/connect-sop-20261015中创建独占合成测试数据，开启并关闭自身loopback18087/18088服务。实际main middleware/router→队列→Windows现有collect函数→完成回执/重启读回；匿名/错worker/跨厂阴性。不改任何repo文件、原有客户数据/任务/模型，不训练、无生产网络或业务写。验收脚本exit0并返回factory/project/job/真实函数/实际下载hash及重启读回证据；不得把collect链路称模型训练或完整Java/平台入口验收。

Python独立交付切片 CONNECT-DATA-VISION-20261015-PY：Owner root，Base4b0209edd95e7f854a7bec659c8d50eccc168b3c，worktree cretas-vision-runner-connect-20261015 / codex/vision-sop-python-ready-20261015。仅19个Python/PSworker/SOP与dispatch文件，产品内容与已验b39899df24逐文件相同，目标75pass/1warning；无Java/Web/Entity/迁移/平台底座源码。复用该内容证据，适用CI另跑。本批默认registry空，生产不登记客户worker/配方、不训练、不启用。VS3 Java/Web接线待平台底座交付，不能将Python后端发布称整个平台接入完成。
