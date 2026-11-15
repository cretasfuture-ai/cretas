(()=>{'use strict';if(window.DEMO_PAGE?.id!=='D-V3-SO-edit')return;
const A=window.SALES_PICKER_COLUMNS_ASSET;let panel,trigger,bypass=false;
window.DEMO_INTERACTION_ASSETS.pickerSchemas['D-OUTSOURCE-product-picker']=A.schema;
function close(){panel?.remove();panel=null;trigger?.setAttribute('aria-expanded','false');}
function fieldIcons(){
 const dialog=document.querySelector('.demo-native-column-dialog');if(!dialog)return;
 if(!dialog.dataset.salesFieldIcons){dialog.dataset.salesFieldIcons='true';dialog.addEventListener('input',()=>queueMicrotask(fieldIcons));}
 for(const [selector,cls]of [['.option-label','option-icon'],['.kodo-art-fct-item-label','kodo-art-fct-item-icon']]){
  for(const label of dialog.querySelectorAll(selector)){
   const svg=A.icons[label.textContent.trim()];if(!svg)continue;
   let icon=label.previousElementSibling;
   if(!icon?.classList.contains(cls)){icon=document.createElement('span');icon.className=cls;label.before(icon);}
   icon.innerHTML='<iconpark-icon>'+svg+'</iconpark-icon>';
  }
 }
}
function open(button){
 close();trigger=button;const t=document.createElement('template');t.innerHTML=A.html;panel=t.content.firstElementChild;
 panel.style.cssText='position:fixed;inset:auto;box-sizing:border-box;min-width:95px;z-index:2147483550';panel.dataset.salesPickerColumns='true';document.body.append(panel);
 const a=button.getBoundingClientRect(),b=panel.getBoundingClientRect();panel.style.left=Math.max(4,Math.min(innerWidth-b.width-4,a.left))+'px';panel.style.top=Math.max(4,Math.min(innerHeight-b.height-4,a.bottom+4))+'px';button.setAttribute('aria-expanded','true');
 const search=panel.querySelector('input');search.setAttribute('aria-label','搜索产品选择器字段');search.addEventListener('input',()=>{
  for(const row of panel.querySelectorAll('.kodo-art-table-toolbar-field-config-item'))row.style.display=row.querySelector('.kodo-art-table-toolbar-field-config-item-title').textContent.toLowerCase().includes(search.value.trim().toLowerCase())?'':'none';
 });
 panel.addEventListener('click',e=>{
  e.stopPropagation();
  if(e.target.closest('.kodo-art-table-toolbar-field-config-batch-btn')){
   close();bypass=true;try{trigger.click();}finally{bypass=false;}fieldIcons();
  }else if(e.target.closest('.kodo-art-table-toolbar-field-config-item-right-icon'))window.DEMO_CONTEXT.toast('字段开关和固定变更的源站恢复尚未核实，暂不应用');
 });
 panel.addEventListener('dragstart',e=>e.preventDefault());
}
window.addEventListener('click',e=>{
 if(bypass)return;
 const button=e.target.closest('.kodo-art-table-toolbar-item');
 const layer=button?.closest('.demo-nested-layer');
 if(button&&layer&&button.textContent.trim().endsWith('字段配置')&&layer.querySelector('[role=dialog]')?.textContent.includes('选择产品')){
  e.preventDefault();e.stopImmediatePropagation();if(panel)close();else open(button);return;
 }
 if(panel&&!panel.contains(e.target))close();
},true);
window.addEventListener('keydown',e=>{if(e.key==='Escape'&&panel){e.preventDefault();e.stopImmediatePropagation();close();trigger?.focus();}},true);
window.addEventListener('resize',close);document.documentElement.dataset.salesPickerColumnsReady='true';
})();
