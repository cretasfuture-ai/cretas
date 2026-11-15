(()=>{'use strict';
if(window.DEMO_PAGE?.id!=='BL-11-09')return;
const style=document.createElement('style');style.textContent='.demo-native-column-dialog .option-icon,.demo-native-column-dialog .kodo-art-fct-item-icon{display:none!important}';document.head.append(style);
let panel,trigger;
function close(){panel?.remove();panel=null;trigger?.setAttribute('aria-expanded','false');}
function open(button){
 close();trigger=button;
 const template=document.createElement('template');template.innerHTML=window.PUNCH_COLUMNS_ASSET;
 panel=template.content.firstElementChild;
 panel.style.cssText='position:fixed;inset:auto;box-sizing:border-box;min-width:95px;z-index:1050';
 panel.dataset.punchColumns='true';document.body.append(panel);
 const anchor=button.getBoundingClientRect(),box=panel.getBoundingClientRect();
 panel.style.left=Math.max(4,Math.min(innerWidth-box.width-4,anchor.left))+'px';
 panel.style.top=Math.max(4,Math.min(innerHeight-box.height-4,anchor.bottom+4))+'px';
 button.setAttribute('aria-expanded','true');
 const input=panel.querySelector('input');input.setAttribute('aria-label','搜索字段');
 input.addEventListener('input',()=>{
  const q=input.value.trim().toLowerCase();
  for(const row of panel.querySelectorAll('.kodo-art-table-toolbar-field-config-item')){
   const matches=row.querySelector('.kodo-art-table-toolbar-field-config-item-title').textContent.toLowerCase().includes(q);
   row.hidden=!matches;row.style.display=matches?'':'none';
  }
 });
 panel.addEventListener('click',e=>{
  e.stopPropagation();
  if(e.target.closest('.kodo-art-table-toolbar-field-config-batch-btn')){
   close();window.DEMO_CONTEXT.columnDialog();
  }else if(e.target.closest('.kodo-art-table-toolbar-field-config-item-right-icon')){
   window.DEMO_CONTEXT.toast('此字段变更的源站恢复路径尚未核实，暂不模拟提交');
  }
 });
 // Source drag/pin/visibility writes are not yet accepted; keep them inert.
 panel.addEventListener('dragstart',e=>e.preventDefault());
}
window.addEventListener('click',e=>{
 const button=e.target.closest('button,.kodo-art-table-toolbar-item');
 if(button&&!panel?.contains(button)&&button.textContent.trim().endsWith('字段配置')){
  e.preventDefault();e.stopImmediatePropagation();if(panel)close();else open(button);return;
 }
 if(panel&&!panel.contains(e.target))close();
},true);
window.addEventListener('keydown',e=>{if(e.key==='Escape'&&panel){e.preventDefault();e.stopImmediatePropagation();close();trigger?.focus();}},true);
window.addEventListener('resize',close);
document.documentElement.dataset.punchColumnsReady='true';
})();
