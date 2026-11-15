<script setup lang="ts">
// 六膳门工作区 · 采购销售库存模块入口（规格 C-supply.md 第 24–27、31、32 节）。
// 黑湖来源只读；Cretas 本地补充与库位/平面图只写 unified-v1/private/lsm-supply-workspace.json（经 /__lsm/supply）。
// 出入库开单、审批、盘点、库存调整仍在黑湖（DECISIONS W4），本模块不提供这些写入口。
import {computed,onMounted,ref,watch} from 'vue'
import type {Source,Row,MenuEntry} from '../frame/types'
import {T,readInventory,readSupply,supplySession,readOrderNos} from './supply-store'
import {fmtTime} from './supply-facts'
import StockPage from './StockPage.vue'
import DocsPage from './DocsPage.vue'
import FlowPage from './FlowPage.vue'
import WarehousePage from './WarehousePage.vue'
import YdwmSales from './YdwmSales.vue'
import YdwmStock from './YdwmStock.vue'
import YdwmAdjust from './YdwmAdjust.vue'
import YdwmPurchase from './YdwmPurchase.vue'
import YdwmInventory from './YdwmInventory.vue'
import YdwmList from './YdwmList.vue'
import BatchPage from './BatchPage.vue'
import LsmExtras from './LsmExtras.vue'
import LsmGapPage from './LsmGapPage.vue'
import {S} from '../frame/store'
import {openImport} from './supplement'
const props=defineProps<{source:Source;entry:MenuEntry}>(),emit=defineEmits<{open:[e:MenuEntry];inspect:[kind:string,row:Row]}>()
type Kind='batches'|'stock'|'summary'|'in'|'out'|'flow'|'warehouse'|'adjust'|'ysales'|'ystock'|'yadjust'|'ypurchase'|'yinv'|'ylist'|'lextra'|'lgap'
// 味美工作区（DECISIONS A1/A2）：外壳以 ws=ydwm 打开 frame 时，渲染 C 线的味美新视图
const ydwm=computed(()=>S.query.get('ws')==='ydwm')
// 开发直达：D 登记路由前，可用 ?ws=ydwm&yview=sales|stock|adjust 在任一 supply 路由下打开味美视图
const YVIEW:Record<string,Kind>={sales:'ysales',stock:'ystock',adjust:'yadjust',purchase:'ypurchase',inventory:'yinv',requisitions:'ylist',suppliers:'ylist',customers:'ylist',pricelists:'ylist',returns:'ylist',transfers:'ylist',stocktakes:'ylist',receiving:'ylist',materials:'ylist'}
// 第二批①：资料与单据精简视图（YdwmList 按页面配置渲染）
const LIST_PATHS:Record<string,string>={'/procurement/requisitions/my':'requisitions','/procurement/requisitions/pending-approval':'requisitions','/procurement/suppliers':'suppliers','/sales/customers':'customers','/procurement/price-lists':'pricelists','/sales/returns':'returns','/transfer/list':'transfers','/warehouse/stocktake-query':'stocktakes','/warehouse/materials':'receiving','/warehouse/material-types':'materials'}
// 第三批①②：六膳门黑湖新接口只读页（LsmExtras 按页面配置渲染）；开发直达 ?xview=
const LSM_EXTRA:Record<string,string>={'/sales/orders':'sales','/procurement/orders':'purchase','/procurement/requisitions/my':'requirement','/sales/customers':'customers','/procurement/suppliers':'vendors','/lsm/customer-prices':'prices','/lsm/outsource':'outsource','/lsm/receivables':'finance'}
// 第八批（R41）：六膳门占位迁入与缺口页（LsmGapPage 按页面配置渲染）；开发直达 ?gapview=
const GAP_PATHS:Record<string,string>={'/transfer/list':'transfers','/sales/shipments':'shipments','/sales/returns':'returns','/warehouse/unordered-inbound-applications':'unordered','/warehouse/stocktake-query':'stocktake','/warehouse/material-types':'mtypes','/warehouse/material-segments':'msegments','/warehouse/material-price-trend':'pricetrend','/procurement/requisitions/pending-approval':'pending','/procurement/ai-purchase':'aipurchase','/procurement/price-lists':'pricelists','/warehouse/wastage-reports':'wastage','/warehouse/source-individuals':'source','/sales/vehicles':'vehicles','/sales/payment-requests':'payreq'}
const gapPage=computed(()=>S.query.get('gapview')||GAP_PATHS[props.entry.path]||'')
const GAP_TITLE:Record<string,string>={transfers:'调拨单',shipments:'出货记录',returns:'销售退货',unordered:'无订单入库申请',stocktake:'盘点',mtypes:'原料类型字典',msegments:'物料分类字典',pricetrend:'物料均价趋势',pending:'待审批请购',aipurchase:'AI受控采购',pricelists:'价格表',wastage:'报损管理',source:'源头个体档案',vehicles:'车辆字典',payreq:'销售付款申请'}
const EXTRA_TITLE:Record<string,string>={sales:'销售订单',purchase:'采购订单',requirement:'采购需求',customers:'客户',vendors:'供应商',prices:'客户价目表',outsource:'委外收发',finance:'应收与收款'}
const extraPage=computed(()=>S.query.get('xview')||LSM_EXTRA[props.entry.path]||'sales')
const LIST_TITLE:Record<string,string>={requisitions:'请购单',suppliers:'供应商',customers:'客户',pricelists:'价格表',returns:'销售退货',transfers:'调拨单',stocktakes:'盘点任务',receiving:'收货任务',materials:'物料字典'}
const listPage=computed(()=>{const y=S.query.get('yview')||'';return YVIEW[y]==='ylist'?y:LIST_PATHS[props.entry.path]||'requisitions'})
// 第六批：开发直达 ?cview=batches（D 登记 /lsm/inbound-batches 之前用）
const kind=computed<Kind>(()=>!ydwm.value&&gapPage.value?'lgap':!ydwm.value&&S.query.get('cview')==='batches'?'batches':!ydwm.value&&(S.query.get('xview')||LSM_EXTRA[props.entry.path])?'lextra':ydwm.value?(YVIEW[S.query.get('yview')||'']||({'/sales/orders':'ysales','/warehouse/inventory-adjustments':'yadjust','/lsm/stock-summary':'ystock','/warehouse/stock-summary':'ystock','/procurement/orders':'ypurchase','/warehouse/inventory-query':'yinv',...Object.fromEntries(Object.keys(LIST_PATHS).map(k=>[k,'ylist']))} as Record<string,Kind>)[props.entry.path]||'ystock'):({'/warehouse/inventory-query':'stock','/lsm/stock-summary':'summary','/warehouse/materials':'in','/warehouse/shipments':'out','/production/material-flow':'flow','/warehouse/storage-locations':'warehouse','/warehouse/inventory-adjustments':'adjust','/lsm/inbound-batches':'batches'} as Record<string,Kind>)[props.entry.path]||'stock')
const META:Record<Kind,{title:string;sub:string;boundary:string}>={
  batches:{title:'入库批次',sub:'Cretas 主来源 · 黑湖入库单只读对照',boundary:'批次、生产日期、保质期和到期日、储存温度由 Cretas 录入为准；黑湖入库单只读关联和对账，不回写黑湖。'},
  stock:{title:'库存查询',sub:'黑湖只读来源 · Cretas 补充',boundary:'在库数量不等于可用库存；黑湖未返回占用、待检和冻结量，不推算。零库存与负库存分开标出，负库存给核对方向，不直接判缺料。'},
  summary:{title:'进销存汇总',sub:'黑湖只读来源 · Cretas 补充',boundary:'期初只取快照或 Cretas 补充，不倒推；入/出取期间内黑湖单据。'},
  in:{title:'入库单据',sub:'黑湖已审批单据 · 只读',boundary:'入库单不等于采购订单；开单与审批仍在黑湖，Cretas 只读展示。'},
  out:{title:'出库单据',sub:'黑湖已审批单据 · 只读',boundary:'销售出库不等于销售订单；领料、调拨出库按场景分开。开单与审批仍在黑湖。'},
  flow:{title:'生产领退料',sub:'黑湖已审批单据 · 只读',boundary:'只有“已发”（生产领料出库）和“退回”（生产退料入库）来自黑湖；需求与实耗黑湖未提供，不用 BOM 推算。领料与退料的办理仍在黑湖，这里只读核对。'},
  warehouse:{title:'仓库档案与布局',sub:'黑湖仓库档案 · Cretas 本地布局',boundary:'仓库档案来自黑湖；区域、库位、平面图在 Cretas 本地配置，只存本机原型，不回写黑湖。'},
  ysales:{title:'销售订单',sub:'味美 · 精简视图（只读，处理回原页面）',boundary:'状态压成未完成 / 进行中 / 已完成 / 已取消，原状态在悬停和详情里；订购、已发、待发按单位分开；取消单不进合计。'},
  ystock:{title:'进销存汇总',sub:'味美 · 库存分析',boundary:'期初没有快照不倒推；味美没有按日期的出库流水，出库只给累计；库龄按批次入库日与保质期分档；补货建议逐项列出加减项，缺项不按 0 计。'},
  yadjust:{title:'库存调整单',sub:'味美 · 本机练习会话',boundary:'单条成品批次小幅调整：原因必选、预览、确认、回读。大于 50% 的调整建议走盘点；原料批次请走盘点。'},
  ypurchase:{title:'采购订单',sub:'味美 · 精简视图（只读，处理回原页面）',boundary:'订购、已收、待收按单位分开；取消单不计待收；收货明细未返回时写“未返回”，不按 0 计。采购交货日期作为正式列。'},
  lgap:{title:'六膳门',sub:'黑湖只读 · Cretas 补充',boundary:'黑湖有数据的页只读展示；黑湖没有的页写明缺口，核心页留 Cretas 补充入口。不回写黑湖，不造数据。'},
  lextra:{title:'黑湖业务',sub:'黑湖新接口 · 只读',boundary:'黑湖新接口数据只读展示：开单、审批、收付款仍在黑湖办理，Cretas 不回写黑湖。'},
  ylist:{title:'精简视图',sub:'味美 · 精简视图（只读，处理回原页面）',boundary:'只读列表：新建、编辑、提交、审批等写操作回原页面办理；缺字段写“未填写/未记录”，不补 0。'},
  yinv:{title:'库存查询',sub:'味美 · 精简视图（只读）',boundary:'在库与可用分列；零库存、负库存分开标出；不同单位不合计；标明读取时点。'},
  adjust:{title:'库存调整单',sub:'六膳门 · 只读说明',boundary:'六膳门的库存调整、盘点在黑湖中办理（DECISIONS W4），Cretas 不提供调整写入，避免与黑湖账面冲突。'}
}
const meta=computed(()=>META[kind.value])
const page=ref<{exportCurrent:()=>void;showRecords?:()=>void}>()
const supplyKinds:Kind[]=['stock','summary','in','out']
async function reload(){if(ydwm.value)return;void readOrderNos();await Promise.all([readInventory(),readSupply()])}
function openStock(wid:string){try{const k='unified-lsm-supply-stock-v2',v=JSON.parse(localStorage.getItem(k)||'{}');localStorage.setItem(k,JSON.stringify({...v,tab:'stock',wh:wid,state:''}))}catch{/* 偏好写入失败时仍可手动筛选 */}emit('open',{path:'/warehouse/inventory-query',title:'库存查询',group:'仓储管理'})}
onMounted(reload);watch(()=>props.entry.path,()=>{T.notice=''})
// R6：精简视图 → 原页面；记回“原页面”偏好（键与 warehouse-background.js 的 compactToggle 相同）
const LIST_NATIVE:Record<string,string>={requisitions:'/procurement/requisitions/my',suppliers:'/procurement/suppliers',customers:'/sales/customers',pricelists:'/procurement/price-lists',returns:'/sales/returns',transfers:'/transfer/list',stocktakes:'/warehouse/stocktake-query',receiving:'/warehouse/materials',materials:'/warehouse/material-types'}
// R22 补（与 A 一致）：「默认用原页面」是明确的选择，写入按账号记住的偏好 native，再打开原页面；「打开原页面办理」仍是一次性的，不写偏好
function nativePath(){return kind.value==='ypurchase'?'/procurement/orders':kind.value==='ylist'?LIST_NATIVE[listPage.value]:'/warehouse/inventory-query'}
function preferNative(){let acc='ydwm_admin';try{acc=(window.top as unknown as {CretasWorkspace?:{account?:string}}).CretasWorkspace?.account||acc}catch{/* 跨源用默认 */}try{localStorage.setItem('cretas-c-view-pref:ydwm:'+acc+':'+nativePath(),'native')}catch{/* 记不住时只打开这一次 */}openNative()}
function openNative(){const p=kind.value==='ypurchase'?'/procurement/orders':kind.value==='ylist'?LIST_NATIVE[listPage.value]:'/warehouse/inventory-query';/* 审计退回 4（R21）：「打开原页面办理」是一次性动作，带 view=native 打开原页面，不改记住的选择（与 A 线一致） */const q=new URLSearchParams({view:'native'});const s=supplySession();if(s)q.set('acceptanceSession',s);const u=p+'?'+q;try{window.top!.location.href=u}catch{location.href=u}}
const products=computed(()=>props.source.resources.product||[]),projects=computed(()=>props.source.resources.project||[])
</script>
<template><div class="supply-page" :data-kind="kind">
<div class="page-heading"><div><p>{{ydwm?'':'六膳门 · '}}{{meta.sub}}</p><h1>{{kind==='lgap'?(GAP_PATHS[entry.path]===gapPage&&entry.title?entry.title:GAP_TITLE[gapPage]):kind==='lextra'?EXTRA_TITLE[extraPage]:kind==='ylist'?LIST_TITLE[listPage]:ydwm||entry.path==='/warehouse/inventory-query'||entry.path==='/warehouse/storage-locations'?meta.title:entry.title||meta.title}}</h1></div>
<div class="actions"><button v-if="kind==='ypurchase'||kind==='yinv'||kind==='ylist'" class="ui-button" title="只这次打开原页面办理新建、收货、盘点等写操作，不改变默认视图" @click="openNative">打开原页面办理</button><button v-if="kind==='ypurchase'||kind==='yinv'||kind==='ylist'" type="button" class="ui-button c-pref" title="以后打开这里默认进原页面（按账号记住）；想再用精简视图，在原页面页头点「精简视图」" @click="preferNative">默认用原页面</button><button v-if="!ydwm" class="ui-button" :disabled="T.busy" @click="reload">{{T.busy?'正在读取…':'重新读取'}}</button><button v-if="supplyKinds.includes(kind)" class="ui-button" :disabled="!T.supply" :title="T.supply?'下载模板、上传、预检、确认':T.supplyError" @click="openImport(page?.showRecords)">批量补充</button><button v-if="!['warehouse','adjust','yadjust'].includes(kind)" class="ui-button" :disabled="!ydwm&&!T.inv" @click="page?.exportCurrent()">导出筛选</button><span class="help" tabindex="0" title="重新读取：读取本机已保存的黑湖只读快照和 Cretas 补充保存区，不触发黑湖采集。导出筛选：导出当前筛选结果（含来源标记）。批量补充：只补黑湖没有返回的数据，黑湖有值时以黑湖为准。">?</span></div></div>
<div v-if="!ydwm" class="source-strip"><span class="live-dot"/>黑湖只读来源 <span>库存／单据快照 {{fmtTime(T.inv?.materialAt)}}</span><span>采集核验 {{fmtTime(T.inv?.checkedAt)}}</span><span v-if="kind==='batches'">入库批次 Cretas 主来源 · {{T.supply?.session?'验收会话 '+T.supply.session:'按当前会话保存'}}</span><span v-else-if="T.supply">Cretas 补充 {{T.supply.records.filter(r=>!r.revokedAt&&!r.replacedAt).length}} 条生效 · {{T.supply.session?'验收会话 '+T.supply.session:'默认保存区'}}</span></div>
<p class="supply-boundary">{{meta.boundary}}</p>
<p v-if="T.error" role="alert" class="error">{{T.error}}</p>
<p v-if="T.notice" role="status" class="notice">{{T.notice}} <button aria-label="关闭提示" @click="T.notice=''">×</button></p>
<YdwmSales v-if="kind==='ysales'" ref="page"/>
<YdwmStock v-else-if="kind==='ystock'" ref="page"/>
<YdwmAdjust v-else-if="kind==='yadjust'" ref="page"/>
<YdwmPurchase v-else-if="kind==='ypurchase'" ref="page"/>
<YdwmInventory v-else-if="kind==='yinv'" ref="page"/>
<LsmGapPage v-else-if="kind==='lgap'" ref="page" :key="gapPage" :page="gapPage" :products="products"/>
<LsmExtras v-else-if="kind==='lextra'" ref="page" :key="extraPage" :page="extraPage"/>
<YdwmList v-else-if="kind==='ylist'" ref="page" :key="listPage" :page="listPage"/>
<template v-else-if="T.inv">
<StockPage v-if="kind==='stock'||kind==='summary'" ref="page" :key="kind" :products="products" :initial-tab="kind==='summary'?'summary':undefined"/>
<DocsPage v-else-if="kind==='in'||kind==='out'" ref="page" :key="kind" :dir="kind" :default-cat="kind==='out'?'out-sales':undefined"/>
<FlowPage v-else-if="kind==='flow'" ref="page" :projects="projects" @inspect="(k,r)=>emit('inspect',k,r)"/>
<BatchPage v-else-if="kind==='batches'"/>
<WarehousePage v-else-if="kind==='warehouse'" @open-stock="openStock"/>
<section v-else class="card"><div class="review-content"><p>六膳门的库存调整在黑湖中办理。需要核对某条余额时，到“库存查询”查看黑湖返回值与负库存核对方向；黑湖没有返回的余额，可以在“未返回余额”里补充（只存本机原型）。</p><button class="ui-button" @click="emit('open',{path:'/warehouse/inventory-query',title:'库存查询',group:'仓储管理'})">去库存查询</button></div></section>
</template>
<p v-else-if="T.busy" class="empty">正在读取六膳门库存来源…</p>
</div></template>
<style>
.supply-page .supply-boundary,.supply-page .supply-note{padding:8px 12px;font-size:var(--cs-type-caption,12px);color:var(--cs-muted);background:var(--cs-surface-subtle);border-radius:var(--cs-radius-sm,6px);margin:0 0 12px}
.supply-page .supply-boundary{color:#80652f;background:var(--cs-warning-soft,#fff8e9)}
.supply-page .supply-tabs small{margin-left:4px;color:var(--cs-muted);font-variant-numeric:tabular-nums}
.supply-page .supply-table td,.supply-page .supply-table th{height:32px;padding:4px 8px;line-height:20px;white-space:nowrap}
.supply-page .supply-table td small{display:block;font-size:11px;color:var(--cs-muted);line-height:14px}
.supply-page .supply-table .num{text-align:right;font-variant-numeric:tabular-nums}
.supply-page .supply-table tr[data-state=negative] td.num{color:var(--cs-danger)}
.supply-page .supply-table tr[data-state=zero] td.num{color:var(--cs-muted)}
.supply-page .supply-badge{display:inline-block;margin-left:4px;padding:0 6px;border-radius:4px;font-size:11px;line-height:18px;background:var(--cs-surface-subtle);color:var(--cs-muted)}
.supply-page .supply-badge[data-tone=negative],.supply-page .supply-badge[data-tone=error]{background:var(--cs-danger-soft);color:var(--cs-danger)}
.supply-page .supply-badge[data-tone=zero]{background:var(--cs-warning-soft);color:#80652f}
.supply-page .supply-badge[data-tone=ok]{background:var(--cs-success-soft);color:var(--cs-success)}
.supply-page .src-tag{display:inline-block;margin-left:4px;padding:0 5px;border:1px solid var(--cs-border);border-radius:4px;font-size:10px;line-height:16px;color:var(--cs-muted)}
.supply-page .src-tag[data-tone=supply]{border-color:var(--cs-primary);color:var(--cs-primary);background:var(--cs-primary-soft)}
.supply-page .src-tag[data-tone=muted]{text-decoration:line-through}
.supply-page .muted{color:var(--cs-muted)}
.supply-page .detail-pad{margin:8px 14px}
.supply-page .view-tabs-host{padding:8px 14px 0}
.supply-page .supply-table td.clip{max-width:160px;overflow:hidden;text-overflow:ellipsis}
.supply-page .supply-table tr.total-row td{background:var(--cs-surface-subtle);font-size:12px}
.supply-page .ops{display:flex;gap:4px}
.supply-page .filters .check{display:inline-flex;align-items:center;gap:4px;font-size:12px}
.supply-page .filters .check input{width:14px;height:14px;min-width:0;flex:none;margin:0}
.supply-page .seg{display:inline-flex;margin-left:auto;border:1px solid var(--cs-border);border-radius:6px;overflow:hidden}
.supply-page .seg button{border:0;background:var(--cs-surface);padding:4px 10px;font-size:12px}
.supply-page .seg button[aria-pressed=true]{background:var(--cs-primary);color:#fff}
.supply-page .layout-head{display:flex;align-items:center;gap:10px;margin-bottom:10px}
.supply-page .layout-head h2{font-size:var(--cs-type-section,16px);margin:0}
.supply-page .spacer{flex:1}
.supply-page .layout-grid{display:grid;grid-template-columns:minmax(280px,360px) minmax(0,1fr);gap:14px}
.supply-page .layout-side h3,.supply-page .layout-map h3{font-size:13px;margin:12px 14px 6px}
.supply-page .layout-list{list-style:none;margin:0;padding:0 14px 10px}
.supply-page .layout-list li{display:flex;align-items:center;gap:6px;min-height:32px;border-bottom:1px solid var(--cs-border);font-size:12px}
.supply-page .layout-list small{color:var(--cs-muted)}
.supply-page .layout-canvas{position:relative;margin:0 14px;user-select:none;touch-action:none;border:1px solid var(--cs-border);border-radius:6px;overflow:hidden}
.supply-page .layout-canvas img{display:block;width:100%;height:auto;pointer-events:none}
.supply-page .layout-zone{position:absolute;border:2px solid var(--cs-primary);background:color-mix(in srgb,var(--cs-primary) 18%,transparent);border-radius:4px;cursor:move;padding:2px 4px;font-size:11px;color:var(--cs-text);overflow:hidden}
.supply-page .layout-zone:focus-visible{outline:2px solid var(--cs-warning);outline-offset:1px}
.supply-page .layout-zone small{display:block;color:var(--cs-muted)}
.supply-page .layout-resize{position:absolute;right:0;bottom:0;width:12px;height:12px;cursor:nwse-resize;background:var(--cs-primary)}
.supply-form{display:grid;gap:10px;font-size:13px}
.supply-form label{display:grid;gap:4px}
.supply-form input,.supply-form textarea,.supply-form select{border:1px solid var(--cs-border);border-radius:6px;padding:6px 8px;font:inherit}
.supply-form .supply-note{padding:8px 12px;font-size:12px;color:var(--cs-muted);background:var(--cs-surface-subtle);border-radius:6px;margin:0}
.supply-form .supply-actions{display:flex;justify-content:flex-end;gap:8px}
.supply-form .supply-preview{display:grid;grid-template-columns:90px 1fr;gap:6px 10px;margin:0}
.supply-form .supply-preview dd{margin:0}
.supply-form .supply-badge{display:inline-block;padding:0 6px;border-radius:4px;font-size:11px;background:var(--cs-surface-subtle)}
.supply-form .supply-badge[data-tone=ok]{background:var(--cs-success-soft);color:var(--cs-success)}
.supply-form .supply-badge[data-tone=error]{background:var(--cs-danger-soft);color:var(--cs-danger)}
.supply-form .supply-badge[data-tone=ignored]{background:var(--cs-warning-soft);color:#80652f}
.supply-page .seg-progress{display:inline-flex;gap:2px}
.supply-page .seg-progress i{font-style:normal;font-size:11px;line-height:18px;padding:0 6px;border-radius:3px;background:var(--cs-surface-subtle);color:var(--cs-muted)}
.supply-page .seg-progress i[data-on]{background:var(--cs-success-soft);color:var(--cs-success)}
.supply-page .seg-progress i[data-part]{background:var(--cs-warning-soft);color:#80652f}
.supply-page .supply-badge[data-tone=info]{background:var(--cs-primary-soft);color:var(--cs-primary)}
.supply-page .board{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;padding:0 14px 14px}
.supply-page .board-col h3{font-size:13px;margin:8px 0}
.supply-page .board-card{display:block;width:100%;text-align:left;border:1px solid var(--cs-border);border-radius:6px;background:var(--cs-surface);padding:8px;margin-bottom:6px}
.supply-page .board-card small{display:block;color:var(--cs-muted);font-size:11px}
.supply-page .detail-card{margin-top:14px;padding-bottom:12px}
.supply-page .detail-card h3{font-size:13px;margin:12px 14px 6px}
.supply-page .detail-card>p,.supply-page .detail-card>table{margin:0 14px}
.supply-page .kv{display:grid;grid-template-columns:90px 1fr;gap:6px 10px;margin:8px 14px;font-size:13px}
.supply-page .kv dd{margin:0}
.supply-page .stages{margin:0 14px;padding-left:18px;font-size:13px}
.supply-page .stages li[data-on]{color:var(--cs-success)}
.supply-page .adjust-form{display:grid;gap:10px;padding:14px}
.supply-page .adjust-form label{display:grid;gap:4px;font-size:13px}
.supply-page .supply-actions{display:flex;justify-content:flex-end;gap:8px}
.supply-page .ui-button[data-variant=primary]{background:var(--cs-primary);color:#fff;border-color:var(--cs-primary)}
@media(max-width:1100px){.supply-page .layout-grid{grid-template-columns:1fr}.supply-page .board{grid-template-columns:1fr 1fr}}
</style>
