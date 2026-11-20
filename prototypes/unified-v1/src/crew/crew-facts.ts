// F 线 · 数字班组的事实计算（纯函数）。只读六膳门黑湖快照（工单、报工、BOM、工艺路线）与库存快照，代码出数字；
// 算不出的给原因，不给 0。模型不参与任何数字（DECISIONS 第三节严格模式）。
/* eslint-disable @typescript-eslint/no-explicit-any */
export type Row=Record<string,any>
export interface Src{revision:string;checkedAt:string;resources:Record<string,Row[]>;costs?:Row[]}
export interface Inv{checkedAt?:string;materialAt?:string;stock:Row[]}
const n=(v:unknown)=>v==null||v===''?null:Number.isFinite(Number(v))?Number(v):null
const r2=(v:number)=>Math.round(v*100)/100
export const pct=(v:number|null)=>v==null?'无法计算':(v*100).toFixed(2)+'%'
export const qty=(v:number|null,u:string)=>v==null?'未返回':v.toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})+' '+u
const unitCn=(u:string)=>u==='KG'?'kg':u
export const bjDate=(iso?:string)=>iso?new Date(Date.parse(iso)+8*3600e3).toISOString().slice(0,16).replace('T',' '):'未知'

function procNames(src:Src){return Object.fromEntries((src.resources.process||[]).map(p=>[String(p.id),String(p.name)]))}
export function product(src:Src,code:string){return (src.resources.product||[]).find(p=>p.productCode===code)}
function routeOf(src:Src,code:string):string[]|null{
  const p=product(src,code);if(!p||!p.prcessRoutingCode)return null
  const rt=(src.resources.routing||[]).find(x=>x.processRoutingCode===p.prcessRoutingCode);return rt?(rt.processRoutingSteps||[]).slice().sort((a:Row,b:Row)=>a.seq-b.seq).map((s:Row)=>String(s.name)):null
}

// ---- 每张工单按工序汇总报工良品（同工序多笔相加；单位不一致标出） ----
export interface ProcAgg{name:string;qty:number|null;unit:string;units:string[];reports:number;first:string}
export interface WorkOrder{id:string;code:string;productCode:string;productName:string;planDate:string;planAmount:number|null;actualAmount:number|null;unit:string;status:string;procs:ProcAgg[]}
export function workOrders(src:Src,productCode?:string):WorkOrder[]{
  const pn=procNames(src),by:Record<string,Row[]>={}
  for(const o of src.resources.output||[])(by[o.projectId]=by[o.projectId]||[]).push(o)
  return (src.resources.project||[]).filter(p=>!productCode||p.productCode===productCode).map(p=>{
    const agg:Record<string,ProcAgg>={}
    for(const o of by[p.id]||[]){const name=pn[String(o.processId)]||'工序 '+o.processId,a=agg[name]=agg[name]||{name,qty:0,unit:String(o.stockUnit||''),units:[],reports:0,first:String(o.starttimeofReport||'')}
      const v=n(o.numberofgoodProducts);a.qty=a.qty==null||v==null?null:r2(a.qty+v);a.reports++;const u=String(o.stockUnit||'');if(!a.units.includes(u))a.units.push(u);if(String(o.starttimeofReport||'')<a.first)a.first=String(o.starttimeofReport)}
    for(const a of Object.values(agg))if(a.units.length>1)a.unit='单位不一致（'+a.units.join('、')+'）'
    return {id:String(p.id),code:String(p.projectCode),productCode:String(p.productCode),productName:String(p.productName),planDate:String(p.planStartTime||'').slice(0,10),planAmount:n(p.planAmount),actualAmount:n(p.actualAmount),unit:String(p.projectUnit||''),status:String(p.status),procs:Object.values(agg)}
  }).sort((a,b)=>a.planDate<b.planDate?-1:a.planDate>b.planDate?1:a.code<b.code?-1:1)
}

