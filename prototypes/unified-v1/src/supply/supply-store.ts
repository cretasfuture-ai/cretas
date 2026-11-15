// 六膳门采购销售库存模块的共享状态：黑湖只读快照 + Cretas 本地补充保存区。
import {reactive} from 'vue'
import type {Inventory} from './supply-facts'

export interface SupplyRecord{id:string;kind:'stock'|'opening'|'lineBatch'|'lineCost';key:string;productId?:string;warehouseId?:string;lineId?:string;value:Record<string,unknown>;reason:string;by:string;at:string;revokedAt?:string;revokeReason?:string;replacedAt?:string}
export interface Zone{id:string;name:string;prefix:string;placed:boolean;x:number;y:number;w:number;h:number}
export interface Location{id:string;zoneId:string;code:string;name:string}
export interface Layout{zones:Zone[];locations:Location[];image:{dataUrl:string;name:string;at:string}|null;savedAt?:string}
export interface SupplyState{factoryId:string;session?:string|null;version:number;records:SupplyRecord[];layouts:Record<string,Layout>;audit:Record<string,unknown>[]}

export const KIND_LABEL:Record<string,string>={stock:'在库余额',opening:'期初库存',lineBatch:'批次号',lineCost:'单据金额'}
export const T=reactive({
  saleNos:new Set<string>() as Set<string>,poNos:new Set<string>() as Set<string>,
  inv:undefined as Inventory|undefined,
  supply:undefined as SupplyState|undefined,
  busy:false,error:'',supplyError:'',notice:''
})

export async function readInventory(){
  T.busy=true;T.error=''
  try{const r=await fetch('/api/inventory-source'),b=await r.json();if(!r.ok||!b.success||b.data.factoryId!=='LIUSHANMEN')throw Error(b.message||'库存来源读取失败');T.inv=b.data}
  catch(e){T.error=e instanceof Error?e.message:'读取失败'}
  finally{T.busy=false}
}
// 验收会话（W2、审计退回第 6 条）：页面或外壳地址带 acceptanceSession 时，补充与库位写入该会话自己的保存区
// 审计第二轮必修 1：会话可能在地址查询串里，也可能在 hash 里（frame 的 #/路由?acceptanceSession=…，外壳的 #lsm:/路由?acceptanceSession=…）。
// 四处都看：本页查询串 → 本页 hash → 外壳查询串 → 外壳 hash；与 B/D/E 一致，保证两种地址写法都落到同一会话。
function sessionIn(href:string):string{try{const u=new URL(href);const q=u.searchParams.get('acceptanceSession');if(q)return q;const h=u.hash;const i=h.indexOf('?');if(i>=0){const s=new URLSearchParams(h.slice(i+1)).get('acceptanceSession');if(s)return s}}catch{/* 地址无法解析时忽略 */}return ''}
// 第三批菜单复测：外壳菜单跳转会去掉地址里的 acceptanceSession。地址里有会话时记进本标签页 sessionStorage；
// 地址里没有时，先读本线记住的会话，再读外壳记住的验收工作区（ydwm-acceptance-workspace，同一标签页同源共享），只接受 test- 开头的会话名。
const SS_KEY='cretas-c-supply-session',okSession=(s:string|null)=>!!s&&/^test-[a-z0-9-]{8,64}$/.test(s)
export function supplySession():string{let s=sessionIn(location.href);if(!s){try{if(window.top&&window.top!==window)s=sessionIn(window.top.location.href)}catch{/* 跨源时不读外壳地址 */}}
  try{if(s){if(okSession(s))sessionStorage.setItem(SS_KEY,s);return s}const m=sessionStorage.getItem(SS_KEY);if(okSession(m))return m as string;const w=sessionStorage.getItem('ydwm-acceptance-workspace');if(okSession(w))return w as string}catch{/* 存储不可用时用地址结果 */}return s}
const sessionHeaders=():Record<string,string>=>{const s=supplySession();return s?{'x-acceptance-session':s}:{}}
export const supplyUrl=(path:string)=>{const s=supplySession();return '/__lsm/supply'+path+(s?(path.includes('?')?'&':'?')+'acceptanceSession='+encodeURIComponent(s):'')}
// 第三批③：黑湖新接口里的销售 / 采购单号，用于把出入库、领退料里的关联单号做成跳转
export async function readOrderNos(){try{const r=await fetch('/__lsm/supply/extras'),b=await r.json();if(!b.success)return;T.saleNos=new Set((b.data.resources.sale_order||[]).map((o:{orderNo:unknown})=>String(o.orderNo)));T.poNos=new Set((b.data.resources.purchase_order||[]).map((o:{orderCode:unknown})=>String(o.orderCode)))}catch{/* 读不到时不做跳转 */}}
export function orderLink(no:string):{path:string;no:string}|undefined{if(!no)return undefined;if(T.saleNos.has(no))return {path:'/sales/orders',no};if(T.poNos.has(no))return {path:'/procurement/orders',no};return undefined}
export async function readSupply(){
  T.supplyError=''
  try{
    const r=await fetch('/__lsm/supply/state',{headers:sessionHeaders()});let b:{success?:boolean;data?:SupplyState;message?:string}={}
    const NA='补充保存区尚未接入统一入口（等待 /__lsm/supply 路由），补充与库位暂不可保存'
    try{b=await r.json()}catch{throw Error(NA)}
    if((r.status===403||r.status===404)&&!b.data)throw Error(NA)
    if(!r.ok||!b.success||b.data?.factoryId!=='LIUSHANMEN')throw Error(b.message||'补充保存区读取失败')
    T.supply=b.data
  }catch(e){T.supply=undefined;T.supplyError=e instanceof Error?e.message:'补充保存区读取失败'}
}
export async function post<D>(path:string,body:unknown):Promise<D>{
  const r=await fetch('/__lsm/supply'+path,{method:'POST',headers:{'Content-Type':'application/json','x-prototype-action':'explicit',...sessionHeaders()},body:JSON.stringify(body)})
  let b:{success?:boolean;message?:string;data?:D}={}
  try{b=await r.json()}catch{throw Error('保存失败（'+r.status+'）')}
  if(!r.ok||!b.success)throw Error(b.message||'保存失败')
  return b.data as D
}
// 有效补充：未撤销、未被同 key 新记录替换
export function activeRecords(kind?:string){return (T.supply?.records||[]).filter(r=>!r.revokedAt&&!r.replacedAt&&(!kind||r.kind===kind))}
export function activeFor(kind:string,key:string){return activeRecords(kind).find(r=>r.key===key)}
