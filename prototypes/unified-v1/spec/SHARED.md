# 共享件清单（D 线提供，各线必须复用，不另写一套）

## 第 1 批（2026-11-11 D 线交付，自测 `unified-v1\tools\verify-d-protocol.cjs` 16/16）
唯一实现：`ydwm-v1\public\design-system.js`（`window.CretasUI` 1.3.0），类型 `design-system.d.ts`，样式 `design-system.css`。味美页面直接用 `window.CretasUI.xxx`；六膳门 iframe 已在 `unified-v1\index.html` 加载同一脚本，Vue 组件从 `unified-v1\src\frame\ai-protocol.ts` 引用（`fmtMoney/fmtQty/fmtNumber/fmtPct/valueKind/safeRatio/ai()/aiState/sourceTagHTML/cretasUI`）。

1. **格式（T5）**：`fmtMoney(v,{currency='元'})` → `1,234.50 元`；`fmtQty(v,unit)` → `100.00 kg`，缺单位标「（单位未填）」；缺失一律 `—`，格式错 `格式无效`，0 保留 `0.00`；`safeRatio(a,b)` 零分母返回 `{value:null,reason}`，显示「无法计算：原因」；`valueKind` 区分 missing/zero/invalid/value。
2. **AI 协议**：`CretasUI.ai.STATES`（answered / draft / done / mismatch / queued / running / stale / missing / forbidden / unavailable / sourceError / rejected）；`ai.badge(state)`、`badgeHTML` 统一标签和颜色；`ai.TEXT` 统一降级文案；`ai.queueText({position,waitedMs})`（10 分钟后提示仍在排队）；`ai.sourceErrorText(404|502|503,name)`；`ai.cacheKey({workspace,objectType,objectId,filter,sourceHash,ruleVersion,question})`；`ai.loadPosition/savePosition(workspace)`；解释模式 `ai.checkParagraphs(text,facts)`（数字绑定、矛盾检查）+ `ai.renderChecked`（未通过段落折叠成「未核实内容」）。严格模式（reviewCodes）仍按各领域 facts 模块。
3. **来源与补充**：`CretasUI.sourceTag('blacklake'|'cretas'|'ydwm'|'missing'|'system')`；`gapNotice(parent,{title,detail,owner,onSupplement,onImport})`；`supplementForm({title,object,fields,onSubmit})`（表单 → 预览「来源值/补充后」+原因 → 确认 → onSubmit 回读 → Toast 带查看记录）；`supplementImport({title,templateHref,onParse,onConfirm})`（Excel 预检 → 确认）。保存、回读、撤销接口由各线自己实现。

