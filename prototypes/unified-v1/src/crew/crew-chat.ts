// F 线 · 数字员工对话引擎（规则分析，不调用模型）：识别要办的事 → 代码算事实 → 回答；要写的事只生成预览，送进「等我确认」。
// 版式与行为遵守 docs/ux/ai-chat-standard.md：追问芯片只用这里明确给出的 followUps；澄清选项同一样式；写确认卡不出反馈。
import {CREW,crewById,crewPerm,type CrewId} from './crew-config'
import {rushCheck,yieldSeries,scanYieldDrops,extraUse,costOf,workOrders,product,pct,qty,type Src,type Inv,type YieldSeries} from './crew-facts'
import {nextFriday,today} from './crew-store'

export interface Opt{label:string;question:string;desc?:string}
// head：第一行结论（能不能做、差在哪、建议怎么办）；有 head 时 text 和表格算依据，页面默认折叠
export interface Msg{id:string;role:'user'|'assistant';text:string;head?:string;card?:{type:string;data:unknown};followUps?:Opt[];options?:Opt[];kind?:'answer'|'clarify'|'draft'|'error'|'notice'}
let seq=0;const mid=()=>'m'+(++seq)
export const user=(text:string):Msg=>({id:mid(),role:'user',text})
const say=(text:string,o:Partial<Msg>={}):Msg=>({id:mid(),role:'assistant',text,kind:'answer',...o})

// 产品识别：最长公共片段 × 10 + 共有汉字数；并列最高时澄清，不替用户选
function lcs(a:string,b:string){let m=0;for(let i=0;i<a.length;i++)for(let j=0;j<b.length;j++){let k=0;while(a[i+k]&&a[i+k]===b[j+k])k++;if(k>m)m=k}return m}
export function matchProduct(text:string,cands:{code:string;name:string}[]){
  const t=text.replace(/\s/g,''),scored=cands.map(c=>{const name=c.name.replace(/叮咚好食光|\d+g/g,'');const l=lcs(t,name);return {...c,score:l>=2?l*10+[...new Set(name)].filter(ch=>/[一-龥]/.test(ch)&&t.includes(ch)).length:0}}).filter(c=>c.score>0).sort((a,b)=>b.score-a.score)
  if(!scored.length)return {hit:null,ties:[] as typeof scored}
  const top=scored.filter(c=>c.score===scored[0].score);return top.length===1?{hit:top[0],ties:[]}:{hit:null,ties:top.slice(0,4)}
}
export function parseQtyKg(t:string){const m=t.match(/(\d+(?:\.\d+)?)\s*(吨|t|T|公斤|kg|KG|千克|斤)/);if(!m)return null;const v=Number(m[1]);return m[2]==='吨'||m[2]==='t'||m[2]==='T'?v*1000:m[2]==='斤'?v/2:v}
export function parseDate(t:string){if(/周五|星期五|礼拜五/.test(t))return nextFriday();if(/明天/.test(t))return new Date(Date.parse(today())+864e5).toISOString().slice(0,10);if(/今天/.test(t))return today()
  const a=t.match(/(\d{4})-(\d{1,2})-(\d{1,2})/);if(a)return a[1]+'-'+a[2].padStart(2,'0')+'-'+a[3].padStart(2,'0')
  const b=t.match(/(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]/);if(b)return today().slice(0,4)+'-'+b[1].padStart(2,'0')+'-'+b[2].padStart(2,'0');return null}

export interface Ctx{src?:Src;inv:Inv|null;perms:Record<string,string>|null;ctx?:{kind:string;id:string;label:string}|null}
export function welcome(id:CrewId,c:Ctx):Msg{
  const d=crewById(id)!,p=crewPerm(d,c.perms)
  const can=d.capabilities.filter(x=>x.backend!=='gap'),gap=d.capabilities.filter(x=>x.backend==='gap')
  const head=p==='-'?'按你现在的权限，我在这里只能说明能力，查不了也办不了（缺「'+d.modules.join('、')+'」模块权限）。':p==='r'?'按你现在的权限，我只能帮你查，不能替你办（写操作需要办理权限）。':'我能帮你查，也能替你把事办到「等你确认」，你确认后才执行。'
  const ex:Record<CrewId,Opt[]>={
    planner:[{label:'周五加 2 吨缓化料',question:'老板说周五要加 2 吨牛肉丝/片缓化料，排得进吗？'},{label:'今天的工单进度',question:'今天的工单进度怎么样？'}],
    accountant:[{label:'卤猪舌出成率',question:'卤猪舌（轻卤门腔）的出成率是不是掉了？'},{label:'哪些产品出成下降',question:'最近哪些产品出成率下降了？'}],
    keeper:[{label:'缓化料原料库存',question:'牛肉丝/片缓化料的原料库存够吗？'}],
    inspector:[{label:'检验情况',question:'现在有几张检验单、几条标准？'}],
    clerk:[{label:'我能办什么',question:'你能帮我办什么？'}]}
  return say(d.name+'：'+head+'\n能办：'+can.map(x=>x.label).join('；')+'。\n还办不了：'+(gap.map(x=>x.label+'（'+x.note+'）').join('；')||'无')+'。',{kind:'notice',options:c.ctx||!c.src?[]:ex[id]})
}

