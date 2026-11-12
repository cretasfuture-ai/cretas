// Run from a private artifact directory linked to the bundled artifact-tool runtime.
import fs from 'node:fs/promises';
import path from 'node:path';
import {Workbook,SpreadsheetFile} from '@oai/artifact-tool';
const dir=process.argv[2];
const data=JSON.parse(await fs.readFile(path.join(dir,'checklist.json'),'utf8'));
const wb=Workbook.create();
const specs=[
 ['93张工单',['工单编码','产品','原始主分类','复查后主分类','当前状态','最终良品量','关联材料行数','材料净成本（原值）','任务数','工资字段已知任务数','成品单位','全部阻断原因（可重叠）','需要补核的资料或动作','责任角色'],data.detail],
 ['材料估值缺口',['工单编码','材料编码','材料','材料单位','领退料单','业务时间（源端）','业务类型','库存变动数量（原值）','成本单价（原值）','带符号成本金额（原值）','零值或缺失','参考价格线索（不能回填）','此前正价入库候选行数','候选入库单','待核动作'],data.materials],
 ['成品数量缺口',['工单编码','产品','成品单位','工单最终良品','工单实际量','工单入库量','任务数','正审批良品任务数','报工数','正良品报工数','精确关联成品入库行数','关联入库单','口径说明'],data.quantity],
 ['人工缺口',['工单编码','工序或问题','工序序号','累计报工秒数（原值）','累计工资（原值）','关联报工数','待核动作'],data.labor],
 ['未关联领退料',['领退料单','业务类型','材料编码','材料','关联业务ID（原值）','关联工单编码（原值）','关联类型（原值）','备注或来源字段工单编码线索','待核动作'],data.unlinked]
];
const summary=wb.worksheets.add('分类与处理');
for(const spec of specs){if(spec[0]!=='93张工单')spec[1].push('缺失字段或记录','源端显式零值字段');}
for(const [name] of specs)wb.worksheets.add(name);
function style(sheet,headers,rows,index){
 sheet.showGridLines=false;
 const all=[headers,...rows];
 sheet.getRangeByIndexes(0,0,all.length,headers.length).values=all;
 const used=sheet.getRangeByIndexes(0,0,all.length,headers.length);
 used.format.font={name:'Microsoft YaHei',size:10,color:'#243746'};
 used.format.columnWidth=23;used.format.rowHeight=60;used.format.wrapText=true;used.format.verticalAlignment='center';
 sheet.getRange('A:A').format.columnWidth=29;
 if(sheet.name==='93张工单')sheet.getRange('C:D').format.columnWidth=38;
 sheet.getRangeByIndexes(0,0,1,headers.length).format={fill:'#123B4A',font:{name:'Microsoft YaHei',bold:true,color:'#FFFFFF',size:11},rowHeight:38};
 if(rows.length)sheet.tables.add(`A1:${String.fromCharCode(64+headers.length)}${all.length}`,true,`GapTable${index}`);
 sheet.freezePanes.freezeRows(1);sheet.freezePanes.freezeColumns(1);
}
// A1 ranges are explicit because export includes all real rows and filter buttons.
for(let i=0;i<specs.length;i++){
 const [name,heads,rows]=specs[i];style(wb.worksheets.getItem(name),heads,rows,i);
}
const categories=['未完工，暂不核算成品单位成本','已完工但缺正数成品良品量','已完工有成品但无关联领退料','已完工材料金额全部为零','已完工部分领料零价或缺价'];
const actions=['待实际完工后核对；属于生产进度条件','核实最终良品量、是否空单或取消','补真实领退料及工单ID/编码关联','核实历史材料估值，区分真实零成本与漏估值','逐材料补核历史单价/成本金额'];
summary.showGridLines=false;
summary.getRange('A1:D1').merge();summary.getRange('A1').values=[['六膳门｜93张工单成本缺口清单']];
summary.getRange('A2:D2').merge();summary.getRange('A2').values=[[`官方只读复查完成：${data.finished_at}（UTC）；原93张固定队列`]];
summary.getRange('A4:D4').values=[['互斥主分类','原始张数','复查后张数','需要处理的事项']];
for(let i=0;i<categories.length;i++){
 const row=5+i;summary.getRange(`A${row}:D${row}`).values=[[categories[i],null,null,actions[i]]];
 summary.getRange(`B${row}`).formulas=[[`=COUNTIF('93张工单'!C2:C94,A${row})`]];
 summary.getRange(`C${row}`).formulas=[[`=COUNTIF('93张工单'!D2:D94,A${row})`]];
}
summary.getRange('A10:D10').values=[['合计',null,null,'主分类互斥；全部阻断原因可重叠，不能相加']];
summary.getRange('B10:C10').formulas=[['=SUM(B5:B9)','=SUM(C5:C9)']];
const notes=[
 ['复查结果',`22次官方只读查询；新增可核算0张；原93张均返回；黑湖业务写入0次。`],
 ['优先处理',`已完工75张：材料零价60张、成品良品为零9张、无关联领退料6张。`],
 ['材料明细',`74条待核材料行覆盖63张工单（包含未完工）；仅2条有正数参考价，未检出同ID/编码/单位的此前正价入库。`],
 ['数量及人工',`9张无正数最终良品：任务审批良品、报工及精确关联成品入库均无正数证据。人工清单3行：2张无任务，1张有工时但工资为零。`],
 ['无法关联',`另有2条生产领退料明细缺可靠归属；来源/备注字段未检出已知工单编码线索，未猜配到6张工单。`],
 ['成本口径','可识别直接成本仅含核实材料与人工；不等于完整制造成本或毛利。当前参考价格不代替历史成本，零值不视作缺失。'],
 ['来源与范围','黑湖正式环境官方project/task/output/stock_order/product/process查询；只核对六膳门原109工单中的93张。旧归档与SmartBI快照未改写。'],
 ['阅读顺序','先筛“93张工单”的当前分类/责任角色，再按工单编码核对材料、成品数量、人工或未关联领退料明细。'],
 ['隐私','本文件含工厂业务明细，仅存本机授权目录；未包含账号、认证材料或人员姓名。']
];
notes.push(['证据版本',`probe ${data.probe_id}；基线 ${data.baseline_run_id}；scope ${data.scope_digest}`]);
for(let i=0;i<notes.length;i++){
 const r=13+i;summary.getRange(`A${r}`).values=[[notes[i][0]]];summary.getRange(`B${r}:D${r}`).merge();summary.getRange(`B${r}`).values=[[notes[i][1]]];
}
summary.getRange('A1:D22').format.font={name:'Microsoft YaHei',size:11,color:'#243746'};
summary.getRange('A1:D22').format.wrapText=true;summary.getRange('A1:D22').format.rowHeight=48;
summary.getRange('A1:D22').format.verticalAlignment='center';summary.getRange('A22:D22').format.rowHeight=80;
summary.getRange('A:A').format.columnWidth=45;summary.getRange('B:C').format.columnWidth=14;summary.getRange('D:D').format.columnWidth=55;
summary.getRange('A1:D1').format={fill:'#123B4A',font:{name:'Microsoft YaHei',size:19,bold:true,color:'#FFFFFF'},rowHeight:48};
summary.getRange('A4:D4').format.fill='#D8EAE8';summary.getRange('A10:D10').format.fill='#D8EAE8';
summary.freezePanes.freezeRows(4);
wb.recalculate();
await fs.writeFile(path.join(dir,'workbook-inspect.json'),JSON.stringify(await wb.inspect({kind:'region',sheetId:'分类与处理',range:'A4:D10',maxChars:4000})));
if(summary.getRange('B10').values[0][0]!==93 || summary.getRange('C10').values[0][0]!==93)throw Error('CATEGORY_TOTAL_NOT_93');
const output=await SpreadsheetFile.exportXlsx(wb);await output.save(path.join(dir,'六膳门-93张工单成本缺口.xlsx'));
// Bounded previews: full data remains available in the filterable workbook.
for(const [name,range] of [['分类与处理','A1:D22'],...specs.map(([n])=>[n,'A1:F5'])]){
 const preview=await wb.render({sheetName:name,range,scale:1,format:'png'});
 await fs.writeFile(path.join(dir,`${name}-preview.png`),new Uint8Array(await preview.arrayBuffer()));
}
console.log(JSON.stringify({exported:true,sheets:6,projects:data.detail.length,materials:data.materials.length,categoryTotals:[93,93]}));
