// F 线 · 数字班组配置：五位数字员工 = 一组现有能力 + 一组权限。
// 能力不写死岗位：每位员工只声明「管哪些权限模块」，能不能办由当前登录人的权限（permissions/current）决定：
// rw → 能查也能办（写操作先预览后确认）；r → 只能查；- → 不具备，卡片上写明缺什么权限。
export type CrewId='clerk'|'keeper'|'planner'|'inspector'|'accountant'
export type Perm='rw'|'r'|'-'
export interface Capability{label:string;kind:'query'|'write';module:string;feature:string;backend:'existing'|'prototype'|'gap';note:string}
export interface CrewDef{id:CrewId;name:string;short:string;duty:string;modules:string[];capabilities:Capability[];mobileRoles:string[]}

export const CREW:CrewDef[]=[
  {id:'clerk',name:'数字文员',short:'文员',duty:'销售、采购单据的查询与跟进；开单仍在黑湖，文员把要办的事整理成协同任务',modules:['sales','procurement'],mobileRoles:[],capabilities:[
    {label:'查销售订单、发运、客户',kind:'query',module:'sales',feature:'销售订单（黑湖只读）',backend:'existing',note:'C 线 LsmExtras / YdwmSales'},
    {label:'查采购订单、采购需求、供应商',kind:'query',module:'procurement',feature:'采购订单（黑湖只读）',backend:'existing',note:'C 线 LsmExtras'},
    {label:'把单据跟进事项转成协同任务',kind:'write',module:'sales',feature:'协同任务',backend:'existing',note:'E 线 /__unified/e/api/tasks/save'},
    {label:'在黑湖开销售单、采购单',kind:'write',module:'sales',feature:'—',backend:'gap',note:'销售、采购开单在黑湖，Cretas 只读'}]},
  {id:'keeper',name:'数字仓管',short:'仓管',duty:'库存、入库批次、临期与盘点；出入库单仍在黑湖',modules:['warehouse','inventory'],mobileRoles:['仓管'],capabilities:[
    {label:'查库存、进销存、临期批次',kind:'query',module:'warehouse',feature:'库存查询 / 进销存汇总 / 入库批次',backend:'existing',note:'C 线 supply-lsm、supply-batch'},
    {label:'建入库批次（Cretas 主来源）',kind:'write',module:'warehouse',feature:'入库批次',backend:'existing',note:'C 线第六批 supply-batch.cjs；本原型手机端只预览，未接'},
    {label:'库存调整单',kind:'write',module:'inventory',feature:'库存调整单',backend:'existing',note:'C 线 N5；本原型未接'},
    {label:'黑湖出入库开单、审批',kind:'write',module:'warehouse',feature:'—',backend:'gap',note:'出入库开单和审批在黑湖办理，Cretas 只读'}]},
  {id:'planner',name:'数字计划员',short:'计划员',duty:'工单进度、齐套与缺料、急单安排；工单在黑湖开，计划员出安排草案和影响说明',modules:['production','scheduling'],mobileRoles:['操作员'],capabilities:[
    {label:'查工单进度、报工、在制品',kind:'query',module:'production',feature:'生产工单 / 生产报工 / 在制品',backend:'existing',note:'A 线 ProductionList、WipMatrix'},
    {label:'急单核对：物料、历史产出、原有安排',kind:'query',module:'production',feature:'BOM + 库存 + 工单',backend:'existing',note:'读黑湖快照与库存快照，代码计算'},
    {label:'安排草案确认后交给车间（协同任务）',kind:'write',module:'production',feature:'协同任务',backend:'existing',note:'E 线 tasks/save'},
    {label:'产能核对',kind:'query',module:'scheduling',feature:'—',backend:'gap',note:'六膳门没有配置日产能；正式系统有产能配置和急单插单分析，原型还没接'},
    {label:'在黑湖开工单',kind:'write',module:'production',feature:'—',backend:'gap',note:'工单在黑湖开，Cretas 只读'}]},
  {id:'inspector',name:'数字质检员',short:'质检员',duty:'检验单、检验标准、不良分析；不合格转协同任务',modules:['quality'],mobileRoles:['质检员'],capabilities:[
    {label:'查检验单、不良分布',kind:'query',module:'quality',feature:'质量检验 / 不良分析',backend:'existing',note:'E 线'},
    {label:'记检验单（按标准判定）',kind:'write',module:'quality',feature:'质量检验',backend:'existing',note:'E 线 inspections/save；本原型手机端只预览，未接'},
    {label:'不合格转协同任务',kind:'write',module:'quality',feature:'协同任务',backend:'existing',note:'E 线 tasks/save'}]},
  {id:'accountant',name:'数字成本会计',short:'成本会计',duty:'出成率、工单成本、对账差异；发现费料生成核对待办',modules:['finance','analytics'],mobileRoles:[],capabilities:[
    {label:'按工单比出成率（黑湖报工良品）',kind:'query',module:'analytics',feature:'报工记录',backend:'existing',note:'读黑湖报工快照，代码计算'},
    {label:'查工单成本、成本依据',kind:'query',module:'analytics',feature:'成本分析',backend:'existing',note:'B 线 cost-lsm'},
    {label:'生成核对待办（协同任务）',kind:'write',module:'analytics',feature:'协同任务',backend:'existing',note:'E 线 tasks/save'},
    {label:'按工序算费料金额',kind:'query',module:'finance',feature:'—',backend:'gap',note:'材料成本按工单汇总，拆不到单道工序；部分工单黑湖材料成本为 0 或未返回'}]}
]
export const crewById=(id:string)=>CREW.find(c=>c.id===id)
export const MODULE_CN:Record<string,string>={sales:'销售',procurement:'采购',warehouse:'仓储',inventory:'库存',production:'生产',scheduling:'排产',quality:'质量',finance:'财务',analytics:'数据分析',report:'报表',dashboard:'首页',work_report:'报工'}
export const modCn=(m:string)=>MODULE_CN[m]||m

