import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { getFusionUploadTable } from './fusion-upload.mjs'
const require=createRequire(import.meta.url),{Workspace,keyOf}=require('../tools/v3/workspace.cjs')
export const fusionSessions={factory:'test-v5-fusion-factory-20261029',platform:'test-v5-fusion-platform-20261029'}
const workspaceCache=new Map();let sourceCache={mtime:0,responses:{}}
export function fusionWorkspace(parent,role,session=fusionSessions[role]){
  const cacheKey=parent+'|'+role+'|'+session,source=path.join(parent,'samples-v5/private/fusion-responses.json'),mtime=role==='factory'&&fs.existsSync(source)?fs.statSync(source).mtimeMs:0;let entry=workspaceCache.get(cacheKey)
  if(!entry||entry.mtime!==mtime){const w=new Workspace(parent,role,session);if(role==='factory'&&mtime){if(sourceCache.mtime!==mtime)sourceCache={mtime,responses:JSON.parse(fs.readFileSync(source))};Object.assign(w.baseline.responses,sourceCache.responses);w.baseline.responses=Object.fromEntries(Object.entries(w.baseline.responses).map(([key,value])=>[key.startsWith('GET ')?keyOf('GET',key.slice(4)):key,value]));w.build()}entry={w,mtime};workspaceCache.set(cacheKey,entry)}
  const w=entry.w;if(fs.existsSync(w.file))w.state=JSON.parse(fs.readFileSync(w.file));w.syncShared()
  return w
}
export function fusionData(parent,session){
  const data=JSON.parse(fs.readFileSync(path.join(parent,'samples-v5/private/sample-data.json')))
  for(const [role,base,key] of [['factory','/api/mobile/F006/production-plans','plans'],['platform','/api/platform/factories','factories']]){
    const w=fusionWorkspace(parent,role,session||fusionSessions[role]),model=w.models.get(base)
    if(model)data[key]=w.rows(model).map(raw=>({...data[key].find(r=>String(r.id)===String(raw.id)),...raw,raw}))
  }
  data.planSourceTotal=data.plans.length
  return data
}
export function uploadTable(w,id){
  const local=w.session&&getFusionUploadTable(path.join(w.root,'samples-v5'),w.session,id);if(local)return local
  const prefix='GET /api/mobile/F006/smart-bi/uploads/'+id+'/data?',keys=Object.keys(w.baseline.responses).filter(k=>k.startsWith(prefix)),params=k=>new URL(k.slice(4),'http://local').searchParams
  const size=Math.max(0,...keys.map(k=>Number(params(k).get('size'))||0))
  const pages=keys.filter(k=>Number(params(k).get('size'))===size).sort((a,b)=>Number(params(a).get('page'))-Number(params(b).get('page'))).map(k=>w.baseline.responses[k]).filter(r=>r.status===200&&Array.isArray(r.body?.data?.data))
  return pages.length?{...pages[0].body.data,data:pages.flatMap(r=>r.body.data.data)}:null
}
export function localSheetAnalysis(table){
  const rows=table.data,headers=table.headers||Object.keys(rows[0]||{}),names=headers.map(h=>typeof h==='string'?h:h.fieldName||h.name||h.label)
  const objects=rows.map(row=>Array.isArray(row)?Object.fromEntries(names.map((name,i)=>[name,row[i]])):row)
  const columns=names.map(name=>{const values=objects.map(row=>row[name]),present=values.filter(v=>v!==null&&v!==undefined&&v!==''),numeric=present.length>0&&present.every(v=>typeof v==='number'||typeof v==='string'&&/^-?\d+(\.\d+)?$/.test(v.trim())),numbers=numeric?present.map(Number):[];return {name,type:numeric?'numeric':'categorical',nullCount:values.length-present.length,uniqueCount:new Set(present.map(String)).size,aggStrategy:numeric&& !/率|比例|单价|人效|平均|评分|%/.test(name)?'sum':numeric?'mean':'none',...(numeric?{sum:numbers.reduce((a,b)=>a+b,0),min:Math.min(...numbers),max:Math.max(...numbers),mean:numbers.reduce((a,b)=>a+b,0)/numbers.length}:{})}})
  const dimension=columns.find(c=>c.type!=='numeric')?.name,metrics=columns.filter(c=>c.type==='numeric').slice(0,6),categories=dimension?objects.map(r=>String(r[dimension]??'未提供')):objects.map((_,i)=>String(i+1))
  const charts=metrics.map(metric=>({chartType:'bar',title:metric.name+' · 原始记录',config:{tooltip:{trigger:'axis'},grid:{left:65,right:24,top:24,bottom:70,containLabel:true},xAxis:{type:'category',data:categories,axisLabel:{rotate:categories.length>8?35:0}},yAxis:{type:'value',name:metric.name},series:[{name:metric.name,type:'bar',data:objects.map(r=>r[metric.name]==null?null:Number(r[metric.name])),itemStyle:{color:'#2d6fa3',borderRadius:[4,4,0,0]}}]}}))
  return {success:true,cached:true,cachedAt:new Date().toISOString(),charts,kpiSummary:{rowCount:rows.length,columnCount:names.length,columns},aiAnalysis:'### 本机数据摘要\n\n当前表共 '+rows.length+' 行、'+names.length+' 列。图表和指标逐行使用该账号收录的数据计算；缺失值保留为空，零值保留为零。\n\n这是可交互原型中的本地统计演示，未调用在线大模型，不推断未记录的业务事实。',chartConfig:charts[0]?.config,rawData:objects,prototypeLocalOnly:true}
}
