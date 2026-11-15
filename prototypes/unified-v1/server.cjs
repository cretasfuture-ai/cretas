'use strict';
// 统一入口（本机 19063）：把味美（19060）原样反向代理，并注入「工作区」外壳；
// 六膳门工作区以同源 iframe 嵌入，数据经 /__lsm/api 转发到六膳门独立服务（19062），成本分析走本目录适配层。
// 不修改味美、六膳门、原版（19061）的任何文件或进程；味美与六膳门数据、保存各自隔离。
const http = require('http'), fs = require('fs'), path = require('path'), crypto = require('crypto');
const ROOT = __dirname, PORT = 19063, YD = { host: '127.0.0.1', port: 19060 }, LS = { host: '127.0.0.1', port: 19062 };
const ACCOUNTS = path.resolve(ROOT, '..');
const YD_SESSION = 'test-unified-ydwm-20261111', YD_SESSION_PATH = /^\/(api|smartbi-api|__account|__prototype)\//;
const cost = require('./lib/cost-lsm.cjs');
// 各线后端挂载（DECISIONS A4，D 统一加转发）：文件缺失时不让 19063 起不来，对应路由返回 503
function optional(file) { try { return require(file); } catch (e) { console.error('mount missing', file, e.message); return null; } }
const supply = optional('./src/supply/supply-lsm.cjs');
const UI_BUILD = process.env.UNIFIED_YDWM_UI_BUILD === 'fast' ? '' : 'classic';
fs.mkdirSync(path.join(ROOT, 'private'), { recursive: true });

const MIME = { '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.html': 'text/html; charset=utf-8', '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.ttf': 'font/ttf' };
function send(res, status, data, type = 'application/json; charset=utf-8', extra = {}) {
  const body = Buffer.isBuffer(data) ? data : Buffer.from(typeof data === 'string' ? data : JSON.stringify(data));
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', ...extra });
  res.end(body);
}
function readBody(req, limit = 8_000_000) {
  return new Promise((resolve, reject) => { let n = 0; const chunks = []; req.on('data', c => { n += c.length; if (n > limit) { reject(Error('提交内容过大')); req.destroy(); } else chunks.push(c); }); req.on('end', () => resolve(Buffer.concat(chunks))); req.on('error', reject); });
}