// ---- 出成对比：同一产品、相邻两道都按 KG 报工的工序，后道良品 ÷ 前道良品 ----
// 工序顺序：黑湖有工艺路线按路线；没有路线时「出料」排第一，其余按工序目录顺序（不按报工时间，黑湖报工时间有先后颠倒）。
export interface YieldPoint{code:string;date:string;from:number|null;to:number|null;fromUnit:string;toUnit:string;ratio:number|null;reason:string;suspect?:string}
export interface YieldJump{from:YieldPoint;to:YieldPoint;deltaPts:number}
export interface YieldSeries{productCode:string;productName:string;fromProc:string;toProc:string;order:'route'|'catalog';points:YieldPoint[];drop:YieldJump|null;jumps:YieldJump[];range:{min:YieldPoint;max:YieldPoint}|null}
function procOrder(src:Src,code:string):{list:string[];order:'route'|'catalog'}{
  const rt=routeOf(src,code);if(rt)return {list:rt,order:'route'}
  const cat=(src.resources.process||[]).map(p=>String(p.name));return {list:['出料',...cat.filter(x=>x!=='出料')],order:'catalog'}
}
// R30 数据疑点：出成 ≥ 99.5%（像没称重直接录入）、> 105% 或 ≤ 30%（不像同一物料）的点不参与比较；
// 相邻两单跳变超过 15 个百分点也算数据疑点（找车间核），不当作出成下降。
export const SUSPECT_HIGH=0.995,JUMP_PTS=15
export function yieldSeries(src:Src,productCode:string,fromProc:string,toProc:string,o:{dropPts?:number}={}):YieldSeries{
  const wos=workOrders(src,productCode),p=product(src,productCode),ord=procOrder(src,productCode)
  const points:YieldPoint[]=[]
  for(const w of wos){const a=w.procs.find(x=>x.name===fromProc),b=w.procs.find(x=>x.name===toProc);if(!a&&!b)continue
    let reason='',ratio:number|null=null,suspect=''
    if(!a||!b)reason=(!a?fromProc:toProc)+'未报工'
    else if(a.unit!=='KG'||b.unit!=='KG'){reason='单位不是 kg（'+fromProc+' '+unitCn(a.unit)+'，'+toProc+' '+unitCn(b.unit)+'），不能直接相除';suspect='单位填错'}
    else if(!a.qty)reason=fromProc+'良品为 0'
    else ratio=b.qty!=null?b.qty/a.qty:null
    if(ratio!=null&&ratio>=SUSPECT_HIGH&&ratio<=1.05)suspect='出成 '+pct(ratio)+'，像没称重直接录入'
    else if(ratio!=null&&(ratio>1.05||ratio<=0.3))suspect='出成 '+pct(ratio)+'，不像同一物料'
    if(suspect&&ratio!=null)reason=suspect+'，不参与比较'
    points.push({code:w.code,date:w.planDate,from:a?a.qty:null,to:b?b.qty:null,fromUnit:a?unitCn(a.unit):'',toUnit:b?unitCn(b.unit):'',ratio,reason,suspect:suspect||undefined})}
  const ok=points.filter(x=>x.ratio!=null&&!x.suspect);let drop:YieldJump|null=null;const jumps:YieldJump[]=[]
  const lim=-(o.dropPts??3)/100
  for(let i=1;i<ok.length;i++){const d=ok[i].ratio!-ok[i-1].ratio!,j={from:ok[i-1],to:ok[i],deltaPts:r2(d*100)}
    if(Math.abs(d)*100>JUMP_PTS){jumps.push(j);continue}
    if(d<=lim&&(!drop||j.deltaPts<drop.deltaPts))drop=j}
  const range=ok.length?{min:ok.reduce((a,b)=>b.ratio!<a.ratio!?b:a),max:ok.reduce((a,b)=>b.ratio!>a.ratio!?b:a)}:null
  return {productCode,productName:p?String(p.productName):productCode,fromProc,toProc,order:ord.order,points,drop,jumps,range}
}
// 全厂扫描（主动汇报用）：每个产品取工序顺序上相邻、都按 KG 报工、至少 4 张工单可算的工序对。
// drops = 通过合理性检查后的出成下降（业务异常）；suspects = 和本工序对正常水平混在一起的疑点点位或大跳变（数据疑点 · 找车间核）；
// passThrough = 每单前后数量都一样（≥ 99.5%）的工序对，整体说明一次，不逐条报。
export function scanYieldDrops(src:Src,o:{dropPts?:number;minPoints?:number}={}){
  const drops:YieldSeries[]=[],suspects:{series:YieldSeries;reasons:string[]}[]=[],passThrough:YieldSeries[]=[],codes=[...new Set((src.resources.project||[]).map(p=>String(p.productCode)))]
  const min=o.minPoints??4
  for(const code of codes){const wos=workOrders(src,code);if(wos.length<min)continue
    const ord=procOrder(src,code).list,kg=new Set<string>()
    for(const w of wos)for(const a of w.procs)if(a.unit==='KG')kg.add(a.name)
    const seq=ord.filter(x=>kg.has(x))
    for(let i=0;i+1<seq.length;i++){const s=yieldSeries(src,code,seq[i],seq[i+1],o);const calc=s.points.filter(x=>x.ratio!=null)
      if(calc.length<min)continue
      const good=calc.filter(x=>!x.suspect),bad=calc.filter(x=>x.suspect)
      if(!good.length){if(bad.every(x=>x.ratio!>=SUSPECT_HIGH&&x.ratio!<=1.05))passThrough.push(s);continue}
      if(bad.length||s.jumps.length)suspects.push({series:s,reasons:[...bad.map(x=>x.code+'：'+x.suspect),...s.jumps.map(j=>j.from.code+' '+pct(j.from.ratio)+' → '+j.to.code+' '+pct(j.to.ratio)+'，跳变 '+Math.abs(j.deltaPts).toFixed(2)+' 个百分点')]})
      if(s.drop&&good.length>=min)drops.push(s)}}
  return {drops:drops.sort((a,b)=>a.drop!.deltaPts-b.drop!.deltaPts),suspects,passThrough}
}
// 费料核对：按「下降前那一单」的出成，算下降那一单本该用多少前道物料、实际多用了多少（只用两单的报工良品，代码计算）
export function extraUse(s:YieldSeries){if(!s.drop)return null;const a=s.drop.from,b=s.drop.to;if(a.ratio==null||b.to==null||b.from==null)return null
  const expected=r2(b.to/a.ratio);return {expected,actual:b.from,extra:r2(b.from-expected),formula:b.code+' '+s.toProc+'良品 '+b.to+' kg ÷ '+a.code+' 出成 '+pct(a.ratio)+' = 按上一单出成应'+s.fromProc+' '+expected+' kg；实际'+s.fromProc+' '+b.from+' kg'}}
