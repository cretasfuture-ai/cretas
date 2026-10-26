# ERP-S311-SOURCE-ASSIGNEE-20261023

Root DIRECT final CI correction scope (Base0fab0115359e45ac917abb781ea3f3b2c6bce8b0): release frontend/CretasFoodTrace/jest.config.js and .github/workflows/rn-gate.yml only, unique Owner Root. No other registered exact-file Owner found in fresh ACTIVE/worktree check. Actual0fab RN passed314suites/3325tests but took1119.849s due per-suite worker recycle. Independent native all314/3325 passed489.844s with one in-band4GiB process, sampledRSSpeak3313659904bytes/2CPU affinity; this is not a same-host CI speedup claim. Remove idle recycle, use one Jest process and explicit4GiB heap; unchanged3CPU/6GiB runner, labels, timeout, suite/selection/assertions and TypeScript step. Actual final WSL CI must validate once; Java/Web product trees and trusted artifacts are unchanged. Hold push until S308 gates finish so no extra Java full steals its queue. No production changes.

Root DIRECT supplementary CI scope: frontend/CretasFoodTrace/jest.config.js only; Base da5b4ce9003eaae1a597493a3c435c535a0b7f2c. Actual RN job120109438087 reached ~2GB V8 heap and failed without assertion failure. Recycle idle CI workers at768MB without changing tests, worker count, heap/resource limits or selection. Acceptance: existing TimeClockLocationRecovery targeted tests, Jest resolved config, then current full RN CI. Java/Web product trees unchanged; valid same-tree evidence remains reusable. No runner/process changes or production writes. Original failure retained in private s311-rn-ci-failure-20261023.

Base e754b5e37006d13d0046a9f50f0e23df0b86344d。唯一写Owner /root/s311_source_assignee，Root协调/接口/正式检查/集成发布，原QA独占手机/浏览器/canonical CSV。WT C:/Users/Steve/cretas-s311-source-assignee-20261023，branch codex/s311-source-assignee-20261023。你不是唯一在代码库工作的人，不覆盖/回滚其他Owner改动，不生成孙代理。

原QA真实1557/F006 normal Web已证明缺来源带入、检验ID与处理人数值手填，S3-11 FAIL，缺陷 ERP-S3-QUALITY-SOURCE-ASSIGNEE-001。原件私有execution/s311-source-original-qa-credential-verified-20261023/receipt.json及六张截图，业务写0；保存刷新/后端阴性未执行。Root单次只读核对现有文档化F006凭据与1557当前bcrypt一致，无密码/密文输出或落盘，无改角色/重置。

精确允许6文件：
- backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/QualityDefectServiceImpl.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/QualityDefectSourceAssigneeContractTest.java（新）
- web-admin/src/api/qualityDefect.ts
- web-admin/src/views/quality/defects/list.vue
- web-admin/src/views/quality/inspections/list.vue
- web-admin/src/views/quality/defects/__tests__/sourceAssignee.spec.ts（新）

接口裁决：复用同厂processing/quality/inspections列表/详情、users/directory最小字段端点，不新增全局用户权限/HR访问或共享composable。处理人是本厂活跃且未删除人员，不意味着转授quality权限，不自行规定只有质量角色可被分派。后端写入前复用既有UserRepository.findByIdAndFactoryId校验非空assignedTo的工厂、isActive及deletedAt；串厂/不存在/失效均明确拒绝且不保存。保留既有null选填服务兼容，不默默将失效选择变null后提交。原缺陷factory/来源校验、关闭/分派状态与权限必须保持。无需改Repository/Entity/迁移/Security/Controller/DTO接口。

来源：真实检验详情/列表提供“登记不良”受原quality写权限保护入口，正常带入可验证同厂来源；不良表单提供可见真实来源选择而非主键搬运，失效/越权来源不加载或阻止提交。人员选择必须可读姓名/岗位或部门，加载失败/工厂切换清空旧候选、旧选项不暗留。候选来自服务端同厂directory，不造人/造检验；不修改既有检验结果、配方或库存。

目标验收命令（本scope内、独立target）：Web npm exec -- vitest run src/views/quality/defects/__tests__/sourceAssignee.spec.ts --maxWorkers=1 --minWorkers=1；Java mvnw.cmd test -Dtest=QualityDefectSourceAssigneeContractTest -Dcretas.testIncludes=**/QualityDefectSourceAssigneeContractTest.java。mounted真实表单带入/候选/保存刷新请求、来源/人员失效或串厂阴性，以及Service原方法工厂/活跃性/零保存拒绝与有效正例必须实测；不改既有断言/基线。只目标测试，不全量/构建制品/发布/生产mutation；RN、调拨、采购、供应商、Gateway、Orchestrator、S2/S6、shared workflow无写scope。

资源：Web单worker/Node≤1.5GB；独立node_modules，不junction/不改lockfile。Java与Root在跑S308本地Maven重任务必须错峰，Root明确释放前不启动Java；之后Maven2CPU/≤2GB，独立WT/target，不使用他人DB。禁止WSL/Docker/runner变更、共享设备/浏览器、提交ACTIVE/CSV。回执包含task/base/实际文件/目标命令退出/内容hash/commit/未验项，源码完成不代替原QA验收。S308先交付，本包不得阻塞其冻结候选，也不得在其正式发布中途合main。


接收更新：原代码Owner已释放，Root接收e98e5fb802bf50b0453bc6d7fd5eb4f13baacbd0六文件，14项Java/5项Web退出0，独立风险复核PASS。Root将冻结S308统一候选6e980d3正常合入本独立候选，不改S308 PR。后续构建和检查以本集成候选为准，未发布/未业务验收；S308优先交付，发布期间不合本包。原QA及canonical资源归属不变。
