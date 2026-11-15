import fs from 'node:fs'
import path from 'node:path'
export function nativeDraft({ root, role, session, method, target, body, send, res }) {
  if(role!=='factory')return false
  const match=target.match(/^\/api\/mobile\/F006\/product-process-workflows\/([A-Za-z0-9_-]+)(?:\/(draft|versions)(?:\/(\d+))?)?$/)
  if(!match)return false
  const [,owner,action,version]=match,dir=path.join(root,'private/native-drafts'),file=path.join(dir,session+'.json')
  const state=fs.existsSync(file)?JSON.parse(fs.readFileSync(file,'utf8')):{},stored=state[owner]
  const reply=(data,message)=>send(res,200,{success:true,data,message,prototypeLocalOnly:true,source:'independent-local-draft'})
  if(method==='GET'){
    if(!stored)return false
    if(!action){reply(stored,'已读取独立本机画布草稿');return true}
    if(action==='versions'&&!version){reply([{definitionVersion:stored.version,status:'DRAFT',updatedAt:stored.updatedAt,active:false,productionPlans:[],prototypeLocalOnly:true}],'仅展示当前本机草稿；未推断源版本历史');return true}
    if(action==='versions'&&Number(version)===stored.version){reply(stored,'本机草稿版本');return true}
    return false
  }
  if(method!=='PUT'||action!=='draft')return false
  let incoming;try{incoming=JSON.parse(body?.toString()||'{}')}catch{send(res,400,{success:false,message:'画布内容格式错误'});return true}
  if(incoming.factoryId&&incoming.factoryId!=='F006'||incoming.productTypeId&&incoming.productTypeId!==owner||incoming.schemaVersion!==1||!Array.isArray(incoming.nodes)||!Array.isArray(incoming.edges)){send(res,422,{success:false,message:'工厂、产品或画布结构不一致，未保存。'});return true}
  if(stored&&(incoming.lockVersion??0)!==stored.lockVersion){send(res,409,{success:false,message:'本机草稿已被更新，请重新载入后再编辑。',code:'OPTIMISTIC_LOCK_CONFLICT'});return true}
  const ids=new Set(incoming.nodes.map(n=>n.id));if(ids.size!==incoming.nodes.length||incoming.edges.some(e=>!ids.has(e.source)||!ids.has(e.target))){send(res,422,{success:false,message:'画布包含重复节点或无效连接，未保存。'});return true}
  const value={...incoming,id:stored?.id||Date.now(),factoryId:'F006',productTypeId:owner,status:'DRAFT',version:stored?.version||incoming.version||1,lockVersion:(stored?.lockVersion??-1)+1,prototypeLocalOnly:true,updatedAt:new Date().toISOString()}
  state[owner]=value;fs.mkdirSync(dir,{recursive:true});const temporary=file+'.tmp';fs.writeFileSync(temporary,JSON.stringify(state));fs.renameSync(temporary,file)
  const persisted=JSON.parse(fs.readFileSync(file,'utf8'))[owner];if(JSON.stringify(persisted)!==JSON.stringify(value))throw Error('Native draft readback failed')
  reply(persisted,'画布已保存到独立本机草稿并回读确认；未发布到生产。');return true
}
