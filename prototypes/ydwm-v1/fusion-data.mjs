import fs from 'node:fs'
import path from 'node:path'
import {createRequire} from 'node:module'
import {fileURLToPath} from 'node:url'
import {getFusionUploadTable} from './fusion-upload.mjs'
const require=createRequire(import.meta.url),{Workspace}=require('./tools/engine/workspace.cjs'),{hydrate}=require('./tools/request-workspace.cjs'),ROOT=path.dirname(fileURLToPath(import.meta.url))
export function fusionWorkspace(parent,role,session){const cache=globalThis.__YDWM_WORKSPACES||(globalThis.__YDWM_WORKSPACES=new Map()),key=session||'account';if(!cache.has(key))cache.set(key,new Workspace(ROOT,'factory',session==='account'?null:session));return hydrate(cache.get(key))}
export function uploadTable(w,id){return getFusionUploadTable(ROOT,w.session||'account',id)}
export function localSheetAnalysis(table){
  const rows=table.data,headers=table.headers||Object.keys(rows[0]||{}),names=headers.map(h=>typeof h==='string'?h:h.fieldName||h.name||h.label)
  const objects=rows.map(row=>Array.isArray(row)?Object.fromEntries(names.map((name,i)=>[name,row[i]])):row)
  const ledger=require('./tools/ledger-facts.cjs');const ledgerFacts=ledger.isLedger(objects)?ledger.facts(objects):null
  const columns=names.map(name=>{const values=objects.map(row=>row[name]),present=values.filter(v=>v!==null&&v!==undefined&&v!==''),numeric=present.length>0&&present.every(v=>typeof v==='number'||typeof v==='string'&&/^-?\d+(\.\d+)?$/.test(v.trim())),numbers=numeric?present.map(Number):[];return {name,type:numeric?'numeric':'categorical',nullCount:values.length-present.length,uniqueCount:new Set(present.map(String)).size,aggStrategy:numeric&& !/率|比例|单价|人效|平均|评分|%/.test(name)?'sum':numeric?'mean':'none',...(numeric?{sum:numbers.reduce((a,b)=>a+b,0),min:Math.min(...numbers),max:Math.max(...numbers),mean:numbers.reduce((a,b)=>a+b,0)/numbers.length}:{})}})
  for(const c of columns)if(ledgerFacts&&ledger.FIELDS.includes(c.name)){
    const parsed=objects.map(r=>ledger.numeric(r[c.name])),known=parsed.filter(q=>q.text!==undefined);c.type=known.length?'numeric':'categorical';c.aggStrategy=known.length?'sum':'none';c.nullCount=parsed.filter(q=>q.kind==='missing').length;c.invalidCount=parsed.filter(q=>q.kind==='invalid').length;c.sum=known.length?Number(known.map(q=>q.text).reduce(ledger.add,'0')):null;c.precision=Math.max(0,...known.map(q=>(q.text.split('.')[1]||'').length));
    c.displayName=({'需求数量':'原始需求（含取消单）','已发数量':'已发累计（含关单）','实际耗用':'实耗记录合计','退回数量':'退回记录合计'})[c.name]+(c.nullCount||c.invalidCount?'（非完整）':'')
    if(ledgerFacts.groups.length!==1||!ledgerFacts.groups[0].unit){c.aggStrategy='none';c.sum=null}
  }
  const dimension=ledgerFacts?'领料单':columns.find(c=>c.type!=='numeric')?.name,metrics=columns.filter(c=>c.type==='numeric').slice(0,6)
  const chartGroups=ledgerFacts?ledgerFacts.groups.filter(g=>g.unit).map(g=>({unit:g.unit,rows:objects.filter(r=>String(r['单位']||'').trim()===g.unit)})):[{unit:'',rows:objects}]
  const charts=chartGroups.flatMap(group=>metrics.map(metric=>({chartType:'bar',title:(metric.displayName||metric.name)+(group.unit?' · '+group.unit:'')+' · 原始记录',config:{tooltip:{trigger:'axis'},grid:{left:65,right:24,top:24,bottom:70,containLabel:true},xAxis:{type:'category',data:group.rows.map((r,i)=>String(r[dimension]??i+1)),axisLabel:{rotate:group.rows.length>8?35:0}},yAxis:{type:'value',name:metric.name+(group.unit?' ('+group.unit+')':'')},series:[{name:metric.name,type:'bar',data:group.rows.map(r=>ledgerFacts?(ledger.numeric(r[metric.name]).text===undefined?null:Number(ledger.numeric(r[metric.name]).text)):r[metric.name]==null?null:Number(r[metric.name])),itemStyle:{color:'#2d6fa3',borderRadius:[4,4,0,0]}}]}})))
  return {success:true,cached:true,cachedAt:new Date().toISOString(),charts,kpiSummary:{rowCount:rows.length,columnCount:names.length,columns},aiAnalysis:ledgerFacts?ledger.render(ledgerFacts):'### 本机数据摘要\n\n当前表共 '+rows.length+' 行、'+names.length+' 列。图表和指标逐行使用该账号收录的数据计算；缺失值保留为空，零值保留为零。\n\n这是可交互原型中的本地统计演示，未调用在线大模型，不推断未记录的业务事实。',chartConfig:charts[0]?.config,rawData:objects,prototypeLocalOnly:true}
}