// 工单成本（B 线 cost 快照里的同一工单）：材料成本没返回时写原因，不算费料金额
export function costOf(src:Src,code:string){const c=(src.costs||[]).find(x=>x.projectCode===code);if(!c)return {known:false,text:'成本快照没有这张工单'}
  const lines=n(c.materialLines)??0,known=n(c.materialCostKnownLines)??0,mc=n(c.materialCost)
  if(mc==null)return {known:false,materialCost:null,text:'材料成本未算出：材料行 '+lines+' 行，有单价 '+known+' 行'}
  if(mc===0)return {known:false,materialCost:null,text:'黑湖材料成本为 0'+((n(c.sourceZeroCostLines)??0)>0?'（来源单价为 0）':'')+'，按未知处理'}
  return {known:true,materialCost:mc,text:'材料成本 '+mc.toFixed(2)+' 元'}}

// ---- 急单核对：物料（BOM 一层 + 库存快照）、历史产出、原有安排；产能没有数据，明确写缺口 ----
export interface RushInput{productCode:string;qty:number;date:string;today?:string}
export interface RushLine{code:string;name:string;unit:string;unitQty:number;lossRate:number;need:number|null;stock:number|null;stockUnit:string;warehouses:string}
export function rushCheck(src:Src,inv:Inv|null,x:RushInput){
  const p=product(src,x.productCode),bom=(src.resources.bom||[]).filter(b=>b.lastProductCode===x.productCode)
  const lines:RushLine[]=bom.map(b=>{const c=product(src,String(b.nextProductCode)),st=inv?inv.stock.filter(s=>s.productCode===b.nextProductCode):[]
    const sum=st.length?r2(st.reduce((a,s)=>a+(n(s.qtyInWarehouse)??0),0)):null
    const uq=n(b.unitQty)??0,base=n(b.parentBaseQty)||1,loss=n(b.lossRate)??0
    return {code:String(b.nextProductCode),name:c?String(c.productName):'黑湖未返回名称',unit:c?unitCn(String(c.unit)):'',unitQty:uq,lossRate:loss,need:r2(x.qty*uq/base*(1+loss)),stock:inv?sum:null,stockUnit:st[0]?unitCn(String(st[0].unitName)):'',warehouses:st.map(s=>s.warehouseName+' '+(n(s.qtyInWarehouse)??0)+unitCn(String(s.unitName))).join('；')||'库存快照没有这一项'}})
  // 多个投入都是 1:1、损耗 0：更像「任选其一」的替代料，不能相加，也不能判断该用哪一项
  const alt=lines.length>1&&lines.every(l=>l.unitQty===1&&l.lossRate===0)
  const wos=workOrders(src,x.productCode),today=x.today||bjDate(src.checkedAt).slice(0,10)
  const since=new Date(Date.parse(today)-13*864e5).toISOString().slice(0,10)
  const recent=wos.filter(w=>w.planDate>=since&&w.planDate<=today),byDay:Record<string,number>={}
  for(const w of recent)if(w.actualAmount!=null)byDay[w.planDate]=r2((byDay[w.planDate]||0)+w.actualAmount)
  const days=Object.entries(byDay).sort(),max=days.reduce((a,d)=>d[1]>a[1]?d:a,['',0] as [string,number])
  const sameDay=(src.resources.project||[]).filter(w=>String(w.planStartTime||'').slice(0,10)===x.date)
  const zeroAll=lines.length>0&&lines.every(l=>l.stock===0)
  const madeRecently=days.length>0
  const material=!lines.length?{state:'不能判断',text:'黑湖没有这款产品的 BOM 投料行'}
    :zeroAll&&madeRecently?{state:'不能判断',text:'BOM 里 '+lines.length+' 项原料在黑湖库存快照里全是 0，但近 14 天有 '+days.length+' 天在生产：原料入库很可能没在黑湖登记（或在快照没覆盖的仓库），系统不倒推库存，需仓库核实'}
    :alt?{state:'不能判断',text:lines.length+' 项投入都是 1:1、损耗 0，像替代料，系统不能判断用哪一项'}
    :{state:lines.some(l=>l.stock==null||l.need==null||l.stock<l.need)?'缺料':'齐套',text:''}
  return {product:p?{code:String(p.productCode),name:String(p.productName),unit:unitCn(String(p.unit)),routing:routeOf(src,x.productCode)}:null,lines,alt,material,
    history:{since,today,days,maxDay:max[0]?{date:max[0],qty:max[1]}:null,count:recent.length},
    sameDay:sameDay.map(w=>({code:String(w.projectCode),name:String(w.productName),plan:n(w.planAmount),unit:unitCn(String(w.projectUnit))})),
    capacity:{known:false,text:'六膳门没有配置这款产品的日产能（正式系统有产品日产能、产线产能配置和急单插单分析，原型还没接），不能判断这一天做不做得完；只能拿近 14 天这款产品的实际单日产出作参考'},
    snapshot:{source:bjDate(src.checkedAt),inventory:bjDate(inv?.checkedAt)}}
}

