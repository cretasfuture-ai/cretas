(()=>{'use strict';
const C=window.DEMO_CONTEXT,P=window.DEMO_PAGE,A=window.REVIEWED_V35_ASSETS;
if(!C||!P)return;
if(P.id==='D-PASSIVE-BL-09-12-blankform22'){
 const query=new URLSearchParams(location.search),mode=query.get('fee35Mode')==='edit'?'edit':'create',id=mode==='edit'?query.get('fee35Record'):null;
 location.replace('BL-09-12.html?'+new URLSearchParams({fee35Mode:mode,...(id?{fee35Record:id}:{})}));return;
}
if(P.id!=='BL-09-12'||!C.feeLifecycle35||!A)return;
const F=C.feeLifecycle35,$=(s,r=document)=>r.querySelector(s),$$=(s,r=document)=>[...r.querySelectorAll(s)];
$$('.ant-breadcrumb a,.kodo-new-breadcrumb a',document).filter(a=>a.textContent.trim()==='基础数据').forEach(a=>{a.href='BL-09-01.html';a.setAttribute('aria-label','基础数据');});
let modal,mode,record,expected,origin,enabled=true,expenseType='加项',busy=false;
function close(){modal?.remove();modal=null;origin?.isConnected?origin.focus():$('[data-fee35-bin]')?.focus();}
function paint(){if(!modal)return;for(const radio of $$('input[type=radio]',modal)){const chosen=radio.value===(expenseType==='减项'?'2':'1');radio.checked=chosen;radio.closest('.kodo-new-radio')?.classList.toggle('kodo-new-radio-checked',chosen);radio.closest('label')?.classList.toggle('kodo-new-radio-wrapper-checked',chosen);}const toggle=$('[role=switch]',modal);if(toggle){toggle.setAttribute('aria-checked',String(enabled));toggle.classList.toggle('kodo-new-switch-checked',enabled);}}
function showError(text){let error=$('[data-fee35-error]',modal);if(!error){error=document.createElement('div');error.dataset.fee35Error='';error.setAttribute('role','alert');$('.kodo-new-modal-body',modal)?.append(error);}error.textContent=text;}
function open(kind,anchor,id){
 close();origin=anchor;mode=kind;busy=false;if(kind!=='create'&&!id){C.toast('未选择费用记录，请从列表打开');return;}record=id?F.list().find(r=>r.id===id):null;
 if(id&&!record){C.toast('费用记录已不存在，请刷新后重试');return;}
 expected=record?F.token(record):undefined;enabled=record?!['否','关','false','0'].includes(String(record.fields['是否启用'])):true;expenseType=record?.fields['费用类型']==='减项'?'减项':'加项';
 modal=document.createElement('div');modal.dataset.fee35Form=kind;modal.innerHTML=A[kind];document.body.append(modal);
 const name=$('input[type=text]',modal);if(name){name.value=kind==='edit'?String(record.fields['费用名称']||''):'';name.maxLength=200;name.setAttribute('aria-label','费用名称');}
 if(kind==='delete'){$('.kodo-new-modal-confirm-content',modal).textContent='仅删除本地记录，可在回收站恢复。';}
 paint();(kind==='delete'?$('[data-fee35-cancel]',modal):name)?.focus();
}
function submit(){if(!modal||busy)return;busy=true;try{
 if(mode==='delete'){F.remove(record.id,expected);C.toast('本地费用已删除，可在本地回收站恢复');}
 else{const values={'费用名称':$('input[type=text]',modal).value.trim(),'费用类型':expenseType,'是否启用':enabled?'是':'否'};if(mode==='create')F.create(values);else F.update(values,record.id,expected);C.toast(mode==='create'?'本地费用创建成功':'本地费用修改已保存');}
 close();
 }catch(error){busy=false;showError(error.message);}}
const toolbar=document.createElement('button');toolbar.dataset.fee35Bin='';toolbar.textContent='本地回收站';toolbar.className='kodo-new-btn css-uy6x3o kodo-new-btn-default';const create=$$('button').find(b=>b.textContent.trim()==='创建费用项目');create?.after(toolbar);
function binError35(dialog,text){let error=$('[data-fee35-bin-error]',dialog);if(!error){error=document.createElement('p');error.dataset.fee35BinError='';error.setAttribute('role','alert');error.style.color='#cf1322';$('[data-fee35-bin-list]',dialog).before(error);}error.textContent=text;}
function bin(){const dialog=C.dialog('本地回收站（原型增强）','<p>黑湖源站提示删除后无法恢复；以下恢复仅作用于本地演示。</p><div data-fee35-bin-list></div>'),holder=$('[data-fee35-bin-list]',dialog),items=F.deleted();if(!items.length){holder.textContent='暂无本地删除记录';return;}for(const item of items){const row=document.createElement('div'),name=document.createElement('span'),restore=document.createElement('button'),logs=document.createElement('button');row.className='fee35-bin-row';name.textContent=String(item.record.fields['费用名称'])+' · '+item.record.id;restore.textContent='恢复';logs.textContent='操作日志';restore.onclick=event=>{event.stopPropagation();try{F.restore(item.record.id);row.remove();C.toast('本地费用已恢复，原ID和日志保留');if(!holder.children.length)holder.textContent='暂无本地删除记录';}catch(error){binError35(dialog,error.message);if(!F.deleted().some(x=>x.record.id===item.record.id)){restore.disabled=true;restore.textContent='已恢复';}}};logs.onclick=event=>{event.stopPropagation();dialog.close();C.openFeeLog35(item.record.id,toolbar);};row.append(name,logs,restore);holder.append(row);}}
window.addEventListener('click',e=>{
 const el=e.target;
 if(modal&&el.closest?.('[data-fee35-form]')){e.stopImmediatePropagation();if(el.matches('input[type=text],input[type=radio]'))return;e.preventDefault();const label=el.closest('label');if(label?.querySelector('input[type=radio]')){expenseType=label.textContent.trim();paint();return;}if(el.closest('[role=switch]')){enabled=!enabled;paint();return;}if(el.closest('[data-fee35-cancel],.kodo-new-modal-close')){close();return;}if(el.closest('[data-fee35-submit]'))submit();return;}
 const binButton=el.closest?.('[data-fee35-bin]');if(binButton){e.preventDefault();e.stopImmediatePropagation();bin();return;}
 const hit=el.closest?.('button,[role=button]');if(!hit||el.closest('[data-v34-log],dialog,.demo-bar'))return;const label=hit.textContent.trim();
 if(label==='创建费用项目'){e.preventDefault();e.stopImmediatePropagation();open('create',hit);return;}
 const row=hit.closest('[data-demo-record]');if(row&&['编辑','删除'].includes(label)){e.preventDefault();e.stopImmediatePropagation();open(label==='编辑'?'edit':'delete',hit,row.dataset.demoRecord);}
},true);
window.addEventListener('input',e=>{if(e.target.closest?.('[data-fee35-form]'))e.stopImmediatePropagation();},true);
window.addEventListener('change',e=>{if(!e.target.closest?.('[data-fee35-form]'))return;e.stopImmediatePropagation();if(e.target.matches('input[type=radio]')){expenseType=e.target.value==='2'?'减项':'加项';paint();}},true);
window.addEventListener('keydown',e=>{if(!modal)return;if(e.key==='Escape'){e.preventDefault();e.stopImmediatePropagation();close();return;}if(!e.target.closest('[data-fee35-form]'))return;e.stopImmediatePropagation();if(e.key==='Enter'&&!e.isComposing&&e.target.matches('input[type=text]')){e.preventDefault();submit();}if(e.key==='Tab'){const list=$$('button,input',modal).filter(x=>!x.disabled&&x.getClientRects().length),first=list[0],last=list.at(-1);if(e.shiftKey&&document.activeElement===first){e.preventDefault();last?.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first?.focus();}}},true);
window.addEventListener('storage',e=>{if(e.key==='blacklake-demo-v3-BL-09-12'){try{F.refresh();}catch(error){C.toast(error.message);}}});
const undo=$('[data-demo-undo]');if(undo){const old=undo.onclick;undo.onclick=()=>{const items=F.deleted();if(items.length){try{F.restore(items.at(-1).record.id);C.toast('本地费用已恢复，详见操作日志');}catch(error){C.toast(error.message);}}else old?.();};}
const q=new URLSearchParams(location.search),start=q.get('fee35Mode');if(['create','edit'].includes(start)){history.replaceState(null,'','BL-09-12.html');open(start,create,q.get('fee35Record')||undefined);}
})();
