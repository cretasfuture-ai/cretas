// 交付风险判定（R39）：订单交期 × 计划进度 × 齐套。纯函数，代码判定，不让模型算；原因逐条列出，点得回三个来源。
// 判定口径（写在页面上，和这里一致）：
//   已逾期（交期早于今天、订单还没完成）→ 高
//   齐套 缺料：距交期 ≤7 天 → 高，否则 中；部分：距交期 ≤2 天 → 高，否则 中；不能判断：距交期 ≤3 天 → 中
//   计划进度 已超期 → 高；落后：距交期 ≤2 天 → 高，否则 中；未开始且距交期 ≤2 天 → 高；料齐待产且未开始、距交期 ≤2 天 → 中
//   没有一项能判断（齐套、进度都拿不到）→ 不能判断；其余 → 低
// 不用别的数据顶替：来源没有订单到工单的关联时，计划进度写「不能判断」，不拿同产品工单当这张订单的进度。
import type {KitOrderStatus} from './kitting'
import type {Schedule} from './progress'
export type Risk='高'|'中'|'低'|'不能判断'
export interface RiskIn{due:string;today:string;kit:KitOrderStatus|null;schedule:Schedule|null}
export interface RiskOut{risk:Risk;reasons:string[];daysLeft:number|null}
export const RULE='判定规则：已逾期＝高；缺料且距交期 7 天内＝高，否则中；部分齐套 2 天内＝高，否则中；计划已超期＝高，落后 2 天内＝高，否则中；未开始且 2 天内＝高；齐套和计划进度都拿不到＝不能判断；其余＝低。'
const ORDER:Record<Risk,number>={高:3,中:2,低:1,不能判断:0}
export const riskRank=(r:Risk)=>ORDER[r]
const dayMs=(d:string)=>Date.parse(d.slice(0,10)+'T00:00:00+08:00')
/** 距交期天数：负数＝已逾期 */
export const daysBetween=(due:string,today:string)=>due?Math.round((dayMs(due)-dayMs(today))/864e5):null
export function assess(i:RiskIn):RiskOut{
  const daysLeft=daysBetween(i.due,i.today),reasons:string[]=[]
  let level:Risk='低'
  const up=(r:Risk,why:string)=>{reasons.push(why);if(ORDER[r]>ORDER[level])level=r}
  if(daysLeft===null)return {risk:'不能判断',reasons:['交期未填写'],daysLeft}
  if(daysLeft<0)up('高','已逾期 '+(-daysLeft)+' 天')
  const near=(n:number)=>daysLeft<=n
  if(i.kit==='缺料')up(near(7)?'高':'中','缺料')
  else if(i.kit==='部分')up(near(2)?'高':'中','部分齐套')
  else if(i.kit==='不能判断'&&near(3))up('中','齐套不能判断（缺库存、BOM 或单位不一致）')
  if(i.schedule==='overdue')up('高','计划已超期')
  else if(i.schedule==='behind')up(near(2)?'高':'中','计划落后')
  else if(i.schedule==='not-started'&&near(2))up(i.kit==='料齐待产'?'中':'高','距交期 '+Math.max(daysLeft,0)+' 天，计划还没开始')
  if(!reasons.length){
    if((i.kit===null||i.kit==='不能判断')&&(i.schedule===null||i.schedule==='unknown'))return {risk:'不能判断',reasons:['齐套和计划进度都拿不到'],daysLeft}
    reasons.push('交期、齐套、进度未见风险')
  }
  return {risk:level,reasons,daysLeft}
}
