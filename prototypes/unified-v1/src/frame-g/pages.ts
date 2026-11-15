// G 线骨架页规格：重整后菜单、岗位一天、各骨架页的页签 / 筛选 / 主操作 / 列 / 行操作。
// 只描述结构，不做计算、不填数字；数字位一律「—（骨架）」或写明数据来自哪里。
// 链接：'/g/*' 是本线骨架页；其他路由是原型已有页面；'F:*' 是 F 线数字班组路由（以 F 登记为准，见 F_ROUTES）。

export interface Act { label: string; to?: string; say?: string; primary?: boolean; write?: boolean }
export interface Tab { id: string; label: string; filters?: string[]; actions?: Act[]; cols?: string[]; rowActs?: Act[]; source?: string; note?: string }
export interface Page {
  route: string; title: string; place: string; crew: string; line: string; formal: string[];
  why: string; tabs: Tab[]; links?: Act[]; reference?: boolean
}

// F 线路由（11-12 按 impl-F / D 登记的实际路由；菜单里查不到时点击只弹说明，不乱跳）
export const F_ROUTES: Record<string, { route: string; label: string }> = {
  home: { route: '/crew', label: '数字班组首页' },
  inbox: { route: '/crew/inbox', label: '等我确认' },
  records: { route: '/crew/ledger', label: '工作记录与额度' },
  onboard: { route: '/crew/onboard', label: '班组入职' },
  reports: { route: '/crew/reports', label: '主动汇报' },
  mobile: { route: '/crew/mobile', label: '一线手机端' },
  map: { route: '/crew/map', label: '功能归属与架构' },
  // R33：「发现的问题」一页归 F（/crew/issues），/g/issues 直接转过去；F 未登记前保留骨架
  issues: { route: '/crew/issues', label: '发现的问题' },
  clerk: { route: '/crew/worker?id=clerk', label: '数字文员工作台' },
  keeper: { route: '/crew/worker?id=keeper', label: '数字仓管工作台' },
  planner: { route: '/crew/worker?id=planner', label: '数字计划员工作台' },
  inspector: { route: '/crew/worker?id=inspector', label: '数字质检员工作台' },
  accountant: { route: '/crew/worker?id=accountant', label: '数字成本会计工作台' },
  // R27：「设置 › 数字班组配置」单独一页，归 F，下一批做；未登记前点击只弹「下一批由数字班组做」
  settings: { route: '/crew/settings', label: '数字班组配置' }
}

export const CREW = [
  { id: 'clerk', name: '数字文员', does: '单据录入与整理、审批材料汇总、订单与客户资料' },
  { id: 'keeper', name: '数字仓管', does: '入库建批次、库存与临期、盘点差异、出库建议批次' },
  { id: 'planner', name: '数字计划员', does: '排产与急单、缺料核对、交付风险、采购建议' },
  { id: 'inspector', name: '数字质检员', does: '检验待办、放行、CCP 与偏离、处置与召回、追溯' },
  { id: 'accountant', name: '数字成本会计', does: '出成与成本差异、毛利、应收待开票、对账' }
]