// 权限 → 能力等级。一位员工管多个模块时，取最高；全部是 '-' 才算不具备。
export function crewPerm(c:CrewDef,perms:Record<string,string>|null):Perm{
  if(!perms)return '-'
  const v=c.modules.map(m=>perms[m]||'-');return v.includes('rw')?'rw':v.includes('r')?'r':'-'
}
export function canWrite(module:string,perms:Record<string,string>|null){return !!perms&&perms[module]==='rw'}

// 演示身份：当前账号读真实权限；其余岗位是原型假设，只用于预览「按权限过滤」，不是账号真实权限（标注在页面上）。
export const DEMO_ROLES:Record<string,{label:string;perms:Record<string,string>|null;mobile?:CrewId}>={
  self:{label:'当前账号（真实权限）',perms:null},
  manager:{label:'厂长（演示：全部模块可办）',perms:{sales:'rw',procurement:'rw',warehouse:'rw',inventory:'rw',production:'rw',scheduling:'rw',quality:'rw',finance:'rw',analytics:'rw'}},
  planner:{label:'计划主管（演示）',perms:{production:'rw',scheduling:'rw',warehouse:'r',inventory:'r',analytics:'r'},mobile:'planner'},
  keeper:{label:'仓管（演示）',perms:{warehouse:'rw',inventory:'rw',production:'r'},mobile:'keeper'},
  inspector:{label:'质检员（演示）',perms:{quality:'rw',production:'r'},mobile:'inspector'},
  operator:{label:'操作员（演示）',perms:{production:'r',work_report:'rw'},mobile:'planner'},
  accountant:{label:'成本会计（演示）',perms:{finance:'rw',analytics:'rw',production:'r'}}
}

