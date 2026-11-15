'use strict';
const unitAliases={'千克':'kg','公斤':'kg','克':'g','吨':'t'};
function enrich({text,fields,rows}){
 const keys=new Set(fields.map(f=>f.name)),out={},latest=text.includes('用户：')?text.slice(text.lastIndexOf('用户：')+3):text;
 for(const [key,suffix]of [['supplierName','/suppliers'],['customerName','/customers']])if(keys.has(key)){const matches=rows(suffix).filter(r=>r.isActive!==false&&latest.includes(String(r.name||''))).sort((a,b)=>String(b.name).length-String(a.name).length);if(matches.length&&(!matches[1]||matches[0].name.length>matches[1].name.length))out[key]=matches[0].name;}
 if(keys.has('items')){
  const purchase=keys.has('supplierName'),catalog=rows(purchase?'/raw-material-types':'/product-types').filter(r=>r.isActive!==false),candidates=catalog.filter(r=>latest.includes(String(r.name||''))).map(r=>({...r,index:latest.lastIndexOf(r.name)})).sort((a,b)=>a.index-b.index),items=[];
  for(let i=0;i<candidates.length;i++){const record=candidates[i],tail=latest.slice(record.index+record.name.length,candidates[i+1]?.index??latest.length),quantity=tail.match(/^\s*[，,：:]?\s*(-?\d+(?:\.\d+)?)\s*(kg|千克|公斤|g|克|吨|t|盒|袋|箱|个|件|只|瓶)/i);if(!quantity||Number(quantity[1])<=0)continue;const price=tail.match(/(?:含税单价|单价)[为是：:\s]*(\d+(?:\.\d+)?)/),tax=tail.match(/税率[为是：:\s]*(0|1|3|6|9|13)\s*%?/),unit=unitAliases[quantity[2]]||quantity[2];items.push({[purchase?'materialName':'productName']:record.name,quantity:Number(quantity[1]),unit,quantityUnit:unit,...(price?{unitPrice:Number(price[1]),...(purchase?{taxIncludedUnitPrice:Number(price[1])}:{})}:{}),...(tax?{taxRate:Number(tax[1])}:{})});}
  if(!items.length&&text!==latest){const prior=text.slice(0,text.lastIndexOf('用户：')),previous=catalog.filter(r=>prior.includes(r.name));if(previous.length===1){const q=latest.match(/(-?\d+(?:\.\d+)?)\s*(kg|千克|公斤|g|克|吨|t|盒|袋|箱|个|件|只|瓶)/i);if(q&&Number(q[1])>0){const tail=prior.slice(prior.lastIndexOf(previous[0].name)+previous[0].name.length),price=latest.match(/(?:含税单价|单价)[为是：:\s]*(\d+(?:\.\d+)?)/)||tail.match(/(?:含税单价|单价)[为是：:\s]*(\d+(?:\.\d+)?)/),tax=latest.match(/税率[为是：:\s]*(0|1|3|6|9|13)\s*%?/ )||tail.match(/税率[为是：:\s]*(0|1|3|6|9|13)\s*%?/),unit=unitAliases[q[2]]||q[2];items.push({[purchase?'materialName':'productName']:previous[0].name,quantity:Number(q[1]),unit,quantityUnit:unit,...(price?{unitPrice:Number(price[1]),...(purchase?{taxIncludedUnitPrice:Number(price[1])}:{})}:{}),...(tax?{taxRate:Number(tax[1])}:{})})}}}
  if(items.length)out.items=items;
 }
 if(keys.has('productCategory')){const named=latest.match(/(?:产品大类|产品类型|大类)[为是：:\s]*(半成品|成品|客户自带原料加工|纯代工|FINISHED_PRODUCT|SEMI_FINISHED)/);if(named)out.productCategory=({成品:'FINISHED_PRODUCT',半成品:'SEMI_FINISHED','客户自带原料加工':'CUSTOMER_MATERIAL',纯代工:'CONTRACT_MANUFACTURING'})[named[1]]||named[1];}
 if(keys.has('unit')){const explicit=latest.match(/单位[为是：:\s]*(kg|千克|公斤|g|克|吨|t|盒|袋|箱|个|件|只|瓶)/i);if(explicit)out.unit=unitAliases[explicit[1]]||explicit[1];}
 return out;
}
module.exports={enrich};
