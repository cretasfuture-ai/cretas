// 统一框架路由：菜单路径 → 内容视图。菜单路径与味美 menu-map.json 同名（同一套任务组语法）。
// view 决定由哪个模块文件渲染：home / production / canvas / cost / supply / reports / e / pending。
// 各线新增路由报给 D 登记（DECISIONS 第七节）；味美工作区用 frame 打开的路由另登记在 server.cjs 的 FRAME_ROUTES，hash 带 ws=ydwm。
import type {ListKind} from './types'
export type View='g'|'crew'|'reconcile'|'finance'|'home'|'production'|'canvas'|'cost'|'supply'|'pending'|'reports'|'aireport'|'oa'|'e'
export interface RouteInfo{view:View;list?:ListKind;title:string;sub:string;ydwmOnly?:boolean;lsmOnly?:boolean}
export const ROUTES:Record<string,RouteInfo>={
  // 第八批登记（D）· A 的 6 页迁入六膳门（味美继续走编译原生页）
  '/production/shortage-cases':{view:'production',list:'orders',title:'缺料异常',sub:'缺料事件与处理',lsmOnly:true},
  '/dashboard/production-progress':{view:'production',list:'orders',title:'车间大屏',sub:'工单进度大屏',lsmOnly:true},
  '/production/batches':{view:'production',list:'orders',title:'生产批次',sub:'六膳门按工单展示',lsmOnly:true},
  '/production/yield-standard':{view:'production',list:'orders',title:'标准出成率',sub:'出成标准',lsmOnly:true},
  '/production/kanban':{view:'production',list:'orders',title:'状态看板',sub:'工单状态看板',lsmOnly:true},
  '/production/analysis-query':{view:'production',list:'orders',title:'生产分析',sub:'生产查询与分析',lsmOnly:true},
  // 第八批登记（D）：E 质量闭环、C 占位迁入、A 交付风险与工程变更通知、B 调价建议
  '/transfer/list':{view:'supply',title:'调拨单',sub:'黑湖只读 · 缺口写明',lsmOnly:true},
  '/sales/shipments':{view:'supply',title:'发运记录',sub:'黑湖只读 · 缺口写明',lsmOnly:true},
  '/sales/returns':{view:'supply',title:'销售退货',sub:'黑湖只读 · 缺口写明',lsmOnly:true},
  '/warehouse/unordered-inbound-applications':{view:'supply',title:'无订单入库申请',sub:'黑湖只读 · 缺口写明',lsmOnly:true},
  '/warehouse/stocktake-query':{view:'supply',title:'盘点任务',sub:'黑湖只读 · 缺口写明',lsmOnly:true},
  '/warehouse/material-types':{view:'supply',title:'物料类型字典',sub:'黑湖只读 · 缺口写明',lsmOnly:true},
  '/warehouse/material-segments':{view:'supply',title:'物料分类',sub:'黑湖只读 · 缺口写明',lsmOnly:true},
  '/warehouse/material-price-trend':{view:'supply',title:'物料均价趋势',sub:'黑湖只读 · 缺口写明',lsmOnly:true},
  '/procurement/requisitions/pending-approval':{view:'supply',title:'待审批请购',sub:'黑湖只读 · 缺口写明',lsmOnly:true},
  '/procurement/ai-purchase':{view:'supply',title:'采购建议',sub:'黑湖只读 · 缺口写明',lsmOnly:true},
  '/procurement/price-lists':{view:'supply',title:'价格表',sub:'黑湖只读 · 缺口写明',lsmOnly:true},
  '/warehouse/wastage-reports':{view:'supply',title:'报损管理',sub:'黑湖只读 · 缺口写明',lsmOnly:true},
  '/warehouse/source-individuals':{view:'supply',title:'源头个体档案',sub:'黑湖只读 · 缺口写明',lsmOnly:true},
  '/sales/vehicles':{view:'supply',title:'车辆字典',sub:'黑湖只读 · 缺口写明',lsmOnly:true},
  '/sales/payment-requests':{view:'supply',title:'销售付款申请',sub:'黑湖只读 · 缺口写明',lsmOnly:true},
  '/quality/ccp':{view:'e',title:'CCP 监控记录',sub:'工序关键控制点监控',lsmOnly:true},
  '/quality/returns':{view:'e',title:'质检退回单',sub:'不合格退回处理',lsmOnly:true},
  '/quality/disposals':{view:'e',title:'废弃处理',sub:'废弃（报废、销毁）记录',lsmOnly:true},
  '/quality/recalls':{view:'e',title:'批次召回',sub:'按 C 批次发起召回'},
  '/quality/param-deviation':{view:'e',title:'工艺参数偏离',sub:'只写缺口'},
  '/quality/cold-chain':{view:'e',title:'冷链超温批次',sub:'只写缺口'},
  '/quality/vision-training':{view:'e',title:'视觉质检训练',sub:'只写缺口（下沉到设置）'},
  '/quality/label-qc':{view:'e',title:'包装标签拍检',sub:'六膳门迁入（R41）',lsmOnly:true},
  '/production/delivery-risks':{view:'production',list:'orders',title:'交付风险',sub:'交期 × 计划进度 × 齐套'},
  '/production/bom/ecns':{view:'production',list:'orders',title:'工程变更通知',sub:'黑湖没有此类数据',lsmOnly:true},
  '/production/bom/price-adjustments':{view:'cost',title:'BOM调价建议',sub:'原料价变动 → 成本影响 → 调价建议（只作建议）'},
  '/dashboard':{view:'home',title:'工厂总览',sub:'首页'},
  '/system/products':{view:'production',list:'products',title:'产品',sub:'产品、工艺与BOM'},
  '/production/plans':{view:'production',list:'orders',title:'生产工单',sub:'计划与执行记录'},
  '/production/approval':{view:'production',list:'reports',title:'生产报工 · 只读',sub:'逐笔产出 · 审批在黑湖办理'},
  '/production/process-hub':{view:'canvas',title:'工序配置',sub:'工艺目录 · 画布 · 节点用料'},
  '/lsm/cost':{view:'cost',title:'成本分析',sub:'产品 → 工单 → 工序 → 成本依据'},
  // D 线第三批①：六膳门经营分析接财务口径（味美经营分析仍是原页面）
  '/smart-bi/analysis-hub':{view:'finance',title:'经营分析',sub:'财务口径',lsmOnly:true},
  // 第六批（R23）：称重报工归 A（ProductionList 按路径分到 A 的称重页），入库批次归 C（SupplyRecords 按路径分到 C 的批次页），对账汇总归 D
  '/production/weigh':{view:'production',list:'orders',title:'称重报工',sub:'逐笔称重 · 出成率 · 对账',lsmOnly:true},
  '/lsm/inbound-batches':{view:'supply',title:'入库批次',sub:'Cretas 主来源 · 批次与到期',lsmOnly:true},
  '/lsm/reconcile':{view:'reconcile',title:'对账（Cretas ↔ 黑湖）',sub:'称重报工 · 入库批次',lsmOnly:true},
  // F 线（数字班组，DECISIONS 第十四节）：两个工作区都开
  '/crew':{view:'crew',title:'班组首页',sub:'数字班组'},
  '/crew/worker':{view:'crew',title:'数字员工工作台',sub:'数字班组'},
  '/crew/inbox':{view:'crew',title:'等我确认',sub:'数字班组'},
  '/crew/ledger':{view:'crew',title:'工作记录与额度',sub:'数字班组'},
  '/crew/onboard':{view:'crew',title:'班组入职',sub:'数字班组'},
  '/crew/reports':{view:'crew',title:'发现的问题',sub:'数字班组'},
  '/crew/issues':{view:'crew',title:'发现的问题',sub:'数字班组'},
  '/crew/settings':{view:'crew',title:'数字班组配置',sub:'设置'},
  '/crew/mobile':{view:'crew',title:'一线手机端',sub:'数字班组'},
  '/crew/map':{view:'crew',title:'功能归属与架构',sub:'数字班组'},
  // G 线（管理端盘点与智能体框架重整，DECISIONS 第十五节）：骨架页，两个工作区都开；主菜单暂不放（等 Steve 回答 G1/G2），从「原型与来源 → 菜单与动线对照」进
  '/g/today':{view:'g',title:'今天',sub:'重整方案 · 骨架'},
  '/g/todo':{view:'g',title:'待办',sub:'重整方案 · 骨架'},
  '/g/issues':{view:'g',title:'发现的问题',sub:'重整方案 · 骨架'},
  '/g/scheduling':{view:'g',title:'智能排产',sub:'重整方案 · 骨架'},
  '/g/delivery':{view:'g',title:'交付与备货',sub:'重整方案 · 骨架'},
  '/g/engineering':{view:'g',title:'工程变更与调价',sub:'重整方案 · 骨架'},
  '/g/rd':{view:'g',title:'研发样品',sub:'重整方案 · 骨架'},
  '/g/warehouse-more':{view:'g',title:'报损与其他仓储',sub:'重整方案 · 骨架'},
  '/g/purchase-more':{view:'g',title:'收票退货与核价',sub:'重整方案 · 骨架'},
  '/g/sales-more':{view:'g',title:'报价跟踪与提醒',sub:'重整方案 · 骨架'},
  '/g/quality-loop':{view:'g',title:'放行与处置',sub:'重整方案 · 骨架'},
  '/g/trace':{view:'g',title:'追溯与复盘',sub:'重整方案 · 骨架'},
  '/g/finance':{view:'g',title:'财务',sub:'重整方案 · 骨架'},
  '/g/reports':{view:'g',title:'经营报告与指标',sub:'重整方案 · 骨架'},
  '/g/people':{view:'g',title:'组织与人员',sub:'重整方案 · 骨架'},
  '/g/equipment':{view:'g',title:'设备台账',sub:'重整方案 · 骨架'},
  '/g/settings':{view:'g',title:'设置',sub:'重整方案 · 骨架'},
  '/g/map':{view:'g',title:'菜单与动线对照',sub:'重整方案 · 骨架'},
  '/unified/reports':{view:'reports',title:'自定义报表',sub:'报表模板与周期快照'},
  '/analytics/ai-reports':{view:'aireport',title:'AI 经营报告',sub:'周报与月报 · 事实表'},
  '/workflow/pending':{view:'oa',title:'待我处理',sub:'个人 OA · 审批中心',lsmOnly:true},
  '/workflow/my-created':{view:'oa',title:'我发起的',sub:'个人 OA · 审批中心',lsmOnly:true},
  '/workflow/acted':{view:'oa',title:'已处理',sub:'个人 OA · 审批中心',lsmOnly:true},
  '/workflow/copied':{view:'oa',title:'抄送我的',sub:'个人 OA · 审批中心',lsmOnly:true},
  '/warehouse/inventory-query':{view:'supply',title:'库存查询',sub:'来源业务记录'},
  '/warehouse/materials':{view:'supply',title:'入库记录',sub:'来源业务记录'},
  '/warehouse/shipments':{view:'supply',title:'出库记录',sub:'来源业务记录'},
  '/warehouse/storage-locations':{view:'supply',title:'仓库档案',sub:'来源业务记录'},
  '/production/material-flow':{view:'supply',title:'生产领退料',sub:'来源业务记录'},
  // C 线登记（N5 / N6；味美侧销售订单精简视图）
  '/lsm/stock-summary':{view:'supply',title:'进销存汇总',sub:'期初、入、出、期末'},
  '/warehouse/inventory-adjustments':{view:'supply',title:'库存调整单',sub:'单条调整'},
  // C 线第三批①②：六膳门黑湖只读页（SupplyRecords 按 LSM_EXTRA 分到 LsmExtras.vue）；/sales/orders 两个工作区都走 supply
  '/sales/orders':{view:'supply',title:'销售订单',sub:'黑湖只读'},
  '/procurement/orders':{view:'supply',title:'采购订单',sub:'黑湖只读'},
  '/procurement/requisitions/my':{view:'supply',title:'采购需求',sub:'黑湖只读'},
  '/sales/customers':{view:'supply',title:'客户',sub:'黑湖只读'},
  '/procurement/suppliers':{view:'supply',title:'供应商',sub:'黑湖只读'},
  '/lsm/customer-prices':{view:'supply',title:'客户价目表',sub:'黑湖只读',lsmOnly:true},
  '/lsm/outsource':{view:'supply',title:'委外收发',sub:'黑湖只读',lsmOnly:true},
  '/lsm/receivables':{view:'supply',title:'应收与收款',sub:'应收 · 收款 · 待开票',lsmOnly:true},
  // E 线登记（N7 / N8 / B7）
  '/quality/inspections':{view:'e',title:'质量检验',sub:'按阶段检验'},
  '/quality/standards':{view:'e',title:'检验标准',sub:'按阶段维护'},
  '/quality/defects':{view:'e',title:'不良分析',sub:'不良品项分布'},
  '/collab/tasks':{view:'e',title:'协同任务',sub:'指派与跟进'},
  // A 线 BL 10 / BL 15：在制品与效率（六膳门才有工序任务数据，味美不开）；ProductionList 分流到 WipMatrix.vue，?tab=eff 进效率页签
  '/production/wip':{view:'production',list:'orders',title:'在制品与效率',sub:'工序 × 工单 · 效率与达标率',lsmOnly:true}
}
// 旧六膳门独立路由 → 统一路由（菜单合并不删原入口）。/bom 菜单已删（D8），路由跳工序配置的用料页签。
export const ALIASES:Record<string,string>={'/quality/shortage-cases':'/production/shortage-cases','/procurement/shortage-cases':'/production/shortage-cases','/sales/shortage-cases':'/production/shortage-cases','/sales/delivery-risks':'/production/delivery-risks','/crew/reports':'/crew/issues','/lsm/weighing':'/production/weigh','/lsm/batches':'/lsm/inbound-batches','/bom':'/production/process-hub?tab=bom','/cost':'/lsm/cost','/products':'/system/products','/orders':'/production/plans','/reports':'/production/approval','/processes':'/production/process-hub'}
// 深链约定（DECISIONS 六之后）：hash 里的查询参数原样透传，不改写
export function parseHash(hash:string):{path:string;query:URLSearchParams}{
  const raw=hash.replace(/^#/,'')||'/dashboard',i=raw.indexOf('?'),p=i<0?raw:raw.slice(0,i),q=i<0?'':raw.slice(i+1)
  const alias=ALIASES[p],[ap,aq]=alias?alias.split('?'):[p,'']
  const query=new URLSearchParams(aq||'');new URLSearchParams(q).forEach((v,k)=>query.set(k,v))
  return {path:ap.startsWith('/')?ap:'/dashboard',query}
}
// 味美工作区（ws=ydwm）下改走别的视图的路由（A 线 R4：领料在味美由 ProductionList 分流到味美新视图）
export const YDWM_ROUTES:Record<string,RouteInfo>={
  '/production/bom/price-adjustments':{view:'cost',title:'BOM调价建议',sub:'味美 · 精简视图'},
  '/production/material-flow':{view:'production',list:'orders',title:'领料',sub:'味美 · 精简视图'},
  // C 线 R6：采购订单精简视图（库存查询、销售订单本来就是 supply）
  '/procurement/orders':{view:'supply',title:'采购订单',sub:'味美 · 精简视图'},
  '/procurement/requisitions/my':{view:'supply',title:'我的请购',sub:'味美 · 精简视图'},
  '/procurement/suppliers':{view:'supply',title:'供应商',sub:'味美 · 精简视图'},
  '/sales/customers':{view:'supply',title:'客户管理',sub:'味美 · 精简视图'},
  '/procurement/price-lists':{view:'supply',title:'价格表',sub:'味美 · 精简视图'},
  '/sales/returns':{view:'supply',title:'销售退货',sub:'味美 · 精简视图'},
  '/transfer/list':{view:'supply',title:'调拨单',sub:'味美 · 精简视图'},
  '/warehouse/stocktake-query':{view:'supply',title:'盘点任务',sub:'味美 · 精简视图'},
  // C 线第五批
  '/warehouse/materials':{view:'supply',title:'收货任务',sub:'味美 · 精简视图'},
  '/warehouse/material-types':{view:'supply',title:'物料字典',sub:'味美 · 精简视图'}
}
export function routeInfo(path:string,title?:string,ws?:string):RouteInfo{if(ws==='ydwm'&&YDWM_ROUTES[path])return YDWM_ROUTES[path];const r=ROUTES[path];if(r&&(!r.ydwmOnly||ws==='ydwm')&&(!r.lsmOnly||ws!=='ydwm'))return r;return {view:'pending',title:title||'待整理页面',sub:'模块逐项评审'}}
