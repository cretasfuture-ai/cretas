// 第五批 ①：六膳门 A 线「Cretas 补充」批量导入与重算（BOM 投料工序 / 产品路线 / 工序标准）。
// 导入走共享 CretasUI.supplementImport（下载模板 → 预检逐行报错 → 确认）；保存、回读、撤销在 a-api.cjs。
// 重算：effectiveSource() 把生效的补充叠到黑湖来源上——只补黑湖没返回的字段（黑湖有值时以黑湖为准），
// 补充出来的路线和投料工序带 _source / _feedSource = 'cretas'，页面据此标「Cretas 补充」。
import {reactive} from 'vue'
import type {Row,Source} from '../frame/types'
import {cretasUI,confirmReason} from '../frame/ai-protocol'
import {acceptanceSession} from './session'
import {bjTime} from './time'

export const SUP=reactive({version:0,bomFeeds:[] as Row[],routes:[] as Row[],standards:[] as Row[],batches:[] as Row[],loaded:false,error:''})
const aq=(path:string)=>{const p=new URLSearchParams({ws:'lsm'});const s=acceptanceSession();if(s)p.set('acceptanceSession',s);return '/__unified/a/api/'+path+'?'+p}
async function post<T>(path:string,body:Record<string,unknown>):Promise<T>{const r=await fetch(aq(path),{method:'POST',headers:{'content-type':'application/json','x-prototype-action':'explicit'},body:JSON.stringify({ws:'lsm',...body})});const b=await r.json().catch(()=>({success:false,message:'服务返回格式不正确'}));if(!r.ok||!b.success)throw Error(b.message||'请求失败');return b.data as T}

export async function loadSupplements(){try{const b=await (await fetch(aq('supplements'))).json();if(!b.success)throw Error(b.message==='未找到'?'补充服务尚未接入统一入口':b.message);Object.assign(SUP,b.data,{loaded:true,error:''})}catch(e){SUP.error=e instanceof Error?e.message:'读取补充失败'}}

const steps=(r:Row|undefined)=>Array.isArray(r?.processRoutingSteps)?r!.processRoutingSteps as Row[]:[]
export function effectiveSource(src:Source):Source{
  if(!SUP.routes.length&&!SUP.bomFeeds.length)return src
  const r=src.resources,routing=r.routing||[],byCode=new Map(routing.map(x=>[x.processRoutingCode,x])),procBy=new Map((r.process||[]).map(p=>[p.code,p]))
  const routeBy=new Map(SUP.routes.map(x=>[String(x.productCode),x])),feedBy=new Map(SUP.bomFeeds.map(x=>[String(x.bomId),x]))
  const extra=SUP.routes.map(x=>({processRoutingCode:'CRETAS-'+x.productCode,processRoutingName:'Cretas 补充路线',_source:'cretas',_supplementId:x.id,_batchId:x.batchId,processRoutingSteps:(x.steps as string[]).map((c,i)=>({seq:i+1,code:c,name:procBy.get(c)?.name||c,_source:'cretas'}))}))
  const product=(r.product||[]).map(p=>!steps(byCode.get(p.prcessRoutingCode)).length&&routeBy.has(String(p.productCode))?{...p,prcessRoutingCode:'CRETAS-'+p.productCode,_routeSource:'cretas'}:p)
  const bom=(r.bom||[]).map(b=>!b.feedProcessCode&&feedBy.has(String(b.id))?{...b,feedProcessCode:feedBy.get(String(b.id))!.processCode,_feedSource:'cretas',_batchId:feedBy.get(String(b.id))!.batchId}:b)
  return {...src,resources:{...r,routing:[...routing,...extra],product,bom}}
}

