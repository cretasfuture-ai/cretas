exports.patchTransferRefresh=(file,text)=>{
 if(!/^detail-.*\.js$/.test(require('node:path').basename(file))||!text.includes('__name:"WarehouseDeliveryPanel"'))return text;
 text='import{w as ydwmWatch,aU as ydwmActivated}from"./el-icons-nBZ-K2EA.js";'+text;
 text=text.replace('Je(U);const le=x(', 'Je(U);ydwmWatch(()=>[A.transferId,A.status],()=>U());const le=x(');
 text=text.replace('Je(()=>{S(),_()});async function S()', 'Je(()=>{S(),_()});ydwmActivated(()=>S());async function S()');
 return text;
};
