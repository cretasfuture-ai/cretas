import {convertQuantity} from '../../fusion-report-contract.mjs';
// RAW quantities describe one recipe output batch; packaging is per product base unit.
export function materialDemand(recipe,item,master,actualRawKg){
 if(actualRawKg==null)return null;
 const raw=(recipe.items||[]).filter(r=>r.materialCategory==='RAW'&&!r.deleted);
 const totalKg=raw.reduce((sum,r)=>sum+convertQuantity(Number(r.actualQuantity??r.standardQuantity),r.unit,'kg'),0);
 if(!(totalKg>0)||!(Number(recipe.outputQuantityPerUnit)>0))throw Error('配方缺少可折算的原料或产出基数，不能自动生成用量。');
 if(item.materialCategory==='RAW')return convertQuantity(actualRawKg*convertQuantity(Number(item.actualQuantity??item.standardQuantity),item.unit,'kg')/totalKg,'kg',master.unit);
 const output=actualRawKg/totalKg*Number(recipe.outputQuantityPerUnit);
 const scale=item.materialCategory==='PACKAGING'?output:output/Number(recipe.outputQuantityPerUnit);
 return item.standardQuantity==null?null:convertQuantity(Number(item.actualQuantity??item.standardQuantity)*scale,item.unit,master.unit);
}
export function requisitionDemandCheck(recipe,record){
 const statusLabel=({PENDING:'待备料',PICKING:'备料中',TRANSFERRED:'已调拨在途',ISSUED:'已签收',IN_USE:'使用中',CLOSED:'已关单',CANCELLED:'已取消'})[record.status]||record.status;
 if(record.status==='CANCELLED')return{requisitionNo:record.requisitionNo,version:record.sourceRecipeVersion,statusLabel,skipComparison:true,reason:'已取消且不执行；不再建议重复领料。'};
 if(!recipe)return{requisitionNo:record.requisitionNo,statusLabel,error:'生成时配方缺失，无法核对，不能用当前配方替代。'};
 try{const rawInputKg=record.items.filter(i=>i.materialCategory==='RAW').reduce((s,i)=>s+convertQuantity(i.requiredQty,i.unit,'kg'),0),rawBasisKg=recipe.items.filter(i=>i.materialCategory==='RAW'&&!i.deleted).reduce((s,i)=>s+convertQuantity(i.actualQuantity??i.standardQuantity,i.unit,'kg'),0);
 const lines=record.items.map(line=>{const items=recipe.items.filter(i=>!i.deleted&&i.materialTypeId===line.materialTypeId);const seasoning=(recipe.seasoningItems||[]).filter(i=>!i.deleted&&i.countInSeasoning!==false&&i.materialTypeId===line.materialTypeId);const expectedQty=items.length?items.reduce((s,i)=>s+materialDemand(recipe,i,{unit:line.unit},rawInputKg),0):seasoning.length?seasoning.reduce((s,i)=>s+convertQuantity(i.dosagePerKgG*rawInputKg,'g',line.unit),0):null;return{materialName:line.materialName,expectedQty:expectedQty==null?null:Number(expectedQty.toFixed(6)),requiredQty:line.requiredQty,issuedQty:line.issuedQty,consumedQty:line.consumedQty,unit:line.unit,matches:expectedQty!=null&&Math.abs(expectedQty-Number(line.requiredQty))<1e-5}});
 return{requisitionNo:record.requisitionNo,version:record.sourceRecipeVersion,statusLabel,rawInputKg,expectedOutput:Number((rawInputKg/rawBasisKg*recipe.outputQuantityPerUnit).toFixed(6)),outputUnit:recipe.outputUnit,allMatch:lines.every(i=>i.matches),lines,note:'按本次领料原料投入量核对；整份配方基数与本批数量分母不同，不能直接比较。已签收或已发出不等于已耗用。'};
 }catch(e){return{requisitionNo:record.requisitionNo,statusLabel,error:e.message}}
}
