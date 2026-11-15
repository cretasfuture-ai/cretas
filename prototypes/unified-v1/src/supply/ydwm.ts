// 味美工作区新视图的数据层（DECISIONS A1）：经 19063 代理读写味美 API，会话跟随当前地址。
// 注意：frame 的 fetch 包装会把以 /api/ 开头的相对地址改去六膳门，所以这里一律用绝对地址。
import type {Row} from '../frame/types'
import {supplySession} from './supply-store'
// 审计第三轮：会话跟随当前地址（本页或外壳的 acceptanceSession），不写死。
// 没有会话时不发会话头，由统一入口按原页面同样的规则落到默认味美工作区，精简视图与原页面看到的是同一份数据。
// 与原页面 enhance.js 同一规则：地址里没有会话时，用本标签页 sessionStorage 记住的味美验收工作区（ydwm-acceptance-workspace），
// 这样精简视图和原页面在同一个标签页里看到的永远是同一份数据。
export const ydSession=():string=>{const s=supplySession();if(s)return s;try{const w=sessionStorage.getItem('ydwm-acceptance-workspace');if(w&&/^test-[a-z0-9-]{8,64}$/.test(w))return w}catch{/* 存储不可用时用默认工作区 */}return ''}
export const YD_SESSION_LABEL=():string=>{const s=ydSession();return s?'验收会话 '+s:'与原页面同一工作区'}
/** 给跳回原页面的链接带上当前会话（有才带） */
export function withSession(q:URLSearchParams){const s=ydSession();if(s)q.set('acceptanceSession',s);return q}
const BASE=()=>location.origin+'/api/mobile/FOOD_0000_001/'
const H=(write=false):Record<string,string>=>({...(ydSession()?{'x-prototype-test-session':ydSession()}:{}),...(write?{'Content-Type':'application/json','x-prototype-action':'explicit','x-prototype-user-action':'local-edit'}:{})})
export async function yget<T=Row[]>(path:string):Promise<T>{
  const r=await fetch(BASE()+path,{headers:H()});let b:{success?:boolean;code?:number;message?:string;data?:unknown}={}
  try{b=await r.json()}catch{throw Error('味美接口读取失败（'+r.status+'）')}
  if(!r.ok||(b.success===false))throw Error(b.message||'味美接口读取失败')
  const d=b.data as {content?:unknown;records?:unknown;list?:unknown}|unknown
  const list=(d&&typeof d==='object'&&!Array.isArray(d))?((d as {content?:unknown}).content??(d as {records?:unknown}).records??(d as {list?:unknown}).list??d):d
  return list as T
}
export async function ypost<T=Row>(path:string,body:unknown):Promise<T>{
  const r=await fetch(BASE()+path,{method:'POST',headers:H(true),body:JSON.stringify(body)});let b:{success?:boolean;message?:string;data?:T}={}
  try{b=await r.json()}catch{throw Error('味美接口保存失败（'+r.status+'）')}
  if(!r.ok||b.success===false)throw Error(b.message||'味美接口保存失败')
  return b.data as T
}
export const num=(v:unknown)=>typeof v==='number'&&Number.isFinite(v)?v:typeof v==='string'&&/^-?\d+(?:\.\d+)?$/.test(v)?Number(v):undefined
export const dateOnly=(v:unknown)=>v?String(v).slice(0,10):''
export const today=()=>new Date(Date.now()+8*3600e3).toISOString().slice(0,10)
export const days=(from:string,to=today())=>from?Math.floor((Date.parse(to)-Date.parse(from))/864e5):undefined

// N2 三态与 B4 分段（规格 31；BL-borrow 第 4 条）
export const SALES3:Record<string,'未完成'|'进行中'|'已完成'|'已取消'>={DRAFT:'未完成',PENDING_APPROVAL:'未完成',SUBMITTED:'未完成',REJECTED:'未完成',APPROVED:'进行中',CONFIRMED:'进行中',PROCESSING:'进行中',PARTIAL_DELIVERED:'进行中',SHIPPED:'进行中',DELIVERED:'已完成',COMPLETED:'已完成',CLOSED:'已完成',CANCELLED:'已取消'}
export const RAW_STATUS:Record<string,string>={DRAFT:'草稿',PENDING_APPROVAL:'待审批',SUBMITTED:'已提交',REJECTED:'已驳回',APPROVED:'已审核',CONFIRMED:'已确认',PROCESSING:'处理中',PARTIAL_DELIVERED:'部分发货',SHIPPED:'已发货',DELIVERED:'已送达',COMPLETED:'已完成',CLOSED:'已关闭',CANCELLED:'已取消'}
export const RELEASED=new Set(['APPROVED','CONFIRMED','PROCESSING','PARTIAL_DELIVERED','SHIPPED','DELIVERED','COMPLETED','CLOSED'])
export const DELIVERY_KEYS=['plannedDeliveryDate','deliveryDate','expectedDeliveryDate','requiredDate']
export function deliveryDateOf(o:Row){for(const k of DELIVERY_KEYS)if(o[k])return dateOnly(o[k]);return ''}
