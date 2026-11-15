import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { nativeDraft } from './native-drafts.mjs'
import { handleFusionWorkflow } from './fusion-workflow.mjs'
import { fusionWorkspace, uploadTable, localSheetAnalysis } from './fusion-data.mjs'
import { handleFusionSmartBI } from './fusion-smartbi.mjs'
import { handleFusionLocalEdit } from './fusion-local-edits.mjs'
import { handleFusionUpload } from './fusion-upload.mjs'
import { handleFusionBom } from './fusion-bom.mjs'
import { handleFusionProduction } from './fusion-production.mjs'
import { handleFusionProcessSheet } from './fusion-process-sheet.mjs'
import { handleFusionProcessLibrary } from './fusion-process-library.mjs'
import { handleFusionPlanExcel } from './fusion-plan-excel.mjs'
import { handleFusionPlanSupplements } from './fusion-plan-supplements.mjs'
import { handleFusionWorkflowExcel } from './fusion-workflow-excel.mjs'
const require = createRequire(import.meta.url)
const { Workspace } = require('../tools/v3/workspace.cjs')
const { readDomain } = require('../tools/v3/domain-contracts.cjs')
const sessions = { factory: 'test-v5-fusion-factory-20261029', platform: 'test-v5-fusion-platform-20261029' }
const workspaces = new Map()
function embeddedBootstrap(role) {
  return `<script>(()=>{const role=${JSON.stringify(role)},prefix='/__v5/business/'+role;window.__V5_BUSINESS_ROLE__=role;const rewrite=input=>{try{const u=new URL(input,location.href);if(u.origin===location.origin&&/^\\/(?:api\\/|smartbi-api\\/|__prototype\\/)/.test(u.pathname))return prefix+u.pathname+u.search;return input}catch{return input}};const rawFetch=window.fetch.bind(window);window.fetch=(input,init)=>input instanceof Request?rawFetch(new Request(rewrite(input.url),input),init):rawFetch(rewrite(String(input)),init);const rawOpen=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(method,url,...args){return rawOpen.call(this,method,rewrite(String(url)),...args)};for(const method of ['pushState','replaceState']){const original=history[method].bind(history);history[method]=(state,title,url)=>{if(url){const u=new URL(url,location.href);if(u.origin===location.origin&&!u.pathname.startsWith('/__v5/')){u.searchParams.set('v5business',role);url=u.pathname+u.search+u.hash}}return original(state,title,url)}};window.addEventListener('error',event=>{if(event.target?.tagName==='SCRIPT')parent.postMessage({type:'cretas-business-load-error'},location.origin)},true);})();</script>`
}
export function createNativeBusiness({ parent, access, send, audit, persist }) {
  return async function handleBusiness(req,res,url) {
    const matched=url.pathname.match(/^\/__v5\/business\/(factory|platform)(\/.*)$/),queryRole=url.searchParams.get('v5business')
    if(!matched&&!['factory','platform'].includes(queryRole))return false
    const role=matched?.[1]||queryRole,p=matched?.[2]||url.pathname,method=req.method||'GET'
    const requestedSession=req.headers['x-v5-business-session'],session=typeof requestedSession==='string'&&/^test-v5-[a-z0-9-]{8,54}$/.test(requestedSession)?requestedSession:sessions[role]
    if(p.includes('..')||p.includes('\\')||p.includes('%')||p.includes('//')){send(res,400,{success:false,message:'无效路径'});return true}
    const api=/^\/(?:api\/|smartbi-api\/)/.test(p),prototype=p.startsWith('/__prototype/')
    if(!api&&!prototype&&matched){send(res,404,{success:false,message:'业务代理仅提供本地接口'});return true}
    if(api&&role==='factory'&&!(p.startsWith('/api/mobile/F006/')||p.startsWith('/api/mobile/auth/')||p.startsWith('/smartbi-api/')||method==='GET'&&p==='/api/admin/role-permissions')){send(res,403,{success:false,message:'工厂工作区只允许F006业务'});return true}
    if(api&&role==='platform'&&!(p.startsWith('/api/platform/')||p.startsWith('/api/admin/')||p.startsWith('/api/mobile/auth/'))){send(res,403,{success:false,message:'平台工作区不办理工厂业务，请明确切换工厂账号。',code:'PLATFORM_SCOPE_ONLY'});return true}
    if(role==='factory'&&method==='GET'&&p==='/api/admin/role-permissions'){
      const records=JSON.parse(fs.readFileSync(path.join(parent,'samples-v5/private/fusion-responses.json'))),record=records['GET '+p]
      if(record){send(res,record.status,record.body,record.type);return true}
    }
    if(prototype&&/source-photo|media/.test(p)){send(res,404,{success:false,message:'此业务样板未开放来源图片读取'});return true}
    const analysisCache=p.match(/^\/smartbi-api\/api\/smartbi\/analysis-cache\/(\d+)$/)
    if(role==='factory'&&method==='GET'&&/^\/smartbi-api\/api\/smartbi\/analytics\/cached\/\d+$/.test(p)){send(res,200,{success:false,cached:false,data:null,message:'本机尚未保存该表的模板分析缓存'});return true}
    if(role==='factory'&&method==='GET'&&analysisCache){const table=uploadTable(fusionWorkspace(parent,role,session),analysisCache[1]);send(res,200,table?localSheetAnalysis(table):{success:false,cached:false,message:'未收录该表的数据'});return true}
    if(prototype&&!/^\/__prototype\/(?:bootstrap\.js|[a-z0-9-]+\.(?:js|css)|status|health|workspace|undo|analyst|files\/[^/]+|upload\/[^/]+)$/.test(p)){send(res,403,{success:false,message:'此入口未开放原型管理操作'});return true}
    if(api&&/\/photos\/[^/]+\/image$|\/attachments\/[^/]+\/download$/.test(p)){send(res,404,{success:false,message:'附件或图片未开放'});return true}
    if(method==='GET'&&await handleFusionWorkflowExcel({parent,role,session,method,target:p+url.search,send,res}))return true
    if(method==='GET'&&await handleFusionPlanExcel({parent,role,session,method,target:p+url.search,headers:req.headers,send,res}))return true
    if(method==='GET'&&await handleFusionUpload({parent,root:path.join(parent,'samples-v5'),role,session,method,target:p+url.search,headers:req.headers,send,res}))return true
    if(method==='GET'&&await handleFusionBom({parent,root:path.join(parent,'samples-v5'),role,session,method,target:p+url.search,send,res}))return true
    if(method==='GET'&&handleFusionProcessLibrary({parent,role,session,method,target:p+url.search,send,res}))return true
    if(method==='GET'&&await handleFusionProcessSheet({parent,root:path.join(parent,'samples-v5'),role,session,method,target:p+url.search,send,res}))return true
    if(method==='GET'&&handleFusionPlanSupplements({parent,role,session,method,target:p+url.search,send,res}))return true
    if(method==='GET'&&handleFusionProduction({parent,role,session,method,target:p+url.search,headers:req.headers,send,res}))return true
    if(method==='GET'&&await handleFusionWorkflow({root:path.join(parent,'samples-v5'),role,session,method,target:p+url.search,send,res}))return true
    if(method==='GET'&&nativeDraft({root:path.join(parent,'samples-v5'),role,session,method,target:p,send,res}))return true
    if(api&&method==='GET'&&!p.startsWith('/api/mobile/auth/')&&!/\/(?:export|export-excel|download-template|template)(?:\/|$)/.test(p)){
      let workspace=workspaces.get(role+session);if(!workspace){workspace=fusionWorkspace(parent,role,session);workspaces.set(role+session,workspace)}workspace.refreshSource();workspace.syncShared()
      if(fs.existsSync(workspace.file)){workspace.state=JSON.parse(fs.readFileSync(workspace.file));workspace.syncShared()}
      const materialStatus=p.match(/^\/api\/mobile\/F006\/material-batches\/status\/([A-Z_]+)$/)
      if(materialStatus){const model=workspace.models.get('/api/mobile/F006/material-batches'),rows=workspace.filtered(workspace.rows(model).filter(row=>row.status===materialStatus[1]),p+url.search),size=Math.max(1,Number(url.searchParams.get('size'))||200),page=Math.max(0,Number(url.searchParams.get('page')||1)-1);send(res,200,{success:true,data:{content:rows.slice(page*size,(page+1)*size),totalElements:rows.length,totalPages:Math.ceil(rows.length/size),size,number:page},prototypeLocalOnly:true});return true}
      const bom=p.match(/^\/api\/mobile\/F006\/bom\/recipes\/by-product\/([^/]+)\/(current|versions)$/)
      if(bom){const model=workspace.models.get('/api/mobile/F006/bom/recipes'),rows=workspace.rows(model).filter(r=>String(r.productTypeId)===bom[1]&&!r.deleted&&!r.deletedAt).sort((a,b)=>Number(b.version)-Number(a.version));const current=rows.find(r=>r.status==='ACTIVE'&&r.isCurrent===true);send(res,200,{success:true,data:bom[2]==='versions'?rows:current||null,message:'按完整账号BOM目录读取',prototypeLocalOnly:true});return true}
      const upload=p.match(/^\/api\/mobile\/F006\/smart-bi\/uploads\/(\d+)\/data$/)
      if(upload){const keys=Object.keys(workspace.baseline.responses).filter(k=>k.startsWith('GET '+p+'?')),maximumSize=Math.max(...keys.map(k=>Number(new URL(k.slice(4),'http://local').searchParams.get('size'))||0)),all=keys.filter(k=>Number(new URL(k.slice(4),'http://local').searchParams.get('size'))===maximumSize).sort((a,b)=>Number(new URL(a.slice(4),'http://local').searchParams.get('page'))-Number(new URL(b.slice(4),'http://local').searchParams.get('page'))).map(k=>workspace.baseline.responses[k]).filter(r=>r.status===200&&r.body?.data?.data),rows=all.flatMap(r=>r.body.data.data),template=all[0]?.body?.data;if(template){const size=Math.max(1,Number(url.searchParams.get('size'))||50),page=Math.max(0,Number(url.searchParams.get('page'))||0);send(res,200,{success:true,data:{...template,data:rows.slice(page*size,(page+1)*size),total:rows.length,page,size,totalPages:Math.ceil(rows.length/size)},prototypeLocalOnly:true});return true}}
      const target=p+url.search,result=readDomain(workspace,target)||workspace.read('GET',target,null)
      if(!result){send(res,404,{success:false,message:'当前账号快照未收录此读取；未访问生产。',code:'OFFLINE_NOT_CAPTURED'});return true}
      if(result.body){audit.originalSnapshotReads++;send(res,result.status||200,result.body,result.type||'application/json; charset=utf-8');return true}
      if(result.success!==undefined){audit.originalSnapshotReads++;send(res,200,result);return true}
    }
    const headers={cookie:'cretas_prototype_access='+access.ticket,'x-prototype-test-session':session}
    if(req.headers['content-type'])headers['content-type']=req.headers['content-type']
    if(req.headers['x-prototype-user-action']==='local-edit')headers['x-prototype-user-action']='local-edit'
    let body;if(!['GET','HEAD'].includes(method)){const chunks=[];let size=0;for await(const chunk of req){size+=chunk.length;if(size>51*1024*1024){send(res,413,{success:false,message:'样板文件上传上限为50MB'});return true}chunks.push(chunk)}body=Buffer.concat(chunks)}
    if(role==='factory'&&method==='POST'&&p==='/api/mobile/F006/ai-intents/execute'){
      let request;try{request=JSON.parse(body.toString())}catch{}
      const query=String(request?.userInput||'')
      if(/计划|排产/.test(query)&&!/创建|新增|删除|修改|改为|设置|取消|执行/.test(query)){
        const w=fusionWorkspace(parent,role,session),model=w.models.get('/api/mobile/F006/production-plans'),all=model?w.rows(model):[],rows=/本月|这个月/.test(query)?all.filter(r=>String(r.plannedDate||r.createdAt||'').startsWith(new Date().toISOString().slice(0,7))):all,counts={};for(const row of rows)counts[row.status||'未标记']=(counts[row.status||'未标记']||0)+1
        const text='当前 F006 本机工作区共有 '+rows.length+' 张'+(/本月|这个月/.test(query)?'本月':'已收录')+'生产计划。\n\n'+Object.entries(counts).map(([status,n])=>status+'：'+n+' 张').join('；')+'。\n\n读取的是生产计划列表，保留不同数量单位；本机统计演示，未调用在线大模型。',data={intentRecognized:true,intentCode:'LOCAL_PRODUCTION_PLAN_ANALYSIS',intentName:'生产计划统计',status:'SUCCESS',message:text,formattedText:text,aiMode:'READ',resultData:{data:{answer:text,summary:{recordCount:rows.length},rows:rows.slice(0,20),sources:model?[...model.sourceKeys]:[],source:'account-snapshot'}}};send(res,200,{success:true,data,prototypeLocalOnly:true});return true
      }
      if(request?.context?.uploadId&&!/创建|新增|删除|修改|改为|设置|取消|执行/.test(query)){
        const table=uploadTable(fusionWorkspace(parent,role,session),request.context.uploadId),analysis=table&&localSheetAnalysis(table),column=analysis?.kpiSummary.columns.find(c=>c.type==='numeric'&&query.includes(c.name))
        if(analysis&&(/这.*表|文件|Excel|excel|数据源|所选|分析/.test(query)||column)){
          const detail=column?'\n\n'+column.name+'：有效记录 '+(table.data.length-column.nullCount)+' 行，合计 '+column.sum+'，平均 '+column.mean+'，最小 '+column.min+'，最大 '+column.max+'。':'',text=analysis.aiAnalysis+detail,data={intentRecognized:true,intentCode:'LOCAL_UPLOAD_ANALYSIS',intentName:'所选数据表分析',status:'SUCCESS',aiMode:'READ',message:text,formattedText:text,resultData:{data:{answer:text,summary:{recordCount:table.data.length},rows:table.data.slice(0,20),charts:analysis.charts.map(c=>({type:c.chartType,title:c.title,option:c.config})),source:'local-statistics',sources:['F006 / uploads / '+request.context.uploadId]}}};send(res,200,{success:true,data,prototypeLocalOnly:true});return true
        }
      }
    }
    if(await handleFusionPlanExcel({parent,role,session,method,target:p+url.search,body,headers:req.headers,send,res}))return true
    if(await handleFusionUpload({parent,root:path.join(parent,'samples-v5'),role,session,method,target:p+url.search,body,headers:req.headers,send,res}))return true
    if(await handleFusionBom({parent,root:path.join(parent,'samples-v5'),role,session,method,target:p+url.search,body,send,res}))return true
    if(handleFusionProcessLibrary({parent,role,session,method,target:p+url.search,body,send,res}))return true
    if(await handleFusionProcessSheet({parent,root:path.join(parent,'samples-v5'),role,session,method,target:p+url.search,body,headers:req.headers,send,res}))return true
    if(handleFusionProduction({parent,role,session,method,target:p+url.search,body,headers:req.headers,send,res}))return true
    if(handleFusionLocalEdit({parent,role,session,method,target:p+url.search,body,send,res}))return true
    if(handleFusionSmartBI({parent,role,session,method,target:p+url.search,body,send,res}))return true
    if(await handleFusionWorkflow({root:path.join(parent,'samples-v5'),role,session,method,target:p+url.search,body,send,res}))return true
    if(nativeDraft({root:path.join(parent,'samples-v5'),role,session,method,target:p,body,send,res}))return true
    if(api&&!['GET','HEAD'].includes(method)&&!req.headers['content-type']?.includes('multipart/form-data')){
      let input;try{input=JSON.parse(body?.toString()||'{}')}catch{send(res,400,{success:false,message:'无效请求体'});return true}
      const w=fusionWorkspace(parent,role,session),{Analyst,formatAnalysis,stream}=require('../tools/v3/analyst.cjs')
      const summary=p.match(/^\/api\/mobile\/F006\/list-summary\/([^/]+)$/)
      if(summary){const aliases={productionPlan:'/production-plans',salesOrder:'/sales/orders',purchaseOrder:'/purchase/orders',inventory:'/material-batches',attendance:'/attendance/records',qualityInspection:'/quality-inspections',internalTransfer:'/transfers'},suffix=aliases[summary[1]],model=suffix&&[...w.models.values()].find(m=>m.base.endsWith(suffix));if(model){const params=new URLSearchParams(Object.entries(input.filterConditions||{}).filter(([,v])=>v!=null).map(([k,v])=>[k,String(v)])),rows=w.filtered(w.rows(model),model.base+'?'+params),units=new Map();for(const row of rows){const unit=row.plannedUnit||row.unit||row.quantityUnit;if(!unit)continue;const qty=row.plannedQuantity??row.quantity;if(typeof qty==='number')units.set(unit,(units.get(unit)||0)+qty)}send(res,200,{success:true,data:{entityType:summary[1],stats:[{label:'当前筛选',value:rows.length,unit:'条',format:'number'},...Array.from(units,([unit,value])=>({label:summary[1]==='productionPlan'?'计划数量':'数量',value,unit,format:'number'}))]},prototypeLocalOnly:true});return true}}
      if(/\/(?:client-errors|activity-events|feedback)$/.test(p)){send(res,200,{success:true,data:null,prototypeLocalOnly:true});return true}
      if(p.endsWith('/ai-intents/execute')){send(res,200,new Analyst(w).execute(input,req.headers));return true}
      if(p.endsWith('/ai-intents/confirm')){const result=new Analyst(w).confirm(input,req.headers);send(res,result.success?200:409,result);return true}
      const queried=require('../tools/v3/query-contracts.cjs').queryContract(w,p,input)||require('../tools/v3/analytical-contracts.cjs').reply(w,method,p+url.search,input)||require('../tools/v3/embedded-ai.cjs').embeddedAI(w,method,p+url.search,input,req.headers)
      if(queried){send(res,queried.success===false?422:200,queried);return true}
      if(/general-analysis-stream|comprehensive-stream|chat.*stream|analysis.*stream/.test(p)){await stream(res,formatAnalysis(w,String(input.query??input.question??input.message??''),input.context));return true}
      if(req.headers['x-prototype-user-action']!=='local-edit'){send(res,422,{success:false,message:'请在页面中明确操作后保存',code:'LOCAL_ACTION_REQUIRED'});return true}
      const result=require('../tools/v3/configs.cjs').configMutation(w,method,p+url.search,input)||w.transaction(method,p+url.search,input)
      send(res,result.success?200:422,result);return true
    }
    const upstream=await fetch('http://127.0.0.1:'+(role==='factory'?18842:18841)+p+url.search,{method,headers,body,redirect:'manual'})
    audit.localOriginalFetches++;audit.nativeBusinessRequests=(audit.nativeBusinessRequests||0)+1;if(!['GET','HEAD'].includes(method))audit.nativeBusinessLocalWrites=(audit.nativeBusinessLocalWrites||0)+1;persist()
    const type=upstream.headers.get('content-type')||'application/octet-stream';let bytes=Buffer.from(await upstream.arrayBuffer())
    if(type.includes('text/html')){
      const html=bytes.toString().replace(/<script src="\/__prototype\/bootstrap\.js"><\/script>/g,'').replace(/<link rel="stylesheet" href="\/__prototype\/modern\.css">/g,'').replace(/<link[^>]+(?:rel="preconnect"|rel="dns-prefetch")[^>]*>/g,'').replace('</head>',embeddedBootstrap(role)+'<script src="/__v5/business/'+role+'/__prototype/bootstrap.js"></script><link rel="stylesheet" href="/__v5/business-theme.css"></head>')
      bytes=Buffer.from(html)
    }
    const extra={};if(upstream.headers.has('content-disposition'))extra['Content-Disposition']=upstream.headers.get('content-disposition')
    send(res,upstream.status,bytes,type,extra);return true
  }
}