// ---- 入职：口述 → 工序。按黑湖工序目录逐词匹配，和这款产品工单上真实报过的工序对照；记录要求按第六批称重报工字段 ----
export interface OnboardStep{said:string;process:string|null;inHistory:boolean;unitSeen:string;records:string[];note:string}
const ALIAS:Record<string,string>={'卤制':'熟制','卤':'熟制','煮':'熟制','焯':'焯水','汆水':'焯水','去苔':'去舌苔','刮舌苔':'去舌苔','领料':'出料','解冻':'出料','包装':'气调','气调包装':'气调','贴标':'贴成品盒签','贴签':'贴成品盒签'}
export function onboardParse(src:Src,productCode:string,text:string){
  const cat=(src.resources.process||[]).map(p=>String(p.name)),hist:Record<string,string>={}
  for(const w of workOrders(src,productCode))for(const a of w.procs)hist[a.name]=hist[a.name]||unitCn(a.unit)
  // 按句切分，每句找第一个能对上工序目录（或常用叫法）的词
  const parts=text.split(/[，,。；;、\n]|然后|再|接着|最后/).map(s=>s.trim()).filter(Boolean),steps:OnboardStep[]=[],unmatched:string[]=[]
  for(const s of parts){let hit:string|null=null,at=Infinity,len=0
    // 句子里最先出现的工序名优先（「气调包装」取气调）；同位置取更长的；目录没有再看常用叫法
    for(const c of cat){const i=s.indexOf(c);if(i>=0&&(i<at||(i===at&&c.length>len))){hit=c;at=i;len=c.length}}
    if(!hit)for(const [k,v] of Object.entries(ALIAS)){const i=s.indexOf(k);if(i>=0&&cat.includes(v)&&(i<at||(i===at&&k.length>len))){hit=v;at=i;len=k.length}}
    if(!hit){if(/\d/.test(s)||s.length>=4)unmatched.push(s);continue}
    if(steps.some(x=>x.process===hit)){const st=steps.find(x=>x.process===hit)!;st.said+='；'+s;continue}
    const kg=hist[hit]==='kg'||/斤|公斤|kg|重/.test(s),rec=kg?['投入重量','良品重量','不良重量（选不良项）','开始 / 结束时间']:['良品数量','不良数量','开始 / 结束时间']
    const extra:string[]=[];const t=s.match(/(\d+(?:\.\d+)?)\s*(分钟|小时|度|℃)/g);if(t)extra.push('口述提到「'+t.join('、')+'」：系统报工没有温度 / 时长参数字段，先记在工序说明里')
    steps.push({said:s,process:hit,inHistory:!!hist[hit],unitSeen:hist[hit]||'',records:rec,note:extra.join('；')})}
  const missing=Object.keys(hist).filter(h=>!steps.some(s=>s.process===h))
  return {steps,unmatched,historyProcs:Object.keys(hist),missing,hasRoute:!!routeOf(src,productCode),workOrders:workOrders(src,productCode).length}
}

