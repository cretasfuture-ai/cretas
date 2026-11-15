// A 线味美新视图的数据层（DECISIONS A1 / R4）：经 19063 代理只读味美 API，只用 A 线隔离会话（test-a-r2-20261111 或顶层地址带的 test-a… 会话）。
// frame 的 fetch 包装会把以 /api/ 开头的相对地址改去六膳门，所以一律用绝对地址。写操作回到味美原页面完成。
import type {Row} from '../frame/types'
import {acceptanceSession} from './session'
import {bjDay} from './time'
// 第二批起默认 test-a-r2-20261111；顶层地址若带本线会话（test-a…），跟随它，保证和原页面看到同一份数据
export const YD_SESSION=(()=>{const s=acceptanceSession();return /^test-a(-[a-z0-9-]+)?$/.test(s)?s:'test-a-r2-20261111'})()
const BASE=()=>location.origin+'/api/mobile/FOOD_0000_001/'
export async function yget<T=Row[]>(path:string):Promise<T>{
  const r=await fetch(BASE()+path,{headers:{'x-prototype-test-session':YD_SESSION}});let b:{success?:boolean;message?:string;data?:unknown}={}
  try{b=await r.json()}catch{throw Error('味美接口读取失败（'+r.status+'）')}
  if(!r.ok||b.success===false)throw Error(b.message||'味美接口读取失败')
  const d=b.data as Record<string,unknown>|unknown
  return ((d&&typeof d==='object'&&!Array.isArray(d))?((d as Record<string,unknown>).content??(d as Record<string,unknown>).records??d):d) as T
}
export const num=(v:unknown)=>v===null||v===undefined||v===''?null:Number.isFinite(Number(v))?Number(v):null
// R22：申请 / 审批时间是带时区的 ISO（…Z），按北京日期显示；纯日期原样
export const day=(v:unknown)=>bjDay(v)
// 打开味美原页面（写操作在那里完成），带上本线隔离会话
// 打开原页面一律带 view=native：按账号记住「精简」时，原页面的偏好重定向会放行显式的 view=native（purpose-flow.js）
export function nativeHref(path:string,q:Record<string,string>={}){const p=new URLSearchParams({...q,view:'native',acceptanceSession:YD_SESSION});return path+'?'+p.toString()}
export const PLAN_STATUS:Record<string,string>={PENDING:'待执行',PLANNED:'待执行',IN_PROGRESS:'进行中',PAUSED:'已暂停',STOPPED:'已停产',COMPLETED:'已完成',CANCELLED:'已取消'}
export const REQ_STATUS:Record<string,string>={PENDING:'待备料',PICKING:'拣货中',PICKED:'已拣货',IN_TRANSIT:'在途',RECEIVED:'已签收',CLOSED:'已关单',CANCELLED:'已取消'}
export const REV_STATUS:Record<string,string>={PENDING:'待审批',PENDING_APPROVAL:'待审批',APPROVED:'已批准',EXECUTED:'已批准并执行',REJECTED:'已驳回'}
export const REV_SCOPE:Record<string,string>={FULL:'整单撤回',BATCH:'整单撤回',PARTIAL:'部分撤回',LAST:'最近一次'}
