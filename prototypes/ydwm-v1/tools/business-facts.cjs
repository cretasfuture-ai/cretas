/* Typed, deterministic read models. Inference may select review codes, never business numbers. */
const {numeric,add}=require('./ledger-facts.cjs'),crypto=require('node:crypto');
const VERSION='business-truth-v1';
const statusLabels={DRAFT:'草稿',SUBMITTED:'已提交',PENDING_APPROVAL:'待审批',APPROVED:'已批准',FINANCE_APPROVED:'财审通过',REJECTED:'已驳回',RECEIVING:'收货中',PARTIALLY_RECEIVED:'部分收货',RECEIVED:'已收货',CLOSED:'已关闭',COMPLETED:'已完成',SHORT_CLOSED:'短交关闭',CANCELLED:'已取消',CANCELED:'已取消',DELIVERED:'已发货',PARTIALLY_DELIVERED:'部分发货',SHIPPING:'发货中',AVAILABLE:'可用状态',PENDING_OPENING:'待期初',FROZEN:'已冻结',EXPIRED:'已过期'};
const md=v=>String(v??'').replace(/\|/g,'｜').replace(/[\r\n]/g,' '),statusName=s=>statusLabels[s]||s||'状态未填';
const fieldLabels={name:'名称',productName:'产品',materialName:'物料',customerName:'客户',supplierName:'供应商',planNumber:'计划编号',orderNumber:'单号',batchNumber:'批次',plannedQuantity:'计划数量',plannedUnit:'计划单位',quantity:'记录数量',actualQuantity:'已记录产出（不代表成品结算）',requiredQty:'需求量',issuedQty:'已发量',consumedQty:'实耗',returnedQty:'退回量',receivedQuantity:'收货记录量',deliveredQuantity:'发货记录量',unit:'单位',quantityUnit:'数量单位',statusDisplayName:'状态',plannedDate:'计划日期',expectedCompletionDate:'预计完成',expectedDeliveryDate:'预计到货',deliveryDate:'发货日期',orderDate:'下单日期',selectedWorkflowVersion:'绑定工序版本',selectedBomVersion:'绑定配方版本',fromWarehouseName:'调出仓',toWarehouseName:'调入仓',totalAmount:'单据记录额',refundAmount:'退款记录额',returnType:'退货类型',productCategory:'产品类别',specification:'规格',notes:'备注',remark:'说明'};
function readable(values,domain){const cell=value=>value===null||value===undefined||typeof value==='string'&&!value.trim()?'未填写':value===0||value==='0'?'0（已记录）':typeof value==='object'?JSON.stringify(value):String(value);if(domain==='table')return Object.entries(values).map(([key,value])=>md(key)+'：'+(Array.isArray(value)&&['工序分摊','计划级领退料'].includes(key)?(value.length?value.map((item,i)=>'\n\n'+(i+1)+'. '+Object.entries(item).map(([k,v])=>md(k)+' '+md(cell(v))).join('；')).join(''):'无对应记录'):md(cell(value)))).join('；');const rows=Object.entries(fieldLabels).filter(([key])=>Object.hasOwn(values,key)).map(([key,label])=>label+'：'+md(cell(values[key])));if(Array.isArray(values.items)){rows.push('明细 '+values.items.length+' 项');for(const item of values.items.slice(0,12))rows.push(Object.entries(fieldLabels).filter(([key])=>Object.hasOwn(item,key)).map(([key,label])=>label+' '+md(cell(item[key]))).join('；'))}return rows.length?rows.join('  \n'):'该条记录的可读业务字段未提供，请在原详情中核对。'}
const REVIEW={COMPLETE:'核对未填写项；已记录的0保留，不重复补填。',INVALID:'核对格式无效或负数的原始数量，不据此作完整合计。',CANCELLED:'取消单保留历史，排除在有效需求和待履约量之外；历史收发仍单列核对。',RECONCILE:'核对对应的收货、发货或库存流水；单据数量不等于实际收发，库存不等于可用量。',STATUS:'核对未识别状态；状态明确前不判断是否需要继续履约。'};
const specs={purchase:{name:'采购订单',ordered:'订购',actual:'收货',actualKey:'receivedQuantity',closed:['CLOSED','COMPLETED','SHORT_CLOSED'],statuses:['DRAFT','SUBMITTED','PENDING_APPROVAL','APPROVED','FINANCE_APPROVED','REJECTED','RECEIVING','PARTIALLY_RECEIVED','RECEIVED','CLOSED','COMPLETED','SHORT_CLOSED','CANCELLED','CANCELED']},sales:{name:'销售订单',ordered:'订购',actual:'发货',actualKey:'deliveredQuantity',closed:['CLOSED','COMPLETED','SHORT_CLOSED','DELIVERED'],statuses:['DRAFT','SUBMITTED','PENDING_APPROVAL','APPROVED','FINANCE_APPROVED','REJECTED','PRODUCTION_APPROVED','IN_PRODUCTION','PRODUCING','READY','SHIPPING','PARTIALLY_DELIVERED','DELIVERED','CLOSED','COMPLETED','SHORT_CLOSED','CANCELLED','CANCELED']},inventory:{name:'库存批次',statuses:['AVAILABLE','PENDING_OPENING','RESERVED','DEPLETED','EXPIRED','FROZEN','DISPOSED','USED_UP','IN_USE','CANCELLED','CANCELED']}};
const hash=v=>crypto.createHash('sha256').update(JSON.stringify(v)).digest('hex'),cancelled=s=>['CANCELLED','CANCELED'].includes(s);
function sum(qs){let value='0',missing=0,invalid=0,zero=0;for(const q of qs){if(q.kind==='missing')missing++;else if(q.kind==='invalid')invalid++;else {value=add(value,q.text);if(q.kind==='zero')zero++}}return {value,missing,invalid,zero,complete:missing===0&&invalid===0,count:qs.length}}
function subtract(a,b){if(!a.text||!b.text)return {kind:'missing'};const scale=Math.max((a.text.split('.')[1]||'').length,(b.text.split('.')[1]||'').length),n=s=>{const p=s.split('.');return BigInt(p[0]+(p[1]||'').padEnd(scale,'0'))},v=n(a.text)-n(b.text);if(v<=0n)return numeric(0);const t=v.toString().padStart(scale+1,'0');return numeric(scale?t.slice(0,-scale)+'.'+t.slice(-scale):t)}
function facts(domain,rows,scope={}){
 if(!Array.isArray(rows))throw Error('业务记录必须为数组');const spec=specs[domain],records=[],issues=[];
 const units=new Map(),statuses={};const group=(unit,key,q)=>{const u=typeof unit==='string'?unit.trim():'';if(!u){issues.push('单位未填写');return}if(!units.has(u))units.set(u,{});const g=units.get(u);(g[key]||=[]).push(q)};
 for(const [index,row]of rows.entries()){
  const status=String(row.status??row.qualityStatus??'').trim().toUpperCase(),id=String(row.orderNumber||row.batchNumber||row.planNumber||row.name||row.productName||row.id||index+1);if(status||spec)statuses[status||'未填写']=(statuses[status||'未填写']||0)+1;
  const known=!!spec?.statuses.includes(status),excluded=cancelled(status),r={id,status,excluded,fields:[]};
  if(spec&&domain!=='inventory'){
   if(!Array.isArray(row.items)||!row.items.length)issues.push(id+'：订单明细未提供');
   for(const [i,item]of (Array.isArray(row.items)?row.items:[]).entries()){
    const unit=item.unit,ordered=numeric(item.quantity),actual=numeric(item[spec.actualKey]);
    const detail={label:item.materialName||item.productName||'明细'+(i+1),unit,ordered,actual};r.fields.push(detail);
    group(unit,'actualHistory',actual);
    if(known&&!excluded){group(unit,'ordered',ordered);group(unit,'actual',actual);if(!spec.closed.includes(status)&&!['DRAFT','SUBMITTED','PENDING_APPROVAL','REJECTED'].includes(status))group(unit,'pending',subtract(ordered,actual));}
   }
   // Amount is one explicit field, never substitute payable/settled amounts for it.
   r.amount=numeric(row.totalAmount);if(known&&!excluded)group(row.currency&&row.currency!=='CNY'?String(row.currency):'元','amount',r.amount);
  }else if(domain==='inventory'){
   // Each field has its own meaning. Never choose the first non-null quantity.
   for(const [key,label]of [['quantity','入库记录量'],['currentQuantity','当前库存'],['availableQuantity','可用量'],['remainingQuantity','剩余量'],['reservedQuantity','预留量']]){
    const q=numeric(row[key]);r.fields.push({label,quantity:q,unit:row.unit});if(!excluded)group(row.unit,key,q);
   }
  }
  if(!spec)r.values=Object.fromEntries(Object.entries(row).filter(([k])=>!['__proto__','constructor','prototype'].includes(k)));
  if(spec&&!known)issues.push(id+'：状态未识别（'+(status||'未填写')+'）');records.push(r);
 }
 const groups=[...units].map(([unit,values])=>({unit,values:Object.fromEntries(Object.entries(values).map(([key,qs])=>[key,sum(qs)]))}));
 const measurements=records.flatMap(r=>r.fields.flatMap(f=>f.quantity?[f.quantity]:[f.ordered,f.actual])).concat(records.filter(r=>r.amount).map(r=>r.amount));
 const genericMissing=records.some(r=>r.values&&Object.values(r.values).some(v=>v==null||typeof v==='string'&&!v.trim()));const allowed=[];if(genericMissing||measurements.some(q=>q.kind==='missing')||issues.length)allowed.push('COMPLETE');if(measurements.some(q=>q.kind==='invalid'))allowed.push('INVALID');if(records.some(r=>r.excluded))allowed.push('CANCELLED');if(records.length)allowed.push('RECONCILE');if(spec&&records.some(r=>!spec.statuses.includes(r.status)))allowed.push('STATUS');
 return {version:VERSION,domain,name:spec?.name||scope.name||'当前记录',hash:hash({domain,rows,scope}),scope,recordCount:rows.length,statuses,records,groups,issues,allowed,zero:measurements.filter(q=>q.kind==='zero').length,missing:measurements.filter(q=>q.kind==='missing').length,invalid:measurements.filter(q=>q.kind==='invalid').length};
}
function qtext(q){return q.kind==='missing'?'未填写':q.kind==='invalid'?'格式无效':q.text+(q.kind==='zero'?'（已记录）':'')}
function total(q,u){if(q.count>0&&q.missing+q.invalid===q.count)return '未提供有效记录（未填写'+q.missing+'项、无效'+q.invalid+'项），不作合计';return (q.complete?'':'已知 ')+q.value+' '+u+(q.complete?'':'（未填写'+q.missing+'项、无效'+q.invalid+'项，不是完整合计）')}
function render(f,codes=null){
 if(f.scope.presentation==='chart-bullets'){const r=f.records[0]?.values||{};return ['- '+(r.标题||'当前图表')+'：'+(r.页面展示摘要||'未提供展示摘要。'),'- 0保留为已记录零值；未展示的指标不补0，不推断利润、趋势或待执行量；不与本机练习明细混用。','- '+(codes?.length?codes.map(c=>REVIEW[c]).join(' '):'核对本图表的数据范围与来源，操作需另外确认。')].join('\n')}
 const spec=specs[f.domain],lines=['### '+f.name+' · 记录核对',f.scope.source==='page-summary'?'当前页面展示摘要 '+f.recordCount+' 份（不代表业务明细行数）。':'当前范围 '+f.recordCount+' 条；'+(f.scope.range||'按当前筛选读取全部匹配记录')+'。','0是已记录零值；空白/null是未填写；无效格式单列。缺少字段不推断为0。'];
 if(f.scope.taskNote)lines.push(f.scope.taskNote);
 if(!f.recordCount)lines.push('此范围没有记录。记录数为0，不代表采购、销售或库存业务数量已经记录为0。');
 if(spec&&f.domain!=='inventory')lines.push('取消单不计未取消订购或待履约；关闭、短交关闭单不再待履约；草稿、待审批、驳回单不列入执行待履约量。实际'+spec.actual+'按明细字段单独累计，含取消/关闭单的历史收发另外保留。订购、收货、发货、实耗不互相代替。未识别状态排除有效订购和待履约。','金额仅为未取消且状态已识别订单的订单总额字段合计（未标币种按原页面人民币口径；其他币种另列），不是已结算、应收/应付或收入。');
 if(f.domain==='inventory')lines.push('入库记录量、当前库存、可用量、剩余量、预留量分别读取同名字段；不互相代替，不默认“可用=当前−预留”。不同单位分别计算；同单位总览不代表物料可以互换，批次库存不代表实耗。取消批次不计当前合计；待期初/冻结/过期等状态不自动等同可用库存。');
 const labels={ordered:'未取消订购记录（含草稿/关闭）',actual:spec?'有效订单已'+spec.actual:'实际量',actualHistory:spec?'历史'+spec.actual+'（含取消和关闭单）':'历史量',pending:'未结束待'+(spec?.actual||'履约'),amount:'订单记录金额',quantity:'入库记录量',currentQuantity:'当前库存',availableQuantity:'可用字段记录量（非出库承诺）',remainingQuantity:'剩余量',reservedQuantity:'预留量'};
 for(const g of f.groups)lines.push('**'+md(g.unit)+'**\n\n| 数量口径 | 记录量 |\n| --- | --- |\n'+Object.entries(g.values).map(([k,q])=>'| '+md(labels[k])+' | '+md(total(q,g.unit))+' |').join('\n'));
 if(Object.keys(f.statuses).length)lines.push('状态分布：'+Object.entries(f.statuses).map(([s,n])=>statusName(s)+' '+n+'条').join('；'));
 if(spec)lines.push('零值 '+f.zero+' 项；未填写 '+f.missing+' 项；格式无效 '+f.invalid+' 项。');
 if(!spec)lines.push('仅展示各字段的原始记录；没有明确单位和业务含义的数字不相加、不推断为业务缺失或趋势。取消/关闭记录保留状态供核对，不据此推算待执行量。');
 if(f.records.length){lines.push('### 逐条依据'+(f.records.length>20?'（展示前20条；以上合计使用全部记录）':''));for(const r of f.records.slice(0,20))lines.push('**'+md(r.id)+(r.status?' · '+md(statusName(r.status)):spec?' · 状态未填':'')+'**\n'+(r.values?readable(r.values,f.domain):r.fields.map(d=>d.quantity?d.label+' '+qtext(d.quantity)+(d.unit?' '+d.unit:'（单位未填，不合计）'):d.label+'：'+spec.ordered+' '+qtext(d.ordered)+'；'+spec.actual+' '+qtext(d.actual)+(d.unit?' '+d.unit:'（单位未填，不合计）')).join('；')));}
 if(f.issues.length)lines.push('待核对：'+f.issues.slice(0,20).join('；'));
 if(codes!==null)lines.push('### 核对建议',...(codes.length?codes.map(c=>'- '+REVIEW[c]):['本次没有额外核对建议。']));
 return lines.join('\n\n');
}
function selection(f,value){if(!value||Array.isArray(value)||Object.keys(value).length!==1||!Array.isArray(value.reviewCodes)||value.reviewCodes.length>5||new Set(value.reviewCodes).size!==value.reviewCodes.length||value.reviewCodes.some(c=>!f.allowed.includes(c)))throw Error('解读未通过业务口径校验，请重试。');return value.reviewCodes}
function proof(f,codes){selection(f,{reviewCodes:codes});return {version:VERSION,factHash:f.hash,reviewCodes:codes}}
function verify(f,p){if(p?.version!==VERSION||p.factHash!==f.hash)throw Error('记录或口径已变化，请重新分析。');return selection(f,{reviewCodes:p.reviewCodes})}
module.exports={VERSION,REVIEW,facts,render,selection,proof,verify,numeric};