// ---- 菜单：直接由味美 menu-map.json 生成，六膳门沿用同一套任务组语法 ----
// 统一入口的菜单调整（D 线是菜单与路由唯一 Owner；裁定见 unified-spec/DECISIONS.md）：
//  D1 成本分析放「数据与分析」组（不放生产分析）；D4 岗位工作台并入首页（旧路由跳首页对应岗位）；
//  D6 物料均价趋势只在「数据与分析 / 经营分析」；D8 六膳门 BOM 旧菜单删除，路由跳画布 BOM 页签；
//  D10 固定到常用（外壳与 menu-composition.js 实现，按账号保存）。
const IMPLEMENTED = {
  // 第八批登记（D）
  '/production/shortage-cases': ['live', 'production'], '/dashboard/production-progress': ['live', 'production'], '/production/batches': ['live', 'production'], '/production/yield-standard': ['live', 'production'], '/production/kanban': ['live', 'production'], '/production/analysis-query': ['live', 'production'],
  '/quality/ccp': ['live', 'e'], '/quality/returns': ['live', 'e'], '/quality/disposals': ['live', 'e'], '/quality/recalls': ['live', 'e'], '/quality/param-deviation': ['live', 'e'], '/quality/cold-chain': ['live', 'e'], '/quality/vision-training': ['live', 'e'], '/quality/label-qc': ['live', 'e'], '/production/delivery-risks': ['live', 'production'], '/production/bom/ecns': ['live', 'production'], '/production/bom/price-adjustments': ['live', 'cost'], '/transfer/list': ['live', 'supply'], '/sales/shipments': ['live', 'supply'], '/sales/returns': ['live', 'supply'], '/warehouse/unordered-inbound-applications': ['live', 'supply'], '/warehouse/stocktake-query': ['live', 'supply'], '/warehouse/material-types': ['live', 'supply'], '/warehouse/material-segments': ['live', 'supply'], '/warehouse/material-price-trend': ['live', 'supply'], '/procurement/requisitions/pending-approval': ['live', 'supply'], '/procurement/ai-purchase': ['live', 'supply'], '/procurement/price-lists': ['live', 'supply'], '/warehouse/wastage-reports': ['live', 'supply'], '/warehouse/source-individuals': ['live', 'supply'], '/sales/vehicles': ['live', 'supply'], '/sales/payment-requests': ['live', 'supply'],
  '/dashboard': ['live', 'home'], '/system/products': ['live', 'products'], '/production/plans': ['live', 'orders'], '/production/approval': ['live', 'reports'],
  '/production/process-hub': ['live', 'processes'], '/lsm/cost': ['live', 'cost'], '/unified/reports': ['live', 'reports-custom'], '/analytics/ai-reports': ['live', 'aireport'], '/workflow/pending': ['live', 'oa'], '/workflow/my-created': ['live', 'oa'], '/workflow/acted': ['live', 'oa'], '/workflow/copied': ['live', 'oa'], '/smart-bi/analysis-hub': ['live', 'finance'],
  '/quality/inspections': ['live', 'e'], '/quality/standards': ['live', 'e'], '/quality/defects': ['live', 'e'], '/collab/tasks': ['live', 'e'],
  '/lsm/stock-summary': ['live', 'supply'], '/warehouse/inventory-adjustments': ['live', 'supply'], '/production/wip': ['live', 'orders'],
  // C 线第三批：六膳门黑湖只读页
  '/sales/orders': ['live', 'supply'], '/procurement/orders': ['live', 'supply'], '/procurement/requisitions/my': ['live', 'supply'], '/sales/customers': ['live', 'supply'], '/procurement/suppliers': ['live', 'supply'],
  '/lsm/customer-prices': ['live', 'supply'], '/lsm/reconcile': ['live', 'reconcile'],
  // 第六批：称重报工（A）、入库批次（C）先登记为「待整理」，各线页面接上后改 live
'/g/today': ['live', 'g'], '/g/todo': ['live', 'g'], '/g/issues': ['live', 'g'], '/g/scheduling': ['live', 'g'], '/g/delivery': ['live', 'g'], '/g/engineering': ['live', 'g'], '/g/rd': ['live', 'g'], '/g/warehouse-more': ['live', 'g'], '/g/purchase-more': ['live', 'g'], '/g/sales-more': ['live', 'g'], '/g/quality-loop': ['live', 'g'], '/g/trace': ['live', 'g'], '/g/finance': ['live', 'g'], '/g/reports': ['live', 'g'], '/g/people': ['live', 'g'], '/g/equipment': ['live', 'g'], '/g/settings': ['live', 'g'], '/g/map': ['live', 'g'],
  '/crew': ['live', 'crew'], '/crew/worker': ['live', 'crew'], '/crew/inbox': ['live', 'crew'], '/crew/ledger': ['live', 'crew'], '/crew/onboard': ['live', 'crew'], '/crew/reports': ['live', 'crew'], '/crew/issues': ['live', 'crew'], '/crew/settings': ['live', 'crew'], '/crew/mobile': ['live', 'crew'], '/crew/map': ['live', 'crew'],
  '/production/weigh': ['live', 'production'], '/lsm/inbound-batches': ['live', 'supply'], '/lsm/outsource': ['live', 'supply'], '/lsm/receivables': ['live', 'supply'],
  '/warehouse/inventory-query': ['view', 'ops'], '/warehouse/materials': ['view', 'ops'], '/warehouse/shipments': ['view', 'ops'], '/warehouse/storage-locations': ['view', 'ops'], '/production/material-flow': ['view', 'ops']
};
const ROLE_ROUTES = { '/workdesk/sales-owner': 'sales', '/workdesk/production-manager': 'production', '/workdesk/warehouse-keeper': 'warehouse', '/workdesk/purchaser': 'purchase' };
// ---- A2 味美工作区也用统一框架打开的路由表（各线登记，D 维护）：外壳在 ws=ydwm 时把这些路径覆盖为 frame 页面，hash 带 ws=ydwm ----
// match: 味美地址（path，可选 query）；frame: 框架内路由；line: 登记线
const FRAME_ROUTES = [
  // 第八批登记（D）：味美没有原生页的路由走框架页
  { path: '/quality/recalls', host: '/dashboard', frame: '/quality/recalls', line: 'E', title: '批次召回' },
  { path: '/quality/param-deviation', host: '/dashboard', frame: '/quality/param-deviation', line: 'E', title: '工艺参数偏离' },
  { path: '/quality/cold-chain', host: '/dashboard', frame: '/quality/cold-chain', line: 'E', title: '冷链超温批次' },
  { path: '/quality/vision-training', host: '/dashboard', frame: '/quality/vision-training', line: 'E', title: '视觉质检训练' },
  { path: '/production/delivery-risks', host: '/dashboard', frame: '/production/delivery-risks', line: 'A', title: '交付风险' },
  { path: '/production/bom/price-adjustments', host: '/dashboard', frame: '/production/bom/price-adjustments', line: 'B', title: 'BOM调价建议' },
  { path: '/smart-bi/query-templates', query: { view: 'custom-reports' }, frame: '/unified/reports', line: 'D', title: '自定义报表' },
  { path: '/analytics/ai-reports', frame: '/analytics/ai-reports', line: 'D', title: 'AI 经营报告' },
  { path: '/quality/inspections', frame: '/quality/inspections', line: 'E', title: '质量检验' },
  { path: '/quality/standards', host: '/quality/label-qc', frame: '/quality/standards', line: 'E', title: '检验标准' },
  { path: '/quality/defects', host: '/quality/label-qc', frame: '/quality/defects', line: 'E', title: '不良分析' },
  { path: '/collab/tasks', host: '/workflow/pending', frame: '/collab/tasks', line: 'E', title: '协同任务' },
  // C 线 R6：精简视图都是只读入口（带 view=list），原页面仍为默认；params 原样带进 frame hash，C 的组件按 yview 选视图
  // W6 / R7 更正：味美销售订单默认进精简视图，头部「打开原页面办理」进 ?view=native（原页面）
  { path: '/sales/orders', frame: '/sales/orders', params: { yview: 'sales' }, native: true, line: 'C', title: '销售订单' },
  { path: '/procurement/orders', query: { view: 'list' }, frame: '/procurement/orders', params: { yview: 'purchase' }, toggle: true, line: 'C', title: '采购订单 · 精简视图' },
  { path: '/warehouse/inventory-query', query: { view: 'list' }, frame: '/warehouse/inventory-query', params: { yview: 'inventory' }, toggle: true, line: 'C', title: '库存查询 · 精简视图' },
  // C 线审计第二批第 6 条：其余 7 张精简视图（原页面默认，页头「精简视图」，按账号记住）
  { path: '/procurement/requisitions/my', query: { view: 'list' }, frame: '/procurement/requisitions/my', params: { yview: 'requisitions' }, toggle: true, line: 'C', title: '我的请购 · 精简视图' },
  { path: '/procurement/suppliers', query: { view: 'list' }, frame: '/procurement/suppliers', params: { yview: 'suppliers' }, toggle: true, line: 'C', title: '供应商 · 精简视图' },
  { path: '/sales/customers', query: { view: 'list' }, frame: '/sales/customers', params: { yview: 'customers' }, toggle: true, line: 'C', title: '客户管理 · 精简视图' },
  { path: '/procurement/price-lists', query: { view: 'list' }, frame: '/procurement/price-lists', params: { yview: 'pricelists' }, toggle: true, line: 'C', title: '价格表 · 精简视图' },
  { path: '/sales/returns', query: { view: 'list' }, frame: '/sales/returns', params: { yview: 'returns' }, toggle: true, line: 'C', title: '销售退货 · 精简视图' },
  { path: '/transfer/list', query: { view: 'list' }, frame: '/transfer/list', params: { yview: 'transfers' }, toggle: true, line: 'C', title: '调拨单 · 精简视图' },
  // C 线第五批：收货任务、物料字典精简视图（原页面默认，页头「精简视图」切换）
  { path: '/warehouse/materials', query: { view: 'list' }, frame: '/warehouse/materials', params: { yview: 'receiving' }, toggle: true, line: 'C', title: '收货任务 · 精简视图' },
  { path: '/warehouse/material-types', query: { view: 'list' }, frame: '/warehouse/material-types', params: { yview: 'materials' }, toggle: true, line: 'C', title: '物料字典 · 精简视图' },
  { path: '/warehouse/stocktake-query', query: { view: 'list' }, frame: '/warehouse/stocktake-query', params: { yview: 'stocktakes' }, toggle: true, line: 'C', title: '盘点任务 · 精简视图' },
  // A 线 R4：带 view=list 才进只读精简视图，原生页面（有录入、小结、签收、批准等写操作）仍是默认
  { path: '/production/plans', query: { view: 'list' }, frame: '/production/plans', line: 'A', title: '生产计划 · 精简' },
  { path: '/production/material-flow', query: { view: 'list' }, frame: '/production/material-flow', line: 'A', title: '领料 · 精简' },
  { path: '/production/approval', query: { view: 'list' }, frame: '/production/approval', line: 'A', title: '报工撤回审批 · 精简' },
  { path: '/warehouse/inventory-adjustments', host: '/warehouse/inventory-query', frame: '/warehouse/inventory-adjustments', line: 'C', title: '库存调整单' },
  { path: '/lsm/stock-summary', host: '/warehouse/inventory-query', frame: '/lsm/stock-summary', line: 'C', title: '进销存汇总' },
  // F 线数字班组（味美原版没有这些路由：宿主借用「首页」，地址改成 /dashboard?uframe=/crew…）
  { path: '/crew', host: '/dashboard', frame: '/crew', line: 'F', title: '班组首页' },
  { path: '/crew/worker', host: '/dashboard', frame: '/crew/worker', line: 'F', title: '数字员工工作台' },
  { path: '/crew/inbox', host: '/dashboard', frame: '/crew/inbox', line: 'F', title: '等我确认' },
  { path: '/crew/ledger', host: '/dashboard', frame: '/crew/ledger', line: 'F', title: '工作记录与额度' },
  { path: '/crew/onboard', host: '/dashboard', frame: '/crew/onboard', line: 'F', title: '班组入职' },
  { path: '/crew/reports', host: '/dashboard', frame: '/crew/issues', line: 'F', title: '发现的问题（原主动汇报）' },
  { path: '/crew/issues', host: '/dashboard', frame: '/crew/issues', line: 'F', title: '发现的问题' },
  { path: '/crew/settings', host: '/dashboard', frame: '/crew/settings', line: 'F', title: '数字班组配置' },
  { path: '/crew/mobile', host: '/dashboard', frame: '/crew/mobile', line: 'F', title: '一线手机端' },
  { path: '/crew/map', host: '/dashboard', frame: '/crew/map', line: 'F', title: '功能归属与架构' },
  // G 线骨架页（重整方案）
  { path: '/g/today', host: '/dashboard', frame: '/g/today', line: 'G', title: '今天' },
  { path: '/g/todo', host: '/dashboard', frame: '/g/todo', line: 'G', title: '待办' },
  { path: '/g/issues', host: '/dashboard', frame: '/g/issues', line: 'G', title: '发现的问题' },
  { path: '/g/scheduling', host: '/dashboard', frame: '/g/scheduling', line: 'G', title: '智能排产（重整方案）' },
  { path: '/g/delivery', host: '/dashboard', frame: '/g/delivery', line: 'G', title: '交付与备货（重整方案）' },
  { path: '/g/engineering', host: '/dashboard', frame: '/g/engineering', line: 'G', title: '工程变更与调价（重整方案）' },
  { path: '/g/rd', host: '/dashboard', frame: '/g/rd', line: 'G', title: '研发样品（重整方案）' },
  { path: '/g/warehouse-more', host: '/dashboard', frame: '/g/warehouse-more', line: 'G', title: '报损与其他仓储（重整方案）' },
  { path: '/g/purchase-more', host: '/dashboard', frame: '/g/purchase-more', line: 'G', title: '收票退货与核价（重整方案）' },
  { path: '/g/sales-more', host: '/dashboard', frame: '/g/sales-more', line: 'G', title: '报价跟踪与提醒（重整方案）' },
  { path: '/g/quality-loop', host: '/dashboard', frame: '/g/quality-loop', line: 'G', title: '放行与处置（重整方案）' },
  { path: '/g/trace', host: '/dashboard', frame: '/g/trace', line: 'G', title: '追溯与复盘（重整方案）' },
  { path: '/g/finance', host: '/dashboard', frame: '/g/finance', line: 'G', title: '财务（重整方案）' },
  { path: '/g/reports', host: '/dashboard', frame: '/g/reports', line: 'G', title: '经营报告与指标（重整方案）' },
  { path: '/g/people', host: '/dashboard', frame: '/g/people', line: 'G', title: '组织与人员（重整方案）' },
  { path: '/g/equipment', host: '/dashboard', frame: '/g/equipment', line: 'G', title: '设备台账（重整方案）' },
  { path: '/g/settings', host: '/dashboard', frame: '/g/settings', line: 'G', title: '设置（重整方案）' },
  { path: '/g/map', host: '/dashboard', frame: '/g/map', line: 'G', title: '菜单与动线对照' }
];
// R32 新顶层组（六膳门侧栏单项组直接显示成入口；味美侧在「首页」后建静态组）
const NEW_GROUPS = ['今天', '数字班组', '待办', '发现的问题', '设置'];
const SOLO_GROUPS = ['今天', '待办', '发现的问题'];
// 统一入口新增的主入口/子页（两个工作区同位置）
const NEW_ENTRIES = [
  { id: 'quality-ccp', group: '质量管理', label: 'CCP 监控记录', route: '/quality/ccp', sourceTitle: '统一入口新增（第八批，E 线）', reason: '工序关键控制点的监控记录；味美直达编译页。' },
  { id: 'quality-returns', group: '质量管理', label: '质检退回单', route: '/quality/returns', sourceTitle: '统一入口新增（第八批，E 线）', reason: '不合格退回处理；味美直达编译页。' },
  { id: 'quality-disposals', group: '质量管理', label: '废弃处理', route: '/quality/disposals', sourceTitle: '统一入口新增（第八批，E 线）', reason: '报废、销毁记录；味美直达编译页。' },
  { id: 'quality-recalls', group: '质量管理', label: '批次召回', route: '/quality/recalls', sourceTitle: '统一入口新增（第八批，E 线）', reason: '按批次发起召回（依赖 C 的批次与追溯）。' },
  { id: 'quality-param-deviation', group: '质量管理', label: '工艺参数偏离', route: '/quality/param-deviation', sourceTitle: '统一入口新增（第八批，E 线）', reason: '只写缺口。' },
  { id: 'quality-cold-chain', group: '质量管理', label: '冷链超温批次', route: '/quality/cold-chain', sourceTitle: '统一入口新增（第八批，E 线）', reason: '只写缺口。' },
  { id: 'quality-vision-training', group: '设置', label: '视觉质检训练', route: '/quality/vision-training', sourceTitle: '统一入口新增（第八批，E 线）', reason: '只写缺口（下沉到设置，只在六膳门侧栏）。' },
  { id: 'prod-delivery-risks', group: '生产管理', label: '交付风险', route: '/production/delivery-risks', sourceTitle: '统一入口新增（第八批，A 线）', reason: '订单交期 × 计划进度 × 齐套。' },
  { id: 'sales-delivery-risks', group: '销售管理', label: '交付风险', route: '/production/delivery-risks', sourceTitle: '统一入口新增（第八批，A 线）', reason: '与生产管理里的「交付风险」同一页。' },
  { id: 'prod-ecns', group: '生产管理', label: '工程变更通知', route: '/production/bom/ecns', sourceTitle: '统一入口新增（第八批，A 线）', reason: '六膳门：黑湖没有此类数据；味美直达编译页。' },
  { id: 'prod-price-adjustments', group: '生产管理', label: 'BOM调价建议', route: '/production/bom/price-adjustments', sourceTitle: '统一入口新增（第八批，B 线）', reason: '原料价变动 → 成本影响 → 调价建议，只作建议、不回写。' },
  { id: 'wh-wastage', group: '仓储管理', label: '报损管理', route: '/warehouse/wastage-reports', sourceTitle: '统一入口新增（第八批，C 线）', reason: '报损记录；味美直达编译页。' },
  { id: 'wh-source-individuals', group: '仓储管理', label: '源头个体档案', route: '/warehouse/source-individuals', sourceTitle: '统一入口新增（第八批，C 线）', reason: '源头个体档案；味美直达编译页。' },
  { id: 'sales-vehicles', group: '销售管理', label: '车辆字典', route: '/sales/vehicles', sourceTitle: '统一入口新增（第八批，C 线）', reason: '车辆字典（主数据）；味美直达编译页。' },
  { id: 'sales-payment-requests', group: '销售管理', label: '销售付款申请', route: '/sales/payment-requests', sourceTitle: '统一入口新增（第八批，C 线）', reason: '只做编译页能直接打开的部分，不开财务模块。' },
  // F 线数字班组：独立一组「数字班组」，排在首页之后（工作台从班组首页卡片进，不单列）
  { id: 'crew-home', group: '数字班组', label: '班组首页', route: '/crew', sourceTitle: '统一入口新增（F 线，数字班组）', reason: '五位数字员工在办什么、等谁确认。' },
  { id: 'crew-ledger', group: '数字班组', label: '工作记录与额度', route: '/crew/ledger', sourceTitle: '统一入口新增（F 线）', reason: '数字员工做过的事与额度。' },
  { id: 'crew-onboard', group: '数字班组', label: '班组入职', route: '/crew/onboard', sourceTitle: '统一入口新增（F 线）', reason: '启用哪几位数字员工、各自权限。' },
  // 第七批 R32：顶层四组「今天 / 数字班组 / 待办 / 发现的问题」+ 底部「设置」（两个工作区同位置）
  { id: 'g-today', group: '今天', label: '今天', route: '/g/today', sourceTitle: '统一入口新增（R32，G 线页面）', reason: '今天要看的事：待办、风险、数字员工在办什么。' },
  { id: 'g-todo', group: '待办', label: '待办', route: '/g/todo', sourceTitle: '统一入口新增（R32，G 线页面）', reason: '等我确认、待我审批、指派给我、我发起的、抄送。' },
  { id: 'crew-issues', group: '发现的问题', label: '发现的问题', route: '/crew/issues', sourceTitle: '统一入口新增（R33，F 线页面）', reason: '数字员工汇报、规则预警、对账差异、数据缺口；数据疑点单列。' },
  { id: 'g-settings', group: '设置', label: '设置', route: '/g/settings', sourceTitle: '统一入口新增（R32，G 线页面）', reason: '主数据、权限、集成等配置入口。' },
  { id: 'crew-settings', group: '设置', label: '数字班组配置', route: '/crew/settings', sourceTitle: '统一入口新增（R27 G14，F 线页面）', reason: '启用哪几位数字员工、各自权限。' },
  { id: 'quality-inspections', group: '质量管理', label: '质量检验', route: '/quality/inspections', before: '/quality/label-qc', sourceTitle: '统一入口新增（N7，E 线）', reason: '来料、生产、入库、发货、其他五个阶段的检验单；标准跟着阶段走。' },
  { id: 'quality-standards', group: '质量管理', label: '检验标准', route: '/quality/standards', before: '/quality/label-qc', sourceTitle: '统一入口新增（N7，E 线）', reason: '按阶段维护检验标准与版本。' },
  { id: 'quality-defects', group: '质量管理', label: '不良分析', route: '/quality/defects', before: '/quality/label-qc', sourceTitle: '统一入口新增（B7，E 线）', reason: '按工序、人员统计不良品项分布。' },
  { id: 'd-ai-reports', group: '数据与分析', label: 'AI 经营报告', route: '/analytics/ai-reports', sourceTitle: '统一入口新增（R40，D 线）', reason: '按周、按月由规则生成事实表，每个数字可点回来源；模型解读只在点击后请求。' },
  { id: 'collab-tasks', group: '个人 OA', label: '协同任务', route: '/collab/tasks', sourceTitle: '统一入口新增（N8，E 线）', reason: '任务创建、指派、截止、评论与关联单据。' },
  { id: 'stock-summary', group: '仓储管理', label: '进销存汇总', route: '/lsm/stock-summary', after: '/warehouse/inventory-query', sourceTitle: '统一入口新增（N6，C 线）', reason: '按物料、仓库、期间汇总期初、入、出、期末；库龄与补货。' },
  { id: 'inventory-adjustments', group: '仓储管理', label: '库存调整单', route: '/warehouse/inventory-adjustments', after: '/warehouse/inventory-query', sourceTitle: '统一入口新增（N5，C 线）', reason: '单条细微调整，原因、预览、确认、回读。' }
];
// A 线菜单改名（两个工作区同名）
const MEMBER_LABELS = { '/production/shortage-cases': '缺料异常', '/production/approval': '报工撤回审批', '/production/kanban': '状态看板', '/dashboard/production-progress': '车间大屏' };
// 菜单搜索别名（新名、原名之外的业务说法；A/B/C/E 线经 organizer 补充）
const SEARCH_ALIASES = {
  '/production/shortage-cases': ['缺料','缺料异常','缺料事件','短缺'],
  '/production/delivery-risks': ["交付风险","交期","逾期","齐套","延期"], '/production/bom/ecns': ["工程变更通知","ECN","变更","BOM变更"], '/production/bom/price-adjustments': ["调价","BOM调价","原料价"], '/quality/ccp': ["CCP","关键控制点"], '/quality/returns': ["退回","返工"], '/quality/disposals': ["报废","销毁"], '/quality/recalls': ["召回"], '/quality/param-deviation': ["参数偏离"], '/quality/cold-chain': ["冷链","超温"], '/quality/vision-training': ["视觉","训练"], '/warehouse/wastage-reports': ["报损","损耗"], '/warehouse/source-individuals': ["源头","个体"], '/sales/vehicles': ["车辆","车牌"], '/sales/payment-requests': ["付款申请"],
  '/production/process-hub': ['BOM', '配方', '用料', '工艺', '生产版本', '工艺路线'],
  '/production/kanban': ['看板', '大屏', '进度'], '/dashboard/production-progress': ['大屏', '进度', '看板'],
  '/lsm/cost': ['成本', '单位成本', '产品成本', '工单成本'], '/analytics/overview?view=product-cost': ['成本', '单位成本', '产品成本'],
  '/smart-bi/upload': ['导入', '上传', 'Excel', '文件'], '/quality/label-qc': ['质检', '标签', '拍检', '质量'],
  '/workflow/pending': ['审批', 'OA', '待办'], '/procurement/requisitions/my': ['请购'], '/production/yield-standard': ['出成率', '出成'],
  '/analytics/alert-dashboard': ['预警', '告警', '异常'], '/warehouse/inventory-query': ['库存', '现存量'], '/warehouse/stocktake-query': ['盘点'],
  '/smart-bi/analysis': ['问答', '问数据', '提问'], '/unified/reports': ['报表', '周报', '图表', '自定义', '周会'], '/analytics/ai-reports': ['经营报告', '周报', '月报', 'AI报告', '报告'], '/smart-bi/query-templates?view=custom-reports': ['报表', '周报', '图表', '自定义', '周会'],
  '/dashboard': ['首页', '工作台', '岗位', '待办', '销售跟进', '生产安排', '仓库任务', '采购安排'],
  '/quality/inspections': ['检验', '质检', '来料检验', '出货检验', 'IQC', 'OQC'], '/quality/standards': ['检验标准', '质检标准', '检验项'], '/quality/defects': ['不良', '不良品', '缺陷'],
  '/collab/tasks': ['任务', '协同', '待办', '指派'], '/production/wip': ['在制品', 'WIP', '效率', '达标率', '标准工时'], '/lsm/stock-summary': ['进销存', '库龄', '呆滞', '补货'], '/warehouse/inventory-adjustments': ['调整', '库存调整', '调账'],
  '/lsm/receivables': ['应收', '收款', '待开票', '账本', '对账', '逾期'], '/lsm/customer-prices': ['价目表', '客户价格', '售价'], '/lsm/outsource': ['委外', '外协', '发料', '收货'],
  '/g/today': ['今天', '首页', '工作台', '概览'], '/g/todo': ['待办', '待我处理', '审批', '指派', '抄送', '等我确认'], '/g/settings': ['设置', '配置', '主数据'],
  '/crew/issues': ['问题', '异常', '预警', '汇报', '对账差异', '数据缺口', '数据疑点', '发现的问题'], '/crew/settings': ['数字员工启用', '班组配置', '岗位入口'],
  '/crew': ['数字班组', '数字员工', 'AI', '班组'], '/crew/inbox': ['确认', '待确认', '审批'], '/crew/ledger': ['额度', '计费', '工作记录'],
  '/production/weigh': ['称重', '报工', '出成率', '副产品'], '/lsm/inbound-batches': ['批次', '入库批次', '到期', '保质期', '临期'], '/lsm/reconcile': ['对账', '差异', '核对', '黑湖对照'],
  '/smart-bi/analysis-hub': ['经营分析', '财务', '应收', '已收', '待开票', '逾期']
};
// 味美工作区 menu-map 的统一补丁（只在 19063 下生效，不改味美原文件）
function patchYdwmMenuMap(m) {
  m = JSON.parse(JSON.stringify(m));
  m.groups = m.groups.filter(g => g.id !== 'role-workdesks');
  for (const p of m.pages) {
    if (ROLE_ROUTES[p.path]) { p.visibleMenu = '首页'; p.canonical = '/dashboard'; p.menuGroup = null; p.group = '概览'; p.decision = 'merged-into-home'; p.reason = '岗位工作台并入首页按岗位筛选（DECISIONS D4）；旧路由跳首页对应岗位。'; }
    if (p.path === '/warehouse/material-price-trend') { p.originalGroup = p.group; p.group = '数据与分析'; }
    if (ROLE_ROUTES[p.path]) p.originalGroup = '工作台';
  }
  for (const g of m.groups) for (const mem of g.members) if (MEMBER_LABELS[mem.route]) mem.label = MEMBER_LABELS[mem.route];
  m.extraEntries = [
    { id: 'cost-analysis', group: '数据与分析', label: '成本分析', route: '/analytics/overview?view=product-cost', after: '/smart-bi/analysis-hub', sourceTitle: '产品成本（原经营总览页签）', reason: '成本分析唯一入口（DECISIONS D1）；经营总览只放跳转卡。' },
    { id: 'custom-reports', group: '数据与分析', label: '自定义报表', route: '/smart-bi/query-templates?view=custom-reports', sourceTitle: '统一入口新增（N9）', reason: '选数据源、字段、筛选、分组和图表，保存为模板并按周期生成，代替周会汇报。' }
  ];
  m.extraEntries.push(...NEW_ENTRIES.filter(x => x.group !== '设置')); // 味美侧只在「首页」后加四组入口，设置组只在六膳门侧栏
  m.newGroups = ['今天', '数字班组', '待办', '发现的问题'];
  m.frameRoutes = FRAME_ROUTES;
  m.roleRoutes = ROLE_ROUTES; m.searchAliases = SEARCH_ALIASES; m.headingLabels = { 'AI 探索': '问答与导入' };
  m.visibleEntries = (m.visibleEntries || 0) - 1 + m.extraEntries.length;
  m.unifiedPatch = 'D1/D4/D6/D10/N9 · ' + new Date().toISOString().slice(0, 10);
  return m;
}
function buildMenu() {
  const m = patchYdwmMenuMap(JSON.parse(fs.readFileSync(path.join(ACCOUNTS, 'ydwm-v1/public/system-kit/menu-map.json'), 'utf8')));
  const l = JSON.parse(fs.readFileSync(path.join(ACCOUNTS, 'liushanmen-v1/public/menu-catalog.json'), 'utf8'));
  const titles = new Map(l.map(e => [e.path, e.title])), reasons = new Map(l.map(e => [e.path, e.reason]));
  const byGroupCanonical = new Map(m.groups.map(g => [g.canonical, g]));
  const groups = [], seenGroup = new Map(), mapping = [];
  for (const p of m.pages) {
    if (ROLE_ROUTES[p.path]) { mapping.push({ source: p.path, sourceTitle: titles.get(p.path) || p.title, group: '概览', entry: '首页', member: '首页按岗位筛选（旧路由跳转）', status: 'live' }); continue; }
    let g = seenGroup.get(p.group);
    if (!g) { g = { label: p.group, entries: [] }; seenGroup.set(p.group, g); groups.push(g); }
    let e = g.entries.find(x => x.label === p.visibleMenu);
    if (!e) {
      const grp = byGroupCanonical.get(p.canonical);
      e = { label: p.visibleMenu, canonical: p.canonical, members: (grp ? grp.members : [{ route: p.path, label: p.visibleMenu, sourceTitle: titles.get(p.path) }]).map(x => ({ route: x.route, label: x.label, sourceTitle: titles.get(x.route) || x.sourceTitle })) };
      g.entries.push(e);
    }
  }
  // 六膳门工作区的成本分析与自定义报表：数据与分析组下的独立主入口
  const data = groups.find(g => g.label === '数据与分析');
  data.entries.push({ label: '成本分析', canonical: '/lsm/cost', members: [{ route: '/lsm/cost', label: '成本分析', sourceTitle: '成本分析（六膳门新增）' }] });
  data.entries.push({ label: '自定义报表', canonical: '/unified/reports', members: [{ route: '/unified/reports', label: '自定义报表', sourceTitle: '统一入口新增（N9）' }] });
  for (const x of NEW_ENTRIES) {
    let g = groups.find(g => g.label === x.group);
    // F 线「数字班组」是新组：没有就建（排序见 GROUP_ORDER）
    if (!g && NEW_GROUPS.includes(x.group)) { g = { label: x.group, entries: [] }; if (SOLO_GROUPS.includes(x.group)) g.solo = true; groups.push(g); }
    if (!g) continue;
    if (x.after === '/warehouse/inventory-query') { const e = g.entries.find(e => e.members.some(m => m.route === x.after)); if (e) { e.members.push({ route: x.route, label: x.label, sourceTitle: x.sourceTitle }); continue; } }
    const entry = { label: x.label, canonical: x.route, members: [{ route: x.route, label: x.label, sourceTitle: x.sourceTitle }] };
    const bi = x.before ? g.entries.findIndex(e => e.members.some(m => m.route === x.before)) : -1;
    if (bi >= 0) g.entries.splice(bi, 0, entry); else g.entries.push(entry);
  }
  // 第六批（R23）A 线：六膳门「计划执行」下加「称重报工」（A 要求的位置）
  for (const g of groups) for (const e of g.entries) if (e.members.some(m => m.route === '/production/plans') && !e.members.some(m => m.route === '/production/weigh')) e.members.push({ route: '/production/weigh', label: '称重报工', sourceTitle: '统一入口新增（第六批 R23，A 线，Cretas 主来源）' });
  // A 线 BL 10/15：六膳门「生产总览」加「在制品」视图（味美没有工序任务数据，不加）
  for (const g of groups) for (const e of g.entries) if (e.label === '生产总览' && !e.members.some(m => m.route === '/production/wip')) e.members.push({ route: '/production/wip', label: '在制品', sourceTitle: '统一入口新增（A 线 BL 10/15）' });
  // C 线第三批：六膳门独有的三张黑湖只读页（味美没有对应数据，不进味美菜单）
  const LSM_ONLY = [
    { group: '销售管理', near: '/sales/customers', route: '/lsm/customer-prices', label: '客户价目表', sourceTitle: '统一入口新增（C 线第三批，黑湖 customer_price）' },
    { group: '销售管理', near: '/sales/orders', route: '/lsm/receivables', label: '应收与收款', sourceTitle: '统一入口新增（C 线第三批，黑湖 receivable / receipt / ledger）' },
    { group: '生产管理', near: null, route: '/lsm/outsource', label: '委外收发', sourceTitle: '统一入口新增（C 线第三批，黑湖 outsource_*）' },
    // 第六批（R23）
    { group: '仓储管理', near: '/warehouse/inventory-query', route: '/lsm/inbound-batches', label: '入库批次', sourceTitle: '统一入口新增（第六批 R23，C 线，Cretas 主来源）' },
    // 第八批（R41，A 线）：缺料异常算一页，生产管理里原有「缺料事件」之外，质量、采购、销售三处也挂同一路由（只六膳门；味美保持原生页）
    { group: '质量管理', near: null, route: '/production/shortage-cases', label: '缺料异常', sourceTitle: '统一入口新增（第八批 R41，A 线，缺料异常四处挂载同一页）' },
    { group: '采购管理', near: null, route: '/production/shortage-cases', label: '缺料异常', sourceTitle: '统一入口新增（第八批 R41，A 线，缺料异常四处挂载同一页）' },
    { group: '销售管理', near: null, route: '/production/shortage-cases', label: '缺料异常', sourceTitle: '统一入口新增（第八批 R41，A 线，缺料异常四处挂载同一页）' },
    { group: '数据与分析', near: '/lsm/cost', route: '/lsm/reconcile', label: '对账（Cretas ↔ 黑湖）', sourceTitle: '统一入口新增（第六批 R23，D 线汇总）' }
  ];
  for (const x of LSM_ONLY) {
    const g = groups.find(g => g.label === x.group); if (!g || g.entries.some(e => e.members.some(m => m.route === x.route))) continue;
    const entry = { label: x.label, canonical: x.route, members: [{ route: x.route, label: x.label, sourceTitle: x.sourceTitle }] };
    const i = x.near ? g.entries.findIndex(e => e.members.some(m => m.route === x.near)) : -1;
    if (i >= 0) g.entries.splice(i + 1, 0, entry); else g.entries.push(entry);
  }
  // 第三批审计第 11 条：六膳门侧请购入口是黑湖采购需求，任务组名与页面标题一致
  for (const g of groups) for (const e of g.entries) if (e.label === '请购管理' && e.members.some(m => m.route === '/procurement/requisitions/my')) e.label = '采购需求';
  // 质量检验三项按登记顺序排在拍检前
  const q = groups.find(g => g.label === '质量管理'); if (q) { const order = ['质量检验', '检验标准', '不良分析', 'CCP 监控记录', '质检退回单', '废弃处理', '批次召回', '工艺参数偏离', '冷链超温批次', '包装标签拍检', '缺料异常']; q.entries.sort((a, b) => order.indexOf(a.label) - order.indexOf(b.label)); }
  for (const g of groups) for (const e of g.entries) for (const mem of e.members) {
    if (MEMBER_LABELS[mem.route]) mem.label = MEMBER_LABELS[mem.route];
    // 六膳门里 /production/approval 是黑湖逐笔报工（只读），不是撤回审批（A 线，终版审计退回第 3 条）；味美仍叫「报工撤回审批」
    if (mem.route === '/production/approval') mem.label = '生产报工';
    // C 线第三批：六膳门侧是黑湖采购需求，不是味美的「我的请购」
    if (mem.route === '/procurement/requisitions/my') mem.label = '采购需求';
    const s = IMPLEMENTED[mem.route]; mem.status = s ? s[0] : 'pending'; mem.view = s ? s[1] : 'review'; if (reasons.get(mem.route)) mem.reason = reasons.get(mem.route);
    if (SEARCH_ALIASES[mem.route]) mem.aliases = SEARCH_ALIASES[mem.route];
    mapping.push({ source: mem.route, sourceTitle: mem.sourceTitle, group: g.label, entry: e.label, member: mem.label, status: mem.status });
  }
  mapping.push({ source: '/bom', sourceTitle: 'BOM 用料依据（六膳门原菜单）', group: '生产管理', entry: '工序与标准', member: '菜单已删除（D8），旧路由跳工序配置的 BOM 页签', status: 'live' });
  // R32：原业务组收进「全部功能」；原「概览」的首页改叫「岗位首页」（六膳门首页已换成 /g/today）；设置下沉到底部
  const ov = groups.find(g => g.label === '概览'); if (ov) { ov.section = '全部功能'; for (const e of ov.entries) if (e.label === '首页') e.label = '岗位首页'; }
  const GROUP_ORDER = ['今天', '数字班组', '待办', '发现的问题', '概览', '个人 OA', '采购管理', '销售管理', '生产管理', '仓储管理', '质量管理', '数据与分析', '设置'];
  const ENTRY_ORDER = { '数据与分析': ['经营总览', '经营分析', '成本分析', '对账（Cretas ↔ 黑湖）', '异常预警', '数据工作台', '自定义报表'] };
  groups.sort((a, b) => GROUP_ORDER.indexOf(a.label) - GROUP_ORDER.indexOf(b.label));
  for (const g of groups) if (ENTRY_ORDER[g.label]) g.entries.sort((a, b) => ENTRY_ORDER[g.label].indexOf(a.label) - ENTRY_ORDER[g.label].indexOf(b.label));
  const aliases = { '/bom': '/production/process-hub', '/crew/reports': '/crew/issues', '/crew/inbox': '/g/todo', '/crew/worker': '/crew', '/crew/mobile': '/crew', '/crew/map': '/crew' };
  for (const r of Object.keys(ROLE_ROUTES)) aliases[r] = '/dashboard';
  return { generatedFrom: 'ydwm menu-map.json + liushanmen menu-catalog.json + unified patch (D1/D4/D6/D8/D10/N9, A/C/E 登记)', groups, mapping, aliases, roleRoutes: ROLE_ROUTES, frameRoutes: FRAME_ROUTES };
}
let MENU = buildMenu();


