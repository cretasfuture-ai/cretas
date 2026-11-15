'use strict';
// Modern-only compatibility: filter the full server result before pagination.
exports.patch=(file,text)=>{
 if(text.includes('搜索 订单号 / 供应商')&&text.includes('const r=await ee(t,{params:e})')){
  const from='e={page:x.value.page,size:x.value.size};G.value&&(e.status=G.value)',to='e={page:x.value.page,size:x.value.size,supplyKeyword:Te.value.trim(),startDate:ae.value?.[0]||undefined,endDate:ae.value?.[1]||undefined};G.value&&(e.status=G.value)';
  if(!text.includes(from)&&!text.includes(to))throw Error('Purchase filter source changed; review full-result search patch');text=text.replace(from,to);
  const summary='filterConditions:G.value?{status:G.value}:{}',scoped='filterConditions:{...(G.value?{status:G.value}:{}),...(Te.value.trim()?{supplyKeyword:Te.value.trim()}:{}),...(ae.value?.[0]?{startDate:ae.value[0]}:{}),...(ae.value?.[1]?{endDate:ae.value[1]}:{})}';
  if(!text.includes(summary)&&!text.includes(scoped))throw Error('Purchase summary filter source changed');text=text.replace(summary,scoped);
 }
 if(text.includes('搜索 订单号/客户')&&text.includes('const s=await be(e,{params:i})')){
  const from='i={page:J.value.page,size:t};X.value&&(i.status=X.value)',to='i={page:J.value.page,size:t,supplyKeyword:ft.value.trim()};X.value&&(i.status=X.value)';
  if(!text.includes(from)&&!text.includes(to))throw Error('Sales filter source changed; review full-result search patch');text=text.replace(from,to);
  const summary='sl=F(()=>({filterConditions:X.value?{status:X.value}:{}})),{summary:ia,loading:rl}=vi("salesOrder",sl),ft=_("")',scoped='ft=_(""),sl=F(()=>({filterConditions:{...(X.value?{status:X.value}:{}),...(ft.value.trim()?{supplyKeyword:ft.value.trim()}:{} )}})),{summary:ia,loading:rl}=vi("salesOrder",sl)';
  if(!text.includes(summary)&&!text.includes(scoped))throw Error('Sales summary source changed');text=text.replace(summary,scoped);
 }
 return text;
};
