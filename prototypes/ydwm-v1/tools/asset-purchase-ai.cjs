exports.patchPurchaseAI=text=>{
 const start=text.indexOf('function Va(t){const e=String(t.supplierName||"")');if(start<0||!text.includes('AI 原料匹配失败'))return text;
 const end=text.indexOf('return(t,e)=>',start);if(end<0)throw Error('Original purchase AI render boundary changed');let body=text.slice(start,end);
 const before='return}if(o.value.supplierId=r.id,',row='return{materialTypeId:l.id,quantity:',last='J.value=!0}';
 if(!body.includes(before)||!body.includes(row)||!body.endsWith(last))throw Error('Original purchase AI callback changed');
 body=body.replace('function Va(t){','async function Va(t){').replace(before,'return}o.value.supplierId=r.id;await it();if(o.value.supplierId=r.id,').replace(row,'return{supplierMaterialId:M.value.find(r=>String(r.materialTypeId)===String(l.id))?.id||"",materialTypeId:l.id,quantity:');
 body=body.slice(0,-last.length)+'for(const row of o.value.items){const stated={...row};if(row.supplierMaterialId){await ba(row);if(stated.unit!==row.unit){row.purchasePackagingSpecId=null;row.materialPackagingSpecId=null}Object.assign(row,stated)}}'+last;
 return text.slice(0,start)+body+text.slice(end);
};
