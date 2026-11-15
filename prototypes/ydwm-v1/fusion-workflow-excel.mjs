import fs from 'node:fs'
import path from 'node:path'
import XLSX from './tools/vendor/xlsx.full.min.cjs'
import { fusionWorkspace } from './fusion-data.mjs'
import { handleFusionWorkflow } from './fusion-workflow.mjs'

const plain = row => Object.fromEntries(Object.entries(row).map(([key,value]) => [key,value && typeof value === 'object' ? JSON.stringify(value) : value]))

export async function handleFusionWorkflowExcel({parent,role,session,method,target,send,res}) {
  const url = new URL(target,'http://local')
  if(role !== 'factory' || method !== 'GET' || url.pathname !== '/api/mobile/FOOD_0000_001/bom/workflow-export') return false
  const root=parent,w=fusionWorkspace(parent,role,session)
  const captured=JSON.parse(fs.readFileSync(path.join(root,'private/fusion-workflows.json'),'utf8'))
  const statePath=path.join(root,'private/fusion-state',session+'.json')
  const state=fs.existsSync(statePath)?JSON.parse(fs.readFileSync(statePath,'utf8')):{owners:{}}
  const requested=(url.searchParams.get('productTypeIds')||'').split(',').filter(Boolean)
  const owners=requested.length?requested:[...new Set([...captured.map(row=>row.id||row.definition?.productTypeId||row.productTypeId),...Object.keys(state.owners||{})])]
  const products=w.rows(w.models.get('/api/mobile/FOOD_0000_001/product-types')),recipes=w.rows(w.models.get('/api/mobile/FOOD_0000_001/bom/recipes'))
  const sheets={'工艺版本':[],'节点':[],'端口':[],'连线':[],'包材配置':[],'BOM配方':[],'BOM物料':[],'调料配置':[],'原始工艺':[]}
  for(const owner of owners) {
    let reply
    await handleFusionWorkflow({root,role,session,method:'GET',target:'/api/mobile/FOOD_0000_001/product-process-workflows/'+encodeURIComponent(owner),res:{},send:(_,status,body)=>reply={status,body}})
    const d=reply?.body?.data
    if(reply?.status!==200 || !d) {send(res,422,{success:false,message:'所选产品尚未保存工艺，无法导出。',code:'WORKFLOW_NOT_FOUND'});return true}
    const context={产品ID:owner,产品名称:products.find(row=>String(row.id)===String(owner))?.name||null,工艺版本:d.version,修订ID:d.revisionId}
    sheets['工艺版本'].push({...context,状态:d.status,工艺ID:d.id,修订哈希:d.revisionHash})
    for(const node of d.nodes||[]) {
      sheets['节点'].push({...context,...plain(node),...plain(node.data||{})})
      for(const port of node.data?.ports||[]) sheets['端口'].push({...context,节点ID:node.id,...plain(port)})
      for(const binding of node.data?.packagingBindings||[]) sheets['包材配置'].push({...context,节点ID:node.id,...plain(binding)})
    }
    for(const edge of d.edges||[]) sheets['连线'].push({...context,...plain(edge)})
    for(const recipe of recipes.filter(row=>String(row.productTypeId)===String(owner)&&!row.deleted&&!row.deletedAt)) {
      const {items,seasoningItems,...fields}=recipe
      sheets['BOM配方'].push({...context,...plain(fields)})
      for(const item of items||[]) sheets['BOM物料'].push({...context,配方ID:recipe.id,...plain(item)})
      for(const item of seasoningItems||[]) sheets['调料配置'].push({...context,配方ID:recipe.id,...plain(item)})
    }
    // A row per node/edge keeps every original field without Excel's cell size limit.
    const {nodes,edges,...definition}=d
    sheets['原始工艺'].push({...context,记录类型:'定义',...plain(definition)})
    for(const node of nodes||[]) sheets['原始工艺'].push({...context,记录类型:'节点',...plain(node)})
    for(const edge of edges||[]) sheets['原始工艺'].push({...context,记录类型:'连线',...plain(edge)})
  }
  const book=XLSX.utils.book_new()
  for(const [name,rows] of Object.entries(sheets)) XLSX.utils.book_append_sheet(book,rows.length?XLSX.utils.json_to_sheet(rows):XLSX.utils.aoa_to_sheet([['当前所选工艺无此类配置']]),name)
  send(res,200,XLSX.write(book,{type:'buffer',bookType:'xlsx'}),'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',{'Content-Disposition':'attachment; filename="workflow-bom.xlsx"'})
  return true
}