// ---- W2：演示写入会话名校验。味美只认 test-[a-z0-9-]{8,64}，其它名字会被它静默回落到账号工作区；统一入口显式报错，不回落 ----
const SESSION_RE = /^test-[a-z0-9-]{8,64}$/;
function sessionError(u, headers) {
  const q = u.searchParams.get('acceptanceSession');
  if (q !== null && !SESSION_RE.test(q)) return { where: '地址参数 acceptanceSession', value: q };
  const h = headers['x-prototype-test-session'];
  if (h !== undefined && !SESSION_RE.test(String(h))) return { where: '请求头 x-prototype-test-session', value: String(h) };
  return null;
}
function sessionErrorPage(err) {
  const e = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  return '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>会话名不合规</title><link rel="stylesheet" href="/design-system.css"></head><body class="cs-app" style="font:14px/1.6 system-ui,sans-serif;padding:48px;max-width:720px;margin:auto"><h1 style="font-size:22px">演示会话名不合规，已停止加载</h1><p>' + e(err.where) + ' 的值 <code>' + e(err.value) + '</code> 不符合「test- 开头、8–64 位小写字母数字和连字符」的格式。</p><p>为避免写进账号工作区，统一入口不会回落到默认工作区。请改用例如 <code>?acceptanceSession=test-d-20261111</code>，或去掉该参数（统一入口默认使用独立会话 ' + e(YD_SESSION) + '）。</p></body></html>';
}

