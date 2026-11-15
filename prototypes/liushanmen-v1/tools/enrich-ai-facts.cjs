const fs=require('fs'),path=require('path'),file=path.resolve(__dirname,'../server.cjs');let s=fs.readFileSync(file,'utf8');function edit(a,b){if(s.split(a).length!==2)throw Error('AI facts anchor '+a.slice(0,40));s=s.replace(a,b)}
edit("核算条件:c?.issues.join('；')","核算条件:c?.issues.length?c.issues.join('；'):'无额外核算阻断'");
edit("数量口径:'成品量取工单良品", "BOM标准依据:s.resources.bom.filter(b=>b.lastProductCode===p.productCode).map(b=>`BOM行ID ${b.id}，产出编码 ${b.lastProductCode} → 投入编码 ${b.nextProductCode}，标准数量 ${b.unitQty??'未填写'} / 产出基数 ${b.parentBaseQty??'未填写'}，投料工序编码 ${b.feedProcessCode??'未绑定'}`).join('；')||'当前来源未返回此产品BOM，不推算替代配方',领退料依据:(c?.materialLinesDetail||[]).map(m=>`来源单据 ${m.orderCode??'未填写'}，材料 ${m.materialCode??m.material??'未填写'}，净耗用 ${m.netConsumption??'未填写'} ${m.unit??'单位未填写'}，已记录金额 ${m.recognizedCost??'未填写'}`).join('；')||'当前来源未能关联工单领退料明细',数量口径:'成品量取工单良品");
edit("name:'六膳门产品与工单成本',factoryId:","name:'六膳门产品与工单成本',factContract:'liushanmen-blacklake-v1',factoryId:");
fs.writeFileSync(file,s);
