import {fusionWorkspace} from './fusion-data.mjs';
import {handleFusionWorkflow} from './fusion-workflow.mjs';
import {handleFusionBom} from './fusion-bom.mjs';
import {handleFusionProduction} from './fusion-production.mjs';
const BASE='/api/mobile/FOOD_0000_001',TYPES={serial:'多道工序与单位换算',joint:'联产与副产返工',merge:'并行加工与合流',substitution:'替代辅料与续锅'};
export async function prepareComplexPractice(root,session,type){
 if(!Object.hasOwn(TYPES,type))throw Error('请选择已配置的本机组合练习。');
 const w=fusionWorkspace(root,'factory',session),key='ydwm-complex-v1/'+type;
 if(w.state.configs[key]?.complete)return structuredClone(w.state.configs[key]);
 const saved=w.state.configs[key]||{},put=()=>{w.state.configs[key]=saved;w.persist()};
 const create=(key,suffix,input)=>{if(saved[key])return saved[key];w.ensureModel(BASE+suffix);const r=w.transaction('POST',BASE+suffix,{...input,notes:'本机复杂流程练习，不是账号历史记录。',prototypeLocalOnly:true});if(!r.success)throw Error(suffix+' '+r.message);saved[key]=r.data;put();return r.data};
 const title='本机练习 · '+TYPES[type];
 const raw=create('raw','/raw-material-types',{name:title+'原料',code:'LOCAL-CX-'+type,unit:'kg',isActive:true});
 const raw2=create('raw2','/raw-material-types',{name:title+'配合原料',code:'LOCAL-CX-'+type+'-2',unit:'kg',isActive:true});
 const semi=create('semi','/raw-material-types',{name:title+'半成品',code:'LOCAL-CX-'+type+'-SEMI',unit:'kg',isIntermediate:true,isActive:true});
 const semi2=create('semi2','/raw-material-types',{name:title+'半成品B',code:'LOCAL-CX-'+type+'-SEMI2',unit:'kg',isIntermediate:true,isActive:true});
 const product=create('product','/product-types',{name:title+'主成品',code:'LOCAL-CX-'+type+'-FG',unit:'kg',productCategory:'FINISHED_PRODUCT',isActive:true});
 const product2=create('product2','/product-types',{name:title+'计件成品',code:'LOCAL-CX-'+type+'-FG2',unit:'pcs',gramsPerUnit:100,productCategory:'FINISHED_PRODUCT',isActive:true});
 const byproduct=create('byproduct','/raw-material-types',{name:title+'副产料',code:'LOCAL-CX-'+type+'-BYP',unit:'kg',isByproduct:true,isActive:true});
 const rework=create('rework','/raw-material-types',{name:title+'返工料',code:'LOCAL-CX-'+type+'-RW',unit:'kg',isActive:true});
 const seasoning=create('seasoning','/raw-material-types',{name:title+'调料',code:'LOCAL-CX-'+type+'-AUX',unit:'g',materialCategory:'AUXILIARY',materialReferencePrice:0.01,isActive:true});
 const alternative=create('alternative','/raw-material-types',{name:title+'替代调料',code:'LOCAL-CX-'+type+'-ALT',unit:'g',materialCategory:'AUXILIARY',materialReferencePrice:0.02,isActive:true});
 const packaging=create('packaging','/raw-material-types',{name:title+'包材',code:'LOCAL-CX-'+type+'-PKG',unit:'pcs',materialCategory:'PACKAGING',materialReferencePrice:0.2,isActive:true});
 const warehouse=create('warehouse','/warehouses',{name:title+'生产仓',code:'LOCAL-CX-'+type+'-WH',type:'WORKSHOP',isActive:true});
 for(const [name,material,quantity]of [['rawStock',raw,500],['raw2Stock',raw2,500],['seasoningStock',seasoning,type==='substitution'?0:5000],['alternativeStock',alternative,5000],['packagingStock',packaging,5000]])create(name,'/material-batches',{materialTypeId:material.id,materialName:material.name,batchNumber:'LOCAL-CX-'+type+'-'+name,warehouseId:warehouse.id,quantity,receiptQuantity:quantity,remainingQuantity:quantity,currentQuantity:quantity,availableQuantity:quantity,unit:material.unit,unitPrice:material.unit==='kg'?12:material.materialReferencePrice,status:'AVAILABLE',qualityStatus:'PASSED'});
 const nodes=[],edges=[];
 const mat=(id,kind,record,x,y=100)=>{const actualKind=['BYPRODUCT','REWORK'].includes(kind)?'SEMI_FINISHED':kind;nodes.push({id,kind:actualKind,position:{x,y},data:{name:record.name,bound:true,skuId:record.id,baseUnit:record.unit,...(kind==='BYPRODUCT'?{isByproduct:true}:{}),...(kind==='REWORK'?{isRework:true}:{})}});return {id,kind:actualKind,special:kind,record}};
 const a=mat('raw','RAW_MATERIAL',raw,20),z=mat('finished','FINISHED_GOOD',product,1000);
 const proc=(id,name,inputs,outputs,x,y=100,inputUnit='kg')=>{
  const record=create(id,'/work-processes',{processName:title+' · '+name,unit:inputUnit,inputUnit,outputUnit:outputs[0].record.unit,processCategory:'PROCESSING',reportingRequired:true,isActive:true});
  const ports=[];for(const [direction,list]of [['INPUT',inputs],['OUTPUT',outputs]])for(const [i,m]of list.entries()){const pid=id+'-'+direction.toLowerCase()+'-'+i;ports.push({id:pid,direction,ordinal:i,materialNodeId:m.id,materialKind:m.kind,materialName:m.record.name,skuId:m.record.id,unit:direction==='INPUT'?inputUnit:m.record.unit,required:true,...(m.kind==='FINISHED_GOOD'&&m.record.unit==='pcs'?{gramsPerUnit:100}:{}),...(m.special==='BYPRODUCT'?{byproduct:true}:{}),...(m.special==='REWORK'?{rework:true}:{} )});edges.push({id:id+'-'+direction+'-'+i,source:direction==='INPUT'?m.id:id,target:direction==='INPUT'?id:m.id,sourceHandle:direction==='INPUT'?'output':pid,targetHandle:direction==='INPUT'?pid:'input'})}
  nodes.push({id,kind:'PROCESS',position:{x,y},data:{workProcessId:record.id,processName:record.processName,processOrder:['p1','p2','p3'].indexOf(id)+1,inputUnit,outputUnit:outputs[0].record.unit,conversionRule:{mode:'ACTUAL_WEIGHT'},reportingSelectionMode:'ACTUAL_IO',reportingRequired:true,allowMultipleUpstreamSources:inputs.length>1,standardBasisQuantity:100,standardBasisUnit:'kg',ports}});
 };
 if(type==='serial'){const s=mat('semi','SEMI_FINISHED',semi,450);proc('p1','前处理',[a],[s],220);proc('p2','精加工',[s],[z],720,100,'g')}
 else if(type==='merge'){const b=mat('raw2','RAW_MATERIAL',raw2,20,500),s=mat('semi','SEMI_FINISHED',semi,450),s2=mat('semi2','SEMI_FINISHED',semi2,450,500);proc('p1','主料处理',[a],[s],220);proc('p2','配料处理',[b],[s2],220,500);proc('p3','合流加工',[s,s2],[z],720,260)}
 else if(type==='joint'){const z2=mat('finished2','FINISHED_GOOD',product2,1000,350),b=mat('byproduct','BYPRODUCT',byproduct,1000,570),r=mat('rework','REWORK',rework,1000,790);proc('p1','联产分装',[a],[z,z2,b,r],370)}
 else proc('p1','调味加工',[a],[z],400);
 if(type==='joint')nodes.find(n=>n.id==='p1').data.reworkPolicy={sources:[{reworkNodeId:'rework',inputRatioPercent:10}]};
 const call=async(handler,method,p,input)=>{let reply;await handler({parent:root,root,role:'factory',session,method,target:BASE+p,body:Buffer.from(JSON.stringify(input||{})),headers:{},res:{},send:(_,status,body)=>reply={status,body}});if(!reply?.body.success)throw Error(reply?.body.message||p);return reply.body.data};
 if(!saved.workflow){const draft=await call(handleFusionWorkflow,'PUT','/product-process-workflows/'+product.id+'/draft',{factoryId:'FOOD_0000_001',productTypeId:product.id,schemaVersion:1,lockVersion:0,nodes,edges,viewport:{x:0,y:0,zoom:0.7}});saved.workflow=await call(handleFusionWorkflow,'POST','/product-process-workflows/'+product.id+'/publish-and-activate',{lockVersion:draft.lockVersion,revisionId:draft.revisionId,revisionHash:draft.revisionHash,definitionVersion:draft.version,idempotencyKey:key+'-publish'});put()}
 if(type==='substitution'&&!saved.configuredRecipe){const draft=await call(handleFusionBom,'POST','/bom/recipes/ensure-draft',{productTypeId:product.id}),full=await call(handleFusionBom,'GET','/bom/recipes/'+draft.id);await call(handleFusionBom,'POST','/bom/recipes/'+draft.id+'/seasoning/processes/'+saved.p1.id+'/bindings',{materialTypeId:seasoning.id,workflowProcessNodeId:'p1',dosagePerKgG:10,subsequentPotRatio:0.5,countInSeasoning:true,expectedRevision:full.seasoningRevision,substitutes:[{substituteMaterialTypeId:alternative.id,conversionFactor:2}]});await call(handleFusionBom,'POST','/bom/recipes/'+draft.id+'/items',{materialTypeId:packaging.id,materialCategory:'PACKAGING',unit:'pcs',standardQuantity:2,packagingRole:'PRIMARY_CONTAINER'});saved.configuredRecipe=await call(handleFusionBom,'POST','/bom/recipes/'+draft.id+'/activate',{});put()}
 if(!saved.plan){saved.plan=await call(handleFusionProduction,'POST','/production-plans',{productTypeId:product.id,targetFinishedGoodIds:type==='joint'?[product.id,product2.id]:[product.id],selectedWorkflowId:saved.workflow.workflow.id,plannedQuantity:100,plannedUnit:'kg',plannedDate:new Date().toISOString().slice(0,10),sourceType:'SAFETY_STOCK',notes:title+'，可修改、报工与刷新回读。'});put()}
 saved.complete=true;saved.type=type;saved.title=title;saved.links={plan:'/production/plans',canvas:'/production/process-hub?tab=product-processes&productTypeId='+product.id};put();return structuredClone(saved);
}
export const complexPracticeTypes=TYPES;