// ---- 上岗条件：每位员工要用的资料在当前工作区是否齐（只看有没有，不评价好坏） ----
export interface Ready{label:string;ok:boolean;detail:string;key?:boolean}
// 上岗结论（F-12）：关键资料缺 → 暂不能上岗；只缺非关键资料 → 带缺口上岗；都齐 → 可上岗
export function verdict(rs:Ready[]):'可上岗'|'带缺口上岗'|'暂不能上岗'{if(rs.some(r=>r.key&&!r.ok))return '暂不能上岗';if(rs.some(r=>!r.ok))return '带缺口上岗';return '可上岗'}
export function readiness(src:Src|null,inv:Inv|null,extra:{inspections?:number;standards?:number;yieldStd?:number|null;productCode?:string}={}):Record<string,Ready[]>{
  const r=src?.resources||{},cnt=(k:string)=>(r[k]||[]).length,pc=extra.productCode
  const routed=(r.product||[]).filter(p=>p.prcessRoutingCode).length,stockNonZero=inv?inv.stock.filter(s=>(n(s.qtyInWarehouse)??0)>0).length:0
  const costKnown=(src?.costs||[]).filter(c=>(n(c.materialCost)??0)>0).length,costZero=(src?.costs||[]).filter(c=>n(c.materialCost)===0).length
  const p=pc?product(src as Src,pc):null
  return {
    planner:[{key:true,label:'工单与报工',ok:cnt('project')>0&&cnt('output')>0,detail:'黑湖工单 '+cnt('project')+' 张、报工 '+cnt('output')+' 笔'},
      {label:'工艺路线',ok:routed>0,detail:'有路线的产品 '+routed+' / '+cnt('product')+(p?'；'+p.productName+(p.prcessRoutingCode?' 有路线':' 没有路线（入职要补）'):'')},
      {key:true,label:'BOM',ok:cnt('bom')>0,detail:'BOM 行 '+cnt('bom')},
      {label:'产能',ok:false,detail:'六膳门没有配置日产能（正式系统有产能配置，原型未接），急单只能参考历史产出'}],
    keeper:[{key:true,label:'库存快照',ok:!!inv,detail:inv?'库存行 '+inv.stock.length+'，大于 0 的 '+stockNonZero+' 行（'+bjDate(inv.checkedAt)+'）':'没有读到库存快照'},
      {label:'入库批次',ok:false,detail:'本页没有读取 Cretas 入库批次，先按库存快照判断'}],
    inspector:[{key:true,label:'检验标准',ok:(extra.standards??0)>0,detail:extra.standards==null?'未读取':'当前会话检验标准 '+extra.standards+' 条'},
      {label:'检验记录',ok:(extra.inspections??0)>0,detail:extra.inspections==null?'未读取':'当前会话检验单 '+extra.inspections+' 张'}],
    accountant:[{key:true,label:'报工良品',ok:cnt('output')>0,detail:'报工 '+cnt('output')+' 笔'},
      {label:'材料成本',ok:costKnown>0,detail:'有材料成本的工单 '+costKnown+' / '+(src?.costs||[]).length+(costZero?'（另有 '+costZero+' 张黑湖成本为 0，按未知处理）':'')},
      {label:'出成标准',ok:extra.yieldStd!=null,detail:extra.yieldStd==null?'这款产品在「出成标准」页没有标准；可在本次入职填一个作草稿':'本次入职填了 '+(extra.yieldStd*100).toFixed(2)+'%（只进入职记录，不写入「出成标准」页）'}],
    clerk:[{key:true,label:'销售 / 采购单（黑湖只读）',ok:true,detail:'在销售订单、采购订单页只读查看'},{label:'开单',ok:false,detail:'开单在黑湖，文员只能把事整理成协同任务'}]
  }
}