## 第 2 批（2026-11-11 晚，19063 已生效）
1. **菜单与路由（D 唯一 Owner，需求直接发 D）**：真值在 `unified-v1\server.cjs`（`patchYdwmMenuMap` / `buildMenu`、`NEW_ENTRIES`、`MEMBER_LABELS`、`SEARCH_ALIASES`）与 `srcrameoutes.ts`。数据与分析组 = 经营总览 / 经营分析 / 成本分析 / 异常预警 / 数据工作台 / 自定义报表；/workdesk/* 302 → `/dashboard?role=sales|production|warehouse|purchase`；原型脚手架收进顶栏「原型与来源」；侧栏顶部「常用 · 固定本页」（按账号存浏览器）；菜单搜索支持新名、原名、别名。
2. **A2 味美工作区 frame 路由表**：`server.cjs` 的 `FRAME_ROUTES`。味美工作区访问登记路径时，外壳把内容区换成 `/__unified/lsm/index.html#<frame 路由>?ws=ydwm&<原查询>`，组件用 `S.query.get('ws')` 分辨工作区。
3. **A3 AiFloat 对象类型**：`srcramei-objects.ts` 提供 `registerAiObject(kind,{noun,describe,facts,explain?,isStale?,placeholder?})` 和 `setAiObject({kind,id},from)`；project/product 仍用 `store.setCurrent`。已注册：E 的 inspection、task。保留名：'task' 归 E（协同任务），生产任务请用 'lsm-task'。
4. **A4 后端挂载**：`/__lsm/supply` → C，`/__unified/e/*` → E（文件缺失时返回 503，不影响启动）；N9 `/__unified/reports`（D）。
5. **W2**：19063 上 `acceptanceSession` 或请求头会话名不合规 → 400，不回落。**AI 排队**：19063 统一排队（并发 2，不设上限，同内容去重）；`/__unified/ai-queue?ticket=` 查位置（请求头 `x-ai-ticket`）；用量只给管理员看（原型与来源 → 解读用量）。
6. **验收在 19063**：19063 请求页面时用味美 classic 组装，`public/*.js` 改动直接生效；19060 仍是冻结的 fast-ui 打包。

## 第 3 批（CretasUI 1.4.0，自测 18/18）
1. **B3 口径弹层**：`CretasUI.formula(parent,spec)` 在数字旁放「口径」按钮；`openFormula(anchor,spec)`，spec = `{title,result:{value,unit},plus:[{label,value,unit?,source?}],minus:[…],formula:'可用 = 现存 − 预留 − 待发',note?}`。代码核对加减结果是否等于显示值，不一致、缺项、单位不同都会标出。只读。
2. **B6 单据操作日志**：`diffFields(before,after,labels)` 得到字段级变化；`changeLogHTML(entries)`、`changeLog(parent,entries)`、`changeLogDrawer({title,object,load})`，entries = `[{at,by,action,reason?,source?,changes:[{field,label,before,after}]}]`，新的在前。
3. frame 里的 Vue 从 `srcramei-protocol.ts` 引用：`openFormula / diffFields / changeLogHTML / changeLogDrawer`。

## 待交付（D 进行中）
nuxt-bridge 成本跳转卡：代码已改，页面上还没生效，正在查。D3 个人 OA、D4 质量拍检入口、D5–D13 数据分析各页、味美 list-analysis 和 App.vue 悬浮窗统一。

## 第 4 批（第二批 · D⑥，CretasUI 1.5.0，自测 21/21）
味美页面用 `window.CretasUI.xxx`；frame 里的 Vue 从 `src/frame/ai-protocol.ts` 引用（`parseNos / matchNos / batchNoSearch / relativeRange / relativeDate / viewTabs`），在 onMounted 里挂到容器上。
1. **BL 21 批量单号查询**：`batchNoSearch(容器,{onSearch(nos), onClear?})` 在搜索框旁放「批量」按钮，弹层粘贴多张单号（换行、逗号、空格、顿号分隔，自动去重，最多 200 个）。onSearch 里用 `matchNos(rows, nos, r=>r.单号)` 精确匹配，返回 `{found: 条数, missing: 未找到单号[]}`，弹层显示「找到 N 条；未找到 M 个」并列出未找到的单号。不做单独的批量页。
2. **BL 22 已存视图 + 状态计数**：`viewTabs(容器,{page:'路由', builtins:[{id,name,state}] (2–3 个岗位视图，如「我的」「今天到期」「已逾期」), getState()=>当前筛选, apply(state), counts?:[{key,label,count}], countKey?, onCount?(key)})`。用户可「＋ 另存视图」，最多 5 个，按账号存浏览器（与侧栏常用同一套），可删除。计数小标签只用在状态少的列表（如三态）上，状态多的列表继续用下拉。返回值 `.refresh(counts, countKey)` 用来更新计数。
3. **BL 23 相对日期**：`relativeDate(容器,{value, onChange(r)})` 提供 全部 / 今天 / 本周 / 本月 / 近 7 天 / 近 30 天；`relativeRange(key)` 返回 `{key,label,from,to}`（yyyy-mm-dd）。**R18（11-12 起，CretasUI 1.6.0 内）**：「本周」= 本周一至本周日，「本月」= 本月 1 日至月末，不再截止到今天（截止日、到期日筛选不漏今天之后才到期的单子）；「今天」「近 7 天」「近 30 天」仍以今天为止。各线筛选里用 `from`/`to` 两端都要比较，不要只比 `from`。**视图里存 key，不存具体日期**，所以「本周到期」每天打开都是当天口径。

## 第 5 批（第三批 · D②，CretasUI 1.6.0）
1. **B6 接黑湖操作日志**：`blacklakeLog(rows)` 把黑湖 `operation_log` 行转成日志条目：时间取 `operateTimeString`（北京时间），动作 = `operateType · objectName`，来源 `blacklake`，操作人写「黑湖未返回操作人」，说明行写单号和「改动类别：系统字段/自定义字段；黑湖未返回字段前后值」，`changes` 为空（不补造前后值）。`loadBlacklakeLog({code|id|object, limit})` 读统一入口只读接口 `GET /__unified/d/lsm-oplog`（按 instanceCode / instanceId / objectName 过滤，新的在前，最多 500 条）；`blacklakeLogDrawer({code,label})` 直接打开抽屉。条目新增可选字段 `note`（说明，不是原因）。frame 里从 `src/frame/ai-protocol.ts` 引用。A 线工单详情已用 `changeLogHTML` + 自己的关联规则（按工单 id、任务 id、报工 id），不必改。
2. **六膳门财务口径**：`GET /__unified/d/lsm-finance`（只读 extras-current.json，按文件修改时间缓存）返回应收、已收、待收、多收或冲减、逾期未收、待开票（客户账本）及两项互相核对；组件 `src/home/FinanceLsm.vue`（`compact` 用在首页）。C 的「应收与账本」明细页可直接用这个接口的 `receivables / toInvoiceTop`，口径保持一致。

## 其余列表页接共享组件（BL 21 / 22 / 23）接入说明
已接情况以下面「已接 / 未接」清单为准（第四批审计退回第 2 条：此处原写 C 的采购精简视图「已接」，实际只接了已存视图 BL22，批量单号与相对日期未接）。其余列表按下面四步各线自己接，D 不替各线改页面。
1. **放在哪**：搜索框所在的工具条。批量单号紧挨搜索框；相对日期放在日期筛选旁；视图页签放在工具条上方一行。不另做批量查询页。
2. **怎么接**（Vue，在 `onMounted` 里挂，`onBeforeUnmount` 里 `element.remove()`）：
   - 批量单号：`batchNoSearch(el,{onSearch:nos=>{nosRef.value=nos;const m=matchNos(allRows,nos,r=>r.单号字段);return {found:m.found,missing:m.missing}},onClear:()=>nosRef.value=[]})`，过滤条件里加「单号在 nos 里」。匹配用**全部匹配记录**，不是当前页。
   - 相对日期：`relativeDate(el,{value:state.dateKey,onChange:r=>{state.dateKey=r?.key||'';state.from=r?.from;state.to=r?.to}})`。按北京时间日历日，本周从周一算。
   - 视图页签：`viewTabs(el,{page:'路由',builtins:[…2–3 个岗位视图],getState:()=>({...筛选,dateKey}),apply:s=>{…还原筛选；dateKey 再用 relativeRange 算日期}})`。**存 key 不存日期**。计数只用于三四个状态的列表，用 `.refresh(counts)` 更新；多状态列表保留下拉。
   - 味美编译页（没有源码的原页面）：照 `pages-d.js` 的 `QF` 配置加一项 `{api, no, date}`，D 来加，发 D 一行即可。
3. **验收怎么测**：粘 3 个单号（含 1 个不存在的）→ 显示「找到 2 条；未找到 1 个」并列出那个单号；选「本周」→ 列表与日期范围一致；另存视图 → 刷新后还在，切换账号后不串；清除后恢复全部。
4. **不做的**：不按单号模糊匹配；不把相对日期翻译成固定日期存；视图不超过 5 个。

## D10 文件导入步骤顺序：为什么不能调换（书面说明）
规格（D-home-analytics D10）希望「先保存后解读」或按规格重排。实际做不到，原因：
1. 文件导入页是味美原版编译后的页面（`/smart-bi/upload`，原版资源只读，规则禁止改编译资源），四个步骤「上传 → 解析与字段映射 → 分析 → 保存」写在原版组件的步骤状态里，按顺序推进，外层拿不到跳步或换序的入口（步骤之间传什么数据未逐项核对源码，只看了页面行为）。
2. 统一入口对编译页只能做适配（改文字、隐藏重复按钮、补说明），不能改组件内部的步骤状态和提交顺序；在外面强行调换会让保存提交的数据不完整，等于改业务行为。
3. 已做的替代：第 3 步改名「保存前预览（规则统计，尚未保存）」，去掉「AI 分析洞察」字样；隐藏与第 1 步重复的「上传并分析」按钮；面包屑改「数据与分析 / 文件导入」。
4. 真正调换顺序需要在味美源码里改导入组件，属于味美产品改动，应由 Steve 决定是否排进味美的版本，不在本原型范围内。

## 共享列表组件「已接 / 未接」清单（第四批 D③；11-12 19:2x 按代码检索 + D 自测，审计按此表测）
口径：「代码」= 源文件里调用了 `batchNoSearch`（BL21）/ `relativeDate`（BL23）/ `viewTabs`（BL22），是 D 按代码检索得到的，不代表已点验；「D 点验」= D 在 19063 实际操作过。各线点验以各自回执为准。

| 页面（路由） | 负责线 | 批量单号 BL21 | 相对日期 BL23 | 已存视图 BL22 | D 点验 |
|---|---|---|---|---|---|
| 味美 生产计划 `/production/plans`（原页面，pages-d 快速查找） | D | 已接 | 已接 | 未接（原页面无源码） | 第二批已点验 |
| 味美 采购订单 `/procurement/orders`（原页面，pages-d 快速查找） | D | 已接 | 已接 | 未接（原页面无源码） | 第二批已点验（列表为空，只测了「全未找到」） |
| 六膳门 经营分析 · 应收单 `/smart-bi/analysis-hub` | D | 已接（第四批） | 不接（5 张应收单，按到期日看即可） | 不接 | 已点验：找到 2、未找到 1，清除恢复 5 张 |
| 六膳门 经营分析 · 待开票前 10 | D | 不接（只列前 10 户，不是单号列表） | 不接 | 不接 | — |
| 自定义报表 `/unified/reports` | D | 不接（模板列表，没有单号） | 不接（周期在模板里选） | 不接 | — |
| 六膳门 生产工单 `/production/plans` | A | 代码已接 | 代码已接 | 未接 | 已点验：找到 2、未找到 1（GD202609099999），清除恢复 20 行 |
| 六膳门 生产报工 `/production/approval`（与生产工单同一组件 ProductionList，按钮「批量报工ID」） | A | 代码已接 | 代码已接 | 未接 | 已点验（第四批审计退回）：185494144、191944946、999999999 → 找到 2、未找到 1，清除恢复 20 行 |
| 六膳门 在制品 `/production/wip` | A | 未接 | 未接 | 未接 | — |
| 六膳门 黑湖只读页（销售 / 采购 / 采购需求 / 客户 / 供应商 / 价目表 / 委外 / 应收）`LsmExtras.vue` | C | 代码已接 | 代码已接 | 未接 | 销售订单已点验：找到 3、未找到 1（S20260052 不在第一页也找到），清除恢复 |
| 追溯 `TracePanel.vue` | C | 未接 | 代码已接 | 未接 | — |
| 味美 采购精简视图 `YdwmPurchase.vue` | C | 未接 | 未接 | 代码已接 | — |
| 味美 其他精简视图（销售、请购、供应商、客户、价目表、退货、调拨、盘点、库存）`YdwmSales/YdwmList/YdwmInventory/YdwmStock` | C | 未接 | 未接 | 未接 | — |
| 六膳门 库存 / 出入库 / 进销存 `StockPage/DocsPage/SupplyRecords` | C | 未接 | 未接 | 未接 | — |
| 质量检验 `QualityInspections.vue` | E | 代码已接 | 代码已接 | 未接 | — |
| 协同任务 `CollabTasks.vue` | E | 代码已接 | 代码已接 | 未接 | — |
| 检验标准、不良分析 | E | 未接 | 未接 | 未接 | — |

各线接完后改自己那一行（或发 D 一行，D 来改）。

## 第 6 批（第五批 · D①③）：两个只读工具，在「原型与来源」
1. **动作权限对照（只读）**：页面 × 动作 × 角色。动作和意图读 `/system-kit/account-flows.json`，页面模块读 `purpose-contracts.json`，当前账号权限读 `permissions/current`。其他岗位没有可读来源，一律写「未知」。写操作 = create / bulk / confirm / danger / import / review 等会改数据的意图；`scope=local`（只存浏览器的设置）标「本机」。不改任何权限。
2. **适配自检**：在隐藏框架里逐页打开味美页面（带 `__dselfcheck=1`，pages-d 只在这种框架里才运行适配），按探针判断：原版文字还在 = 未命中（红），新文字或注入元素在 = 命中，两者都不在 = 未触发（灰）。各线要加检查点：调 `CretasProtoTools.addProbes([{ id, route, name, from?, to?, sel?, cond? }])`，或把清单发 D 写进 `public/proto-tools.js` 的 EXTRA。注意：自检读的是味美页面本身的 DOM，读不到页面里面的统一框架 iframe。

## 第 7 批（第五批审计退回 · R21，CretasUI 1.7.0）：替换浏览器原生 prompt / confirm
**规则**：各线页面里不再用 `window.prompt` / `window.confirm` / `alert`，统一用下面两个设计系统小弹层（样式、键盘、关闭、错误提示与其他弹窗一致）。味美页面用 `window.CretasUI.xxx`；frame 里的 Vue 从 `src/frame/ai-protocol.ts` 引用 `askText` / `confirmReason`。
1. **`askText(o)` → `Promise<string|null>`**：一行文字输入。参数 `{title, label, placeholder, value, hint, maxLength=12, required=true, okLabel='保存'}`。必填为空、超字数时在弹层里显示错误、不关闭；取消、× 、Esc 返回 `null`。
   ```js
   const name = await CretasUI.askText({ title: '另存为视图', label: '视图名称', placeholder: '如「我负责的」', maxLength: 12 });
   if (!name) return; // 用户取消
   ```
2. **`confirmReason(o)` → `Promise<string|null>`**：「填原因确认」，用于撤销、作废、整批操作等需要留痕的确认。参数 `{title, object, message, reasonLabel='原因', placeholder, minLength=2, maxLength=200, required=true, okLabel='确认', danger=false}`。原因不够字数时报错不关闭；确认返回原因文字（调用方写进操作记录），取消返回 `null`。`danger: true` 时确认按钮为危险样式。
   ```js
   const reason = await CretasUI.confirmReason({ title: '撤销整批报工', object: '工单 GD… · 3 条报工', message: '撤销后这 3 条回到草稿，不回写黑湖', okLabel: '确认撤销', danger: true });
   if (reason === null) return; // 用户取消
   await api('/undo', { ids, reason });
   ```
3. **viewTabs「＋ 另存视图」已改用 askText**：名称必填、最多 12 字（输入框显示「n / 12」）、同名不保存；已存满 5 个时按钮仍在，点了先提示「先删掉一个再另存」，不弹输入框。
4. 顺带修了一处共享问题：弹层开关动画在页面处于后台时可能不结束，导致弹层关不掉；现在动画最多等 duration+150ms。

## BL22 口径补充（第五批审计退回第 2 条）
- **状态计数 = 除「状态」以外的当前全部筛选**（包括当前选中的视图、关键词、日期、批量单号等）下，各状态的条数。点计数只切状态，不改其他筛选；切视图后计数按新视图重算。
- **「打开原页面办理」是一次性动作**：只这一次进原页面（带 `view=native`），**不改**按账号记住的「精简 / 原页面」选择；下次从菜单进入仍按记住的选择打开。只有页头「精简视图」按钮和精简视图里的切换才改记住的选择。

## 第 8 批（第六批 · D①，R23，CretasUI 1.8.0）：Cretas ↔ 黑湖对账组件
用于 A「称重报工 ↔ 黑湖报工」、C「入库批次 ↔ 黑湖入库单」，以及 D 的「对账汇总」页。只比对、只读，不改任何一边，不回写黑湖。味美页面用 `window.CretasUI.xxx`；frame 里的 Vue 从 `src/frame/ai-protocol.ts` 引用 `reconcile / reconcileView / reconcileCSV`（类型 `ReconcileOptions` 等也从这里导出）。
1. **`reconcile(o)` 纯函数** → `{ rows, counts }`。
   - `o.cretas` / `o.blacklake`：两边记录数组。
   - `o.key`：匹配键，一个函数（两边同一取法）或 `{ cretas: r=>…, blacklake: r=>… }`。键为空的记录不参与。
   - `o.fields`：要比的字段 `[{ key, label, unit?, digits?, tolerance?(默认 0.005), cretas: r=>值, blacklake: r=>值 }]`。
   - `o.owner(row)`：返回「找谁」（如「车间」「仓库」），`o.label(r,row)`：返回显示名称（如产品名）。
   - 每行 `state`：`match` 一致 / `diff` 有差额 / `cretasOnly` 只在 Cretas / `blacklakeOnly` 只在黑湖；`diffs` 逐字段列出 Cretas、黑湖、差（`delta`）。
   - 口径：数值差的绝对值超过 `tolerance` 才算差额；**一边有值一边没值算差额，写「Cretas 未记录 / 黑湖未记录」，不当 0**；同一键在一边出现多条 → 按第一条比，并在行上标「同键 N 条」。排序：差额 → 只在 Cretas → 只在黑湖 → 一致。
2. **`reconcileView(容器, o)`**：在 `reconcile` 参数之外再给 `{ title, asOf, cretasAt?, keyLabel?, fileName? }`。
   - `asOf` 填**黑湖最近一次采集时点**（R23：每天 08:00 一轮），页面写「黑湖数据时点：…（北京时间）· 每天 08:00 采集一次，不实时」；没给就写「未知」。
   - 页签：全部 / 有差额 / 只在 Cretas / 只在黑湖 / 一致，带条数；表格每个字段一列「Cretas / 黑湖 / 差」，有差的格子标色；最后一列「找谁」；「导出 CSV」按钮（带数据时点和导出时间，防公式注入）。
   - 返回 `{ element, result, refresh(新参数) }`；数据变了调 `refresh({ cretas, blacklake, asOf })`。
3. **示例（A 的称重 ↔ 黑湖报工，按任务比良品重量）**：
   ```js
   reconcileView(el, {
     title: '称重报工 ↔ 黑湖报工', keyLabel: '工序任务', fileName: '称重对账',
     cretas: weighByTask, blacklake: lakeReportsByTask, asOf: snapshot.checkedAt,
     key: { cretas: r => r.taskId, blacklake: r => r.taskId },
     fields: [{ key: 'good', label: '良品', unit: 'kg', cretas: r => r.goodKg, blacklake: r => r.qty }],
     owner: row => row.state === 'blacklakeOnly' ? '车间（补称重）' : '车间',
     label: r => r.projectCode + ' · ' + r.processName
   });
   ```
   两边单位不同时请先换算成同一单位再交给组件；组件不做单位换算。
4. 自测：`verify-d-protocol.cjs` 第 22 条（四类、容差、缺值不当 0、同键重复、CSV）。

## 第 9 批（第六批 · R23）：来源标签加「Cretas 主来源」
- `CretasUI.sourceTag('cretasMain')` / `sourceTagHTML('cretasMain')` → 标签「Cretas」（绿色），悬停「Cretas 主来源（R23：六膳门报工称重、入库批次以 Cretas 录入为准）；黑湖值只作对照，不回写黑湖」。原来的 `'cretas'` 仍是「Cretas 补充」（黄色），两者分开用。
- 类型：新增 `SourceTagKind = DataSource | 'cretasMain'`，`sourceTag / sourceTagHTML / SOURCES` 用它；`DataSource` 本身**没改**（E 等线有 `Record<DataSource,…>`，改了会要求补一项）。frame 里从 ai-protocol.ts 引用 `SourceTagKind`。
- **第 8 批补充（C 反馈）**：`fields` 里每项可加 `type: 'text'`（单位、规格、编码等），按文字完全相同比较、按原文显示（不会把「001」格式化成 1.00）。不写 `type` 时：两边都是数字按数值比（容差）；两边都不是数字自动按文字比（以前会误报「格式无效」，已修）；一边数字一边文字算「内容不同（一边是数字一边是文字）」。