export function reply(id:CrewId,text:string,c:Ctx):Msg[]{
  if(!c.src)return [say('这个工作区没有可核对的数据：味美只有本机练习数据，数字班组的演示在六膳门工作区（六膳门打开时如果看到这句，是黑湖快照还没读到）。没有执行任何写操作。',{kind:'error'})]
  if(id==='planner'&&(/急单|加单|加\s*\d|吨|排得进|安排/.test(text)))return rush(text,c)
  if(id==='planner'&&/进度|工单/.test(text))return progress(c)
  if(id==='accountant'&&/出成|费料|掉|下降|损耗/.test(text))return yieldQ(text,c)
  if(id==='keeper'&&/库存|原料|缺料|够/.test(text))return stockQ(text,c)
  if(id==='inspector'&&/检验|标准|不合格/.test(text))return [say('检验数据在质量检验页。数字质检员在本原型里只读那里的计数，详细请打开质量检验。',{card:{type:'link',data:{label:'打开质量检验',route:'/quality/inspections'}}})]
  const d=crewById(id)!;return [say('这句话我没对上能办的事。'+d.name+'能办：'+d.capabilities.filter(x=>x.backend!=='gap').map(x=>x.label).join('；')+'。',{kind:'clarify',options:welcome(id,c).options})]
}