// 重整后的一级菜单（智能体优先）。kind: agent = 以事为中心的入口；biz = 第二层单据；set = 下沉设置
export interface NavItem { label: string; to: string; note?: string }
export interface NavGroup { label: string; kind: 'agent' | 'biz' | 'set'; items: NavItem[] }
export const NAV_AFTER: NavGroup[] = [
  { label: '今天', kind: 'agent', items: [{ label: '今天', to: '/g/today', note: '原「首页」+ 七个岗位工作台 + 工厂管理工作区「经营总览」' }] },
  { label: '数字班组', kind: 'agent', items: [
    { label: '班组首页', to: 'F:home', note: '含本厂启用哪几位；五位员工的工作台从首页卡片进，不单列' }, { label: '工作记录与额度', to: 'F:records' }, 
    { label: '班组入职', to: 'F:onboard', note: '原「建厂就绪」。一线手机端 /crew/mobile 和功能归属 /crew/map 都不进管理端侧栏（味美在「原型与来源」，六膳门从班组首页底部的链接进）' }] },
  { label: '待办', kind: 'agent', items: [{ label: '待办', to: '/g/todo', note: '一个入口，页签：等我确认（数字班组收件箱）/ 待我审批 / 指派给我 / 我发起的 / 抄送。原个人 OA 四页和协同任务留在「全部功能」原位' }] },
  { label: '发现的问题', kind: 'agent', items: [{ label: '发现的问题', to: '/g/issues', note: '数字班组统一做的一页（/crew/issues），和「主动汇报」合一，按来源分：数字员工汇报 / 规则预警 / 对账差异 / 数据缺口，录入错误另列「数据疑点」' }] },
  { label: '计划与生产', kind: 'biz', items: [
    { label: '生产计划 / 工单', to: '/production/plans' }, { label: '智能排产', to: '/g/scheduling' }, { label: '交付与备货', to: '/g/delivery' },
    { label: '称重报工', to: '/production/weigh' }, { label: '领料', to: '/production/material-flow' }, { label: '生产总览', to: '/production/kanban' }, { label: '委外收发', to: '/lsm/outsource' }] },
  { label: '产品与工艺', kind: 'biz', items: [
    { label: 'SKU 与产品', to: '/system/products' }, { label: '工序与 BOM', to: '/production/process-hub' }, { label: '出成标准', to: '/production/yield-standard' },
    { label: '工程变更与调价', to: '/g/engineering' }, { label: '研发样品', to: '/g/rd' }] },
  { label: '仓储与批次', kind: 'biz', items: [
    { label: '入库与批次', to: '/warehouse/materials' }, { label: '出库执行', to: '/warehouse/shipments' }, { label: '库存', to: '/warehouse/inventory-query' },
    { label: '调拨', to: '/transfer/list' }, { label: '盘点', to: '/warehouse/stocktake-query' }, { label: '仓位与平面图', to: '/warehouse/storage-locations' }, { label: '报损与其他', to: '/g/warehouse-more' }] },
  { label: '采购', kind: 'biz', items: [
    { label: '请购', to: '/procurement/requisitions/my' }, { label: '采购订单', to: '/procurement/orders' }, { label: '采购建议', to: '/procurement/ai-purchase' }, { label: '收票、退货与核价', to: '/g/purchase-more' }] },
  { label: '销售', kind: 'biz', items: [
    { label: '销售订单', to: '/sales/orders' }, { label: '出货与退货', to: '/sales/shipments' }, { label: '应收与收款', to: '/lsm/receivables' }, { label: '报价、跟踪与提醒', to: '/g/sales-more' }] },
  { label: '质量与追溯', kind: 'biz', items: [
    { label: '检验', to: '/quality/inspections' }, { label: '放行与处置', to: '/g/quality-loop' }, { label: '追溯与召回', to: '/g/trace' },
    { label: '不良分析', to: '/quality/defects' }, { label: '标签拍检', to: '/quality/label-qc' }] },
  { label: '财务', kind: 'biz', items: [{ label: '财务', to: '/g/finance', note: '应收应付、开票收票、收付款、凭证、期间、报表、毛利' }] },
  { label: '经营', kind: 'biz', items: [
    { label: '经营总览', to: '/smart-bi/dashboard' }, { label: '经营分析', to: '/smart-bi/analysis-hub' }, { label: '成本分析', to: '/lsm/cost' },
    { label: '对账（Cretas ↔ 黑湖）', to: '/lsm/reconcile' }, { label: '经营报告与指标', to: '/g/reports' }, { label: '自定义报表', to: '/unified/reports' }] },
  { label: '设置', kind: 'set', items: [
    { label: '主数据', to: '/g/settings?tab=master' }, { label: '组织与人员', to: '/g/people' }, { label: '设备台账', to: '/g/equipment' },
    { label: '规则与阈值', to: '/g/settings?tab=rules' }, { label: '数字班组配置', to: 'F:settings', note: '单独一页（/crew/settings），由数字班组做' }, { label: '数据与日志', to: '/g/settings?tab=data' }] }
]

// 现状（统一原型 19063 六膳门工作区菜单，11-12 23:2x 读 /__unified/menu.json）
export const NAV_BEFORE: { label: string; items: string[] }[] = [
  { label: '概览', items: ['首页'] },
  { label: '个人 OA', items: ['审批中心', '协同任务'] },
  { label: '采购管理', items: ['采购需求', '采购订单', '采购建议', '供应商资料'] },
  { label: '销售管理', items: ['销售业务', '应收与收款', '客户管理', '客户价目表'] },
  { label: '生产管理', items: ['计划执行', '称重报工', '生产分析', '生产总览', '工序与标准', 'SKU 管理', '委外收发'] },
  { label: '仓储管理', items: ['入库与批次', '出库执行', '调拨单', '库存查询', '入库批次', '盘点任务', '仓位与平面图', '物料资料'] },
  { label: '质量管理', items: ['质量检验', '检验标准', '不良分析', '包装标签拍检'] },
  { label: '数据与分析', items: ['经营总览', '经营分析', '成本分析', '对账', '异常预警', '数据工作台', '自定义报表'] }
]

