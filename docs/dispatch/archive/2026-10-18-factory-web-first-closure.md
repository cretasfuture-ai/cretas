# FACTORY-FINAL-20261018 Web首版技术收口

Base `49ec925c96175d65148ef728afc059346ce68dad`；Owner root；阶段：实现及本地/UI验收完成，精确head最终CI及手动合并结果见GitHub与私有交付回执。生产未访问、未部署。真机由用户明确单列。

总表及四主线证据映射见 [Web首版技术验收收口](../../acceptance/factory-assembly-web-first-closure.md)。复用既有真实业务链，12项补缺全部完成；真实用户签署不由测试账号代签。

## 本批代码及验证

- 显式工厂受限策略下，未适配行级范围的生产计划/财务入口明确拒绝；ALL、已适配路由和未配置覆盖的原操作员继承行为保留。只改DataScopeRouteInterceptor及新增RestrictedBusinessScopeContractTest，不改Entity/Repository/迁移。
- 原实现对照10项中8项断言失败、0错误；最终相关5类共67项通过、BUILD SUCCESS。真实UI及服务端拒绝403，恢复ALL后读回正常；原操作员计划GET200。
- HR部门编辑省略未改的历史用户名，主动改名及新建保留校验；相关3类26项通过，原实现2项断言失败；真实UI保存及组织恢复200。
- 冻结后的Web类型检查/构建成功。源码冻结后不重复本地全量，最终Java全量/原生PG及Web门禁以PR同一head日志为准。
- 55条业务/租户/权限不变量通过；工艺/生产逐行比较通过。真实AI提取及生成预览成功，新草稿不发布、不应用；原访谈及已应用绑定不变。
- AI/liveness/readiness UP；综合health503的本地邮件检查失败单独保留，未关闭检查。真实用户签署、原生真机、生产启用及生产恢复演练未执行。

## 收回scope

`FACTORY-FINAL-SCOPE-20261018`（final_scope_fix）：仅拦截器与新增安全测试，回执及独立SCOPED PASS已核对。

`FACTORY-FINAL-HR-20261018`（final_hr_fix）：仅员工页与组织组件测试，红绿证据和真实UI已核对。

`FACTORY-FINAL-AI-20261018`（final_ai_runtime）：仅私有向量服务/制品/证据，无源码改动；gRPC SERVING与实际编码已核对。

父任务 `FACTORY-FINAL-20261018`：源码及测试冻结，本commit归档并释放本批scope。私有原始证据位于原验收目录 `target-ui/factory-final-*`，不提交账号、令牌、私有参数或原始业务样本。PR检查失败时仅按具体失败修复及复验，不以历史绿灯替代本次head，也不自动合并。