// ---- AI 排队与用量（DECISIONS 第三节）：不设队列长度上限，按请求内容去重；超过 10 分钟只提示仍在排队；用量只给管理员看 ----
const AI_PATHS = [/^\/__account\/analyze$/, /^\/api\/mobile\/[^/]+\/production\/chain\/questions$/,/^\/smartbi-api\/api\/smartbi\/(chart-insight|chat|ask|analysis)/, /^\/__lsm\/api\/analyze$/];
function isAiRequest(method, p) { return method === 'POST' && AI_PATHS.some(r => r.test(p)); }
// 第四批④：上游没有响应时给页面的统一中文降级文案（与 CretasUI.ai.sourceErrorText(502) 同一口径）
const UPSTREAM_DOWN_TEXT = '数据服务暂时没有响应：没有读取或修改任何数据，请稍后重试。';
// 第五批审计退回 4：页面导航时上游没响应，给一张中文降级页（不显示端口、错误码）；页面 10 秒后自动重试一次
const UPSTREAM_DOWN_HTML = '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>页面暂时打不开</title><style>:root{color-scheme:light dark}body{margin:0;font:15px/1.6 system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#f4f6f9;color:#1f2a37;display:grid;place-items:center;min-height:100vh}main{max-width:440px;margin:16px;padding:28px;background:#fff;border:1px solid #dce3ed;border-radius:12px}h1{font-size:18px;margin:0 0 8px}p{margin:6px 0;color:#4b5563}.row{display:flex;gap:10px;margin-top:18px}a,button{font:inherit;padding:7px 14px;border-radius:7px;border:1px solid #1b65a8;background:#1b65a8;color:#fff;text-decoration:none;cursor:pointer}a{background:#fff;color:#1b65a8}@media(prefers-color-scheme:dark){body{background:#111827;color:#e5e7eb}main{background:#1f2937;border-color:#374151}p{color:#9ca3af}a{background:#1f2937}}</style></head><body><main><h1>页面暂时打不开</h1><p>' + UPSTREAM_DOWN_TEXT + '</p><p id="t">10 秒后自动重试。</p><div class="row"><button type="button" onclick="location.reload()">现在重试</button><a href="/open">回到入口</a></div></main><script>var n=10,t=document.getElementById("t");setInterval(function(){n--;if(n<=0)location.reload();else t.textContent=n+" 秒后自动重试。"},1000)</script></body></html>';
const AI_CONCURRENCY = Number(process.env.UNIFIED_AI_CONCURRENCY || 2);
// 与 CretasUI.ai.TEXT.unavailable 同一句话；服务端改写失败响应时用，不暴露设备名、模型名和内部码
const AI_UNAVAILABLE_TEXT = '解读服务暂不可用：下面是系统按规则算出的结果，可以直接使用；稍后可重试解读。';
const aiQueue = { running: 0, waiting: [], tickets: new Map(), inflight: new Map() };
function aiPump() {
  while (aiQueue.running < AI_CONCURRENCY && aiQueue.waiting.length) { const job = aiQueue.waiting.shift(); aiQueue.running++; job.startedAt = Date.now(); job.start(); }
  aiQueue.waiting.forEach((j, i) => { if (j.ticket) aiQueue.tickets.set(j.ticket, { position: i + 1, queuedAt: j.queuedAt, state: 'queued' }); });
  for (const [k, t] of aiQueue.tickets) if (t.state === 'done' && Date.now() - t.at > 3600000) aiQueue.tickets.delete(k);
}
const USAGE_FILE = path.join(ROOT, 'private', 'ai-usage.jsonl');
function tokensOf(buf) {
  try {
    const t = buf.toString('utf8'), pick = re => { const m = t.match(re); return m ? Number(m[1]) : null; };
    const prompt = pick(/"(?:prompt_eval_count|prompt_tokens|promptTokens|input_tokens)"\s*:\s*(\d+)/), completion = pick(/"(?:eval_count|completion_tokens|completionTokens|output_tokens)"\s*:\s*(\d+)/);
    return prompt == null && completion == null ? null : { prompt, completion };
  } catch { return null; }
}
function logUsage(rec) { try { fs.appendFileSync(USAGE_FILE, JSON.stringify(rec) + '\n'); } catch (e) { console.error('usage log', e.message); } }
// 把一个 AI 请求排进队列：同一内容（路径+会话+请求体）正在跑时直接复用结果，不重复调用
async function proxyAi(req, res, target, rewritePath, workspace, opts = {}) {
  const body = req.method === 'GET' ? Buffer.alloc(0) : await readBody(req);
  const key = crypto.createHash('sha256').update(rewritePath + '|' + (req.headers['x-prototype-test-session'] || '') + '|' + body.toString('utf8')).digest('hex');
  const ticket = String(req.headers['x-ai-ticket'] || '').slice(0, 64) || null;
  const queuedAt = Date.now();
  const finish = (r, deduped, startedAt) => {
    if (!res.headersSent) { res.writeHead(r.status, { ...r.headers, 'content-length': r.body.length, 'x-unified-ai-queue-ms': String((startedAt || Date.now()) - queuedAt), 'x-unified-ai-deduped': deduped ? '1' : '0' }); res.end(r.body); }
    if (ticket) aiQueue.tickets.set(ticket, { position: 0, queuedAt, state: 'done', at: Date.now() });
  };
  if (aiQueue.inflight.has(key)) { if (ticket) aiQueue.tickets.set(ticket, { position: 0, queuedAt, state: 'running' }); const r = await aiQueue.inflight.get(key); logUsage({ at: new Date().toISOString(), workspace, path: rewritePath.split('?')[0], status: r.status, queuedMs: 0, runMs: Date.now() - queuedAt, deduped: true, tokens: null }); finish(r, true); return; }
  let resolveJob; const done = new Promise(r => { resolveJob = r; }); aiQueue.inflight.set(key, done);
  const job = { ticket, queuedAt, startedAt: 0, start() {
    if (ticket) aiQueue.tickets.set(ticket, { position: 0, queuedAt, state: 'running' });
    const headers = { ...req.headers, host: target.host + ':' + target.port, 'accept-encoding': 'identity', 'content-length': String(body.length) };
    const origin = 'http://' + target.host + ':' + target.port; if (headers.origin) headers.origin = origin; if (headers.referer) headers.referer = headers.referer.replace(/^http:\/\/(127\.0\.0\.1|localhost):\d+/, origin);
    if (target === YD && !headers['x-prototype-test-session']) headers['x-prototype-test-session'] = YD_SESSION;
    delete headers['x-ai-ticket'];
    const end = r => { aiQueue.running--; aiQueue.inflight.delete(key); resolveJob(r); finish(r, false, job.startedAt); aiPump(); };
    if (req.method === 'GET') { delete headers['content-length']; }
    const up = http.request({ host: target.host, port: target.port, method: req.method, path: rewritePath, headers }, ur => {
      const chunks = []; ur.on('data', c => chunks.push(c)); ur.on('end', () => {
        let buf = Buffer.concat(chunks); const h = { ...ur.headers }; delete h['content-length']; delete h['transfer-encoding'];
        if (ur.statusCode >= 400 && (opts.cleanError || /Mac mini|SSH|curl|qwen|ollama|（连接 ?\d+）/i.test(buf.toString('utf8')))) buf = Buffer.from(JSON.stringify({ success: false, code: ur.statusCode, message: AI_UNAVAILABLE_TEXT, unavailable: true })), h['content-type'] = 'application/json; charset=utf-8';
        logUsage({ at: new Date().toISOString(), workspace, path: rewritePath.split('?')[0], status: ur.statusCode, queuedMs: job.startedAt - queuedAt, runMs: Date.now() - job.startedAt, deduped: false, tokens: tokensOf(buf) });
        end({ status: ur.statusCode, headers: h, body: buf });
      });
    });
    up.on('error', e => { logUsage({ at: new Date().toISOString(), workspace, path: rewritePath.split('?')[0], status: 502, queuedMs: job.startedAt - queuedAt, runMs: Date.now() - job.startedAt, deduped: false, tokens: null, error: e.code }); end({ status: 502, headers: { 'content-type': 'application/json; charset=utf-8' }, body: Buffer.from(JSON.stringify({ success: false, message: '解读服务暂不可用（上游 ' + target.port + ' 未响应）', unavailable: true })) }); });
    up.end(body);
  } };
  aiQueue.waiting.push(job); aiPump();
}
function usageSummary(limit = 200) {
  let lines = []; try { lines = fs.readFileSync(USAGE_FILE, 'utf8').trim().split('\n').filter(Boolean).map(l => JSON.parse(l)); } catch { /* 还没有记录 */ }
  const sum = (a, f) => a.reduce((n, r) => n + (Number(f(r)) || 0), 0), withTokens = lines.filter(r => r.tokens);
  return { total: lines.length, deduped: lines.filter(r => r.deduped).length, failed: lines.filter(r => r.status >= 400).length, withTokens: withTokens.length, promptTokens: sum(withTokens, r => r.tokens.prompt), completionTokens: sum(withTokens, r => r.tokens.completion), avgQueueMs: lines.length ? Math.round(sum(lines, r => r.queuedMs) / lines.length) : null, avgRunMs: lines.length ? Math.round(sum(lines, r => r.runMs) / lines.length) : null, queue: { running: aiQueue.running, waiting: aiQueue.waiting.length, concurrency: AI_CONCURRENCY }, recent: lines.slice(-limit).reverse(), note: '只统计经统一入口发出的解读请求；上游没有返回 token 计数时记为「未返回」，不估算。' };
}


