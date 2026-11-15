/* Modern prototype only. Typed original reads, local-model insights, session-scoped derived cache. */
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {plan,enabled,TENANT}=require('./online-policy.cjs');
const hash=value=>crypto.createHash('sha256').update(JSON.stringify(value)).digest('hex');
const ledger=require('./ledger-facts.cjs'),business=require('./business-facts.cjs');
const dashboard='/api/mobile/'+TENANT+'/smart-bi/dashboard/executive';
function cachePath(root,session,id){if(!/^(?:account|test-[a-z0-9-]{8,64})$/.test(session)||!/^\d{1,16}$/.test(String(id)))throw Error('分析工作区无效。');return path.join(root,'private/report-analysis',session,String(id)+'.json')}
function tableHash(table){return hash({headers:table.headers,data:table.data})}
async function handle({root,identity,req,res,original,method,target,body,w,send}){
 if(original)return false;
 const u=new URL(target,'http://local'),p=u.pathname,cache=p.match(/^\/smartbi-api\/api\/smartbi\/analysis-cache\/(\d+)$/);
 const sheet=method==='POST'&&['/smartbi-api/api/insight/generate','/smartbi-api/api/insight/generate-stream'].includes(p);
 const dash=method==='GET'&&[dashboard+'/insights/custom',dashboard+'/insights/custom/stream',dashboard+'/insights'].includes(p);
 if(!cache&&!sheet&&!dash)return false;
 try{
  enabled(identity,'analytics');
  if(cache){
   const {getFusionUploadTable}=await import('../fusion-upload.mjs');
   const session=w.session||'account',table=getFusionUploadTable(root,session,cache[1]);if(!table)return false;
   const file=cachePath(root,session,cache[1]);
   if(method==='GET'){
    if(!fs.existsSync(file))return false;const saved=JSON.parse(fs.readFileSync(file));
    if(saved.tableHash!==tableHash(table))return false;
    if(ledger.isLedger(table.data)){const f=ledger.facts(table.data);try{const codes=ledger.verify(f,saved.ledgerReview);saved.result.aiAnalysis=ledger.render(f,codes)}catch{return false}}
    if(!ledger.isLedger(table.data)){try{const f=business.facts('table',table.data,{name:'原始报表记录'});saved.result.aiAnalysis=business.render(f,business.verify(f,saved.businessReview))}catch{return false}}
    send(res,200,{...saved.result,success:true,cached:true,cachedAt:saved.at,source:'macmini',model:'qwen3:8b',prototypeLocalOnly:true});return true;
   }
   if(method==='DELETE'){if(fs.existsSync(file))fs.unlinkSync(file);send(res,200,{success:true,cached:false,prototypeLocalOnly:true});return true}
   if(method==='POST'){
    if(body?.factory_id!==TENANT)throw Object.assign(Error('工厂范围不一致。'),{status:403});
    if(JSON.stringify(body).length>1000000)throw Object.assign(Error('分析缓存过大。'),{status:413});
    // Only retain model text that this server actually produced for these upload rows.
    const receipts=w.state.configs['ydwm-report-model-receipts']||[];
    const receipt=[...receipts].reverse().find(r=>r.answer&&String(body.aiAnalysis||'').includes(r.answer)&&r.dataHash===hash(table.data)&&(!ledger.isLedger(table.data)||r.ledgerReview?.version===ledger.VERSION));
    if(receipt&&ledger.isLedger(table.data))ledger.verify(ledger.facts(table.data),receipt.ledgerReview);if(receipt&&!ledger.isLedger(table.data))business.verify(business.facts('table',table.data,{name:'原始报表记录'}),receipt.businessReview);
    if(!receipt){send(res,422,{success:false,message:'未找到匹配当前原始记录的本地模型结果，未保存缓存。'});return true}
    const {localSheetAnalysis}=await import('../fusion-data.mjs');const actual=localSheetAnalysis(table);
    const result={charts:actual.charts,kpiSummary:actual.kpiSummary,aiAnalysis:receipt.answer,chartConfig:actual.chartConfig,rawData:table.data};
    const saved={at:new Date().toISOString(),tableHash:tableHash(table),ledgerReview:receipt.ledgerReview,businessReview:receipt.businessReview,result};fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,JSON.stringify(saved));
    send(res,200,{success:true,cached:true,prototypeLocalOnly:true});return true;
   }
   return false;
  }
  if(req.headers['x-prototype-ai-source']==='local')return false;
  const bridge=require('./online-bridge.cjs');let data,context,range;
  if(dash){
   const query=new URLSearchParams(u.searchParams);if(p===dashboard+'/insights'){
    if(query.get('period')!=='month'||[...query.keys()].some(k=>k!=='period'))throw Object.assign(Error('驾驶舱仅支持本月或明确日期。'),{status:422});
    const now=new Date(),end=now.toLocaleDateString('sv-SE',{timeZone:'Asia/Shanghai'});query.delete('period');query.set('startDate',end.slice(0,7)+'-01');query.set('endDate',end);
   }
   const reportJob=plan(identity,'GET',dashboard+'/custom?'+query);
   const report=await bridge.invoke(root,reportJob);bridge.record(root,report);
   if(report.status!==200||!report.body?.success)throw Error(report.body?.message||'经营报表读取失败。');
   const raw=report.body.data||{};
   data={时间范围:Object.fromEntries(query),经营指标:raw.kpiCards||raw.metricCards||[],排行:raw.rankings||{},图表:raw.chartList||raw.charts||{},数据说明:(raw.aiInsights||[]).map(r=>({类型:r.category,说明:r.message,建议:r.actionSuggestion})),建议:raw.suggestions||[]};
   context={source:'original-account-report',range:Object.fromEntries(query),factoryId:TENANT};range=query;
  }else{
   if(!Array.isArray(body?.data)||!body.data.length||body.data.length>2000||body.data.some(r=>!r||typeof r!=='object'||Array.isArray(r)))throw Object.assign(Error('请选择有效的实际数据记录。'),{status:422});
   // Nested tenant fields are checked by the existing policy before any model transport.
   data=body.data;context={analysisContext:String(body.analysisContext||'').slice(0,12000),source:'current-upload-rows',recordedZeroMeansZero:true,...(ledger.isLedger(body.data)?{ledgerReview:ledger.VERSION}:{})};
   if(data.every(r=>r['领料单']&&r['状态']))context.ledgerFacts=data.map(r=>({单号:r['领料单'],状态:r['状态'],单位:r['单位'],有效需求:r['状态']==='CANCELLED'?0:r['需求数量'],实际耗用:r['实际耗用'],口径:r['状态']==='CANCELLED'?'已取消：不应补发，也不计入待发需求。':'数量0为已记录零值，不能称为缺失。'}));
  }
  const job=plan(identity,'POST','/smartbi-api/api/chat/general-analysis',{query:'解读当前记录，说明数量、单位、状态与缺失项；不要把取消单计入有效需求，不把已发量当作耗用。数值0是已记录零值，不是缺失；只有null或空值才是缺失。不把单日单据先后顺序称为时间趋势。简短列出依据和下一步。',data,context,allow_tenant_data_fallback:false});
  const controller=new AbortController();res.once('close',()=>{if(!res.writableEnded)controller.abort()});
  const result=await bridge.invoke(root,{...job,inferenceSource:'macmini'},{signal:controller.signal});bridge.record(root,result);
  if(result.status!==200||!result.body?.success)throw Error('本地模型未返回有效解读。');let answer=result.body.answer;
  if(sheet&&ledger.isLedger(data)){const f=ledger.facts(data);answer=ledger.render(f,ledger.verify(f,result.body.ledgerReview))}
  if(sheet){const list=w.state.configs['ydwm-report-model-receipts']||[];list.push({at:new Date().toISOString(),dataHash:hash(data),answer,ledgerReview:result.body.ledgerReview,businessReview:result.body.businessReview,source:'macmini',model:'qwen3:8b'});w.state.configs['ydwm-report-model-receipts']=list.slice(-30);w.persist()}
  const source={source:'macmini',model:'qwen3:8b',prototypeLocalOnly:true};
  if(dash){if(p.endsWith('/stream'))send(res,200,[{type:'meta',...source},{type:'delta',text:answer},{type:'done'}].map(e=>'data: '+JSON.stringify(e)+'\n\n').join(''),'text/event-stream; charset=utf-8');else send(res,200,{success:true,data:[{level:'normal',category:'经营解读',message:answer}],...source})}
  else{const response={success:true,insights:[{type:'解读',text:answer,sentiment:'neutral',importance:1}],...source};if(p.endsWith('-stream'))send(res,200,'event: done\ndata: '+JSON.stringify(response)+'\n\n','text/event-stream; charset=utf-8');else send(res,200,response)}
 }catch(e){send(res,e.status||502,{success:false,message:e.message,error:e.message,source:'macmini',cloudFallback:false})}
 return true;
}
module.exports={handle,cachePath,tableHash};
