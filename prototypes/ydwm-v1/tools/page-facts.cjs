// Display summaries already supplied by native chart/chat controls stay in their own source scope.
function input(question,context={}){
 const page=typeof context.pageContext==='string'?context.pageContext.trim():'',chart=String(question||'').match(/^\[([^\]\n]{1,60})数据\]\s*([^]*?)(?:\n\s*\n|$)/);
 if(!page&&!chart)return null;
 const summary=(page||chart[2]).slice(0,5000);if(!summary)return null;
 return {domain:'table',rows:[{标题:chart?.[1]||'当前页面',页面展示摘要:summary}],scope:{name:'页面展示依据',source:'page-summary',presentation:chart?'chart-bullets':'page',taskNote:'仅核对当前页面提供的展示摘要；不混入本机练习明细，不推断摘要未提供的数量、利润、趋势或日期任务。0保持原值，未提供的指标不补0。'}};
}
module.exports={input};