// ---- N9 自定义报表：模板与快照只写 unified-v1/private/reports-<工作区>-<会话>.json，不写任何业务数据 ----
const REPORT_WS = new Set(['ydwm', 'lsm']);
function reportFile(ws, session) { return path.join(ROOT, 'private', 'reports-' + ws + '-' + session + '.json'); }
function reportSession(req) { const h = String(req.headers['x-prototype-test-session'] || ''); return SESSION_RE.test(h) ? h : 'default'; }
function readReports(ws, session) { try { return JSON.parse(fs.readFileSync(reportFile(ws, session), 'utf8')); } catch { return { version: 0, templates: [], runs: [] }; } }
function writeReports(ws, session, data) { const f = reportFile(ws, session), tmp = f + '.' + process.pid + '.tmp'; fs.writeFileSync(tmp, JSON.stringify(data, null, 2)); fs.renameSync(tmp, f); }
const PERIODS = new Set(['none', 'daily', 'weekly', 'monthly']), CHARTS = new Set(['table', 'bar', 'line']);
function cleanTemplate(t) {
  const str = (v, n) => String(v ?? '').trim().slice(0, n);
  if (!t || typeof t !== 'object') throw Error('模板内容缺失');
  const name = str(t.name, 60); if (!name) throw Error('请填写报表名称');
  const source = str(t.source, 40); if (!/^[a-z-]+$/.test(source)) throw Error('数据源无效');
  const fields = Array.isArray(t.fields) ? t.fields.map(f => str(f, 60)).filter(Boolean).slice(0, 20) : [];
  const groupBy = str(t.groupBy, 60), measure = str(t.measure, 60), agg = ['sum', 'count', 'avg'].includes(t.agg) ? t.agg : 'count';
  const filters = Array.isArray(t.filters) ? t.filters.slice(0, 10).map(f => ({ field: str(f.field, 60), op: ['eq', 'contains', 'gte', 'lte'].includes(f.op) ? f.op : 'eq', value: str(f.value, 100) })).filter(f => f.field) : [];
  const chart = CHARTS.has(t.chart) ? t.chart : 'table', period = PERIODS.has(t.period) ? t.period : 'none';
  return { id: /^[a-z0-9-]{8,64}$/.test(t.id || '') ? t.id : crypto.randomUUID(), name, source, fields, groupBy, measure, agg, filters, chart, period, note: str(t.note, 300) };
}
async function handleReports(req, res, u) {
  const ws = String(u.searchParams.get('ws') || ''); if (!REPORT_WS.has(ws)) { send(res, 400, { success: false, message: '工作区参数无效' }); return; }
  const session = reportSession(req);
  if (req.method === 'GET') { send(res, 200, { success: true, data: { ...readReports(ws, session), workspace: ws, session } }); return; }
  if (req.method !== 'POST') { send(res, 405, { success: false, message: '只支持读取和保存' }); return; }
  if (req.headers['x-prototype-action'] !== 'explicit') { send(res, 403, { success: false, message: '缺少明确操作标记' }); return; }
  let body; try { body = JSON.parse((await readBody(req, 4_000_000)).toString('utf8') || '{}'); } catch { send(res, 400, { success: false, message: '请求格式错误' }); return; }
  const data = readReports(ws, session);
  if (body.expectedVersion !== undefined && body.expectedVersion !== data.version) { send(res, 409, { success: false, message: '报表已在别处修改，请刷新后再保存', version: data.version }); return; }
  try {
    const now = new Date().toISOString();
    if (body.op === 'save') { const t = cleanTemplate(body.template); const i = data.templates.findIndex(x => x.id === t.id); const rec = { ...t, updatedAt: now, createdAt: i >= 0 ? data.templates[i].createdAt : now }; if (i >= 0) data.templates[i] = rec; else data.templates.push(rec); }
    else if (body.op === 'delete') { const before = data.templates.length; data.templates = data.templates.filter(x => x.id !== body.id); if (data.templates.length === before) throw Error('模板不存在'); }
    else if (body.op === 'run') {
      const t = data.templates.find(x => x.id === body.id); if (!t) throw Error('模板不存在');
      const r = body.run || {}; const rows = Array.isArray(r.rows) ? r.rows.slice(0, 2000) : [];
      data.runs.unshift({ id: crypto.randomUUID(), templateId: t.id, templateName: t.name, period: t.period, generatedAt: now, sourceRevision: String(r.sourceRevision || '').slice(0, 80), sourceLabel: String(r.sourceLabel || '').slice(0, 120), columns: Array.isArray(r.columns) ? r.columns.slice(0, 20) : [], rows, summary: r.summary || null, chart: t.chart, config: t });
      data.runs = data.runs.slice(0, 200);
    } else throw Error('未知操作');
    data.version = (data.version || 0) + 1; writeReports(ws, session, data);
    send(res, 200, { success: true, data: { ...readReports(ws, session), workspace: ws, session } });
  } catch (e) { send(res, 400, { success: false, message: e.message }); }
}

