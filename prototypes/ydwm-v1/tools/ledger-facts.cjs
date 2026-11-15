/* Business facts are calculated here. Model output can select eligible review IDs only. */
const crypto=require('node:crypto');
const VERSION='ledger-truth-v1';
const ACTIVE=new Set(['PENDING','PICKING','TRANSFERRED','ISSUED','IN_USE']);
const LABELS={PENDING:'待备料',PICKING:'备料中',TRANSFERRED:'已调拨',ISSUED:'已签收',IN_USE:'使用中',CLOSED:'已关闭',CANCELLED:'已取消'};
const FIELDS=['需求数量','已发数量','实际耗用','退回数量'];
const REVIEW={
 CHECK_USAGE:'核对是否有尚未入账的新报工记录；当前已记录的实耗仍按原值保留。',
 REVIEW_CANCELLED:'查看取消原因和历史记录；取消单不补发，不计入未取消需求和待发量。',
 REVIEW_RETURNS:'核对退料凭证；退回量单列，不改写实耗量。',
 COMPLETE_FIELDS:'补齐明确标为“未填写”的字段；已经填写的0不需要补填。',
 CHECK_INVALID:'核对格式无效或为负数的原始数量；未修正前不作完整合计。',
 CHECK_PENDING:'核对未结束单据的待发数量；先检查现有发料记录，避免重复领料。',
 CHECK_STATUS:'核对未识别的单据状态；状态明确前不判断是否应继续发料。'
};
const digest=value=>crypto.createHash('sha256').update(JSON.stringify(value)).digest('hex');
function numeric(raw){
 if(raw===undefined||raw===null||typeof raw==='string'&&!raw.trim())return {kind:'missing'};
 if(!['number','string'].includes(typeof raw)||typeof raw==='number'&&!Number.isFinite(raw))return {kind:'invalid'};
 const s=String(raw).trim(),m=s.match(/^([+-]?)(\d+(?:\.\d*)?|\.\d+)(?:e([+-]?\d+))?$/i);
 if(!m||s.length>80||Math.abs(Number(m[3]||0))>30)return {kind:'invalid'};
 const [whole,fraction='']=m[2].split('.');let n=BigInt((whole||'0')+fraction),scale=fraction.length-Number(m[3]||0);
 if(scale<0){n*=10n**BigInt(-scale);scale=0}if(m[1]==='-')n=-n;
 if(n<0n||!Number.isFinite(Number(s)))return {kind:'invalid'};
 while(scale>0&&n%10n===0n){n/=10n;scale--}
 let text=n.toString();if(scale)text=text.padStart(scale+1,'0').slice(0,-scale)+'.'+text.padStart(scale+1,'0').slice(-scale);
 return {kind:n===0n?'zero':'value',text};
}
function add(a,b){const x=numeric(a),y=numeric(b),parts=[x.text,y.text].map(s=>s.split('.')),scale=Math.max(...parts.map(p=>(p[1]||'').length));const n=parts.reduce((sum,p)=>sum+BigInt(p[0]+(p[1]||'').padEnd(scale,'0')),0n);return numeric(scale?n.toString().padStart(scale+1,'0').slice(0,-scale)+'.'+n.toString().padStart(scale+1,'0').slice(-scale):n.toString()).text}
function pending(required,issued){if(!required.text||!issued.text)return null;const scale=Math.max((required.text.split('.')[1]||'').length,(issued.text.split('.')[1]||'').length),value=s=>{const p=s.split('.');return BigInt(p[0]+(p[1]||'').padEnd(scale,'0'))};const n=value(required.text)-value(issued.text);if(n<=0n)return '0';return numeric(scale?n.toString().padStart(scale+1,'0').slice(0,-scale)+'.'+n.toString().padStart(scale+1,'0').slice(-scale):n.toString()).text}
function isLedger(rows){return Array.isArray(rows)&&rows.length>0&&rows.every(r=>r&&typeof r==='object'&&!Array.isArray(r)&&Object.hasOwn(r,'领料单')&&Object.hasOwn(r,'状态')&&FIELDS.some(k=>Object.hasOwn(r,k)))}
function facts(rows){
 if(!isLedger(rows))throw Error('缺少领料单、状态或数量字段，不能按领料口径分析。');
 const records=rows.map((r,index)=>{const status=String(r['状态']??'').trim().toUpperCase();return {index,id:String(r['领料单']??'').trim()||'第'+(index+1)+'行',status,statusLabel:LABELS[status]||'未识别状态',unit:typeof r['单位']==='string'?r['单位'].trim():'',quantities:Object.fromEntries(FIELDS.map(k=>[k,numeric(r[k])]))}});
 const groups=[];
 for(const unit of [...new Set(records.map(r=>r.unit))]){
  const rs=records.filter(r=>r.unit===unit),total=selected=>{let sum='0',missing=0,invalid=0;for(const q of selected){if(q.kind==='missing')missing++;else if(q.kind==='invalid')invalid++;else sum=add(sum,q.text)}return {sum,missing,invalid,complete:missing===0&&invalid===0}};
  const known=rs.filter(r=>!!LABELS[r.status]),live=known.filter(r=>ACTIVE.has(r.status));
  const waiting=live.map(r=>pending(r.quantities['需求数量'],r.quantities['已发数量']));
  groups.push({unit,rows:rs.length,cancelled:rs.filter(r=>r.status==='CANCELLED').length,closed:rs.filter(r=>r.status==='CLOSED').length,unknownStatus:rs.filter(r=>!LABELS[r.status]).length,
   demand:total(known.filter(r=>r.status!=='CANCELLED').map(r=>r.quantities['需求数量'])),
   issued:total(rs.map(r=>r.quantities['已发数量'])),consumed:total(rs.map(r=>r.quantities['实际耗用'])),returned:total(rs.map(r=>r.quantities['退回数量'])),
   waiting:{sum:waiting.filter(v=>v!==null).reduce(add,'0'),complete:waiting.every(v=>v!==null)&&rs.every(r=>!!LABELS[r.status])}
  });
 }
 const quantities=records.flatMap(r=>Object.values(r.quantities)),allowed=[];
 if(records.some(r=>r.quantities['实际耗用'].kind==='zero'&&r.status!=='CANCELLED'))allowed.push('CHECK_USAGE');
 if(records.some(r=>r.status==='CANCELLED'))allowed.push('REVIEW_CANCELLED');
 if(records.some(r=>r.quantities['退回数量'].kind==='value'))allowed.push('REVIEW_RETURNS');
 if(quantities.some(q=>q.kind==='missing')||records.some(r=>!r.unit))allowed.push('COMPLETE_FIELDS');
 if(quantities.some(q=>q.kind==='invalid'))allowed.push('CHECK_INVALID');
 if(groups.some(g=>g.unit&&g.waiting.complete&&g.waiting.sum!=='0'))allowed.push('CHECK_PENDING');
 if(records.some(r=>!LABELS[r.status]))allowed.push('CHECK_STATUS');
 return {version:VERSION,hash:digest(rows),records,groups,allowed,missing:quantities.filter(q=>q.kind==='missing').length,invalid:quantities.filter(q=>q.kind==='invalid').length,zero:quantities.filter(q=>q.kind==='zero').length};
}
function selection(f,value){
 if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).length!==1||!Object.hasOwn(value,'reviewCodes')||!Array.isArray(value.reviewCodes)||value.reviewCodes.length>7||new Set(value.reviewCodes).size!==value.reviewCodes.length||value.reviewCodes.some(code=>typeof code!=='string'||!f.allowed.includes(code)))throw Error('本地模型建议未通过口径校验；未采用自由文本或不适用的核对项。');
 return [...value.reviewCodes];
}
function measure(q,unit){const suffix=unit?' '+unit:'（单位未填写，不作跨行合计）';if(q.kind==='missing')return '未填写';if(q.kind==='invalid')return '格式无效';return q.text+suffix+(q.kind==='zero'?'（已记录）':'')}
function totalText(q,unit){return (q.complete?'':'已知 ')+q.sum+' '+unit+(!q.complete?'（有未填、无效或未明确状态的记录，不能视为完整合计）':'')}
function render(f,codes=null){
 const lines=['记录口径（系统计算）','0是已记录零值；空白/null是未填写，格式无效单列。','取消单保留历史，不计入未取消需求或待发量；已关闭单不再计算待发。','已发、实耗、退回分别取各自记录，不能互相代替。'];
 for(const g of f.groups){if(!g.unit){lines.push('单位未填写：'+g.rows+'行，不合并数量。');continue}lines.push('单位 '+g.unit+' · '+g.rows+'行（取消'+g.cancelled+'，关闭'+g.closed+'）',
  '未取消需求（含关闭单）：'+totalText({...g.demand,complete:g.demand.complete&&g.unknownStatus===0},g.unit)+'；未结束待发：'+totalText(g.waiting,g.unit),
  '已发累计：'+totalText(g.issued,g.unit)+'；实耗累计：'+totalText(g.consumed,g.unit)+'；退回累计：'+totalText(g.returned,g.unit));}
 lines.push('逐单依据');for(const r of f.records)lines.push(r.id+' · '+r.statusLabel+'：已发 '+measure(r.quantities['已发数量'],r.unit)+'；实耗 '+measure(r.quantities['实际耗用'],r.unit)+'；退回 '+measure(r.quantities['退回数量'],r.unit)+(r.status==='CANCELLED'?'；取消需求不计入待发，不补发。':r.status==='CLOSED'?'；关单后不再待发。':''));
 lines.push('数量未填写 '+f.missing+' 项；格式无效 '+f.invalid+' 项。');
 if(codes!==null){lines.push('核对建议（智能筛选，系统校验）',...(codes.length?codes.map(c=>REVIEW[c]):['本次没有选择额外核对项。']))}else lines.push('以上为已核实记录；尚未生成本次核对建议。');
 return lines.join('\n\n');
}
function proof(f,codes){selection(f,{reviewCodes:codes});return {version:VERSION,factHash:f.hash,reviewCodes:codes}}
function verify(f,p){if(!p||p.version!==VERSION||p.factHash!==f.hash)throw Error('分析口径或原始记录已变化，请重新分析。');return selection(f,{reviewCodes:p.reviewCodes})}
function safeTask(task){
 if(task.status!=='DONE'||!['macmini','online'].includes(task.source)||!(/领料|取消|已发|实耗|耗用|消耗|退料|退回|零值|实际用量|发出|发料/.test(task.question||'')||task.ledgerReview))return task;
 try{const f=facts(task.ledgerRows);return {...task,answer:(task.ledgerHeader||'')+render(f,verify(f,task.ledgerReview))}}
 catch{return {...task,status:'ERROR',answer:null,legacyAnswerRetained:true,error:'这份历史领料解读未通过当前口径校验，请重新提问。原始记录和历史内容仍保留。'}}
}
module.exports={VERSION,FIELDS,REVIEW,numeric,add,isLedger,facts,selection,render,proof,verify,safeTask};
