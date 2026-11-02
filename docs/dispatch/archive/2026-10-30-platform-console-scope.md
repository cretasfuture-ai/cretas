# PLATFORM-CONSOLE-20261030 scope

Base: cf6d18e1bf8656caafc9041aaabc1c9f264c335f。Root为协调者；代码两个执行者独立目录，禁止孙代理，不改ACTIVE，不提交，回执后root统一审查提交。

## 用户确认与目标

最终组件边界：只交付Java与Web。查看入口只开放已审的六个Java列表/首页，不向Python或BI路径发送查看请求；已有Python受保护接口无法验证域隔离查看签名，不获得任何平台/工厂数据权限。工厂账号的BI状态与普通分析流程保持原状。曾准备的Python纵深防护已从本PR移出并留存私有补丁；Python全量CI发现既有模型注册表21日复审到期，此处不修改审计日期、不削弱门禁，也不部署不属于菜单整理所需的服务。此段覆盖下文初始跨端计划。

平台日常只显示平台管理菜单；保留“进入指定工厂”的协助查看入口。平台管理员可以选择每个工厂配置网页大菜单和子菜单显示；工厂账号不能自行改平台开放范围。这是菜单显示管理，不借机授予业务权限，不删除业务数据。

## UI scope

web-admin/src/components/layout/{AppSidebar.vue,AppHeader.vue,menuConfig.ts,factoryMenuVisibility.ts}及对应测试；src/router/{index.ts,guards.ts}及对应测试；src/store/modules/{auth.ts,permission.ts,platformFactoryContext.ts}及对应测试；src/api/{request.ts,platformFactoryMenus.ts}及对应测试；src/views/platform/factories/**、src/views/system/features/index.vue及对应测试。必要的auth token读取桥接可在现有utils/auth工具内最小修改并回报。禁止后端、共享脚本、package/lockfile改动。

## API scope

新增 com/cretas/aims/controller/platform/PlatformFactoryMenuController.java、对应DTO/service及目标ContractTest；现有 FeatureConfigController 敏感配置字段防护；最小现有JWT/AuthenticatedActor/只读Interceptor/WebMvcConfig扩展与目标测试；Python现有JWT认证中间件只读claim校验及目标测试。禁止修改Entity/Repository/迁移、模块开通/发布版本、全局factory-assembly灰度。需要越scope或改共享持久化契约先回报root。

## 约定API

- GET /api/platform/factories 使用既有真实工厂列表。
- GET /api/platform/factories/{factoryId}/menu-config 返回 {success,data:{factoryId,factoryName,modules:[{moduleId,moduleName,visible,configured,disabledMenuPaths}]},message}。
- PUT /api/platform/factories/{factoryId}/menu-config/{moduleId} 请求 {visible:boolean,disabledMenuPaths:string[]}，只合并 config.menuVisible / disabledMenuPaths；analytics 同步 showInSidebar，保留其他JSON字段。moduleId 使用Web权限目录，smartbi legacy 作为 analytics 的兼容读取；其他工厂不动。没有记录时返回沿用现有显示逻辑的 configured=false，不假称已有开通配置。此API仅控制网页显示，不修改enabled、FactoryModuleConfig或发布版本。
- POST /api/platform/factories/{factoryId}/view-context 返回 {success,data:{factoryId,factoryName,factoryType,accessToken,expiresIn,readOnly:true},message}。短期令牌保留 PLATFORM_ADMIN actor/id/真实role，带服务端签发的工厂查看范围和只读claim，禁止模拟factory user。Java及Python验证该上下文的目标factory、过期、只读方法/已知只读query POST；拒绝业务写入、跨工厂、伪造/缺失scope。现有普通令牌行为不扩权。
- 管理API只接受真实平台 super_admin/platform_admin；不得仅靠前端隐藏或可能失效的注解。工厂用户旧PUT不得改变menuVisible/showInSidebar/disabledMenuPaths，其他合法配置保持可编辑。

## UI行为

独立平台工厂列表和每厂菜单配置页；菜单选项复用现有menuConfig树，不维护第二份手工路由名单。保存、刷新、切厂状态隔离和失败提示必须真实。查看上下文与登录身份分离，显著显示工厂名、只读和“返回平台”；不改造/覆盖平台用户成工厂用户。业务查看使用短期view token，管理API保留原登录token；view token失效不可静默回落到原平台token重试业务请求。canWrite在查看模式为false，业务网络写入另作guard；共享query POST名单仅允许实际只读契约。平台日常不显示生产/仓储/采购/销售等工厂日常菜单；仅保留确实能在无工厂上下文工作的平台工具，条件灰度不打开。

## 验证与交付

Root整合审查确认的必要UI扩展：`src/views/login/index.vue`（平台登录落点）、`src/api/smartbi/common.ts`与`restaurant-agent-runs.ts`（直连请求使用查看凭证）、`src/utils/activityTracker.ts`与`clientErrorReporter.ts`（查看模式停止可选遥测写入）及对应目标测试。UI执行者已交回写Owner，后续由Root整合；不修改其他业务行为。

网络审查发现存量页面有原生fetch/XHR携带原平台凭证，补登记 `PLATFORM-CONSOLE-NETWORK-20261030`，同一Base SHA，Owner UI执行者；只允许 `web-admin/src/utils/platformFactoryViewNetwork.ts`、对应`__tests__`测试和`src/main.ts`启动接线。读取但不改Root拥有的platformFactoryContext/request/common。目标验收：原生fetch/XHR只读和短期凭证、跨源上传拒绝、失效不回退、Request/Header/relativeURL/同步XHR/恢复测试，目标Vitest。与API/root源码scope不重叠；不改ACTIVE，不提交发布。

Root终审扩展：真实菜单快照`backend/java/cretas-api/src/main/resources/platform/factory-menu-paths.json`及前端漂移测试；该资源只用于菜单路径闭集，不影响Entity/Repository。查看凭证暂不开放任何POST（存量查询可能解析工厂用户数字ID或写缓存），因此Root只在查看模式停止`useListSummary.ts`、`useLinkChipCounts.ts`的可选查询，并由`components/list/{TableFooter,LinkChipCell}.vue`明确显示未提供/未加载，禁止伪造零统计；对应目标测试同步覆盖，普通工厂账号行为不变。

Root安全收口scope：新增Java/Python正向只读策略与镜像资源，查看页限定首页/生产计划/仓储物料/采购订单/销售订单/标签质检六页；Header通知、Sidebar计数、PendingApprovalsWidget和L4用户权限在查看态不加载工厂用户专属数据。新增FactoryFeatureMenuWriteLock并接平台/旧配置/ModuleSwitchService，真实PG并发与原正式激活测试验收；不改Entity/Repository/迁移或全局灰度。仅view marker使PriceFieldResponseAdvice与PriceMaskResolver直接脱敏、不查工厂用户，审计从typed actor取原平台租户。`.github/workflows/java-gate-pr.yml`仅把新并发测试加入既有真实PG选择器；独立终审覆盖。所有扩展Owner Root，子代理已回交写权。

最终Web补充：只在平台查看态停止sales/orders/list.vue的可选Gold合计请求，common与原生网络守卫在发送前拒绝Python路径，避免未开放服务的401误使Java查看凭证过期。对应正反例覆盖，普通工厂账号继续原路径。跨语言镜像测试已改为Java资源的正向/拒绝集合；无Python净差异。

回滚边界：查看JWT使用域隔离HMAC-SHA256派生密钥，旧版主密钥解码器拒绝，防止回滚后将查看凭证提升为普通平台凭证。浏览器查看请求统一`X-Client-Type=platform-factory-view`，保留旧版native-skip语义；WebLoginSessionFilter新增限制性skip，禁止用原平台HttpOnly会话覆盖显式查看Bearer。真实cookie→filter→interceptor与旧主密钥拒绝测试覆盖。签发与审计目标ID统一最多64字符（现网8个工厂实时回读均满足）；不扩数据库列/角色权限。

执行者只做目标测试，报告文件/版本/命令/退出码/日志/阻塞与下一步。UI独立node_modules，不用junction；API独占本worktree的Maven target，使用有界堆，不并发Maven；生产写入、发布由root执行。Root整合后一次必要Web全量/typecheck/build、Java权限与跨厂/只读目标门禁、独立高风险终审、CI、PR/main及服务发布。复用同内容成功证据，不重跑无变化切片。UI验收覆盖平台不见工厂菜单、选厂保存回读、只读查看与返回平台、工厂用户不能改菜单、原工厂业务菜单不被全局删除。测试/服务部署/真实业务验收分开报告。
