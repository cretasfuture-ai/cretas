# 六膳门成本页：指定原型组件与配色对齐

2026-11-04；本轮基于 `f015b1f07ead743d0f4dcaa48207a01517bbd0bc`，PR #1390 后续切片。测试页更新，未生产发布。

## 对齐依据

用户指定对话 `01a18137-a2c6-7461-85c6-c55279ba0726`（优化 Cretas 管理端 UI）。实际读取其本机 `Documents/Cretas-admin-双角色完整原型-20261028/samples-v5`，以 `src/style.css`、`src/main.ts`、`src/fusion/CostOverview.vue`、`vite.config.ts` 及安装版本为依据。没有把另一套官网设计或当前正式站主题冒充这份原型。

| 项目 | 原型值 / 本轮实现 |
| --- | --- |
| 组件组合 | Nuxt UI 4.12.0 的 App/Card/Button/Badge；复杂业务表格、选择器、抽屉继续使用 Element Plus |
| 工具版本 | Tailwind 4.3.3、Lucide 图标资源 1.2.148，与参考原型的本机安装一致 |
| 品牌蓝 | `#1b65a8`；浅蓝 `#eaf2fa` |
| 页面 / 卡片 | `#f4f6f9` / `#ffffff` |
| 正文 / 次级正文 | `#1a293d` / `#41536a` |
| 边框 / 圆角 | `#e5eaf1` / 10px |
| 状态色 | 成功 `#29816b`、提醒 `#b38336`、风险 `#bb635f` |
| 字体 | Segoe UI / Microsoft YaHei / 系统无衬线字体 |
| 外壳 | 232px 侧栏、61px 顶栏；侧栏可折叠，手机抽屉导航，尊重减少动效设置 |

原型来源文件的 SHA-256 见同目录 `PROTOTYPE-UI-VALIDATION.json`。这是一份按真实数据改造的业务页面，不声称像素级复制原型的模拟数据画面。

## 页面变化

- 首屏显示 16/109 可核算工单、93 张待补、143 行关联领退料及 74 行源端零值。14.7% 明确为工单数覆盖率，不是金额归集覆盖率。
- 用同成品单位的材料/人工堆叠图解释产品单位直接成本；按各组成本合计除以良品合计，保留产品代码、名称和单位边界。缺编码按工单隔离并显示工单标识；首尾空格编码不静默归一。
- 成本差异信号、数据质量、核算准备条件入口可直接进入异常列表；没有跨产品、单位或上下游工序拼凑总成本。
- 每个数据块下保留明显、紧凑的结论/影响/下一步，计算依据和假设折叠。追问默认关闭，按板块独立打开。同产品价量差额和逐物料证据按需展开。
- 异常可跳到相关板块并绑定告警 ID 追问。更新归档、刷新、用户/角色或事实/规则变化时丢弃旧选择与迟到回答。服务器仍校验当前 run/section/alert 的归属。
- 原型概览图使用显式 plain 模式，不自动添加极值/离群标记或工具栏，以免装饰被误解为业务异常；公共图表默认 standard 模式行为保留，并有回归测试。

## 跟进记录

在 Cretas 隔离测试环境新增“未处理 / 待补资料 / 已核对”，必须填写本次说明。记录按 tenant/sourceFactory/run/alert 隔离，保存用户、时间、版本和状态审计；乐观版本冲突返回 409。界面刷新后实际回读，不用浏览器假状态。

存储为本机私有 `alert-followups.sqlite3`，DDL 在 `blacklake/alert_followups.py`；只写测试侧元数据，不修改原始 JSON、成本、异常规则或黑湖业务数据。标记“已核对”不改变原来的核算阻断。备注不发给模型、不输出日志。

`POST /api/smartbi/blacklake/alerts/{alert_id}/followup` 与已有接口一样，先验证固定测试工厂管理员身份及真实 Java FULL 成本授权，再读取当前归档和验证 alert，最后保存元数据。`GET analysis` 返回本范围 `alertFollowups`。`sections/followup` 可带当前异常 ID，但仍是确定性证据解释 + 27B 白名单核对动作，未变成不受约束的自由归因。

## 隔离与验证边界

Nuxt 原型页拆为 `BlacklakePrototypeAnalysis.vue`、`PrototypeSectionInsight.vue`、`BlacklakePrototypeCostAlerts.vue`，只由隔离测试入口使用。正式路由原有 `BlacklakeAnalysis.vue`、`SectionInsight.vue`、`BlacklakeCostAlerts.vue` 保留，不向未装 Nuxt 运行条件的正式应用混入原型组件。Vite 插件和原型 CSS 只在对应测试模式启用。

目标验证：后端 266 passed、0 skipped；前端 35 passed；Vue 类型检查通过。原型模式、默认正式入口分别构建，最终结果见验证回执。原型浏览器验证包括七板块问答、默认收起、导航折叠、告警上下文、状态保存后刷新恢复、再次刷新清理上下文、无自动极值标记，以及 390px 菜单收起后的布局。独立风险审查提出的入口依赖、过期上下文和同名产品视觉歧义均修复后复读。

原始六膳门快照仍为 2026-11-03 17:21:49（北京时间），16/93 及直接成本口径没有放宽。完整制造成本、币种、实际投入覆盖和逐批原料对应仍待业务核实。本任务没有黑湖业务写入、生产部署或全局主题发布。

入口仍为 `http://127.0.0.1:5178/blacklake-smartbi.html`。浏览器 JSON/截图保存在私有 `Blacklake/outputs/prototype-20261104`，构建和类型日志在 `SmartBIAlerts/prototype-*`，Python 日志为 `Blacklake/validation/pytest-prototype-final.*`。`node web-admin/scripts/blacklake-prototype-acceptance.mjs` 验证脚本只允许已读当前告警的本机跟进元数据写入，源业务写入保持 0。

保留首次选择器误判、下拉框点击位置和等待刷新/菜单动画的仪器失败；修正的是验证定位及等待。构建准备曾因扫描/内存边界中止，最终设置 8GB Node 堆并限制 Tailwind 扫描源目录。依赖安装仍报告既有/传递依赖审计项，本次未自动执行 audit fix。CSS 的 legacy `@charset` 优化警告与构建结果分开记录，不能用构建通过替代视觉检查。

## 本机直接进入

按用户补充要求，六膳门本机测试入口已移除账号密码输入，打开时自动取得真实测试会话。代理只在 blacklake 开发服务器及固定 loopback 同源页面启用；凭据仍由本机服务从 DPAPI 读取，并向实际 Java 登录和成本授权接口验证，浏览器不接收密码、哈希或签名密钥。正式入口及分析接口的认证要求没有放宽。新增验证为 3 项 Python、17 项前端守卫/会话测试、类型检查及完整真实页面流程；实际外部 Origin/缺少预览请求头为 403，分析接口无 Bearer 仍为 401。