// 岗位一天：今天页按岗位显示的「今天要办的事」（每条 = 事情 + 去哪办 + 哪位数字员工先做了什么）
export interface RoleTask { what: string; to: string; crew?: string; agentDid?: string }
export interface Role { id: string; label: string; crew: string; tasks: RoleTask[]; watch: string[] }
export const ROLES: Role[] = [
  { id: 'boss', label: '老板 / 厂长', crew: '全部', tasks: [
    { what: '看昨天出成、成本、交付有没有异常', to: '/g/issues', crew: '数字成本会计', agentDid: '按工单汇总出成与成本差异，列出超阈值的（排除数据疑点）' },
    { what: '确认急单安排草案', to: 'F:inbox', crew: '数字计划员', agentDid: '核对物料和原有安排，出草案与影响说明；产能写「六膳门没有配置日产能，正式系统有产能配置和急单插单分析，原型还没接」' },
    { what: '审批大额采购、调价、报废', to: '/g/todo?tab=approve', crew: '数字文员', agentDid: '把审批材料汇成一页' },
    { what: '看本周经营报告', to: '/g/reports', crew: '数字成本会计' }], watch: ['数字班组今天办成几件', '应收与待开票（标最近录入日期）', '临期批次'] },
  { id: 'plan', label: '计划', crew: '数字计划员', tasks: [
    { what: '处理今天的缺料与交付风险', to: '/g/issues?kind=shortage', crew: '数字计划员', agentDid: '按订单交期排出风险顺序' },
    { what: '排明后天的生产', to: '/g/scheduling', crew: '数字计划员', agentDid: '生成排产草案，你调整后确认' },
    { what: '跟进工单（开单仍在黑湖）', to: '/production/plans', crew: '数字计划员' },
    { what: '确认采购建议', to: '/procurement/ai-purchase', crew: '数字计划员', agentDid: '按缺料与安全库存出建议；没设库存线时写「无法计算」' }], watch: ['超期工单', '齐套率', '急单插单影响'] },
  { id: 'workshop', label: '车间', crew: '数字计划员', tasks: [
    { what: '称重报工（投入、良品、副产品、不良）', to: '/production/weigh', crew: '数字计划员', agentDid: '带出今天（按快照日）待称重的任务和默认时间' },
    { what: '按工单领料', to: '/production/material-flow', crew: '数字计划员' },
    { what: '首检与工序检验', to: '/quality/inspections', crew: '数字质检员' },
    { what: '看车间大屏', to: '/dashboard/production-progress' }], watch: ['出成率偏离标准', '工序超时'] },
  { id: 'warehouse', label: '仓库', crew: '数字仓管', tasks: [
    { what: '收货入库并建批次', to: '/lsm/inbound-batches', crew: '数字仓管', agentDid: '按来源单带出批次号、生产日期、到期日草稿' },
    { what: '按建议批次出库（先到期先出）', to: '/warehouse/shipments', crew: '数字仓管', agentDid: '为出库单推荐先到期的批次' },
    { what: '处理临期与报损', to: '/g/warehouse-more?tab=wastage', crew: '数字仓管' },
    { what: '盘点并核对差异', to: '/warehouse/stocktake-query', crew: '数字仓管' }], watch: ['临期 / 过期批次', '负库存', '对账差异（入库批次 ↔ 黑湖）'] },
  { id: 'purchase', label: '采购', crew: '数字文员', tasks: [
    { what: '确认采购建议并下单', to: '/procurement/ai-purchase', crew: '数字计划员' },
    { what: '跟催未到货', to: '/procurement/orders', crew: '数字文员', agentDid: '列出逾期未到的订单行' },
    { what: '收票核对', to: '/g/purchase-more?tab=invoice', crew: '数字文员' },
    { what: '核价与比价', to: '/g/purchase-more?tab=quote', crew: '数字成本会计' }], watch: ['物料均价变动', '供应商来料不合格'] },
  { id: 'sales', label: '销售', crew: '数字文员', tasks: [
    { what: '录入 / 确认订单', to: '/sales/orders', crew: '数字文员', agentDid: '把客户消息整理成订单草稿' },
    { what: '回复客户交期', to: '/g/delivery', crew: '数字计划员' },
    { what: '跟进应收与开票', to: '/lsm/receivables', crew: '数字文员' },
    { what: '处理退货', to: '/sales/returns', crew: '数字文员' }], watch: ['交付风险订单', '逾期应收（标最近录入日期）'] },
  { id: 'quality', label: '质检', crew: '数字质检员', tasks: [
    { what: '做来料、首检、出货检验', to: '/quality/inspections', crew: '数字质检员', agentDid: '按入库批次和工单生成今天的检验待办' },
    { what: '放行待放行批次', to: '/g/quality-loop?tab=release', crew: '数字质检员' },
    { what: '处理 CCP 偏离与不良', to: '/g/quality-loop?tab=ccp', crew: '数字质检员' },
    { what: '追溯与召回演练', to: '/g/trace', crew: '数字质检员' }], watch: ['CCP 偏离', '不合格待处置批次', '标签拍检不通过'] },
  { id: 'finance', label: '财务', crew: '数字成本会计', tasks: [
    { what: '核对成本差异与出成', to: '/lsm/cost', crew: '数字成本会计', agentDid: '指出差异最大的工序环节' },
    { what: '开票、收款登记', to: '/g/finance?tab=invoice', crew: '数字成本会计' },
    { what: '审批调整与报废', to: '/g/todo?tab=approve', crew: '数字文员' },
    { what: '月结：凭证与期间', to: '/g/finance?tab=voucher', crew: '数字成本会计' }], watch: ['毛利低于红线的 SKU', '对账差异'] }
]

// R27：发现的问题按来源分四类（一级），原类型降为二级筛选；R30：录入错误单列「数据疑点」
export const ISSUE_SOURCES = [
  { id: 'all', label: '全部' }, { id: 'crew', label: '数字员工汇报', from: 'F:reports' }, { id: 'rule', label: '规则预警', from: '/analytics/alert-dashboard' },
  { id: 'reconcile', label: '对账差异', from: '/lsm/reconcile' }, { id: 'data', label: '数据缺口', from: '/smart-bi/data-completeness' }
]
export const ISSUE_KINDS = [
  { id: 'all', label: '全部' }, { id: 'shortage', label: '缺料', from: '/production/shortage-cases', crew: '数字计划员' },
  { id: 'delivery', label: '交付风险', from: '/g/delivery', crew: '数字计划员' }, { id: 'yield', label: '出成偏离', from: '/production/weigh', crew: '数字成本会计' },
  { id: 'cost', label: '成本差异', from: '/lsm/cost', crew: '数字成本会计' }, { id: 'quality', label: '质量偏离', from: '/g/quality-loop', crew: '数字质检员' },
  { id: 'expiry', label: '临期批次', from: '/lsm/inbound-batches', crew: '数字仓管' }, { id: 'equipment', label: '设备', from: '/g/equipment', crew: '数字计划员' },
  { id: 'schedule', label: '排产告警', from: '/g/scheduling', crew: '数字计划员' }, { id: 'reconcile', label: '对账差异', from: '/lsm/reconcile', crew: '数字成本会计' },
  { id: 'data', label: '数据缺口', from: '/smart-bi/data-completeness', crew: '全部' }
]

