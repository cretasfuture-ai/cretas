import {
  assertLiveAdmin, approveWorkflow, attachDemoReceipt, demoTransport, record, rows, scoped,
  type DemoRecord, type DemoTransport,
} from './f006DemoSupport'
import { requireSandboxReady, sandboxSession, SANDBOX_FLOW_STORAGE_KEY } from './f006Sandbox'
export type { DemoTransport } from './f006DemoSupport'
export const FACTORY_ID = 'F006'
export const DEMO_RUN_KEY = 'DEMO-F006-FLOW-20261105'
export const DEMO_STORAGE_KEY = SANDBOX_FLOW_STORAGE_KEY
export type DemoEntity = DemoRecord & { id?: string | number; factoryId?: string }
export interface DemoSession { factoryId?: string | null; user?: { factoryId?: string | null } | null; username?: string | null }
export type DemoStepId = 'purchase-create' | 'sales-order-create' | 'purchase-submit' | 'purchase-approve' | 'purchase-receive' | 'purchase-receive-confirm' | 'sales-order-submit' | 'sales-order-approve' | 'production-plan-create' | 'production-batch-create' | 'material-requisition' | `process-report-${1|2|3|4|5|6}` | 'production-settle' | 'finished-goods-receive' | 'sales-delivery-create' | 'sales-shipment-create' | 'sales-batch-allocate' | 'sales-warehouse-confirm' | 'traceability-read'
export interface DemoField { name: string; label: string; required?: boolean; value?: unknown; options?: unknown[] }
interface DemoProcess { name: string; code: string; order: number; input: DemoRecord; output: DemoRecord }
export interface DemoContext {
  supplier: DemoRecord; customer: DemoRecord; material: DemoRecord; packaging: DemoRecord; product: DemoRecord
  rawWarehouse: DemoRecord; workshopWarehouse: DemoRecord; processes: DemoProcess[]
  rawQuantity: number; finishedQuantity: number
}
export interface DemoState {
  schemaVersion: 2; sandboxSessionId?: string; runKey: string; factoryId: 'F006'; currentStep: number
  entities: Record<string, DemoEntity>; completed: DemoStepId[]; readbacks: Record<string, DemoEntity>
  context?: DemoContext
}
const field = (name: string, label: string): DemoField => ({ name, label, required: true })
const step = (id: DemoStepId, label: string, entity: string, fields: DemoField[] = []) => ({ id, label, entity, fields })
export const DEMO_STEPS = [
  step('purchase-create', '创建采购订单', 'purchaseOrder', [field('supplierId','供应商'),field('materialTypeId','原料'),field('quantity','原料数量'),field('unit','原料单位'),field('unitPrice','原料未税单价'),field('orderDate','下单日期')]),
  step('sales-order-create','建立销售需求并关联采购','salesOrder',[field('customerId','客户'),field('productTypeId','成品'),field('quantity','成品数量'),field('unit','成品单位'),field('unitPrice','销售未税单价'),field('requiredDeliveryDate','交货日期')]),
  step('purchase-submit','提交采购审批','purchaseOrder'), step('purchase-approve','演示采购审批','purchaseOrder'),
  step('purchase-receive','创建仓库收货单','purchaseReceipt',[field('receiveDate','收货日期'),field('warehouseId','入库仓库')]),
  step('purchase-receive-confirm','确认原料与包材入库','purchaseReceipt'),
  step('sales-order-submit','提交销售订单审批','salesOrder'),step('sales-order-approve','完成销售订单审批','salesOrder'),
  step('production-plan-create','按销售需求排产','productionPlan',[field('plannedDate','计划日期'),field('batchDate','生产日期')]),
  step('production-batch-create','开始生产并创建批次','productionBatch'),step('material-requisition','领料并送达生产仓','materialRequisition'),
  ...['原料解冻修整','称量腌制','熟制杀菌','快速冷却','定量包装','金检贴标'].map((name,i)=>step(`process-report-${i+1}` as DemoStepId,`工序${i+1} · ${name}`,`processReport${i+1}`,[field('inputQuantity','投入数量'),field('inputUnit','投入单位'),field('outputQuantity','产出数量'),field('outputUnit','产出单位'),field('workerCount','操作人数'),field('workMinutes','操作分钟')])),
  step('production-settle','完工结算与成本核算','productionSettlement'),step('finished-goods-receive','确认成品入库','productionWarehouseReceipt'),
  step('sales-delivery-create','创建销售发货安排','salesDelivery'),step('sales-shipment-create','生成本次发运单','salesShipment',[field('logisticsCompany','物流公司'),field('trackingNumber','物流单号'),field('deliveryAddress','收货地址')]),
  step('sales-batch-allocate','分配本次成品批次','salesBatchAllocation'),step('sales-warehouse-confirm','仓库确认出库','salesShipment'),step('traceability-read','从销售单反向溯源','traceability'),
] as const
export function createDemoState(runKey = `${DEMO_RUN_KEY}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,7)}`): DemoState {
  if (!runKey.startsWith(DEMO_RUN_KEY)) throw new Error('演示标识不正确')
  return { schemaVersion:2,sandboxSessionId:sandboxSession.value?.sessionId,runKey,factoryId:FACTORY_ID,currentStep:0,entities:{},completed:[],readbacks:{} }
}
export function assertF006Session(session: DemoSession) {
  if ((session.factoryId || session.user?.factoryId) !== FACTORY_ID) throw new Error('流程演示仅允许 F006 身份')
}
function checkpoint(state: DemoState) {
  if (typeof sessionStorage !== 'undefined') sessionStorage.setItem(DEMO_STORAGE_KEY,JSON.stringify(state))
}
function ctx(state: DemoState) { if (!state.context) throw new Error('演示资料尚未加载完成'); return state.context }
function id(r: DemoRecord) { const v=r.id; if (v===undefined || v===null || v==='') throw new Error('业务记录缺少编号'); return String(v) }
function ent(s: DemoState,key:string) { const r=s.entities[key]; if(!r)throw new Error(`请先完成${key}步骤`);return r }
function str(r:DemoRecord,k:string){return String(r[k]??'')}
function positive(v:unknown,label:string){const n=Number(v);if(!Number.isFinite(n)||n<=0)throw new Error(`${label}必须大于零`);return n}
function day(){const d=new Date();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`}
const path=(p:string)=>`/F006/${p}`
function owned(r:DemoRecord,s:DemoState){scoped(r);const marker=String(r.remark??r.notes??'');if(!marker.includes(s.runKey))throw new Error('该单据不是本次演示创建的单据');return r}
function only(list:DemoRecord[],predicate:(r:DemoRecord)=>boolean,label:string){const found=list.filter(predicate);if(found.length!==1)throw new Error(`${label}需要唯一且有效的演示资料，请刷新后重试`);return scoped(found[0])}
async function put(t:DemoTransport,p:string,b?:unknown){if(!t.put)throw new Error('当前连接不支持此业务操作');return t.put(p,b)}
async function readDocument(t:DemoTransport,p:string,s:DemoState){return owned(record(await t.get(p)),s)}
async function findCreated(t:DemoTransport,p:string,s:DemoState){
  let found:DemoRecord|undefined
  for(let page=1;page<=100;page++){
    const value=await t.get(`${p}?page=${page}&size=200`);const pg=Array.isArray(value)?null:record(value);const entries=rows(value)
    for(const item of entries.filter(r=>String(r.remark??r.notes??'').includes(s.runKey))){if(found&&id(found)!==id(item))throw new Error('本次演示存在重复单据，请打开列表核对');found=owned(item,s)}
    if(!pg || page>=Number(pg.totalPages??1))return found
  }
  throw new Error('单据列表超过演示核对范围，已停止创建')
}
export async function initializeDemo(state:DemoState,transport:DemoTransport=demoTransport):Promise<DemoState>{
  if(transport===demoTransport && state.sandboxSessionId!==requireSandboxReady().sessionId)throw new Error('演示记录与当前沙盒不一致')
  await assertLiveAdmin(transport)
  if(state.factoryId!==FACTORY_ID || !state.runKey.startsWith(DEMO_RUN_KEY))throw new Error('演示状态不属于F006')
  const result=await Promise.allSettled([
    transport.get(path('suppliers/active')),transport.get(path('customers/active')),transport.get(path('raw-material-types?keyword=DEMO-20261105&page=1&size=100')),transport.get(path('product-types/active')),transport.get(path('factory/warehouses')),
  ])
  const data=result.map(r=>{if(r.status==='rejected')throw r.reason;return rows(r.value)})
  const supplier=only(data[0],r=>r.code==='DEMO-20261105-SUP0'||r.supplierCode==='DEMO-20261105-SUP0','供应商')
  const customer=only(data[1],r=>r.code==='DEMO-20261105-CUS0'||r.customerCode==='DEMO-20261105-CUS0','客户')
  const material=only(data[2],r=>r.code==='DEMO-20261105-M0','原料')
  const packaging=only(data[2],r=>r.code==='DEMO-20261105-M5','包材')
  const product=only(data[3],r=>r.code==='DEMO-20261105-SKU0','成品')
  const rawWarehouse=only(data[4],r=>r.code==='WH-RAW','原料仓'),workshopWarehouse=only(data[4],r=>r.code==='WH-WKS','生产仓')
  const wf=scoped(record(await transport.get(path(`product-process-workflows/${id(product)}`))))
  if(wf.status!=='PUBLISHED')throw new Error('演示工艺尚未发布，不能开始生产演示')
  const nodes=rows(wf.nodes);const processes=nodes.filter(n=>n.kind==='PROCESS').sort((a,b)=>Number(String(a.id).split(':').at(-1))-Number(String(b.id).split(':').at(-1))).map((n,i)=>{
    const d=record(n.data),ports=rows(d.ports),input=ports.filter(p=>p.direction==='INPUT'),output=ports.filter(p=>p.direction==='OUTPUT')
    if(input.length!==1||output.length!==1)throw new Error('当前演示配方需要六道单进单出工序')
    if(!input[0].materialNodeId||!output[0].materialNodeId)throw new Error('演示工艺缺少稳定物料节点')
    return {name:String(d.processName),code:`p${i+1}`,order:i+1,input:input[0],output:output[0]}
  })
  if(processes.length!==6||String(processes[0].input.skuId)!==id(material)||String(processes[5].output.skuId)!==id(product))throw new Error('演示工艺与原料/成品不匹配')
  const old=state.context
  state.context={supplier,customer,material:{...material,unitPrice:material.materialReferencePrice},packaging:{...packaging,unitPrice:packaging.materialReferencePrice},product,rawWarehouse,workshopWarehouse,processes,rawQuantity:old?.rawQuantity??1.25,finishedQuantity:old?.finishedQuantity??4}
  checkpoint(state);return state
}
export function prepareStep(state:DemoState,stepId:DemoStepId){
  const spec=DEMO_STEPS.find(item=>item.id===stepId);if(!spec)throw new Error('未知演示步骤')
  const c=state.context
  const values:DemoRecord=c?{supplierId:id(c.supplier),materialTypeId:id(c.material),customerId:id(c.customer),productTypeId:id(c.product),warehouseId:id(c.rawWarehouse),quantity:stepId==='sales-order-create'?c.finishedQuantity:c.rawQuantity,unit:stepId==='sales-order-create'?c.product.unit:c.material.unit,unitPrice:stepId==='sales-order-create'?c.product.unitPrice:c.material.unitPrice,orderDate:day(),receiveDate:day(),requiredDeliveryDate:day(),plannedDate:day(),batchDate:day(),logisticsCompany:'演示冷链配送',trackingNumber:state.runKey,deliveryAddress:'演示·上海连锁餐饮收货中心'}:{}
  if(c&&stepId.startsWith('process-report-')){
    const order=Number(stepId.split('-').at(-1));const process=c.processes[order-1];const grams=Number(c.product.gramsPerUnit);const kg=c.finishedQuantity*grams/1000
    const outputs=[c.rawQuantity*.96,c.rawQuantity*.94,kg+.04,kg,c.finishedQuantity,c.finishedQuantity]
    values.inputQuantity=order===1?c.rawQuantity:outputs[order-2];values.outputQuantity=outputs[order-1];values.inputUnit=process.input.unit;values.outputUnit=process.output.unit;values.workerCount=1;values.workMinutes=2
  }
  return {step:stepId,fields:spec.fields.map(f=>({...f,value:values[f.name]??f.value})),entityIds:Object.fromEntries(Object.entries(state.entities).filter(([,v])=>v.id).map(([k,v])=>[k,v.id!]))}
}

export async function executeStep(state:DemoState,stepId:DemoStepId,form:DemoRecord={},transport:DemoTransport=demoTransport):Promise<DemoState>{
  if(transport===demoTransport && state.sandboxSessionId!==requireSandboxReady().sessionId)throw new Error('演示记录与当前沙盒不一致')
  await assertLiveAdmin(transport)
  if(state.factoryId!==FACTORY_ID||!state.runKey.startsWith(DEMO_RUN_KEY))throw new Error('演示状态工厂不正确')
  const index=DEMO_STEPS.findIndex(s=>s.id===stepId);if(index<0||index!==state.currentStep)throw new Error('请按当前步骤推进，失败步骤不能跳过')
  const c=ctx(state),key=(suffix:string)=>`${state.runKey}:${suffix}`
  const save=(name:string,value:DemoRecord)=>{state.entities[name]=value;checkpoint(state);return value}
  const send=(p:string,b?:unknown)=>transport.post(path(p),b,{headers:{'Idempotency-Key':key(stepId)}})
  const po=()=>ent(state,'purchaseOrder'),so=()=>ent(state,'salesOrder'),plan=()=>ent(state,'productionPlan')
  let result:DemoRecord
  if(stepId==='purchase-create'){
    if(String(form.supplierId)!==id(c.supplier)||String(form.materialTypeId)!==id(c.material)||String(form.unit)!==String(c.material.unit))throw new Error('本次演示请使用已准备的供应商和鲜牛腱原料')
    c.rawQuantity=positive(form.quantity,'原料数量');c.finishedQuantity=Math.floor(c.rawQuantity*.8*1000/positive(c.product.gramsPerUnit,'产品克重'))
    if(c.finishedQuantity<1)throw new Error('原料数量不足以产出一盒演示成品')
    let value=state.entities.purchaseOrder||await findCreated(transport,path('purchase/orders'),state)
    if(!value)value=save('purchaseOrder',record(await send('purchase/orders',{supplierId:id(c.supplier),orderDate:form.orderDate||day(),purchaseType:'DIRECT',remark:state.runKey,items:[{materialTypeId:id(c.material),quantity:c.rawQuantity,unit:c.material.unit,priceUnit:c.material.unit,unitPrice:positive(form.unitPrice,'采购单价'),taxRate:0},{materialTypeId:id(c.packaging),quantity:c.finishedQuantity,unit:c.packaging.unit,priceUnit:c.packaging.unit,unitPrice:positive(c.packaging.unitPrice,'包材单价'),taxRate:0}]})))
    result=await readDocument(transport,path(`purchase/orders/${id(value)}`),state)
  }else if(stepId==='sales-order-create'){
    if(String(form.customerId)!==id(c.customer)||String(form.productTypeId)!==id(c.product)||String(form.unit)!==String(c.product.unit))throw new Error('本次演示请使用已准备的客户和成品单位')
    if(Number(form.quantity)!==c.finishedQuantity)throw new Error(`本次投料对应${c.finishedQuantity}${c.product.unit}成品，请保持数量一致`)
    let value=state.entities.salesOrder||await findCreated(transport,path('sales/orders'),state)
    if(!value)value=save('salesOrder',record(await send('sales/orders',{customerId:id(c.customer),orderDate:day(),requiredDeliveryDate:form.requiredDeliveryDate||day(),deliveryAddress:'演示·上海连锁餐饮收货中心',remark:state.runKey,processingMode:'STANDARD_SALE',materialSupplyMode:'FACTORY_SUPPLIED',items:[{productTypeId:id(c.product),productName:c.product.name,quantity:c.finishedQuantity,unit:c.product.unit,unitPrice:positive(form.unitPrice,'销售单价'),taxRate:0}]})))
    result=await readDocument(transport,path(`sales/orders/${id(value)}`),state)
    const p=await readDocument(transport,path(`purchase/orders/${id(po())}`),state)
    if(p.salesOrderId!==id(result)){if(p.status!=='DRAFT')throw new Error('采购单已提交，无法再建立本次销售来源');await put(transport,path(`purchase/orders/${id(p)}`),{salesOrderId:id(result),version:p.version});save('purchaseOrder',await readDocument(transport,path(`purchase/orders/${id(p)}`),state))}
  }else if(stepId==='purchase-submit'||stepId==='purchase-approve'){
    const p=await readDocument(transport,path(`purchase/orders/${id(po())}`),state)
    if(stepId==='purchase-submit'&&p.status==='DRAFT')await send(`purchase/orders/${id(p)}/submit`,{})
    if(stepId==='purchase-approve')await approveWorkflow(state.runKey,'PURCHASE_ORDER',id(p),transport)
    result=await readDocument(transport,path(`purchase/orders/${id(p)}`),state)
    if(stepId==='purchase-approve'&&!['APPROVED','FINANCE_APPROVED','COMPLETED'].includes(String(result.status)))throw new Error('采购审批尚未通过')
  }else if(stepId==='purchase-receive'){
    let r=state.entities.purchaseReceipt
    if(!r){const receipts=rows(await transport.get(path(`warehouse/receiving/receipts/by-order/${id(po())}`)));r=receipts.find(v=>String(v.remark).includes(state.runKey))}
    if(!r){const p=await readDocument(transport,path(`purchase/orders/${id(po())}`),state);r=save('purchaseReceipt',record(await send('warehouse/receiving/receipts',{purchaseOrderId:id(p),supplierId:id(c.supplier),receiveDate:form.receiveDate||day(),warehouseId:id(c.rawWarehouse),remark:state.runKey,items:rows(p.items).map(item=>({purchaseOrderItemId:item.id,materialTypeId:item.materialTypeId,materialName:item.materialName,receivedQuantity:item.quantity,unit:item.unit,priceUnit:item.unit,unitPrice:item.unitPrice}))})))}
    result=await readDocument(transport,path(`warehouse/receiving/receipts/${id(r)}`),state)
    if(!state.entities.receiptAttachment)save('receiptAttachment',await attachDemoReceipt(id(r),str(po(),'orderNumber'),state.runKey,transport))
  }else if(stepId==='purchase-receive-confirm'){
    const r=await readDocument(transport,path(`warehouse/receiving/receipts/${id(ent(state,'purchaseReceipt'))}`),state)
    if(r.status!=='CONFIRMED')await send(`warehouse/receiving/receipts/${id(r)}/confirm`,{})
    result=await readDocument(transport,path(`warehouse/receiving/receipts/${id(r)}`),state)
    if(result.status!=='CONFIRMED'||rows(result.items).some(item=>!item.materialBatchId))throw new Error('收货批次尚未完整入库')
  }else if(stepId==='sales-order-submit'||stepId==='sales-order-approve'){
    let s=await readDocument(transport,path(`sales/orders/${id(so())}`),state)
    if(stepId==='sales-order-submit'&&s.status==='DRAFT'){
      await send(`sales/orders/${id(s)}/confirm`,{})
      s=await readDocument(transport,path(`sales/orders/${id(s)}`),state)
    }
    if(stepId==='sales-order-submit'&&['CONFIRMED','FINANCE_REJECTED'].includes(String(s.status)))await send(`sales/orders/${id(s)}/submit-for-review`,{})
    if(stepId==='sales-order-approve')await approveWorkflow(state.runKey,'SALES_ORDER',id(s),transport)
    result=await readDocument(transport,path(`sales/orders/${id(s)}`),state)
    if(stepId==='sales-order-submit'&&!['FINANCE_APPROVED','PENDING_FINANCE_REVIEW','WORKFLOW_RUNNING','PROCESSING','COMPLETED','PARTIAL_DELIVERED'].includes(String(result.status)))throw new Error('销售订单尚未进入审核或获批状态')
    if(stepId==='sales-order-approve'&&!['FINANCE_APPROVED','PROCESSING','COMPLETED','PARTIAL_DELIVERED'].includes(String(result.status)))throw new Error('销售审批尚未通过')
  }else if(stepId==='production-plan-create'){
    let p=state.entities.productionPlan
    if(!p){const existing=rows(await transport.get(path(`production-plans/by-sales-order/${id(so())}`)));p=existing.find(v=>String(v.notes).includes(state.runKey))}
    if(!p){const sales=await readDocument(transport,path(`sales/orders/${id(so())}`),state);const plans=rows(await send('production-plans/batch-from-so',{sourceOrderId:id(sales),itemIds:rows(sales.items).map(v=>v.id),plannedDate:form.plannedDate||day(),batchDate:form.batchDate||day(),notes:state.runKey,confirmPotentialDuplicate:false}));if(plans.length!==1)throw new Error('本次销售单未生成唯一生产计划');p=save('productionPlan',scoped(plans[0]))}
    result=await readDocument(transport,path(`production-plans/${id(p)}`),state)
  }else if(stepId==='production-batch-create'){
    let b=state.entities.productionBatch
    if(!b)b=save('productionBatch',scoped(record(await send(`production-plans/${id(plan())}/create-batch`,{}))))
    result=scoped(record(await transport.get(path(`processing/batches/${id(b)}`))))
    if(String(result.productionPlanId)!==id(plan()))throw new Error('生产批次不属于本次计划')
  }else if(stepId==='material-requisition'){
    let r=state.entities.materialRequisition
    if(!r){const existing=rows(await transport.get(path(`material-requisitions/by-plan/${id(plan())}`)));r=existing[0]}
    if(!r)r=save('materialRequisition',scoped(record(await send('material-requisitions/generate',{productionPlanId:id(plan()),plannedRawInputKg:c.rawQuantity}))))
    r=scoped(record(await transport.get(path(`material-requisitions/${id(r)}`))))
    if(String(r.productionPlanId)!==id(plan()))throw new Error('领料单不属于本次计划')
    if(r.status==='PENDING')await put(transport,path(`material-requisitions/${id(r)}/start-picking`),{})
    r=scoped(record(await transport.get(path(`material-requisitions/${id(r)}`))))
    if(['PICKING','PENDING','DRAFT'].includes(String(r.status))){
      const receipt=await readDocument(transport,path(`warehouse/receiving/receipts/${id(ent(state,'purchaseReceipt'))}`),state)
      const picks=[]
      for(const item of rows(r.items)){
        const src=rows(receipt.items).find(v=>v.materialTypeId===item.materialTypeId);if(!src?.materialBatchId)throw new Error('领料项不在本次采购收货中，已停止')
        const batch=record(await transport.get(path(`reference-data/batches/${encodeURIComponent(String(src.materialBatchId))}`)))
        if(!batch.batchNumber)throw new Error('本次收货的原料批号无法读取')
        const quantity=item.materialTypeId===id(c.material)?c.rawQuantity:positive(item.requiredQty,'包材领料数量')
        picks.push({itemId:item.id,pickedQty:quantity,batchNumbers:[{batchId:src.materialBatchId,batchNo:batch.batchNumber,qty:quantity}]})
      }
      await put(transport,path(`material-requisitions/${id(r)}/confirm-picking`),{items:picks})
    }
    r=scoped(record(await transport.get(path(`material-requisitions/${id(r)}`))))
    if(r.status==='PICKING' && r.pickedAt)await put(transport,path(`material-requisitions/${id(r)}/transfer`),{})
    r=scoped(record(await transport.get(path(`material-requisitions/${id(r)}`))))
    if(r.status==='TRANSFERRED')await put(transport,path(`material-requisitions/${id(r)}/receive`),{})
    result=scoped(record(await transport.get(path(`material-requisitions/${id(r)}`))))
    if(result.status!=='ISSUED'||rows(result.items).some(item=>!rows(item.batchNumbers).length||!rows(item.batchNumbers).every(b=>b.workshopBatchId)))throw new Error('本次领料尚未送达生产仓')
  }else if(stepId.startsWith('process-report-')){
    const order=Number(stepId.split('-').at(-1)),p=c.processes[order-1],clientRowId=key(`p${order}`)
    const qp=`production-plans/${id(plan())}/process-sheet/rows?process=${encodeURIComponent(p.code)}&processOrder=${order}`
    const existing=rows(await transport.get(path(qp))).find(r=>r.clientRowId===clientRowId)
    const inputQuantity=positive(form.inputQuantity,'投入数量'),outputQuantity=positive(form.outputQuantity,'产出数量')
    if(String(form.inputUnit)!==String(p.input.unit)||String(form.outputUnit)!==String(p.output.unit))throw new Error('投入或产出单位与固定工艺不一致')
    if(order===6&&outputQuantity!==c.finishedQuantity)throw new Error('成品数量必须与本次销售需求一致')
    let posted=state.entities[`processReport${order}`]
    if(!posted&&!existing){
      const payload:DemoRecord={clientRowId,idempotencyKey:clientRowId,processCode:p.code,processOrder:order,processName:p.name,processDate:day(),productTypeId:p.output.skuId,materialNodeId:p.output.materialNodeId,workflowPortId:p.output.id,inputQuantity,outputQuantity,inputUnit:p.input.unit,outputUnit:p.output.unit,unit:p.output.unit,finished:order===6,laborSegments:[{startTime:`09:${String(order*3).padStart(2,'0')}`,endTime:`09:${String(order*3+Number(form.workMinutes||2)).padStart(2,'0')}`,workerCount:positive(form.workerCount,'操作人数')}],productWeight:order===6?c.finishedQuantity*Number(c.product.gramsPerUnit)/1000:undefined}
      if(order===1){const raw=rows(ent(state,'materialRequisition').items).find(i=>i.materialTypeId===id(c.material));if(!raw)throw new Error('本次领料缺少主原料');const wks=rows(raw.batchNumbers)[0];if(!wks?.workshopBatchId)throw new Error('本次主原料未进入生产仓');payload.rawMaterialInputs=[{materialBatchId:wks.workshopBatchId,quantity:inputQuantity,unit:p.input.unit,workflowPortId:p.input.id,materialNodeId:p.input.materialNodeId,skuId:p.input.skuId,sourceType:'RAW_MATERIAL'}]}
      else {const previous=ent(state,`processReport${order-1}`);if(!previous.batchNumber)throw new Error('上道工序尚未形成批次');payload.upstreamSources=[{sourceBatchNumber:previous.batchNumber,feedQuantityKg:inputQuantity,workflowPortId:p.input.id,materialNodeId:p.input.materialNodeId,skuId:p.input.skuId}]}
      posted=save(`processReport${order}`,record(await send(`production-plans/${id(plan())}/process-sheet/row/submit`,payload)))
    }
    const fresh=rows(await transport.get(path(qp))).find(r=>r.clientRowId===clientRowId)
    if(!fresh||fresh.submissionStatus!=='SUBMITTED')throw new Error('正式报工尚未持久化，已停止')
    const payload=record(fresh.payload||fresh)
    const batchNumber=posted?.batchNumber||fresh.batchNumber||payload.batchNumber
    if(!batchNumber)throw new Error('工序报工没有形成可追溯批次')
    result={...(posted||{}),batchNumber,clientRowId,outputQuantity,inputQuantity,processName:p.name,outputUnit:p.output.unit,rowReadback:true}
  }else if(stepId==='production-settle'){
    const prefill=record(await transport.get(path(`production-plans/${id(plan())}/settlement-prefill`)))
    const audit=prefill.audit?record(prefill.audit):null;if(audit&&audit.clean===false)throw new Error('报工数据尚未通过结单核对')
    let posted=state.entities.productionSettlement
    if(!posted)posted=save('productionSettlement',record(await send(`production-plans/${id(plan())}/settle`,{idempotencyKey:key('settle'),confirm:true,actualFinishedQuantity:c.finishedQuantity,quantityUnit:c.product.unit,defectCountOutcome:'NONE_CONFIRMED'})))
    const settlement=record(await transport.get(path(`production-plans/${id(plan())}/settlement`)))
    result={...posted,...settlement};if(!result.postingStatus)throw new Error('结单未返回库存入账状态')
  }else if(stepId==='finished-goods-receive'){
    const final=ent(state,'processReport6'),settlement=record(await transport.get(path(`production-plans/${id(plan())}/settlement`)))
    if(!['POSTED','POSTED_WITH_TOLERANCE'].includes(String(settlement.postingStatus)))save('productionWarehouseReceipt',record(await send(`production-plans/${id(plan())}/warehouse-receipt`,{idempotencyKey:key('warehouse-receipt'),outputLines:[{productTypeId:id(c.product),batchNumber:final.batchNumber,receivedQuantity:c.finishedQuantity,quantityUnit:c.product.unit}]})))
    const fresh=record(await transport.get(path(`production-plans/${id(plan())}/settlement`)))
    if(!['POSTED','POSTED_WITH_TOLERANCE'].includes(String(fresh.postingStatus)))throw new Error('成品尚未确认入库')
    const cost=record(await transport.get(path(`production/batches/${encodeURIComponent(String(final.batchNumber))}/cost-breakdown`)))
    const outputs=Array.isArray(fresh.outputLines)?rows(fresh.outputLines):Array.isArray(fresh.outputs)?rows(fresh.outputs):[]
    const fg=outputs.find(v=>v.productTypeId===id(c.product))||{}
    result={...fresh,...(state.entities.productionWarehouseReceipt||{}),finishedGoodsBatchId:fg.finishedGoodsBatchId||fresh.finishedGoodsBatchId,finishedGoodsBatchNumber:final.batchNumber,totalCost:cost.totalCost,unitCost:cost.perBoxCost,knownCostSubtotal:cost.knownCostSubtotal,missingCostItemCount:cost.missingCostItemCount,calculationStatus:cost.calculationStatus,unit:c.product.unit}
    if(!result.finishedGoodsBatchId)throw new Error('成品入库批次编号尚未读取到，请刷新核对')
  }else if(stepId==='sales-delivery-create'){
    let value=state.entities.salesDelivery
    if(!value)value=save('salesDelivery',scoped(record(await send('sales/deliveries',{salesOrderId:id(so()),customerId:id(c.customer),deliveryDate:day(),deliveryAddress:'演示·上海连锁餐饮收货中心',remark:state.runKey,idempotencyKey:key('delivery'),items:rows(so().items).map(item=>({salesOrderItemId:item.id,productTypeId:item.productTypeId,productName:item.productName,deliveredQuantity:c.finishedQuantity,unit:item.unit,unitPrice:item.unitPrice}))}))))
    result=await readDocument(transport,path(`sales/deliveries/${id(value)}`),state)
  }else if(stepId==='sales-shipment-create'){
    const parent=await readDocument(transport,path(`sales/deliveries/${id(ent(state,'salesDelivery'))}`),state)
    let shipment=state.entities.salesShipment||rows(await transport.get(path(`sales/deliveries/${id(parent)}/shipments`))).find(s=>String(s.parentDeliveryId)===id(parent)&&String(s.salesOrderId)===id(so()))
    if(!shipment)shipment=save('salesShipment',scoped(record(await send(`sales/deliveries/${id(parent)}/shipments`,{idempotencyKey:key('shipment'),plannedShipmentDate:day(),deliveryMethod:'LOGISTICS',deliveryMode:'FULL',logisticsCompany:form.logisticsCompany,trackingNumber:form.trackingNumber,deliveryAddress:form.deliveryAddress,remark:state.runKey,items:rows(parent.items).map(item=>({parentDeliveryItemId:item.id,quantity:c.finishedQuantity}))}))))
    result=scoped(record(await transport.get(path(`sales/deliveries/${id(shipment)}`))))
    if(String(result.parentDeliveryId)!==id(parent)||String(result.salesOrderId)!==id(so()))throw new Error('发运单与本次销售单不一致')
  }else if(stepId==='sales-batch-allocate'){
    const shipment=scoped(record(await transport.get(path(`sales/deliveries/${id(ent(state,'salesShipment'))}`)))),line=rows(shipment.items)[0],fg=ent(state,'productionWarehouseReceipt').finishedGoodsBatchId
    if(!line||!fg)throw new Error('本次发运明细或成品批次缺失')
    await send(`sales-deliveries/items/${id(line)}/batch-allocations`,{allocations:[{finishedGoodsBatchId:fg,allocatedQty:c.finishedQuantity}]})
    const allocation=rows(await transport.get(path(`sales-deliveries/items/${id(line)}/batch-allocations`)))
    if(!allocation.some(a=>a.finishedGoodsBatchId===fg&&Number(a.allocatedQty)===c.finishedQuantity))throw new Error('本次成品批次分配尚未持久化')
    result={allocations:allocation,quantity:c.finishedQuantity,unit:c.product.unit}
  }else if(stepId==='sales-warehouse-confirm'){
    const shipment=scoped(record(await transport.get(path(`sales/deliveries/${id(ent(state,'salesShipment'))}`))))
    if(!['SHIPPED','DELIVERED'].includes(String(shipment.status)))await send(`warehouse/deliveries/${id(shipment)}/confirm`,{actualQuantities:Object.fromEntries(rows(shipment.items).map(item=>[id(item),c.finishedQuantity]))})
    result=scoped(record(await transport.get(path(`sales/deliveries/${id(shipment)}`))))
    if(!['SHIPPED','DELIVERED'].includes(String(result.status)))throw new Error('仓库尚未完成本次出库')
  }else{
    const final=ent(state,'processReport6'),trace=record(await transport.get(path(`traceability/full/${encodeURIComponent(String(final.batchNumber))}`)))
    const production=scoped(record(trace.production));if(production.batchNumber!==final.batchNumber)throw new Error('溯源没有返回本次生产批次')
    if(!rows(trace.materials).length||!rows(trace.shipments).length)throw new Error('本次溯源缺少原料来源或出货记录')
    const documents=record(await transport.get(path(`sales/orders/${id(so())}/document-trace`)))
    if(!rows(documents.documents).some(d=>String(d.documentId??d.id)===id(po())))throw new Error('本次销售单尚未关联到本次采购单')
    result={...trace,salesOrderNumber:so().orderNumber,purchaseOrderNumber:po().orderNumber,batchNumber:final.batchNumber,quantity:c.finishedQuantity,unit:c.product.unit}
  }
  state.entities[DEMO_STEPS[index].entity]=result;state.readbacks[DEMO_STEPS[index].entity]=result;state.completed.push(stepId);state.currentStep=index+1;checkpoint(state);return state
}