// ---- 反向代理 ----
function proxy(req, res, target, rewritePath, opts = {}) {
  const headers = { ...req.headers, host: target.host + ':' + target.port, 'accept-encoding': 'identity' };
  const origin = 'http://' + target.host + ':' + target.port;
  if (headers.origin) headers.origin = origin;
  if (headers.referer) headers.referer = headers.referer.replace(/^http:\/\/(127\.0\.0\.1|localhost):\d+/, origin);
  if (opts.action) headers['x-prototype-action'] = 'explicit';
  // 味美工作区在统一入口下使用独立会话：保存只写 data/v3/test-state 下的本入口会话文件，
  // 不写受保护的账号工作区（data/v3/factory-workspace.json）。已带会话头（如「模拟数据」）时保持原值。
  if (target === YD && YD_SESSION_PATH.test(rewritePath) && !headers['x-prototype-test-session']) headers['x-prototype-test-session'] = YD_SESSION;
  const up = http.request({ host: target.host, port: target.port, method: req.method, path: rewritePath, headers }, ur => {
    const h = { ...ur.headers };
    if (h.location) h.location = h.location.replace(/^http:\/\/(127\.0\.0\.1|localhost):(19060|19061|19062)/, '');
    const type = String(h['content-type'] || '');
    if (opts.inject && type.includes('text/html') && ur.statusCode === 200) {
      const chunks = []; ur.on('data', c => chunks.push(c)); ur.on('end', () => {
        let html = Buffer.concat(chunks).toString('utf8');
        // 框架路由守卫：味美原版路由表没有的框架路由（如 /lsm/stock-summary），原版 router 会改写成 /404；
        // 在应用脚本之前记下目标地址，原版改写成 /404 时换回目标地址，由外壳覆盖为框架页面。
        // 框架路由守卫：味美原版路由表没有的框架路由（host 字段），在原版 router 启动前把地址换成「原生宿主路由?uframe=框架路由」，
        // 原版 router 正常渲染带侧栏的布局，外壳再把内容区覆盖为框架页面；地址保留 uframe，刷新和分享都能回到同一页。
        const hosts = {}; for (const r of FRAME_ROUTES) if (r.host) hosts[r.path] = r.host;
        const guard = '<script>(function(){var H=' + JSON.stringify(hosts) + ';function conv(u){try{var x=new URL(u,location.href);var h=H[x.pathname];if(!h)return null;var q=new URLSearchParams(x.search);q.set("uframe",x.pathname);return h+"?"+q.toString()+x.hash}catch(e){return null}}var c=conv(location.href);if(c)history.replaceState(history.state,"",c);' +
          '["pushState","replaceState"].forEach(function(k){var o=history[k];history[k]=function(s,t,u){if(u!=null){var v=conv(u);if(v)u=v}return o.call(this,s,t,u)}});window.__uniFrameHosts=H})();</script>';
        const tags = guard + '<link rel="stylesheet" href="/__unified/shell.css"><script defer src="/__unified/shell.js"></script><script defer src="/__unified/pages-d.js"></script>';
        html = /<\/head>/i.test(html) ? html.replace(/<\/head>/i, tags + '</head>') : tags + html;
        const body = Buffer.from(html); delete h['content-length']; delete h['transfer-encoding'];
        res.writeHead(ur.statusCode, { ...h, 'content-length': body.length }); res.end(body);
      });
      return;
    }
    res.writeHead(ur.statusCode, h); ur.pipe(res);
  });
  up.on('error', e => { console.error('upstream', target.port, rewritePath, e.code, e.message); if (res.headersSent) { res.destroy(); return; }
    // 第四批：页面不显示端口、错误码等技术细节，端口只进服务日志；第五批审计退回 4：页面导航（GET 且 Accept 含 html）给中文降级页，接口请求仍回 JSON
    if (req.method === 'GET' && /text\/html/.test(req.headers.accept || '')) { const html = Buffer.from(UPSTREAM_DOWN_HTML); res.writeHead(502, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'content-length': html.length }); res.end(html); return; }
    send(res, 502, { success: false, code: 502, unavailable: true, message: UPSTREAM_DOWN_TEXT }); });
  req.pipe(up);
}

