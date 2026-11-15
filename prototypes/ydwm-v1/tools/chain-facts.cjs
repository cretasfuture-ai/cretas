/* Explicit context records; a plan without reporting is not an empty business context. */
exports.rows=c=>{
 const p=c.plan,r=c.recipe,submitted=(c.reports||[]).filter(x=>x.submissionStatus==='SUBMITTED');
 const rows=[{对象:'当前生产计划',计划编号:p?.planNumber??null,计划状态:p?.statusDisplayName||p?.status||null,计划数量:p?.plannedQuantity??null,计划单位:p?.plannedUnit||p?.unit||null,计划绑定工序版本:p?.selectedWorkflowVersion??null,计划绑定BOM版本:p?.selectedBomVersion??null,当前画布版本:c.working?.version??null,正式报工记录数:submitted.length,实际产量依据:submitted.length?'按逐条正式报工记录及各自单位核对，不以计划量代替':'尚无正式报工，实际产量未提供'}];
 if(r)rows.push({对象:'正在查看的BOM',产品:c.product?.name||p?.productName||null,配方版本:r.version??null,配方状态:r.status??null,产出基数:r.outputQuantityPerUnit??null,产出单位:r.outputUnit??null,整体得率百分比:r.overallYieldRate??null,与计划绑定关系:p&&String(p.selectedBomRecipeId)===String(r.id)?'计划绑定版本':'当前查看版本，不等于计划绑定版本',原料条目数:r.items?.length??0,工序辅料条目数:r.seasoningItems?.length??0,说明:'BOM为标准用量，实耗以正式报工记录为准'});
 if(c.node)rows.push({对象:'当前选中节点',节点名称:c.node.data?.processName||c.node.data?.name||c.node.id,配置:c.node.data||{}});
 return rows;
};
