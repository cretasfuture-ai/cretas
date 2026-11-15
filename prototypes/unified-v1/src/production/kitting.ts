// 订单履约齐套计算（BL-borrow B1：A 出计算与事实，C 在销售订单侧显示）。纯函数，不读接口、不调模型、不许手改。
// 口径（借黑湖、按食品配方调整）：
//   需求数 = 根：订单明细数量；子项：父项「净需求数」× 单位用量（父项库存未返回时用计划缺料数并注明）（单位用量 = 标准用量 ÷ 产出基数，已含出成率时不再除；未含时 ÷ 出成率）
//   计划缺料数 = max(需求数 − 已下达数, 0)       已下达：已排产（自制）或已下采购（外购）的数量
//   可用库存   = 库存数量 − 已分配库存            缺失 → null（「库存未返回」），不当 0
//   净需求数   = max(计划缺料数 − 可用库存, 0)
//   齐套状态   = 齐套（净需求 0）/ 部分（0 < 可用 < 计划缺料）/ 缺料（可用 0）/ 不能判断（单位不一致、库存未返回、缺 BOM）
//   下一步     = 自制 → 安排生产；外购 → 安排采购；未知 → 待确认制造方式；齐套 → 无
// 不同单位不换算：子项单位与库存单位不一致时整行标「单位不一致」，不出数。
export type MakeOrBuy='make'|'buy'|'unknown'
export interface BomLine{materialId:string;name:string;qty:number;unit:string;makeOrBuy:MakeOrBuy}
export interface Bom{productId:string;outputBase:number;outputUnit:string;yieldRate?:number|null;yieldIncluded?:boolean;lines:BomLine[];version?:string|number}
export interface Stock{onHand:number|null;allocated:number|null;unit:string|null}
export interface KitInput{
  line:{productId:string;name:string;qty:number;unit:string;makeOrBuy?:MakeOrBuy}
  bomOf:(productId:string)=>Bom|null
  stockOf:(materialId:string)=>Stock|null
  releasedOf?:(materialId:string)=>number|null
  maxDepth?:number
}
export type KitStatus='齐套'|'部分'|'缺料'|'不能判断'
// 订单级汇总多一种：料齐待产 = 下层物料都齐，但根产品还有净需求要生产（行内「安排生产」）
export type KitOrderStatus=KitStatus|'料齐待产'
export interface KitRow{level:string;depth:number;materialId:string;name:string;unit:string;makeOrBuy:MakeOrBuy;required:number|null;released:number|null;planShortage:number|null;available:number|null;netDemand:number|null;status:KitStatus;next:'安排生产'|'安排采购'|'待确认制造方式'|null;issue?:string;bomVersion?:string|number}
export interface KitResult{rows:KitRow[];status:KitOrderStatus;shortItems:number;unknownItems:number;rule:string}
const r6=(v:number)=>Math.round(v*1e6)/1e6
export function kit(input:KitInput):KitResult{
  const rows:KitRow[]=[],maxDepth=input.maxDepth??6
  const walk=(materialId:string,name:string,unit:string,required:number|null,makeOrBuy:MakeOrBuy,level:string,depth:number,seen:Set<string>,bomVersion?:string|number)=>{
    const released=input.releasedOf?.(materialId)??null
    const planShortage=required===null?null:r6(Math.max(required-(released??0),0))
    const st=input.stockOf(materialId)
    let available:number|null=null,issue:string|undefined
    if(!st||st.onHand===null)issue='库存未返回'
    else if(st.unit&&unit&&st.unit!==unit)issue='单位不一致（需求 '+unit+'，库存 '+st.unit+'），未换算'
    else available=r6(st.onHand-(st.allocated??0))
    if(required===null&&!issue)issue='需求数无法计算'
    const netDemand=planShortage===null||available===null?null:r6(Math.max(planShortage-Math.max(available,0),0))
    const status:KitStatus=netDemand===null?'不能判断':netDemand===0?'齐套':available!==null&&available>0?'部分':'缺料'
    const next=status==='齐套'||status==='不能判断'?null:makeOrBuy==='make'?'安排生产':makeOrBuy==='buy'?'安排采购':'待确认制造方式'
    rows.push({level,depth,materialId,name,unit,makeOrBuy,required:required===null?null:r6(required),released,planShortage,available,netDemand,status,next,issue,bomVersion})
    if(makeOrBuy!=='make'||depth>=maxDepth)return
    if(seen.has(materialId)){rows[rows.length-1].issue='BOM 循环引用，停止展开';return}
    const bom=input.bomOf(materialId)
    if(!bom){rows[rows.length-1].issue=(rows[rows.length-1].issue?rows[rows.length-1].issue+'；':'')+'缺 BOM，不能展开下层';return}
    if(bom.outputUnit&&unit&&bom.outputUnit!==unit){rows[rows.length-1].issue='BOM 产出单位 '+bom.outputUnit+' 与需求单位 '+unit+' 不一致，未展开';return}
    if(!(bom.outputBase>0)){rows[rows.length-1].issue='BOM 产出基数缺失或为 0，未展开';return}
    // 子项需求按父项「净需求」展开：已下达和父项现有可用库存都不再重复要料；父项库存未返回时按计划缺料展开并注明
    const parentNeed=netDemand!==null?netDemand:planShortage
    if(netDemand===null&&planShortage!==null)rows[rows.length-1].issue=(rows[rows.length-1].issue?rows[rows.length-1].issue+'；':'')+'下层按全部计划缺料展开'
    const yieldDiv=bom.yieldIncluded===false&&bom.yieldRate&&bom.yieldRate>0?bom.yieldRate:1
    bom.lines.forEach((l,i)=>walk(l.materialId,l.name,l.unit,parentNeed===null?null:parentNeed*(l.qty/bom.outputBase)/yieldDiv,l.makeOrBuy,level+'.'+(i+1),depth+1,new Set([...seen,materialId]),bom.version))
  }
  walk(input.line.productId,input.line.name,input.line.unit,input.line.qty,input.line.makeOrBuy??'make','1',0,new Set())
  const leaves=rows.filter((r,i)=>!rows[i+1]||rows[i+1].depth<=r.depth)
  const unknownItems=leaves.filter(r=>r.status==='不能判断').length,shortItems=leaves.filter(r=>r.status==='缺料'||r.status==='部分').length
  const root=rows[0]
  // 根行已齐套 → 齐套；有不能判断的叶子 → 不能判断；叶子缺料 → 部分/缺料；叶子都齐但根还要生产 → 料齐待产（不再报「齐套」）
  const status:KitOrderStatus=root.status==='齐套'?'齐套':unknownItems?'不能判断':shortItems?(leaves.some(r=>r.status==='齐套'||r.status==='部分')?'部分':'缺料'):root===leaves[0]&&leaves.length===1?root.status:'料齐待产'
  return {rows,status,shortItems,unknownItems,rule:'需求→已下达→计划缺料→可用库存→净需求；子项按父项净需求×单位用量展开；不同单位不换算'}
}