function serveFile(res, base, rel) {
  const file = path.resolve(base, rel);
  if (!file.startsWith(path.resolve(base) + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) { send(res, 404, { success: false, message: '未找到' }); return; }
  send(res, 200, fs.readFileSync(file), MIME[path.extname(file)] || 'application/octet-stream', { 'Cache-Control': path.extname(file) === '.html' ? 'no-store' : 'no-cache' });
}

const server = http.createServer(async (req, res) => {
  try {
    if (!['127.0.0.1:' + PORT, 'localhost:' + PORT].includes(req.headers.host)) { send(res, 403, { success: false, message: '仅允许本机统一入口访问' }); return; }
    if (req.headers.origin && !['http://127.0.0.1:' + PORT, 'http://localhost:' + PORT].includes(req.headers.origin)) { send(res, 403, { success: false, message: '不接受外部网页请求' }); return; }
    const u = new URL(req.url, 'http://localhost'), p = u.pathname;
    if (p === '/__unified/health') { send(res, 200, { ready: true, entry: 'unified', port: PORT, workspaces: ['ydwm', 'liushanmen'] }); return; }
    if (p === '/__unified/menu.json') { MENU = buildMenu(); send(res, 200, MENU); return; }
    const sErr = sessionError(u, req.headers);
    if (sErr) { if (req.method === 'GET' && String(req.headers.accept || '').includes('text/html')) send(res, 400, sessionErrorPage(sErr), 'text/html; charset=utf-8'); else send(res, 400, { success: false, message: '演示会话名不合规（' + sErr.where + '：' + sErr.value + '），已拒绝，未回落到账号工作区。请用 test- 开头的名称，如 test-d-20261111。' }); return; }
    if (p === '/__unified/reports') { await handleReports(req, res, u); return; }
    if (p === '/__unified/ai-queue') { const t = aiQueue.tickets.get(String(u.searchParams.get('ticket') || '')); send(res, 200, { success: true, data: t ? { ...t, waitedMs: Date.now() - t.queuedAt } : { state: 'unknown' }, queue: { running: aiQueue.running, waiting: aiQueue.waiting.length } }); return; }
    if (p === '/__unified/ai-usage') { if (req.headers['x-unified-admin'] !== '1') { send(res, 403, { success: false, message: '解读用量只对管理员开放（账号菜单 → 原型与来源 → 解读用量）。' }); return; } send(res, 200, { success: true, data: usageSummary() }); return; }
    if (p === '/system-kit/menu-map.json' && req.method === 'GET') { send(res, 200, patchYdwmMenuMap(JSON.parse(fs.readFileSync(path.join(ACCOUNTS, 'ydwm-v1/public/system-kit/menu-map.json'), 'utf8')))); return; }
    if (req.method === 'GET' && ROLE_ROUTES[p]) { res.writeHead(302, { Location: '/dashboard?role=' + ROLE_ROUTES[p] }); res.end(); return; }
    // 经营驾驶舱「经营洞察」会走模型：页面打开时原版会自动请求。统一入口只放行用户点了「经营解读」的请求（带 x-unified-explicit-ai），
    // 自动请求直接回一个空结果，不调模型；放行的请求失败时，去掉设备名和内部码，换成统一降级文案（复测退回第 11 条）
    if (/\/executive\/insights(\/|$|\?)/.test(p + (u.search ? '?' : ''))) {
      if (req.headers['x-unified-explicit-ai'] !== '1') { send(res, 200, { success: true, code: 200, message: '尚未生成：点「经营解读」后才分析', data: null, unifiedSkipped: true }); return; }
      await proxyAi(req, res, YD, req.url, 'ydwm', { cleanError: true }); return;
    }
    if (isAiRequest(req.method, p)) { if (p.startsWith('/__lsm/api/')) await proxyAi(req, res, LS, '/api/' + p.slice('/__lsm/api/'.length) + u.search, 'lsm'); else await proxyAi(req, res, YD, req.url, 'ydwm'); return; }
    if (p.startsWith('/__unified/a/')) { const a = optional('./src/production/a-api.cjs'); if (!a) { send(res, 503, { success: false, message: '生产链接口未就绪（A 线文件缺失）' }); return; } await a.handle({ req, res, u, p, send, readBody, root: ROOT }); return; }
    // D 线第三批：六膳门财务口径与黑湖操作日志（只读 liushanmen private/extras-current.json）
    // 第四批④：降级文案验收用的探针，固定转发到本机 8083（不在运行的服务），只接受 GET，走与正式代理同一条错误路径
    // 第四批②：异常预警「预警归因」（解释模式，只读 E 的规则提醒；只在点击时请求）
    if (p === '/__unified/d/alert-explain' || p === '/__unified/d/approval-explain' || p === '/__unified/d/report-explain' || p === '/__unified/d/report-archive') { const m = optional('./src/home/d-ai.cjs'); if (!m) { send(res, 503, { success: false, message: '预警归因未就绪' }); return; } await m.handle({ req, res, u, send, readBody }); return; }
    if (p === '/__unified/d/probe-down') { if (req.method !== 'GET') { send(res, 405, { success: false, message: '只读探针' }); return; } proxy(req, res, { host: '127.0.0.1', port: 8083 }, '/'); return; }
    if (p === '/__unified/d/lsm-finance' || p === '/__unified/d/lsm-oplog') { const f = optional('./src/home/finance-lsm.cjs'); if (!f) { send(res, 503, { success: false, message: '财务口径接口未就绪' }); return; } await f.handle({ req, res, u, send }); return; }
    // F 线数字班组后端（文件缺失时 503，不影响启动）
    if (p.startsWith('/__unified/f/')) { const f = optional('./src/crew/f-api.cjs'); if (!f) { send(res, 503, { success: false, message: '数字班组接口未就绪（F 线文件缺失）' }); return; } await f.handle({ req, res, u, p, send, readBody, root: ROOT }); return; }
    if (p.startsWith('/__unified/e/')) { const e = optional('./src/collab/e-api.cjs'); if (!e) { send(res, 503, { success: false, message: '质量与协同任务接口未就绪（E 线文件缺失）' }); return; } await e.handle({ req, res, u, p, send, readBody, root: ROOT }); return; }
    if (p.startsWith('/__unified/lsm/')) { serveFile(res, path.join(ROOT, 'dist'), p.slice('/__unified/lsm/'.length) || 'index.html'); return; }
    if (p === '/__unified/lsm') { res.writeHead(302, { Location: '/__unified/lsm/index.html' }); res.end(); return; }
    if (p.startsWith('/__unified/')) { serveFile(res, path.join(ROOT, 'public'), p.slice('/__unified/'.length)); return; }
    if (p.startsWith('/__lsm/product-cost')) {
      if (req.method === 'POST' && !req.headers.origin) { send(res, 403, { success: false, message: '缺少同源请求头' }); return; }
      const raw = req.method === 'POST' ? (await readBody(req)).toString('utf8') : '';
      await cost.handle({ req, res, u, method: req.method, send, rawBody: raw }); return;
    }
    if (p.startsWith('/__lsm/supply')) {
      if (!supply) { send(res, 503, { success: false, message: '采购销售库存接口未就绪（C 线文件缺失）' }); return; }
      if (req.method === 'POST' && !req.headers.origin) { send(res, 403, { success: false, message: '缺少同源请求头' }); return; }
      const raw = req.method === 'POST' ? (await readBody(req)).toString('utf8') : '';
      await supply.handle({ req, res, u, method: req.method, send, rawBody: raw }); return;
    }
    if (p.startsWith('/__lsm/api/')) { proxy(req, res, LS, '/api/' + p.slice('/__lsm/api/'.length) + u.search); return; }
    if (p === '/design-tokens.css') { proxy(req, res, YD, '/design-system.css'); return; }
    // 味美默认把 public/*.js 打包成冻结的 fast-ui（选择记录在受保护的 private/ 下，不能重建）。
    // 统一入口请求页面时改用 classic 组装，让各线对 public/*.js 的修改直接生效；浏览器地址不变。
    let forward = req.url;
    if (req.method === 'GET' && String(req.headers.accept || '').includes('text/html') && UI_BUILD && !u.searchParams.has('uiBuild')) { const f = new URL(req.url, 'http://localhost'); f.searchParams.set('uiBuild', UI_BUILD); forward = f.pathname + f.search; }
    proxy(req, res, YD, forward, { inject: req.method === 'GET' });
  } catch (e) { if (!res.headersSent) send(res, 500, { success: false, message: String(e.message) }); }
});
if (require.main === module) server.listen(PORT, '127.0.0.1', () => {
  fs.writeFileSync(path.join(ROOT, 'private', 'server-state.json'), JSON.stringify({ pid: process.pid, port: PORT, startedAt: new Date().toISOString() }));
  console.log('Unified entry ready on http://127.0.0.1:' + PORT + '/open');
});
module.exports = { buildMenu, patchYdwmMenuMap, sessionError, isAiRequest };
