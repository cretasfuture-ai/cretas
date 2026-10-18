# 视觉 SOP 挂载平台建厂入口

任务 `CONNECT-DATA-VISION-20261015`，2026-10-15。独立工作区 `C:/Users/Steve/cretas-analysis-provenance-20261015`，分支 `codex/vision-platform-entry-20261015`；冻结底座 Base `95e824cb1726a94959f75c2127183e9b44b5a048`。原平台工作区没有被本任务修改。该底座尚未合并，其 Owner 仍在推进 generation gateway 等增量；本分支只交付视觉增量，不替其批准迁移、权限或生产发布。

## 复用与本次接线

- 指标桥接 #988 和视觉 Python/本地 runner #990 已合并、部署；未重新开发或发布。此前 75 条目标、9969 条 CI 以及真实 HTTP collect 的未变源码证据直接复用。
- 复用已保留的视觉组件/API、Java 固定操作代理/Controller/合同测试。新平台页面仅增加组件 import 和挂载，使用其现有 `factoryId` 和生成的产品名称；没有增加另一套建厂流程或修改闭合的 capability catalog。
- 真接线检查发现：共享 `JwtAuthInterceptor` 不建立这里需要的 typed actor；现有 `PlatformAssemblyAccessConfiguration` 才在 `/api/platform/factory-assembly/**` 校验显式平台 JWT、active 数据库平台账号及 `super_admin`。视觉公开路径改为 `/api/platform/factory-assembly/factories/{factoryId}/vision-training`，完整复用该鉴权和默认关闭开关。未复制鉴权、未修改共享 Security/Entity/Repository/Flyway。
- Java 继续校验目标工厂真实存在，调用有限 Python 操作；前端仅提交 stage/recipe ID。模型、数据、配置、独立验收和 worker 的工厂绑定仍由现有服务执行。默认无专用 recipe，六膳门流程仅对显式登记的目标厂可见，不成为通用模型。

## 本次冻结验证

证据目录 `C:/Users/Steve/Documents/cretas-connect-20261015/vision/platform-entry/`。

| 验证 | 实际结果 |
| --- | --- |
| Java client/controller/真实 MVC 映射 | 9 tests，0 failure/error/skip；`java-final.log` |
| MVC 正反证据 | 原接法对受保护 URL 返回404，匿名应401的断言失败；接线后通过；`mvc-red2.log`保留。第一次新测试的辅助方法命名编译错误另保留，不计产品失败 |
| Vue API/组件 | 11 tests 通过；`web-target-final.log` |
| Vue 类型检查/生产构建 | exit0；`web-typecheck-final.log`、`web-build.log` |
| 真实平台登录及建厂页面 | 使用实际 `/login` 与 `/factory-onboarding`、原页面及组件；无 HTTP response mock、认证头代理或 storageState 注入 |
| 空 registry | A 厂预览/保存/刷新恢复，B 厂无 A 项目，两厂无六膳门专用模板，无任务提交；`ui-generic-2/platform-result.json`及截图 |
| 显式本厂 recipe | A 厂真实 UI 预览/保存/提交 collect→Python durable queue→现有 PowerShell worker→现有 `VisionLab.collect`→UI 显示采集已完成；B 厂仍无专用模板；`ui-specialized/platform-result.json`及截图 |
| 身份、工厂阴性对照 | 匿名/伪造平台头401，同数字ID真实工厂登录403，旧路径/缺失厂404，错厂项目和body/path厂不一致被拒绝且草稿零写入；`controls-result.json`。后两项上游403被既有Java客户端呈现为503，未将503冒称精确403 |
| 重启及实际存储读回 | Python进程重启后Java读回同项目、同job、同receipt hash；SQLite仅1条collect/succeeded/attempts1，B草稿0；`restart-readback.json` |

项目 `vtp-60fae754c352fbd32b02`；job `vjob-f8e736d354a67468e818809040f2ee58`；receipt SHA256 `4f0e256263e2e398197c8182f3f7d282b67433a0cd729a2d00595a5fc23d0ea9`。实际下载1张合成PNG，SHA256 `431ced6916a2a21a156e38701afe55bbd7f88969fbbfc56d7fe099d47f265460`；未修改原图、人工标注、客户数据或模型。

数据库为新建私有 `vision_entry_probe_20261015`，仅复用底座私有库的schema和全局catalog，不复制客户业务行。A/B工厂、平台92001与同号工厂用户均为本任务合成。实际PG读回产品、原料、生产批次数皆0。私有schema复用不是新迁移验收，不替底座Owner签署JPA/Flyway通过。

首轮浏览器因 Element Plus 将 `data-testid` 转发到 native input 而定位失败，修正测试定位后通过，产品断言保留；原失败截图/JSON保留在 `ui-generic/`。私有启动曾用错误 `embedding.mode=none` 导致缺Bean，改为仓库已有noop配置；合成图片端口18190已被他人占用，改为闲置18087，未终止他人服务。这些均不计产品失败。运行时、凭据和图片在代码外，禁止提交。

`VISION-ENTRY-UI-20261015` 子任务由 vision_entry_ui 在登记的两脚本scope完成，root修正native input定位并运行真实验收；子任务scope已释放。独立只读审查未发现本切片P1/P2；动态证据由root取得。旧公开URL404已在实际Java运行时验证。

## 交付边界和下一步

实现、本地测试和入口→采集回执整链已完成。此次没有提交数据集整理、训练、评测或模型启用；UI/服务显示本厂独立验收待完成，`training_success/accepted/enabled=false`。不能以合成collect通过代签各厂模型精度、真实训练、保护/正常集、ONNX parity、延迟或独立人工验收。

用户本轮明确要求“先不要合并部署，先单独一个branch，然后测试环境打开，我登录看看”。因此本批保持独立分支，本地提交后不push/合并/部署；启动本机隔离Java10060、Python18090、Web31761及既有私有PG55447供用户登录。仅测试数据，保留已完成合成collect项目。凭据放代码外私有文件，不进入此归档。

平台底座仍无PR且有Owner在飞修改，本视觉增量尚未进入main/生产，也没有本批hosted CI。以后必须先取得用户新的合并/部署指示，并等待底座Owner完成迁移/权限/既有业务门禁；只移入本任务的增量commit或patch，核对依赖差异，再运行必要的受影响检查。不要合并本验证分支整段历史或重复移植已有Python代码。当前代码scope只保留视觉client方法、vision Controller/测试、vision API/组件/验收脚本，以及入口import+挂载两个hunk；底座和生产仍归原Owner。
