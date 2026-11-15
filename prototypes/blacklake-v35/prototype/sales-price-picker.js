(()=>{'use strict';
// Source verified on the sales edit form; purchasing keeps its own prerequisite.
if(window.DEMO_PAGE?.id!=='D-V3-SO-edit')return;
window.addEventListener('click',event=>{
 const button=event.target.closest('button');
 if(!button||button.textContent.replace(/\s+/g,'')!=='按价目表添加产品')return;
 const scope=button.closest('[role=dialog]')||document;
 const tables=[...scope.querySelectorAll('.kodo-art-table')];
 const table=tables.find(t=>t.querySelector('.kodo-art-table-thead')?.textContent.includes('产品编号'))||tables.at(-1);
 if(!table||!window.DEMO_CONTEXT?.openNativePicker)return;
 event.preventDefault();event.stopImmediatePropagation();
 window.DEMO_CONTEXT.openNativePicker(button,table,null,'productPicker');
},true);
document.documentElement.dataset.salesPricePickerReady='true';
})();
