/* Serve modern-only compatibility fixes; never rewrite retained native assets. */
exports.patch=(file,text)=>{
 file=require('node:path').basename(file);
 if(/^(Purchaser|SalesOwner|ProductionManager|WarehouseKeeper)Workdesk-[\w-]+\.js$/.test(file)){
  text=text.replace(/AI 正在[^"\n]*预计[^"\n]*\.\.\./g,'正在核对当前记录、状态与数量口径…');
  if(file.startsWith('SalesOwnerWorkdesk-'))text=text.replaceAll('销售总额 (累计)','订单记录金额（含取消）').replaceAll('平均订单金额 (B2B)','记录均额（含取消）').replaceAll('全部订单金额累计','含取消单的样本记录金额，非净销售或收入').replaceAll('销售订单数量','样本单据数（含取消）');
  const raw=text.match(/([\w$]+)\.value=[\w$]+\.formattedText\|\|/),target=/class:"formatted-output",innerHTML:[\w$]+\.value/;
  if(!raw)throw Error('Workdesk answer source signature changed.');
  const start=/if\(([\w$]+)\.value\.trim\(\)\)\{([\w$]+)\.value=!0,([\w$]+)\.value=""/;
  if(!start.test(text))throw Error('Workdesk query start signature changed.');
  text=text.replace(start,match=>match+','+raw[1]+'.value=""');
  if(target.test(text))text=text.replace(target,'class:"formatted-output cs-ai-answer",innerHTML:window.CretasUI.renderAnswer('+raw[1]+'.value)');
  else{
   const props=text.match(/([\w$]+)=\{class:"formatted-output"\}/),escape=s=>s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
   const node=props&&new RegExp('([\\w$]+)\\("div",'+escape(props[1])+',[\\w$]+\\('+escape(raw[1])+ '\\.value\\),1\\)');
   if(!node||!node.test(text))throw Error('Workdesk plain answer signature changed.');
   text=text.replace(node,(_,h)=>h+'("div",{class:"formatted-output cs-ai-answer",innerHTML:window.CretasUI.renderAnswer('+raw[1]+'.value)},null,8,["innerHTML"])');
  }
 }
 if(file==='python-service-0H4qHSYD.js'){
  const before='const i=n.slice(6).replace(/\\\\n/g,`\n`);';
  if(!text.includes(before))throw Error('Insight SSE parser signature changed.');
  text=text.replace(before,'const i=c==="done"?n.slice(6):n.slice(6).replace(/\\\\n/g,`\n`);');
 }
 if(['analysis-CBZGrGu8.js','analysis-hlZC-c6R.js'].includes(file)){
  const before='function Kt(t,s,f,l,o){';if(!text.includes(before))throw Error('Ledger grouping signature changed.');
  // Repeated dates are normal in a record ledger, not evidence of multiple subtables.
  text=text.replace(before,before+'if(t.length&&t.every(r=>r["领料单"]&&r["状态"]))return s;');
  const label='function Dt(t,s,f,l){';if(!text.includes(label))throw Error('Ledger label signature changed.');
  text=text.replace(label,label+'if(t.length&&t.every(r=>r["领料单"]&&r["状态"]))return "领料单";');
  const title='const E=f?.[r.name]||U(r.name);';if(!text.includes(title))throw Error('KPI label signature changed.');
  text=text.replace(title,'const E=r.displayName||f?.[r.name]||U(r.name);').replace('precision:g?2:0','precision:g?2:(r.precision??0)');
 }
 if(file==='DashboardBuilder.vue_vue_type_style_index_0_scoped_727c134c_lang-BJ-_DXV5.js')text=text.replaceAll('AI 分析结论','数据解读').replaceAll('积极发现','记录解读');
 if(['SmartBIAnalysis-DtYhe2BI.js','SmartBIAnalysis-DtcTafJm.js'].includes(file))text=text.replaceAll('积极发现','记录解读').replaceAll('smartbi-demo-cache-v','smartbi-business-truth-v1-${window.CretasWorkspace?.session||"account"}-v');
 return text;
};
