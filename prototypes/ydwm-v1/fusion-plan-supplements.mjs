import { fusionWorkspace } from './fusion-data.mjs'
const BASE='/api/mobile/FOOD_0000_001/production-plans'
export function handleFusionPlanSupplements({parent,role,session,method,target,send,res}){
  const m=method==='GET'&&role==='factory'&&target.split('?')[0].match(/^\/api\/mobile\/FOOD_0000_001\/production-plans\/([^/]+)\/(document-trace|material-advisory|settlement)$/);if(!m)return false
  const w=fusionWorkspace(parent,role,session),model=w.models.get(BASE),plan=w.row(model,m[1]);if(!plan||model.records.has(String(plan.id)))return false
  const path=BASE+'/'+plan.id+'/'+m[2];let data=w.state.configs[path]
  if(data===undefined&&m[2]==='settlement')data={productionPlanId:plan.id,planNumber:plan.planNumber,settled:false,actualFinishedQuantity:null,postingStatus:'NOT_SETTLED'}
  if(data===undefined&&m[2]==='material-advisory'){
    const materials=w.rows(w.models.get('/api/mobile/FOOD_0000_001/material-batches')),recipes=w.rows(w.models.get('/api/mobile/FOOD_0000_001/bom/recipes')),targets=plan.targetFinishedGoodIds?.length?plan.targetFinishedGoodIds:[plan.productTypeId],warnings=[];let reason=null,calculated=false
    if(!Number(plan.plannedQuantity))reason='本计划按实际领料报工，未设置计划数量，不预估缺料数量。'
    else if(targets.length!==1)reason='多产出计划需逐成品确认目标数量，当前不合并不同产品的计划量。'
    else{const recipe=plan.selectedBomRecipeId?w.row(w.models.get('/api/mobile/FOOD_0000_001/bom/recipes'),plan.selectedBomRecipeId):recipes.find(r=>r.productTypeId===targets[0]&&r.status==='ACTIVE'&&r.isCurrent);if(!recipe)reason='该产品尚无生效配方，请在工艺画布核对用料。';else if(recipe.outputUnit!==plan.plannedUnit||!(Number(recipe.outputQuantityPerUnit)>0))reason='计划单位与配方基准尚不能直接换算，请在工艺画布核对。';else{calculated=true;for(const item of recipe.items||[]){if(!Number.isFinite(Number(item.actualQuantity??item.standardQuantity)))continue;const required=Number(item.actualQuantity??item.standardQuantity)*Number(plan.plannedQuantity)/Number(recipe.outputQuantityPerUnit),batches=materials.filter(b=>b.materialTypeId===item.materialTypeId&&b.unit===item.unit),available=batches.reduce((n,b)=>n+Number(b.remainingQuantity??b.availableQuantity??b.quantity??0),0);if(required>available)warnings.push({materialTypeId:item.materialTypeId,materialName:item.materialName,requiredQuantity:required,availableQuantity:available,shortageQuantity:required-available,unit:item.unit,kind:'FACTORY_SHORTAGE',message:'按当前配方和已收录批次计算，仍需补齐 '+(required-available)+' '+item.unit})}}}
    data={planId:plan.id,planNumber:plan.planNumber,hasWarning:warnings.length>0,warnings,calculationStatus:calculated?'CALCULATED':'NOT_CALCULATED',message:reason||(warnings.length?'请核对以下缺料项':'当前可比较单位的已收录批次足够，开工前仍需核对生产库可用量。')}
  }
  if(data===undefined&&m[2]==='document-trace'){
    const documents=[{documentType:'PRODUCTION_PLAN',documentId:plan.id,documentNumber:plan.planNumber,status:plan.status,direction:'EXECUTION',relation:'当前生产计划',occurredAt:plan.createdAt,details:[{label:'产品',value:plan.productName||plan.productTypeName},{label:'计划数量',value:Number(plan.plannedQuantity)>0?plan.plannedQuantity+' '+plan.plannedUnit:'按实际报工'}]}],batches=w.rows(w.models.get('/api/mobile/FOOD_0000_001/processing/batches')).filter(b=>b.productionPlanId===plan.id),orders=w.models.get('/api/mobile/FOOD_0000_001/sales/orders')
    for(const id of new Set([plan.sourceOrderId,...plan.sourceOrderIds||[]].filter(Boolean))){const o=orders&&w.row(orders,id);if(o)documents.push({documentType:'SALES_ORDER',documentId:o.id,documentNumber:o.orderNumber,status:o.status,direction:'UPSTREAM',relation:'来源销售订单',occurredAt:o.createdAt})}
    for(const b of batches)documents.push({documentType:'PRODUCTION_BATCH',documentId:String(b.id),documentNumber:b.batchNumber,status:b.status,direction:'EXECUTION',relation:'关联生产批次',occurredAt:b.createdAt})
    const settlement=w.state.configs[BASE+'/'+plan.id+'/settlement'];if(settlement)documents.push({documentType:'PRODUCTION_SETTLEMENT',documentId:settlement.settlementId,documentNumber:plan.planNumber,status:settlement.status,direction:'DOWNSTREAM',relation:'生产核对单',occurredAt:settlement.createdAt})
    data={productionPlanId:plan.id,planNumber:plan.planNumber,planStatus:plan.status,documents,missingLinks:!batches.length?['尚未创建生产批次']:!settlement?['尚未提交生产核对单']:[]}
  }
  send(res,200,{success:true,data,prototypeLocalOnly:true});return true
}