function kgProducts(c:Ctx){const out:Record<string,{code:string;name:string;n:number}>={};for(const w of c.src!.resources.project||[])if(String(w.projectUnit)==='KG'){const k=String(w.productCode);(out[k]=out[k]||{code:k,name:String(w.productName),n:0}).n++}return Object.values(out).sort((a,b)=>b.n-a.n)}
function rush(text:string,c:Ctx):Msg[]{
  const kg=parseQtyKg(text),date=parseDate(text)
  const m=matchProduct(text,kgProducts(c))
  if(!m.hit){const opts=(m.ties.length?m.ties:kgProducts(c).slice(0,3)).map(p=>({label:p.name,question:text.replace(/[。？?]$/,'')+'（产品：'+p.name+'）',desc:'按 kg 下单的工单 '+(kgProducts(c).find(x=>x.code===p.code)?.n??0)+' 张'}))
    return [say(m.ties.length?'对上了几款产品，请选一款：':'没听出是哪款产品。按 kg 生产、工单最多的是下面几款，请选：',{kind:'clarify',options:opts})]}
  if(kg==null)return [say('要加多少？请带上数量和单位（吨或公斤）。',{kind:'clarify',options:[1,2,3].map(v=>({label:v+' 吨',question:text+' '+v+' 吨'}))})]
  const d=date||nextFriday(),r=rushCheck(c.src!,c.inv,{productCode:m.hit.code,qty:kg,date:d,today:today()})
  const lines=['产品：'+r.product!.name+'，加 '+qty(kg,'kg')+'，'+d+(date?'':'（没说日期，按快照日 '+today()+' 之后最近的周五）')+'。',
    '物料：'+r.material.state+(r.material.text?'。'+r.material.text:'')+'。',
    '产出参考：近 14 天（'+r.history.since+' 至 '+r.history.today+'，按快照日计）有 '+r.history.days.length+' 天生产，单日最多 '+(r.history.maxDay?qty(r.history.maxDay.qty,'kg')+'（'+r.history.maxDay.date+'）':'无记录')+'。'+r.capacity.text+'。',
    '原有安排：黑湖快照里 '+d+' 已开工单 '+r.sameDay.length+' 张'+(r.sameDay.length?'（'+r.sameDay.map(w=>w.code+' '+w.name).slice(0,5).join('、')+'）':'')+'；快照时点 '+r.snapshot.source+'。']
  // 结论只照搬核对结果：不能判断的仍写不能判断，不给「排得进 / 排不进」
  const ms=r.material.state,why=(r.material.text||'').split('：')[0]
  const head=(ms==='齐套'?'物料按库存快照够用，但还不能确认排得进：产能不能判断。':ms==='缺料'?'现在排不进：按库存快照物料不够；产能也不能判断。':'现在不能确认排得进：物料不能判断，产能也不能判断。')
    +'差在：'+(ms==='不能判断'&&why?why+'；':ms==='缺料'?r.lines.filter(l=>l.stock==null||l.need==null||l.stock<l.need).map(l=>l.name+' 需 '+qty(l.need,l.unit||'kg')+'、库存 '+(l.stock==null?'未返回':qty(l.stock,l.stockUnit||l.unit))).slice(0,3).join('，')+'；':'')+'六膳门没有配置这款产品的日产能。'
    +'建议：'+(ms==='齐套'?'':'先让仓库核实原料实际库存；')+'我可以先出安排草案和影响说明，交生产主管确认。'
  return [say(lines.join('\n'),{head,card:{type:'rush',data:{input:{productCode:m.hit.code,productName:r.product!.name,qty:kg,date:d},result:r}},followUps:[{label:'生成安排草案',question:'__draft_rush'}]})]
}
function progress(c:Ctx):Msg[]{
  const t=today(),wos=workOrders(c.src!).filter(w=>w.planDate===t),st:Record<string,number>={}
  for(const w of wos)st[w.status]=(st[w.status]||0)+1
  const L:Record<string,string>={'0':'新建','10':'执行中','20':'已结束','30':'已取消'}
  return [say('今天（'+t+'）计划开工的黑湖工单 '+wos.length+' 张：'+(Object.entries(st).map(([k,v])=>(L[k]||k)+' '+v+' 张').join('，')||'无')+'。数据时点：黑湖快照 '+new Date(Date.parse(c.src!.checkedAt)+8*3600e3).toISOString().slice(0,16).replace('T',' ')+'。',{card:{type:'link',data:{label:'打开生产工单',route:'/production/plans'}}})]
}
function yieldQ(text:string,c:Ctx):Msg[]{
  const drops=scanYieldDrops(c.src!).drops
  const m=matchProduct(text,[...new Map(drops.map(s=>[s.productCode,{code:s.productCode,name:s.productName}])).values()])
  if(!m.hit){return [say(/哪些|最近/.test(text)?'按报工良品算，近期相邻两单出成下降 3 个百分点以上的有 '+drops.length+' 处：':'没对上产品。近期出成下降的有下面几处，请选：',{kind:'clarify',options:drops.slice(0,6).map(s=>({label:s.productName+' · '+s.fromProc+'→'+s.toProc,question:s.productName+' 的出成率是不是掉了？',desc:pct(s.drop!.from.ratio)+' → '+pct(s.drop!.to.ratio)+'（'+s.drop!.from.code+' → '+s.drop!.to.code+'）'}))})]}
  const s=drops.find(x=>x.productCode===m.hit!.code)!
  return [yieldAnswer(c.src!,s)]
}
export function yieldAnswer(src:Src,s0:YieldSeries):Msg{
  const s=yieldSeries(src,s0.productCode,s0.fromProc,s0.toProc),x=extraUse(s),dr=s.drop!
  const costs=[dr.from.code,dr.to.code].map(code=>code+'：'+costOf(src,code).text)
  const text=[s.productName+'：按黑湖报工良品算「'+s.fromProc+' → '+s.toProc+'」出成（'+s.toProc+'良品 ÷ '+s.fromProc+'良品），'+s.points.filter(p=>p.ratio!=null).length+' 张工单可算。',
    '下降：'+dr.from.code+'（'+dr.from.date+'）'+pct(dr.from.ratio)+' → '+dr.to.code+'（'+dr.to.date+'）'+pct(dr.to.ratio)+'，降 '+Math.abs(dr.deltaPts).toFixed(2)+' 个百分点。',
    x?'差异环节在「'+s.toProc+'」：'+x.formula+'，多用 '+x.extra.toFixed(2)+' kg。':'',
    '成本分析里的材料成本：'+costs.join('；')+'。',
    (s.points.some(p=>p.ratio==null)?'不能算的：'+s.points.filter(p=>p.ratio==null).map(p=>p.code+' '+p.reason).join('；')+'。':''),
    s.order==='catalog'?'这款产品在黑湖没有工艺路线，工序先后按「出料在前、其余按工序目录」排。':''].filter(Boolean).join('\n')
  const head=s.productName+'「'+s.fromProc+' → '+s.toProc+'」的出成掉了：'+pct(dr.from.ratio)+' → '+pct(dr.to.ratio)+'，降 '+Math.abs(dr.deltaPts).toFixed(2)+' 个百分点。'
    +(x?'差在「'+s.toProc+'」这道：按上一单的出成，这一单多用'+s.fromProc+' '+x.extra.toFixed(2)+' kg。':'多用了多少算不出来。')
    +'建议：生成核对待办，交生产主管核对 '+dr.to.code+' 这一单的报工和当班情况。'
  return say(text,{head,card:{type:'yield',data:{series:s,extra:x}},followUps:[{label:'生成核对待办',question:'__draft_yield'}]})
}
function stockQ(text:string,c:Ctx):Msg[]{
  const m=matchProduct(text,kgProducts(c));if(!m.hit)return [say('请说是哪款产品的原料。',{kind:'clarify',options:kgProducts(c).slice(0,3).map(p=>({label:p.name,question:p.name+' 的原料库存够吗？'}))})]
  const r=rushCheck(c.src!,c.inv,{productCode:m.hit.code,qty:0,date:today(),today:today()})
  return [say(m.hit.name+' 的 BOM 投入 '+r.lines.length+' 项，库存快照（'+r.snapshot.inventory+'）：\n'+r.lines.map(l=>l.name+'：'+l.warehouses).join('\n')+'\n'+(r.material.text||''),{card:{type:'link',data:{label:'打开库存查询',route:'/warehouse/inventory-query'}}})]
}
export const crewList=()=>CREW
// 产品名截短（卡片标题用）
export const shortName=(src:Src,code:string)=>{const p=product(src,code);return p?String(p.productName):code}