export async function openImport(after?:()=>void){
  await loadSupplements();if(SUP.error){cretasUI().toast({message:SUP.error,tone:'danger'});return}
  let token='',ver=SUP.version
  cretasUI().supplementImport({title:'批量补充（Excel）· 六膳门生产',templateHref:aq('import-template'),templateLabel:'下载补充模板（已列出当前缺口）',
    note:'模板三张表：BOM投料工序、产品路线、工序标准。只读「（填写）」列，留空的行跳过，不自动填值；黑湖已有值的行不接受（黑湖为准）。确认后画布挂接、路线对应、效率达标率按补充重算；只写本机原型，整批可在「补充记录」撤销。',
    onParse:async(file:File)=>{
      if(file.size>5_000_000)throw Error('文件超过 5MB')
      const buf=new Uint8Array(await file.arrayBuffer());let bin='';for(let i=0;i<buf.length;i+=0x8000)bin+=String.fromCharCode(...buf.subarray(i,i+0x8000))
      const d=await post<{token:string;skipped:number;rows:{sheet:string;row:number;target:string;status:string;message:string}[]}>('import/preflight',{fileBase64:btoa(bin),fileName:file.name})
      token=d.token;ver=SUP.version
      return {rows:d.rows.filter(r=>r.status==='ok'),errors:d.rows.filter(r=>r.status!=='ok').map(r=>r.sheet+' 第 '+r.row+' 行（'+r.target+'）：'+r.message)}
    },
    onConfirm:async()=>{const d=await post<{capture:{written:number;batchId:string}}>('import/confirm',{token,expectedVersion:ver});await loadSupplements();after?.();return {count:d.capture.written,viewHistory:()=>openBatches(after)}}
  })
}

// 时间按北京时间显示（服务端存 ISO/UTC），用 A 线共用的 bjTime
const bj=(v:unknown)=>bjTime(v)
export async function openBatches(after?:()=>void){
  await loadSupplements();const ui=cretasUI(),esc=ui.escape as (s:unknown)=>string
  const box=document.createElement('div');box.className='a-batches'
  const render=()=>{box.innerHTML=SUP.batches.length?'<div style="overflow-x:auto"><table class="a-batch-table" style="font-size:12px;border-collapse:collapse;width:100%"><thead><tr><th>批次 / 文件</th><th>导入时间</th><th>路线 / 投料 / 标准</th><th>拒绝</th><th>状态</th><th></th></tr></thead><tbody>'+SUP.batches.map(b=>{const c=b.counts as Record<string,number>,rv=b.revoked as Record<string,string>|null;return '<tr><td>'+esc(b.batchId)+'<br><small>'+esc(b.file||'—')+'</small></td><td>'+esc(bj(b.at))+'</td><td>'+c.route+' / '+c.bom+' / '+c.std+'</td><td>'+esc(b.rejected)+' 行</td><td>'+(rv?'已撤销 · '+esc(rv.reason):'生效 '+ui.sourceTagHTML('cretas'))+'</td><td>'+(rv?'':'<button type="button" class="cs-button" data-variant="ghost" data-revoke="'+esc(b.batchId)+'">整批撤销</button>')+'</td></tr>'}).join('')+'</tbody></table></div>':'<p>还没有导入批次。</p>'}
  box.addEventListener('click',async e=>{const t=(e.target as HTMLElement).closest('[data-revoke]') as HTMLElement|null;if(!t)return;const why=await confirmReason({title:'整批撤销导入批次',object:String(t.dataset.revoke),message:'撤销后保留留痕，可在记录里看到原因；不回写黑湖。',reasonLabel:'撤销原因',okLabel:'确认撤销',danger:true,required:true});if(!why)return;try{await post('import/revoke',{batchId:t.dataset.revoke,reason:why,expectedVersion:SUP.version});await loadSupplements();render();after?.();ui.toast({message:'已整批撤销，画布与路线对应已按黑湖来源重算'})}catch(err){ui.toast({message:'撤销失败：'+(err instanceof Error?err.message:''),tone:'danger'})}})
  render();ui.drawer({title:'补充记录 · 六膳门生产',content:box,actions:[{label:'关闭',variant:'secondary',onClick:x=>{void x.close({force:true})}}]})
}
