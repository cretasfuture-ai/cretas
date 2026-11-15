(()=>{'use strict';const S=window.REVIEWED_V24_STORE,A=window.REVIEWED_V24_ASSETS,$=(s,r=document)=>r.querySelector(s);let active;
function mountList(host){active=host;const rows=[...host.querySelectorAll('button')].filter(b=>b.textContent.trim()==='复制');if(!host._v24Row){host._v24Row=rows[1]?.parentElement?.parentElement?.cloneNode(true);host._v24SystemRow=rows[0]?.parentElement?.parentElement?.cloneNode(true);}
 const original=rows.map(b=>b.parentElement.parentElement);if(!host._v24Parent)host._v24Parent=original[0]?.parentElement;
 if(!host._v24Parent||!host._v24Row)return;
 original.forEach(e=>e.remove());host.querySelectorAll('[data-v24-row]').forEach(e=>e.remove());
 S.read().forEach((record,i)=>{const row=(record.system?host._v24SystemRow:host._v24Row).cloneNode(true);row.dataset.v24Row=record.id;const textNodes=[];const walk=document.createTreeWalker(row,NodeFilter.SHOW_TEXT);while(walk.nextNode())textNodes.push(walk.currentNode);for(const t of textNodes){if(/^[12]$/.test(t.textContent.trim()))t.textContent=String(i+1);if(['销售订单','销售合同（A4）'].includes(t.textContent.trim()))t.textContent=record.name;}
 const edit=[...row.querySelectorAll('button')].find(b=>b.textContent.trim()==='编辑');if(edit)edit.disabled=!!record.system;
 host._v24Parent.append(row);});
 for(const el of host.querySelectorAll('*'))if(el.childElementCount===0&&/^已创建\d+个，最多创建50个$/.test(el.textContent))el.textContent='已创建'+S.read().length+'个，最多创建50个';}
function open(mode,id){const url=new URL('D-V24-print-designer.html',location.href);url.searchParams.set('mode',mode);if(id)url.searchParams.set('id',id);window.open(url.href,'_blank');}
function handle(t,host){const button=t.closest('button');if(!button)return false;const label=button.textContent.trim();if(label==='新建模板'){if(S.read().length>=50){window.DEMO_CONTEXT.toast('最多创建50个');return true;}open('create');return true;}
 const row=button.closest('[data-v24-row]');if(!row||!['复制','编辑','删除'].includes(label))return false;const record=S.read().find(r=>r.id===row.dataset.v24Row);if(!record)return true;
 if(label==='复制'){open('copy',record.id);return true;}if(label==='编辑'){if(!record.system)open('edit',record.id);return true;}
 document.querySelector('[data-v24-delete]')?.remove();const box=document.createElement('div');box.dataset.v24Delete=record.id;box.dataset.reviewedV22Ui='';box.dataset.v22Level='2';box.innerHTML='<div class="kodo-new-modal-mask"></div><div class="kodo-new-modal-wrap">'+A.deleteConfirm+'</div>';document.body.append(box);
 const content=$('.kodo-new-modal-confirm-content',box);if(content)content.textContent='将删除模板"'+record.name+'"，删除后不可恢复。';
 return true;}
function handleDelete(t){const box=t.closest('[data-v24-delete]'),b=t.closest('button');if(!box||!b)return;if(b.textContent.replace(/\s/g,'')==='删除'){try{S.write(S.read().filter(r=>r.id!==box.dataset.v24Delete));mountList(active);}catch{window.DEMO_CONTEXT.toast('本地存储失败，模板未删除');return;}}box.remove();}
window.addEventListener('storage',e=>{if(e.key===S.key&&active?.isConnected)mountList(active);});
window.addEventListener('click',e=>{if(e.target.closest?.('[data-v24-delete]')){e.preventDefault();e.stopImmediatePropagation();handleDelete(e.target);}},true);
window.addEventListener('keydown',e=>{if(e.key==='Escape'&&document.querySelector('[data-v24-delete]')){e.preventDefault();e.stopImmediatePropagation();document.querySelector('[data-v24-delete]').remove();}},true);
window.REVIEWED_V24={mountList,handle,handleDelete};})();