const viewRow: Act = { label: '查看', say: '打开这条记录的详情抽屉（依据、来源、操作日志）。' }
const toCrew = (who: string): Act => ({ label: '交给' + who, say: '带着当前页面和选中记录进入' + who + '的对话；办完回到本页看结果。' })

export const PAGES: Page[] = [
  { route: '/g/todo', title: '待办', place: '待办', crew: '全部', line: 'D（OA 聚合）+ F（等我确认）+ E（协同任务）', formal: ['/workflow/*', '/workspaces/plans-approvals', '/finance/adjustments', '/finance/disposal-approvals', '/procurement/requisitions/pending-approval'],
    why: '一个人「要我动手」的事只在一处：数字员工出的草案等我确认、别人发起的审批、指派给我的任务。审批动作仍在单据详情发生。',
    tabs: [
      { id: 'confirm', label: '等我确认', source: '数字班组「等我确认」（数字员工要改数据的事，先出预览）', filters: ['数字员工', '对象类型', '截止'], actions: [{ label: '打开完整收件箱', to: 'F:inbox', primary: true }],
        cols: ['事项', '哪位数字员工', '要改什么（预览）', '影响', '截止', '状态'], rowActs: [{ label: '看预览', to: 'F:inbox' }, { label: '确认执行', write: true }, { label: '退回并说明', write: true }] },
      { id: 'approve', label: '待我审批', source: '个人 OA 待我审批 + 财务调整 / 报废审批 + 待审批请购', filters: ['单据类型', '发起人', '金额区间', '相对日期'], actions: [{ label: '打开审批中心', to: '/workflow/pending' }],
        cols: ['单据', '类型', '发起人', '金额 / 数量', '数字文员摘要', '提交时间'], rowActs: [{ label: '去单据详情审批', to: '/workflow/pending' }, toCrew('数字文员')] },
      { id: 'assigned', label: '指派给我', source: '协同任务', filters: ['来源', '截止', '状态'], actions: [{ label: '新建任务', to: '/collab/tasks' }],
        cols: ['任务', '关联单据 / 问题', '指派人', '截止', '状态'], rowActs: [{ label: '打开', to: '/collab/tasks' }] },
      { id: 'mine', label: '我发起的', source: '/workflow/my-created', cols: ['单据', '当前节点', '停留时长', '状态'], rowActs: [{ label: '打开', to: '/workflow/my-created' }, { label: '催办', write: true }] },
      { id: 'cc', label: '抄送与已处理', source: '/workflow/copied、/workflow/acted', cols: ['单据', '动作', '时间'], rowActs: [{ label: '打开', to: '/workflow/acted' }] }
    ] },
  { route: '/g/issues', title: '发现的问题', place: '发现的问题', crew: '全部', line: 'D（页面）+ 各线提供规则', formal: ['/workspaces/exceptions', '/analytics/alert-dashboard', '*/shortage-cases ×4', '*/delivery-risks ×2', '*/cost-deviation-cases ×2', '/equipment/alerts', '/scheduling/alerts', '/smart-bi/data-completeness'],
    why: '数字员工汇报、规则预警、对账差异、数据缺口集中在一处（与主动汇报合一）：每条带依据、找谁、能转成待办（进等我确认）；关闭要写结论。明显的录入错误先进「数据疑点」，不当成异常。',
    tabs: [
      { id: 'open', label: '待处理', source: '数字员工汇报（班组主动汇报）、规则预警、对账差异、数据缺口', filters: ['类型（缺料 / 交付 / 出成 / 成本 / 质量 / 临期 / 设备 / 排产）', '发现者', '严重程度', '相对日期'],
        actions: [{ label: '看汇报原文（数字班组）', to: 'F:reports' }, { label: '分析当前筛选', say: '把当前筛选交给悬浮窗（解释模式，依据先行）。' }, { label: '批量转待办', write: true }],
        cols: ['问题', '来源', '类型', '发现者', '依据（来源与时点）', '找谁', '发现时间'], rowActs: [{ label: '看依据', say: '打开依据抽屉：代码算出的事实、来源标签、数据时点。' }, { label: '转成待办', write: true, say: '生成待办草稿，进「等我确认」，确认后指派给人。' }, { label: '去源头页处理', to: '/production/shortage-cases' }, { label: '关闭并写结论', write: true }] },
      { id: 'suspect', label: '数据疑点', source: '合理性检查没通过的记录（出成率 ≥ 100%、单位填错、起止时间相同等）', note: '数据疑点不算业务异常、不上首页头条；先找录入人核对，核对后再参与异常判断。', filters: ['疑点类型', '找谁', '相对日期'],
        cols: ['记录', '疑点', '检查规则', '找谁核', '发现时间'], rowActs: [{ label: '看原记录', say: '打开原记录（工单、报工或入库单），疑点字段标出。' }, { label: '转给录入人核对', write: true }, { label: '核对无误，放回判断', write: true }] },
      { id: 'doing', label: '处理中', cols: ['问题', '待办', '负责人', '截止'], rowActs: [viewRow] },
      { id: 'closed', label: '已关闭', cols: ['问题', '结论', '关闭人', '关闭时间'], rowActs: [viewRow] },
      { id: 'rules', label: '触发规则', source: '设置 / 规则与阈值', actions: [{ label: '去改阈值', to: '/g/settings?tab=rules' }], cols: ['规则', '阈值', '负责数字员工', '最近触发'] }
    ] },
  { route: '/g/scheduling', title: '智能排产', place: '计划与生产 · 智能排产', crew: '数字计划员', line: 'A', reference: true, formal: ['/scheduling/overview', '/scheduling/plans', '/scheduling/proposal-comparison', '/scheduling/realtime', '/scheduling/workers', '/scheduling/settings', '/scheduling/line-capacity'],
    why: '急单演示流程（F 流程 1）落在这里：数字计划员出排产草案和影响说明，人调整后确认生效。正式端调度中心 7 页合成一页 4 个页签。',
    tabs: [
      { id: 'draft', label: '排产草案', source: '生产计划 + 齐套（A kitting）；产能：六膳门没有配置日产能，正式系统有产能配置和急单插单分析，原型还没接', filters: ['日期范围', '产线', '产品'], actions: [{ label: '让数字计划员排明后天', primary: true, write: true, say: '数字计划员按订单交期、齐套和原有安排生成草案（六膳门没有配置日产能，正式系统有产能配置和急单插单分析，原型还没接）；先预览，确认后建协同任务交生产主管（记「已交办」，不扣额度），人在黑湖开单，任务完成回读后才算「已办成」、扣 1 个额度。' }, { label: '插入急单', write: true, say: '输入产品、数量、交期 → 草案标出被挤占的原安排和影响。' }],
        cols: ['产线 / 班次', '工单', '产品', '数量', '开始–结束', '齐套', '冲突'], rowActs: [{ label: '调整', write: true }, { label: '看影响', say: '列出因调整而推迟的工单与订单交期。' }] },
      { id: 'compare', label: '方案比较', cols: ['方案', '准时率', '换线次数', '加班工时', '缺料项'], actions: [{ label: '采用此方案', write: true }] },
      { id: 'workers', label: '人员与产能', source: '人员排班（设置 / 组织与人员）+ 设备台账', cols: ['产线', '可用工时', '已排工时', '设备状态'], actions: [{ label: '去设备台账', to: '/g/equipment' }] },
      { id: 'realtime', label: '执行跟踪', note: '执行情况看车间大屏和状态看板。', actions: [{ label: '打开车间大屏', to: '/dashboard/production-progress' }, { label: '打开状态看板', to: '/production/kanban' }] }
    ] },
  { route: '/g/delivery', title: '交付与备货', place: '计划与生产 · 交付与备货', crew: '数字计划员', line: 'A（算）+ C（显示）', formal: ['/production/delivery-risks', '/sales/delivery-risks', '/production/pending-queue', '/production/restock-board', '/production/delivery-warnings'],
    why: '销售问「能不能按期交」、计划问「先做哪个」是同一张表：订单交期 × 计划进度 × 齐套。正式端分在 5 页。',
    tabs: [
      { id: 'risk', label: '交付风险', source: '销售订单行 + 工单进度 + 齐套', filters: ['客户', '交期相对日期', '风险等级'], actions: [{ label: '让数字计划员给出补救方案', write: true }],
        cols: ['订单', '客户', '产品', '交期', '计划进度', '齐套', '风险原因'], rowActs: [{ label: '看订单', to: '/sales/orders' }, { label: '看工单', to: '/production/plans' }, toCrew('数字计划员')] },
      { id: 'pending', label: '未完成计划', cols: ['工单', '计划完成', '完成进度', '时间进度', '原因'], rowActs: [{ label: '打开', to: '/production/plans' }] },
      { id: 'restock', label: '备货', source: '安全库存（主数据）+ 预测', note: '六膳门安全库存 0 条有值 → 显示「无法计算：未设库存线」并给补充入口。', cols: ['产品', '现存', '安全库存', '建议备货'], actions: [{ label: '补充安全库存', to: '/g/settings?tab=master' }] }
    ] },
  { route: '/g/engineering', title: '工程变更与调价', place: '产品与工艺 · 工程变更与调价', crew: '数字计划员 / 数字成本会计', line: 'A（ECN）+ B（调价）', formal: ['/production/bom/ecns', '/production/bom/price-adjustments'],
    why: '工艺和配方改动要有申请、影响范围和生效的生产版本；原料价变动要看到对产品成本和毛利的影响。',
    tabs: [
      { id: 'ecn', label: '工程变更', filters: ['状态', '产品', '提出人'], actions: [{ label: '发起变更', primary: true, write: true }],
        cols: ['变更单', '产品', '改动（工序 / 用料 / 出成）', '影响在制工单', '生效生产版本', '状态'], rowActs: [{ label: '看差异', say: '新旧生产版本逐项对比。' }, { label: '去工序配置', to: '/production/process-hub' }] },
      { id: 'price', label: '调价建议', source: '物料均价趋势 + 成本分析', filters: ['物料', '变动幅度'], cols: ['物料', '价格变动', '受影响产品', '单位成本影响', '建议售价'], rowActs: [{ label: '看成本', to: '/lsm/cost' }, toCrew('数字成本会计')] }
    ] },
  { route: '/g/rd', title: '研发样品', place: '产品与工艺 · 研发样品', crew: '数字计划员', line: 'A', reference: true, formal: ['/rd/samples', '/rd/converted'],
    why: '新品从样品到正式 SKU：试制记录、报价三价对比、转正式后进入产品与工艺。',
    tabs: [
      { id: 'samples', label: '研发样品', filters: ['状态', '客户', '负责人'], actions: [{ label: '新建样品', primary: true, write: true }], cols: ['样品', '客户', '配方版本', '试制次数', '报价', '状态'], rowActs: [{ label: '转正式 SKU', write: true }, { label: '三价对比', say: '成本价 / 报价 / 成交价对比。' }] },
      { id: 'converted', label: '已转样品库', cols: ['样品', '转为 SKU', '转产日期'], rowActs: [{ label: '打开 SKU', to: '/system/products' }] }
    ] },
  { route: '/g/warehouse-more', title: '报损与其他仓储', place: '仓储与批次 · 报损与其他', crew: '数字仓管', line: 'C', formal: ['/warehouse/wastage-reports', '/warehouse/source-individuals', '/warehouse/reusable-containers', '/warehouse/semi-finished', '/warehouse/manufacturers'],
    why: '低频但食品厂必需的仓储事项合到一页，不占一级菜单。',
    tabs: [
      { id: 'wastage', label: '报损', filters: ['原因', '批次', '相对日期'], actions: [{ label: '登记报损', primary: true, write: true }], cols: ['报损单', '物料 / 批次', '数量', '原因', '处置', '财务审批'], rowActs: [{ label: '转质量处置', to: '/g/quality-loop?tab=disposition' }, viewRow] },
      { id: 'semi', label: '半成品库存', cols: ['半成品', '批次', '数量', '库位', '到期'], note: '与库存查询的批次维度合并。', actions: [{ label: '打开库存', to: '/warehouse/inventory-query' }] },
      { id: 'containers', label: '周转耗材', cols: ['耗材', '在库', '在外', '责任人'] },
      { id: 'source', label: '源头个体档案', cols: ['个体编号', '来源厂商', '检疫证明', '入库批次'], rowActs: [{ label: '追溯', to: '/g/trace' }] }
    ] },
  { route: '/g/purchase-more', title: '收票、退货与核价', place: '采购 · 收票、退货与核价', crew: '数字文员', line: 'C', formal: ['/procurement/invoices', '/finance/purchase-invoices', '/procurement/returns', '/procurement/inquiry-quotes', '/procurement/exceptions', '/procurement/payment-requests', '/procurement/approval-rules'],
    why: '采购下单之后的尾巴集中处理；审批规则下沉到设置。',
    tabs: [
      { id: 'invoice', label: '采购收票', actions: [{ label: '登记发票', primary: true, write: true }, { label: '让数字文员核对三单', say: '订单 / 入库 / 发票三单匹配，差异列出。' }], cols: ['发票', '供应商', '关联订单', '金额', '三单匹配'], rowActs: [viewRow] },
      { id: 'exception', label: '入库异常与退货', cols: ['单号', '供应商', '物料', '问题', '处理'], rowActs: [{ label: '发起退货', write: true }] },
      { id: 'quote', label: '核价', cols: ['核价单', '物料', '报价供应商数', '建议价'], rowActs: [viewRow] },
      { id: 'payreq', label: '付款申请', cols: ['申请', '供应商', '金额', '状态'], rowActs: [{ label: '去待办', to: '/g/todo?tab=approve' }] }
    ] },
  { route: '/g/sales-more', title: '报价、跟踪与提醒', place: '销售 · 报价、跟踪与提醒', crew: '数字文员', line: 'C', formal: ['/sales/quotes', '/sales/customer-tracking', '/sales/needs', '/sales/reminders', '/sales/finance-review', '/sales/payment-requests', '/crm/opportunity'],
    why: '销售跟单的辅助功能放一页；报价味美不开放（只读）。',
    tabs: [
      { id: 'tracking', label: '客户跟踪', actions: [{ label: '记一条跟进', write: true }], cols: ['客户', '最近联系', '下次跟进', '需求'], rowActs: [toCrew('数字文员')] },
      { id: 'reminders', label: '我的提醒', cols: ['提醒', '关联订单', '时间'] },
      { id: 'quotes', label: '报价', note: '味美不开放创建，只读列表。', cols: ['报价单', '客户', '产品', '金额', '状态'] },
      { id: 'review', label: '订单审批进度', cols: ['订单', '成本', '毛利', '审批节点'], rowActs: [{ label: '看订单', to: '/sales/orders' }] },
      { id: 'opportunity', label: '商机', note: 'CRM，建议暂不纳入（见 G-questions）。', cols: ['商机', '客户', '阶段', '预计金额'] }
    ] },
  { route: '/g/quality-loop', title: '放行与处置', place: '质量与追溯 · 放行与处置', crew: '数字质检员', line: 'E', formal: ['/quality/release', '/quality/ccp', '/quality/param-deviation', '/quality/cold-chain', '/quality/disposition', '/quality/recalls', '/quality/disposals', '/quality/returns'],
    why: '检验之后的闭环：放行 → 偏离扣留 → 处置 → 召回 → 废弃。正式端 8 页合成一页，按批次走。',
    tabs: [
      { id: 'release', label: '待放行', source: 'E 检验单结论 + C 入库批次', filters: ['产品', '批次到期', '检验结论'], actions: [{ label: '批量放行', write: true }], cols: ['批次', '产品', '检验单', '结论', 'CCP 记录', '等待时长'], rowActs: [{ label: '放行', write: true }, { label: '扣留', write: true }, { label: '看检验单', to: '/quality/inspections' }] },
      { id: 'ccp', label: 'CCP 与工艺偏离', source: '工序画布上的关键限值（BL 第二组 6）', filters: ['工序', '偏离类型'], cols: ['工单 / 批次', '工序', '关键限值', '实测', '偏离', '已扣留下游'], rowActs: [{ label: '去工序配置', to: '/production/process-hub' }, toCrew('数字质检员')] },
      { id: 'coldchain', label: '冷链超温', cols: ['批次', '库位 / 车辆', '超温时长', '最高温度'], rowActs: [{ label: '扣留', write: true }] },
      { id: 'disposition', label: '不良处置', cols: ['处置单', '批次', '不良项', '数量', '处置方式', '状态'], actions: [{ label: '新建处置', write: true }], rowActs: [{ label: '转报废', to: '/g/finance?tab=approve' }] },
      { id: 'recall', label: '召回', actions: [{ label: '发起召回演练', write: true, say: '选批次 → 正反向追溯 → 列出已出库客户与数量 → 预览通知。' }], cols: ['召回单', '批次', '涉及客户', '已追回'], rowActs: [{ label: '追溯', to: '/g/trace' }] },
      { id: 'returns', label: '质检退回与废弃', cols: ['单号', '批次', '原因', '去向'] }
    ] },
  { route: '/g/trace', title: '追溯与复盘', place: '质量与追溯 · 追溯与召回', crew: '数字质检员', line: 'C（追溯链）+ E（复盘）', formal: ['/production/traceability', '/workspaces/trace-review'],
    why: '追溯从「库存行里点开」升为独立入口：输入批次 / 订单 / 客户，正反向一键查；接不上的环节写「缺」。',
    tabs: [
      { id: 'query', label: '追溯查询', filters: ['批次号', '销售订单', '客户 + 日期', '原料批次'], actions: [{ label: '正向追溯（原料 → 客户）', primary: true }, { label: '反向追溯（客户 → 原料）' }, { label: '在库存里打开追溯', to: '/warehouse/inventory-query' }],
        cols: ['环节', '单据', '批次', '数量', '时间', '来源'], rowActs: [viewRow] },
      { id: 'review', label: '复盘', note: '一次质量事件的时间线、依据和结论，供审核与客户投诉回复。', actions: [{ label: '让数字质检员起草复盘', write: true }], cols: ['事件', '涉及批次', '结论', '整改'] }
    ] },
  { route: '/g/finance', title: '财务', place: '财务', crew: '数字成本会计', line: 'B（核算）+ C（应收应付）', reference: true, formal: ['/finance/*（18 页）', '/sales/payment-requests'],
    why: '财务在味美账号是关闭模块，这里只是参考设计。工厂财务的日常是「对得上」：应收应付、开票收票、收付款、凭证月结；分析类进「经营」。',
    tabs: [
      { id: 'overview', label: '概览', source: 'D 财务口径接口（六膳门）/ 正式端财务概览', cols: ['应收', '待开票', '应付', '本月毛利'], note: '数字位：—（骨架）；六膳门数据标「最近一次录入」与「疑似停用」。', actions: [{ label: '看六膳门财务口径', to: '/smart-bi/analysis-hub' }] },
      { id: 'arap', label: '应收应付', filters: ['客户 / 供应商', '账龄', '相对日期'], cols: ['对象', '应收 / 应付', '已收 / 已付', '逾期', '最近录入'], rowActs: [{ label: '看应收明细', to: '/lsm/receivables' }, toCrew('数字成本会计')] },
      { id: 'invoice', label: '开票与收票', actions: [{ label: '申请开票', write: true }, { label: '登记收票', to: '/g/purchase-more?tab=invoice' }], cols: ['发票', '对象', '金额', '关联单据', '状态'] },
      { id: 'payment', label: '收付款', actions: [{ label: '登记收款', write: true }], cols: ['流水', '对象', '金额', '核销单据'] },
      { id: 'approve', label: '调整与报废审批', note: '审批动作在单据详情，入口进「待办」。', actions: [{ label: '去待办', to: '/g/todo?tab=approve' }] },
      { id: 'voucher', label: '凭证与期间', actions: [{ label: '生成本月凭证', write: true }, { label: '导出凭证', say: '按凭证模板导出到财务软件。' }, { label: '结账', write: true }], cols: ['凭证号', '摘要', '借方', '贷方', '来源单据'] },
      { id: 'margin', label: '毛利与报表', cols: ['SKU', '收入', '成本', '毛利率', '低于红线'], actions: [{ label: '去成本分析', to: '/lsm/cost' }, { label: '财务三表', say: '资产负债、利润、现金流（正式端已有）。' }] },
      { id: 'period', label: '会计期间', cols: ['期间', '状态', '结账人', '结账时间'] }
    ] },
  { route: '/g/reports', title: '经营报告与指标', place: '经营 · 经营报告与指标', crew: '数字成本会计', line: 'D', formal: ['/analytics/ai-reports', '/analytics/indicators', '/finance/reports', '/sales/reports/*（10 张）', '/production-analytics/*'],
    why: '「代替周会汇报」：数字成本会计按周期出报告，数字一律绑定依据；指标的口径和目标值在同一页维护。',
    tabs: [
      { id: 'reports', label: '经营报告', filters: ['周期', '主题'], actions: [{ label: '生成本周报告', primary: true, say: '解释模式：段落数字与依据绑定，未核实段落折叠。' }, { label: '订阅', write: true }], cols: ['报告', '周期', '生成者', '生成时间', '状态'], rowActs: [{ label: '阅读', say: '打开报告全文，每个数字可点到依据。' }] },
      { id: 'kpi', label: '经营指标', cols: ['指标', '口径', '目标', '本期', '趋势'], actions: [{ label: '编辑口径', write: true }] },
      { id: 'sales', label: '销售报表', note: '正式端 10 张（日报、月报、客户 / 产品排行、业务员、区域、增长、毛利）合为一张可切维度的表。', actions: [{ label: '用自定义报表搭', to: '/unified/reports' }] }
    ] },
  { route: '/g/people', title: '组织与人员', place: '设置 · 组织与人员', crew: '—（管理员）', line: 'D', reference: true, formal: ['/hr/*', '/system/badge-generator'],
    why: '工厂版只保留与生产有关的：员工、部门、工种（计件计时工价）、排班考勤（工时来源）、工牌。工资社保不做（见 G-questions）。',
    tabs: [
      { id: 'employees', label: '员工', filters: ['部门', '工种', '状态'], actions: [{ label: '新增员工', write: true }, { label: '批量导入', say: 'Excel 模板 → 预检 → 确认（沿用 supplementImport）。' }], cols: ['姓名', '部门', '工种', '账号', '状态'] },
      { id: 'dept', label: '部门' , cols: ['部门', '负责人', '人数'] },
      { id: 'worktype', label: '工种与工价', cols: ['工种', '计件 / 计时', '工价', '适用工序'], note: '报工即带人工成本。' },
      { id: 'attendance', label: '排班与考勤', cols: ['日期', '班次', '应到', '实到', '异常'] },
      { id: 'badge', label: '工牌', actions: [{ label: '生成工牌', say: '按员工生成带二维码的工牌（扫码报工用）。' }] }
    ] },
  { route: '/g/equipment', title: '设备台账', place: '设置 · 设备台账', crew: '数字计划员', line: 'A', reference: true, formal: ['/equipment/list', '/equipment/maintenance', '/equipment/alerts'],
    why: '设备是排产的产能约束；告警进「发现的问题」。六膳门黑湖设备 0 条，需要 Cretas 补充。',
    tabs: [
      { id: 'list', label: '设备', actions: [{ label: '新增设备', write: true }], cols: ['设备', '产线', '可用工序', '状态', '下次保养'] },
      { id: 'maint', label: '维护记录', cols: ['设备', '类型', '时间', '人', '停机时长'] },
      { id: 'alerts', label: '告警', actions: [{ label: '在「发现的问题」里看', to: '/g/issues?kind=equipment' }] }
    ] },
  { route: '/g/settings', title: '设置', place: '设置', crew: '—（管理员）', line: 'D（框架）+ 各线', formal: ['/system/*', '/unit-dictionary', '/canvas-editor', '/smart-bi/upload 等数据管理', '/warehouse/material-types', '/procurement/suppliers', '/sales/customers'],
    why: '主数据、规则、权限、数字班组配置、数据与日志全部下沉到这里，不占日常菜单。',
    tabs: [
      { id: 'master', label: '主数据', source: '各线现有资料页', actions: [{ label: '物料', to: '/warehouse/material-types' }, { label: '物料分类', to: '/warehouse/material-segments' }, { label: '客户', to: '/sales/customers' }, { label: '供应商与价格表', to: '/procurement/suppliers' }, { label: '客户价目表', to: '/lsm/customer-prices' }, { label: '计量单位' }, { label: '车辆' }],
        cols: ['主数据', '条数', '缺口（主数据完整度）', '谁来补'], note: '缺口来自 BL 第一组 5「主数据完整度」，数字位：—（骨架）。' },
      { id: 'org', label: '账号与权限', actions: [{ label: '账号', write: true }, { label: '角色', write: true }, { label: '看动作权限对照（只读）', say: '原型与来源 → 动作权限对照。' }, { label: '功能模块开关', write: true }], cols: ['账号', '岗位', '角色', '可用数字员工', '状态'] },
      { id: 'rules', label: '规则与阈值', actions: [{ label: '业务阈值', write: true }, { label: '审批链与流转', write: true }, { label: '毛利红线', write: true }, { label: '排产设置', write: true }, { label: '编码规则' }, { label: '工艺保密', write: true }, { label: '凭证模板' }], cols: ['规则', '当前值', '触发的问题类型', '负责数字员工'] },
      { id: 'crew', label: '数字班组配置', source: '数字班组（启用哪几位、能力跟权限走）', actions: [{ label: '打开数字班组配置', to: 'F:settings', primary: true }, { label: '一线 AI 操作权限', write: true }, { label: '技能库', say: '数字员工可复用的技能清单。' }, { label: '定时工作与主动汇报', say: '数字员工按时间表自动做的工作和它们写过的记录。' }, { label: '视觉质检训练' }],
        cols: ['数字员工', '启用', '能办的事（来自权限）', '上岗条件'] },
      { id: 'data', label: '数据与日志', actions: [{ label: '文件导入', to: '/smart-bi/upload' }, { label: '映射复核', to: '/smart-bi/mapping-review' }, { label: '数据检查', to: '/smart-bi/data-completeness' }, { label: '查询模板', to: '/smart-bi/query-templates' }, { label: '导入 / 导出中心' }, { label: '数据备份' }], cols: ['日志', '操作人', '对象', '时间'] }
    ] }
]

// 员工名 → F 的员工 id（等我确认可带 ?crew= 按员工筛选）
export const crewIdOf = (name: string) => CREW.find(c => c.name === name)?.id

export function pageOf(route: string) { return PAGES.find(p => p.route === route) }
