// 第四批① BL13 味美参考价（R16）：价目表价只作参考，不覆盖、不回写。
// 味美价格表没有供应商字段，明细只有「名称＋单位」，所以只能按「物料名称＋单位」精确匹配；有效＝原页面「生效中」（isActive=true）
// 且今天在生效日期与失效日期之间（失效日期空＝长期）。匹配不上写「无有效价目」，不按近似名称猜。
import type {Row} from '../frame/types'
export interface PriceRef{price:number;listId:string;listName:string;from:string;to:string;others:number}
export interface PriceMatch{ref:PriceRef|null;inactive:{listName:string;price:number;reason:string}[]}
const d10=(x:unknown)=>x?String(x).slice(0,10):''
export function matchPurchasePrice(lists:Row[],name:string,unit:string,today:string):PriceMatch{
  const hits:PriceRef[]=[],inactive:PriceMatch['inactive']=[]
  for(const l of lists){if(String(l.priceType)!=='PURCHASE_PRICE')continue
    for(const it of (l.items as Row[])||[]){if(String(it.itemName??'').trim()!==name.trim()||String(it.unit??'').trim()!==unit.trim())continue
      const p=Number(it.standardPrice);if(!Number.isFinite(p))continue
      const from=d10(l.effectiveFrom),to=d10(l.effectiveTo)
      const why=l.isActive!==true?'原页面状态为「未生效」':from&&from>today?'未到生效日期 '+from:to&&to<today?'已过失效日期 '+to:''
      if(why)inactive.push({listName:String(l.name??''),price:p,reason:why});else hits.push({price:p,listId:String(l.id),listName:String(l.name??''),from,to,others:0})}}
  hits.sort((a,b)=>b.from.localeCompare(a.from))
  const ref=hits[0]?{...hits[0],others:hits.length-1}:null
  return {ref,inactive}
}
/** 来源标记：等于参考价＝价目表；有参考价但不同＝手改（算偏离）；没有参考价＝手填 */
export function priceSource(price:number|null,ref:PriceRef|null):{label:'价目表'|'手改'|'手填（无有效价目）';deviation:number|null}{
  if(!ref)return {label:'手填（无有效价目）',deviation:null}
  if(price!=null&&Math.abs(price-ref.price)<0.005)return {label:'价目表',deviation:0}
  return {label:'手改',deviation:price==null||!ref.price?null:(price-ref.price)/ref.price*100}
}