// 现有功能页 → 数字员工（覆盖统一入口菜单 62 页，2026-11-12 23:22 读 /__unified/menu.json）。
// owner=null 的是「暂不归任何人」，reason 说明为什么。菜单新增而本表没有的页，/crew/map 会实时列为「新页面，未归类」。
export interface PageMap{route:string;label:string;group:string;module:string;owner:CrewId|null;reason:string}
export const PAGE_MAP:PageMap[]=[
  {route:'/dashboard',label:'首页',group:'概览',module:'dashboard',owner:null,reason:'全厂入口；班组首页从这里进，首页本身不归某一位'},
  {route:'/workflow/pending',label:'待我处理',group:'个人 OA',module:'dashboard',owner:null,reason:'个人审批聚合；数字员工的待确认事项单独进「等我确认」，不混进 OA 审批'},
  {route:'/workflow/my-created',label:'我发起的',group:'个人 OA',module:'dashboard',owner:null,reason:'同上'},
  {route:'/workflow/acted',label:'已处理',group:'个人 OA',module:'dashboard',owner:null,reason:'同上'},
  {route:'/workflow/copied',label:'抄送我的',group:'个人 OA',module:'dashboard',owner:null,reason:'同上'},
  {route:'/collab/tasks',label:'协同任务',group:'个人 OA',module:'production',owner:null,reason:'公共通道：五位员工确认后的事都落成协同任务交给人，不归某一位'},
  {route:'/procurement/requisitions/my',label:'采购需求',group:'采购管理',module:'procurement',owner:'clerk',reason:''},
  {route:'/procurement/requisitions/pending-approval',label:'采购需求待审批',group:'采购管理',module:'procurement',owner:'clerk',reason:''},
  {route:'/procurement/orders',label:'采购订单',group:'采购管理',module:'procurement',owner:'clerk',reason:''},
  {route:'/procurement/ai-purchase',label:'采购建议',group:'采购管理',module:'procurement',owner:'planner',reason:'缺料 → 采购建议是计划员的齐套结果；下单仍由文员跟进'},
  {route:'/procurement/suppliers',label:'供应商',group:'采购管理',module:'procurement',owner:'clerk',reason:''},
  {route:'/procurement/price-lists',label:'价格表',group:'采购管理',module:'procurement',owner:'clerk',reason:''},
  {route:'/sales/orders',label:'销售订单',group:'销售管理',module:'sales',owner:'clerk',reason:''},
  {route:'/sales/shipments',label:'发运记录',group:'销售管理',module:'sales',owner:'clerk',reason:''},
  {route:'/sales/returns',label:'退货',group:'销售管理',module:'sales',owner:'clerk',reason:''},
  {route:'/lsm/receivables',label:'应收与收款',group:'销售管理',module:'finance',owner:'clerk',reason:'催收、开票跟进是单据事务；金额口径归成本会计只读'},
  {route:'/sales/customers',label:'客户管理',group:'销售管理',module:'sales',owner:'clerk',reason:''},
  {route:'/lsm/customer-prices',label:'客户价目表',group:'销售管理',module:'sales',owner:'clerk',reason:''},
  {route:'/production/plans',label:'生产计划 / 工单',group:'生产管理',module:'production',owner:'planner',reason:''},
  {route:'/production/material-flow',label:'领料',group:'生产管理',module:'production',owner:'planner',reason:'领退料在「计划执行」里办，仓库只读回读'},
  {route:'/production/batches',label:'生产批次',group:'生产管理',module:'production',owner:'planner',reason:''},
  {route:'/production/approval',label:'生产报工',group:'生产管理',module:'production',owner:'planner',reason:'一线报工从手机端进数字计划员'},
  {route:'/production/weigh',label:'称重报工',group:'生产管理',module:'production',owner:'planner',reason:'称重结果同时供成本会计算出成'},
  {route:'/production/analysis-query',label:'生产查询',group:'生产管理',module:'production',owner:'planner',reason:''},
  {route:'/production/shortage-cases',label:'缺料事件',group:'生产管理',module:'production',owner:'planner',reason:''},
  {route:'/production/kanban',label:'状态看板',group:'生产管理',module:'production',owner:'planner',reason:''},
  {route:'/dashboard/production-progress',label:'车间大屏',group:'生产管理',module:'production',owner:null,reason:'展示屏，没有可办的事'},
  {route:'/production/wip',label:'在制品与效率',group:'生产管理',module:'production',owner:'planner',reason:''},
  {route:'/production/process-hub',label:'工序配置',group:'生产管理',module:'production',owner:'planner',reason:'入职时由班组整理工序草稿，最终在这里由人保存'},
  {route:'/production/yield-standard',label:'出成标准',group:'生产管理',module:'production',owner:'accountant',reason:'出成标准在「工序与标准」里维护；成本会计拿它比实际出成，只读'},
  {route:'/system/products',label:'SKU 管理',group:'生产管理',module:'production',owner:'planner',reason:''},
  {route:'/lsm/outsource',label:'委外收发',group:'生产管理',module:'production',owner:'planner',reason:''},
  {route:'/warehouse/materials',label:'入库任务与批次',group:'仓储管理',module:'warehouse',owner:'keeper',reason:''},
  {route:'/warehouse/unordered-inbound-applications',label:'无单申请',group:'仓储管理',module:'warehouse',owner:'keeper',reason:''},
  {route:'/warehouse/shipments',label:'出库执行',group:'仓储管理',module:'warehouse',owner:'keeper',reason:''},
  {route:'/transfer/list',label:'调拨单',group:'仓储管理',module:'inventory',owner:'keeper',reason:''},
  {route:'/warehouse/inventory-query',label:'库存查询',group:'仓储管理',module:'warehouse',owner:'keeper',reason:''},
  {route:'/lsm/stock-summary',label:'进销存汇总',group:'仓储管理',module:'warehouse',owner:'keeper',reason:''},
  {route:'/warehouse/inventory-adjustments',label:'库存调整单',group:'仓储管理',module:'inventory',owner:'keeper',reason:''},
  {route:'/lsm/inbound-batches',label:'入库批次',group:'仓储管理',module:'warehouse',owner:'keeper',reason:''},
  {route:'/warehouse/stocktake-query',label:'盘点任务',group:'仓储管理',module:'warehouse',owner:'keeper',reason:''},
  {route:'/warehouse/storage-locations',label:'仓位与平面图',group:'仓储管理',module:'warehouse',owner:'keeper',reason:''},
  {route:'/warehouse/material-types',label:'原料字典',group:'仓储管理',module:'warehouse',owner:'keeper',reason:''},
  {route:'/warehouse/material-segments',label:'物料分类',group:'仓储管理',module:'warehouse',owner:'keeper',reason:''},
  {route:'/quality/inspections',label:'质量检验',group:'质量管理',module:'quality',owner:'inspector',reason:''},
  {route:'/quality/standards',label:'检验标准',group:'质量管理',module:'quality',owner:'inspector',reason:''},
  {route:'/quality/defects',label:'不良分析',group:'质量管理',module:'quality',owner:'inspector',reason:''},
  {route:'/quality/label-qc',label:'包装标签拍检',group:'质量管理',module:'quality',owner:'inspector',reason:''},
  {route:'/smart-bi/dashboard',label:'经营驾驶舱',group:'数据与分析',module:'analytics',owner:null,reason:'老板看全局的展示页；数字员工的结论汇到班组首页，不往驾驶舱里塞'},
  {route:'/analytics/overview',label:'领域概览',group:'数据与分析',module:'analytics',owner:null,reason:'同上'},
  {route:'/smart-bi/analysis-hub',label:'经营主题（财务口径）',group:'数据与分析',module:'finance',owner:'accountant',reason:''},
  {route:'/analytics/supply-chain',label:'进销存分析',group:'数据与分析',module:'analytics',owner:'keeper',reason:''},
  {route:'/warehouse/material-price-trend',label:'物料均价',group:'数据与分析',module:'warehouse',owner:'accountant',reason:'口径在库存侧，解读归成本会计'},
  {route:'/lsm/cost',label:'成本分析',group:'数据与分析',module:'analytics',owner:'accountant',reason:''},
  {route:'/lsm/reconcile',label:'对账（Cretas ↔ 黑湖）',group:'数据与分析',module:'analytics',owner:'accountant',reason:'差异按「找谁」分给仓管、计划员核；汇总归成本会计'},
  {route:'/analytics/alert-dashboard',label:'异常预警',group:'数据与分析',module:'analytics',owner:null,reason:'预警并入「发现的问题」（规则预警一类），按事项分给对应员工，页面本身不归某一位'},
  {route:'/smart-bi/analysis',label:'数据问答',group:'数据与分析',module:'analytics',owner:null,reason:'通用问数入口，不归某一位'},
  {route:'/smart-bi/upload',label:'文件导入',group:'数据与分析',module:'analytics',owner:null,reason:'数据维护工具，不是业务事项'},
  {route:'/smart-bi/mapping-review',label:'映射复核',group:'数据与分析',module:'analytics',owner:null,reason:'同上'},
  {route:'/smart-bi/data-completeness',label:'数据检查',group:'数据与分析',module:'analytics',owner:null,reason:'同上；入职时的「还缺什么资料」引用它的结论'},
  {route:'/smart-bi/query-templates',label:'查询模板',group:'数据与分析',module:'analytics',owner:null,reason:'同上'},
  {route:'/unified/reports',label:'自定义报表',group:'数据与分析',module:'report',owner:null,reason:'通用报表工具，不归某一位'}
]
