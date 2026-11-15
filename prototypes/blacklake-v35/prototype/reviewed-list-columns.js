(()=>{'use strict';
const A=window.REVIEWED_LIST_COLUMNS_ASSETS?.[window.DEMO_PAGE?.id];if(!A)return;
window.DEMO_PAGE.columnSchema=A.schema;
if(!A.hasTypeIcons){const style=document.createElement('style');style.textContent='.demo-native-column-dialog .option-icon,.demo-native-column-dialog .kodo-art-fct-item-icon{display:none!important}';document.head.append(style);}
const duplicateLabels=new Set(A.schema.filter((field,index)=>A.schema.some((other,otherIndex)=>otherIndex!==index&&other.label===field.label)).map(field=>field.label));
let panel,trigger,bypass=false;
function close(){panel?.remove();panel=null;trigger?.setAttribute('aria-expanded','false');}
function fieldIcons(){
 const dialog=document.querySelector('.demo-native-column-dialog');if(!dialog)return;
 if(!dialog.dataset.reviewedListFieldIcons){dialog.dataset.reviewedListFieldIcons='true';dialog.addEventListener('input',()=>queueMicrotask(fieldIcons));
  if(duplicateLabels.size){
   const guard=e=>{const row=e.target.closest('.kodo-new-tree-treenode');const label=row?.querySelector('.option-label')?.textContent.trim();const button=e.target.closest('button');if(duplicateLabels.has(label)||(e.type==='click'&&button?.textContent.trim()==='确定')){e.preventDefault();e.stopImmediatePropagation();window.DEMO_CONTEXT.toast('同名字段的修改尚未完成源站验证，暂不应用；可搜索或取消');}};
   dialog.addEventListener('click',guard,true);dialog.addEventListener('keydown',e=>{if(e.key===' '||e.key==='Enter')guard(e);},true);
  }
 }
 const occurrences=new Map();
 for(const label of dialog.querySelectorAll('.option-label')){
  const name=label.textContent.trim();if(!duplicateLabels.has(name))continue;
  const index=occurrences.get(name)||0;occurrences.set(name,index+1);const field=A.schema.filter(x=>x.label===name)[index];const row=label.closest('.kodo-new-tree-treenode');const checkbox=row?.querySelector('[role=checkbox]');if(!field||!checkbox)continue;
  checkbox.dataset.reviewedFieldOccurrence=String(index);checkbox.setAttribute('aria-checked',String(field.checked));checkbox.classList.toggle('kodo-new-tree-checkbox-checked',field.checked);row.classList.toggle('kodo-new-tree-treenode-checkbox-checked',field.checked);
 }
 for(const [selector,cls]of [['.option-label','option-icon'],['.kodo-art-fct-item-label','kodo-art-fct-item-icon']])for(const label of dialog.querySelectorAll(selector)){
  const svg=A.icons[label.textContent.trim()];if(!svg)continue;let icon=label.previousElementSibling;
  if(!icon?.classList.contains(cls)){icon=document.createElement('span');icon.className=cls;label.before(icon);}
  icon.innerHTML='<iconpark-icon>'+svg+'</iconpark-icon>';
 }
}
function open(button){
 close();trigger=button;const t=document.createElement('template');t.innerHTML=A.html;panel=t.content.firstElementChild;
 panel.style.cssText='position:fixed;inset:auto;box-sizing:border-box;min-width:95px;z-index:2147483550';panel.dataset.reviewedListColumns='true';document.body.append(panel);
 const a=button.getBoundingClientRect(),b=panel.getBoundingClientRect();panel.style.left=Math.floor(Math.max(4,Math.min(innerWidth-b.width-4,a.left)))+'px';panel.style.top=Math.floor(Math.max(4,Math.min(innerHeight-b.height-4,a.bottom+4)))+'px';button.setAttribute('aria-expanded','true');
 const search=panel.querySelector('input');search.setAttribute('aria-label','搜索列表字段');search.addEventListener('input',()=>{
  for(const row of panel.querySelectorAll('.kodo-art-table-toolbar-field-config-item'))row.style.display=row.querySelector('.kodo-art-table-toolbar-field-config-item-title').textContent.toLowerCase().includes(search.value.trim().toLowerCase())?'':'none';
 });
 panel.addEventListener('click',e=>{e.stopPropagation();if(e.target.closest('.kodo-art-table-toolbar-field-config-batch-btn')){close();bypass=true;try{trigger.click();}finally{bypass=false;}fieldIcons();}else if(e.target.closest('.kodo-art-table-toolbar-field-config-item-right-icon'))window.DEMO_CONTEXT.toast('源站未验证字段开关和固定变更的恢复，暂不应用');});
 panel.addEventListener('dragstart',e=>e.preventDefault());
}
window.addEventListener('click',e=>{
 if(bypass)return;const button=e.target.closest('.kodo-art-table-toolbar-item');
 if(button&&!button.closest('.demo-nested-layer,[role=dialog]')&&button.textContent.trim().endsWith('字段配置')){e.preventDefault();e.stopImmediatePropagation();if(panel)close();else open(button);return;}
 if(panel&&!panel.contains(e.target))close();
},true);
window.addEventListener('keydown',e=>{if(e.key==='Escape'&&panel){e.preventDefault();e.stopImmediatePropagation();close();trigger?.focus();}},true);
window.addEventListener('resize',close);
})();
