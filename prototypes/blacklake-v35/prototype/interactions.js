(()=>{
 'use strict';
 const ctx=window.DEMO_CONTEXT,p=window.DEMO_PAGE,cat=window.DEMO_CATALOG,A=window.DEMO_INTERACTION_ASSETS;
 if(!ctx||!p||!A)return;
 const $=(s,r=document)=>r.querySelector(s),$$=(s,r=document)=>Array.from(r.querySelectorAll(s));
 // React can construct nested button nodes; parsing serialized HTML splits them.
 // Rejoin only relationships recorded by the sanitizer, plus the observed search addon.
 $$('button[data-demo-button-parent]').forEach(e=>{const outer=$('button[data-demo-button-id="'+e.dataset.demoButtonParent+'"]');if(outer&&!outer.contains(e))outer.append(e);});
 $$('.ant-input-group-addon > .ant-input-search-button').forEach(outer=>{const inner=outer.nextElementSibling;if(inner?.matches('button.kodo-new-btn'))outer.append(inner);});
 for(const fix of A.captionFixes?.[p.id]||[]){const button=$$('button')[fix.index];if(!button||button.textContent.trim()===fix.text)continue;const walker=document.createTreeWalker(button,NodeFilter.SHOW_TEXT),nodes=[];while(walker.nextNode())if(walker.currentNode.textContent.trim()&&!walker.currentNode.parentElement.closest('svg,iconpark-icon'))nodes.push(walker.currentNode);if(nodes.length){nodes[0].textContent=fix.text;nodes.slice(1).forEach(n=>n.textContent='');}}
 for(const fix of A.toolbarCaptions?.[p.id]||[]){const scope=$$('[role=dialog]').at(-1)||document,bar=$$('.kodo-art-table-toolbar-item',scope)[fix.index];if(!bar||bar.textContent.trim()===fix.text)continue;const walker=document.createTreeWalker(bar,NodeFilter.SHOW_TEXT),nodes=[];while(walker.nextNode())if(walker.currentNode.textContent.trim())nodes.push(walker.currentNode);if(nodes.length){nodes[0].textContent=fix.text;nodes.slice(1).forEach(node=>node.textContent='');}}
 for(const fix of A.safeCapturedText?.[p.id]||[]){const scope=$$('[role=dialog]').at(-1)||document,node=$$(fix.selector,scope)[fix.index];if(node){const texts=[...node.childNodes].filter(e=>e.nodeType===Node.TEXT_NODE&&e.textContent.trim());if(texts.length){texts[0].textContent=fix.text;texts.slice(1).forEach(e=>e.textContent='');}}}
 for(const fix of A.staticLabelFixes?.[p.id]||[]){const scope=$$('[role=dialog]').at(-1)||document,node=$$(fix.selector,scope)[fix.index];if(node&&!node.children.length&&/演示条目|条演示记录/.test(node.textContent))node.textContent=fix.text;}
 if(A.staticImages?.[p.id]){const scope=$$('[role="dialog"]').at(-1)||document;const imgs=$$('img',scope);for(const item of A.staticImages[p.id]){const img=imgs[item.index];if(img){img.src=item.src;img.style.width=item.width+'px';img.style.height=item.height+'px';}}}
 if(A.commonEmpty)$$('.kodo-art-table-empty-placeholder img').forEach(img=>{img.src=A.commonEmpty;img.width=140;img.height=140;});
 if(['D-CUSTOMREPORT-edit','D-PASSIVE-BL-14-01-blankreport'].includes(p.id))$$('.description__Y0e14').forEach(e=>e.textContent='点击进入编辑');
 if(p.id==='D-CUSTOMREPORT-filter')$$('.editFilter__MYaAx h3').forEach((e,i)=>{e.textContent=['1.选择需要筛选的图表','2.选择筛选的字段','3.其它设置'][i]||e.textContent;});
 if(p.id==='D-BOARD-content-config')$$('div[role="button"][style*="position: relative"]').forEach(e=>e.setAttribute('aria-roledescription','sortable'));
 if(p.id==='D-PROD-tree')for(const [iconName,text]of Object.entries({qiehuanchanpin:'切换产品',quanzhankai:'展开',quanshouqi:'收起'}))$$('iconpark-icon[data-ui-icon="'+iconName+'"]').forEach(icon=>{const span=icon.closest('.modal-btn__iB8VW');if(span){Array.from(span.childNodes).filter(e=>e.nodeType===3).forEach(e=>e.remove());span.append(document.createTextNode(text));span.setAttribute('role','button');span.tabIndex=0;}});
 for(const edge of A.edges.filter(e=>e.from===p.id&&e.text&&!e.text.includes('>')))$$('span,div').filter(e=>!e.children.length&&e.textContent.trim()===edge.text&&!e.closest('button,[role="button"],[role="tab"]')).forEach(e=>{e.setAttribute('role','button');e.tabIndex=0;});
 const norm=s=>String(s||'').replace(/\s+/g,'').replace(/[＊*：:]/g,'');
 const root=p.parentId||p.id,isPicker=p.deepState&&/picker$/.test(p.id),auxTable=p.deepState&&/importlog|logs$|log-detail|BOARD-drilldown|timerules|managerules|readpanel02/.test(p.id),storeKey='blacklake-demo-v3-'+root+(isPicker?'-picker-1-'+p.id:auxTable?'-table-'+p.id:'');
 document.body.classList.toggle('demo-captured-state',!!p.deepState);
 // Restore intrinsic input sizing and textarea rows lost by the sanitized DOM capture.
 // These remain in normal flow; no absolute control coordinates are imposed.
 const observed=A.geometry?.[p.id];
 if(observed){
  if(observed.tableFont)document.body.style.setProperty('--native-table-font',observed.tableFont);
  const pool=[...observed.inputs];
  const scopes=observed.scope.role==='dialog'?$$('[role="dialog"]'):observed.scope.class?Array.from(document.getElementsByClassName(observed.scope.class.split(' ')[0])):[];
  const geometryScope=scopes.filter(e=>e.getBoundingClientRect().width>0).at(-1)||document;
  $$('input,textarea',geometryScope).filter(e=>!e.closest('.kodo-new-layout-sider')).forEach(e=>{
   const at=pool.findIndex(r=>r.tag===e.tagName&&r.class===e.className&&r.text===(e.getAttribute('placeholder')||'')&&r.type===(e.getAttribute('type')));
   if(at<0)return;const r=pool.splice(at,1)[0];
   if(e.tagName==='TEXTAREA'){e.style.height=r.height+'px';e.style.minHeight=r.height+'px';}
   else if(['checkbox','radio'].includes(e.type)){e.style.setProperty('width',r.width+'px','important');e.style.setProperty('height',r.height+'px','important');}
   else if(e.closest('.ant-picker,.kodo-new-picker')){e.size=12;e.readOnly=true;e.style.width=r.width+'px';}
  });
 }
 const read=(k,f)=>{try{return JSON.parse(localStorage.getItem(k))??f;}catch{return f;}};
 let db=read(storeKey,{records:null,settings:{},undo:[]}),view=read(storeKey+'-view',{page:1,size:10,text:'',conditions:[],sort:[],status:'',recent:''});
 let portal=null,reportFilters=null;
 const save=()=>localStorage.setItem(storeKey,JSON.stringify(db));
 const saveView=()=>localStorage.setItem(storeKey+'-view',JSON.stringify(view));
 const byId=id=>cat.find(x=>x.id===id&&x.rendered);
 const parents={
  'D-WO-add-process-picker':'D-WO-create-form','D-WO-add-routing-picker':'D-WO-create-form',
  'D-SO-columns-batch':'D-SO-lines','D-SO-more':'D-SO-basic',
  'D-IQC-product-picker':'D-IQC-create-form','D-IQC-picker-filter-fields':'D-IQC-product-picker',
  'D-CF-WO-number-options':'D-V3-WO-number-rule','D-BOARD-aggregate-formula':'D-BOARD-aggregate-edit',
  'D-BOARD-aggregate-edit':'D-BOARD-chart-config','D-BOARD-chart-config':'D-BOARD-content-config',
  'D-CUSTOMREPORT-filter':'D-CUSTOMREPORT-edit','D-OUT-line-edit':'D-OUT-production-edit',
  'D-OUTSOURCE-product-picker':'D-OUTSOURCE-order-create','D-V3-BOARD-drilldown':'D-BOARD-content-config'
 };
 parents['D-BOARD-raw-fields']='D-BOARD-chart-config';
 Object.assign(parents,A.parents||{});
 Object.assign(parents,{'D-PROCESS-sop-fields':'D-PROCESS-sop-row','D-PASSIVE-BL-02-08-pricerule-options':'D-PASSIVE-BL-02-08-pricerule','D-PASSIVE-BL-02-03-calculation-planshortage':'BL-02-03','D-PASSIVE-BL-02-03-calculation-stockshortage':'BL-02-03','D-PASSIVE-BL-03-01-calculation-planshortage':'BL-03-01'});
 const parent=()=>sessionStorage.getItem('blacklake-return-'+p.id)||parents[p.id]||p.parentStateId||p.parentId||root;
 function fieldLabel(input){
  const taskFilterItem=p.id==='BL-04-02'&&input.closest('.filterSortSearchBar__W6ma2 .formItem__J3XtW');if(taskFilterItem){const label=norm($('.ant-form-item-label',taskFilterItem)?.textContent),inputs=$$('input',taskFilterItem),at=inputs.indexOf(input);return label+(inputs.length>1?' / '+at+(input.placeholder?' '+input.placeholder:''):'');}
  let box=input.closest('.kodo-new-form-item,.ant-form-item,.ant-legacy-form-item,.kodo-lite-form-item,.el-form-item'),label=null;
  while(box&&!label){const candidate=$('.kodo-new-form-item-label,.ant-form-item-label,.ant-legacy-form-item-label,.kodo-lite-form-item-label,label',box);if(candidate?.textContent.trim())label=candidate;else box=box.parentElement?.closest('.kodo-new-form-item,.ant-form-item,.ant-legacy-form-item,.kodo-lite-form-item,.el-form-item');}
  const shared=box&&$$('input:not([type=checkbox]):not([type=radio]),textarea',box).length>1;
  const name=norm(shared&&input.getAttribute('placeholder')||label?.querySelector('.kodo-new-form-item-default-label')?.textContent||label?.textContent||input.getAttribute('aria-label')||input.getAttribute('placeholder')||input.dataset.demoField||'');
  const choice=input.type==='checkbox'?norm(input.closest('label')?.textContent):'';return choice&&choice!==name?name+' / '+choice:name;
 }
 const draftKey=id=>'blacklake-draft-'+id;
 function draft(){
  const data={},scope=p.deepState?$$('[role="dialog"]').filter(e=>!e.classList.contains('demo-v3-portal')).at(-1)||document:document;$$('input,textarea,[contenteditable="true"]',scope).filter(e=>!e.closest('.kodo-new-layout-sider,.demo-bar,.demo-native-column-wrap,.demo-v3-portal,.task-native-menu,.demo-task-date-relative,.kodo-art-table-tbody')).forEach((e,i)=>{
   if(e.disabled&&!e.dataset.demoCostEditable||e.type==='file'||e.type==='password'||e.readOnly&&e.getAttribute('role')!=='combobox'&&!e.closest('.ant-picker,.kodo-new-picker,.filterSortSearchBar__W6ma2')&&e.getAttribute('placeholder')!=='请编辑公式')return;
   const key=e.dataset.demoField||fieldLabel(e)||'field-'+i;if(e.type==='radio'){if(e.checked)data[key]=norm(e.closest('label')?.textContent);return;}
   data[key]=e.type==='checkbox'?e.checked:e.isContentEditable?e.textContent:e.getAttribute('role')==='combobox'&&isMultiSelect(e.closest('.kodo-new-select,.ant-select'))?selectedValues(e):e.getAttribute('role')==='combobox'?(e.value||$('.kodo-new-select-selection-item,.ant-select-selection-item',e.closest('.kodo-new-select,.ant-select'))?.textContent.trim()||''):e.value;
  });
  $$('.demo-v3-formula-value',scope).forEach(e=>{const item=e.closest('.kodo-new-form-item,.ant-form-item,.ant-legacy-form-item,.kodo-lite-form-item'),label=item&&$('.kodo-new-form-item-label,.ant-form-item-label,.ant-legacy-form-item-label,.kodo-lite-form-item-label',item)?.textContent.trim();if(label)data['__formula_'+label]=e.textContent;});
  if(p.deepState&&!isPicker)data.__tables=$$('.kodo-art-table',scope).map(table=>{const columns=Array.from($('.kodo-art-table-thead .kodo-art-table-row',table)?.children||[]).map((e,i)=>e.textContent.trim()||'#'+i);return{columns,rows:$$('.kodo-art-table-tbody .kodo-art-table-row:not(.kodo-art-table-measure-row)',table).map(row=>({class:row.className,style:row.style.cssText,kind:row.dataset.demoRowKind,values:row.dataset.demoRowValues,cells:Array.from(row.children).map(cell=>{const copy=cell.cloneNode(true);$$('input,textarea',cell).forEach((input,i)=>{const out=$$('input,textarea',copy)[i];if(input.type==='file'||input.type==='password')return;if(input.type==='checkbox'||input.type==='radio')out.toggleAttribute('checked',input.checked);else if(input.tagName==='TEXTAREA')out.textContent=input.value;else out.setAttribute('value',input.value);});return copy.outerHTML;})}))};});
  sessionStorage.setItem(draftKey(p.id),JSON.stringify(data));return data;
 }
 function restoreDraft(){
  const creating=/create|blankform|blankview|blankreport|blankboard|D-COLLAB-type-form/.test(p.id)||/创建|新建|新增/.test(p.label),saved=creating?null:db.settings[p.id];
  let data;try{data=JSON.parse(sessionStorage.getItem(draftKey(p.id))||'null')||saved;}catch{data=saved;}
  if(!data)return;
  if(Array.isArray(data.__tables)&&!isPicker){const scope=$$('[role="dialog"]').at(-1)||document;$$('.kodo-art-table',scope).forEach((table,index)=>{const saved=data.__tables[index],body=$('.kodo-art-table-tbody',table),head=$('.kodo-art-table-thead .kodo-art-table-row',table);if(!saved?.rows?.length||!body||!head)return;const headers=Array.from(head.children);$$('.kodo-art-table-empty-placeholder,.demo-empty',table).forEach(e=>e.remove());body.replaceChildren();for(const entry of saved.rows){const row=document.createElement('div');row.className=entry.class;row.style.cssText=entry.style;if(entry.kind)row.dataset.demoRowKind=entry.kind;if(entry.values)row.dataset.demoRowValues=entry.values;headers.forEach((header,i)=>{const name=header.textContent.trim()||'#'+i,at=saved.columns.indexOf(name),html=entry.cells[at];let cell;if(html){const holder=document.createElement('div');holder.innerHTML=html;cell=holder.firstElementChild;}if(!cell){cell=document.createElement('div');cell.className='kodo-art-table-cell';cell.textContent='—';}cell.style.width=header.style.width;cell.style.minWidth=header.style.minWidth;row.append(cell);});body.append(row);}});}
  $$('input,textarea,[contenteditable="true"]').forEach((e,i)=>{
   if(e.type==='file'||e.type==='password'||e.closest('.kodo-new-layout-sider,.demo-bar')||data.__tables&&e.closest('.kodo-art-table-tbody'))return;
   const key=e.dataset.demoField||fieldLabel(e)||'field-'+i;if(!(key in data))return;
   if(e.type==='radio')e.checked=typeof data[key]==='string'?norm(e.closest('label')?.textContent)===norm(data[key]):!!data[key];else if(e.type==='checkbox')e.checked=!!data[key];else if(e.isContentEditable)e.textContent=data[key];else if(e.getAttribute('role')==='combobox'&&data[key])assignSelect(e,data[key],false);else e.value=data[key];
   syncField(e);
  });
  for(const [key,value]of Object.entries(data).filter(([key])=>key.startsWith('__formula_'))){const label=key.slice(10),item=$$('.kodo-new-form-item,.ant-form-item,.ant-legacy-form-item,.kodo-lite-form-item').find(e=>$('.kodo-new-form-item-label,.ant-form-item-label,.ant-legacy-form-item-label,.kodo-lite-form-item-label',e)?.textContent.trim()===label),content=item&&$('.kodo-new-form-item-control-input-content,.ant-form-item-control-input-content',item);if(content){const button=$('button',content),code=document.createElement('code');code.className='demo-v3-formula-value';code.textContent=value;code.style.cssText='display:block;max-width:100%;overflow-wrap:anywhere;white-space:pre-wrap;padding:4px 0;color:#4b6570';if(button)content.replaceChildren(code,button);else content.replaceChildren(code);}}
 }
 const uiScrollSelector='.kodo-new-drawer-body,.ant-drawer-body,.kodo-new-modal-body,.ant-modal-body,.content-box__23uXX,.scrollbar-container,.kodo-new-tree-list-holder,.ant-tree-list-holder,.kodo-art-fct-field-list,[class*="dragFieldWrap"]';
 function rememberUI(){sessionStorage.setItem('blacklake-ui-'+p.id,JSON.stringify({scroll:$$(uiScrollSelector).map((e,index)=>({index,x:e.scrollLeft,y:e.scrollTop})),collapse:$$('.kodo-new-collapse-header[aria-expanded],.ant-collapse-header[aria-expanded],.last-title__NB0wR').map(e=>e.getAttribute('aria-expanded')==='true')}));}
 function restoreUI(){let state;try{state=JSON.parse(sessionStorage.getItem('blacklake-ui-'+p.id)||'null');}catch{}if(!state)return;const nodes=$$(uiScrollSelector);for(const x of state.scroll||[]){if(nodes[x.index]){nodes[x.index].scrollLeft=x.x;nodes[x.index].scrollTop=x.y;}}const collapse=$$('.kodo-new-collapse-header[aria-expanded],.ant-collapse-header[aria-expanded],.last-title__NB0wR');for(const [i,open]of(state.collapse||[]).entries()){const e=collapse[i];if(!e)continue;e.setAttribute('aria-expanded',String(open));if(e.matches('.last-title__NB0wR')){e.classList.toggle('open__XuhEF',open);e.nextElementSibling?.classList.toggle('open__XuhEF',open);}else{const item=e.closest('.kodo-new-collapse-item,.ant-collapse-item'),content=item&&$('[class*="collapse-content"]',item);if(content)content.style.display=open?'':'none';}}}
 ctx.formFieldLabel=fieldLabel;
 function inlineActions(scope){$$('.btn__hLSKz',scope).forEach(action=>{action.setAttribute('role','button');action.tabIndex=0;if(!action.dataset.inlineKeyReady){action.dataset.inlineKeyReady='1';action.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();action.click();}});}});$$('label button',scope).filter(button=>!button.hasAttribute('aria-label')&&!button.querySelector('button')&&button.textContent.trim()).forEach(button=>button.setAttribute('aria-label',button.textContent.trim()));$$('.kodo-new-form-item-label span,.ant-form-item-label span,.kodo-lite-form-item-label span',scope).filter(e=>!e.children.length&&['创建','高级选择'].includes(e.textContent.trim())).forEach(e=>{e.setAttribute('role','button');e.tabIndex=0;});}
 inlineActions(document);ctx.enhanceInlineActions=inlineActions;
 function activateTab(hit){const group=hit.closest('.kodo-new-tabs,.ant-tabs');if(!group)return false;const tabs=$$('[role=tab]',group),panes=$$('[role=tabpanel]',group);if(panes.length!==tabs.length||panes.length<2)return false;const selected=tabs.indexOf(hit);tabs.forEach((tab,i)=>{const active=i===selected;tab.setAttribute('aria-selected',String(active));tab.closest('.kodo-new-tabs-tab,.ant-tabs-tab')?.classList.toggle(tab.closest('.ant-tabs-tab')?'ant-tabs-tab-active':'kodo-new-tabs-tab-active',active);});panes.forEach((pane,i)=>{const active=i===selected;pane.style.display=active?'':'none';pane.setAttribute('aria-hidden',String(!active));pane.classList.toggle('kodo-new-tabs-tabpane-active',active);pane.classList.toggle('kodo-new-tabs-tabpane-hidden',!active);});const ink=$('.kodo-new-tabs-ink-bar,.ant-tabs-ink-bar',group),parent=hit.parentElement;if(ink&&parent){ink.style.width=parent.offsetWidth+'px';ink.style.left=parent.offsetLeft+'px';ink.style.transform='';}return true;}
 ctx.activateTab=activateTab;
 function navigate(id,keep=true){if(!byId(id)){ctx.toast('此界面尚无已核实的结构，当前入口暂不可用');return false;}if(keep){draft();rememberUI();}location.href=id+'.html';return true;}
 function closePortal(){portal?.remove();portal=null;}
 function showPortal(html,anchor,kind='popover'){
  closePortal();portal=document.createElement('div');portal.className='demo-v3-portal demo-v3-'+kind;portal.innerHTML=html;portal.setAttribute('role','dialog');
  document.body.append(portal);const r=anchor?.getBoundingClientRect()||{left:innerWidth/2-250,bottom:100};
  portal.addEventListener('click',e=>e.stopPropagation());portal.addEventListener('keydown',e=>e.stopPropagation());
  portal.style.left=Math.max(8,Math.min(r.left,innerWidth-portal.offsetWidth-8))+'px';portal.style.top=Math.max(8,Math.min(r.bottom+5,innerHeight-portal.offsetHeight-8))+'px';
  portal.querySelectorAll('.kodo-new-dropdown').forEach(e=>{e.style.position='static';e.style.inset='auto';e.style.transform='none';});return portal;
 }
 function icon(el){
  const custom=el.closest('iconpark-icon')||$('iconpark-icon',el);if(custom?.dataset.uiIcon)return custom.dataset.uiIcon;
  const paths=$$('svg path[d]',custom||el).map(x=>x.getAttribute('d')).join('|');
  return A.icons[paths]||el.getAttribute('aria-label')||$('svg[aria-label],[role="img"][aria-label]',el)?.getAttribute('aria-label')||'';
 }
 $$('button,[role="button"],[class*="btn__"],.kodo-new-iconbutton').forEach(e=>{const n=icon(e);if(n){e.dataset.uiAction=n;if(!e.textContent.trim()&&!e.getAttribute('aria-label'))e.setAttribute('aria-label',({'shuaxin-gengxin':'刷新','shanchu2':'关闭','sousuo':'搜索','zhankai':'展开','gengduo':'更多','bianji':'编辑','left':'上一页','right':'下一页'})[n]||n);}});
 function syncField(e){
  const box=e.closest('.kodo-new-checkbox,.ant-checkbox,.kodo-new-radio,.ant-radio');
  if(box){box.classList.toggle(e.type==='radio'?'kodo-new-radio-checked':'kodo-new-checkbox-checked',e.checked);box.classList.toggle(e.type==='radio'?'ant-radio-checked':'ant-checkbox-checked',e.checked);}
 }
 prepareLegacySelects(document);
 $$('.kodo-new-select-multiple .kodo-new-select-selection-item-remove,.ant-select-multiple .ant-select-selection-item-remove,.ant-select-selection__choice__remove').forEach(e=>{const item=e.closest('.kodo-new-select-selection-item,.ant-select-selection-item,.ant-select-selection__choice'),content=item&&$('.kodo-new-select-selection-item-content,.ant-select-selection-item-content,.ant-select-selection__choice__content',item);if(content){e.dataset.demoSelectRemove=content.textContent.trim();e.setAttribute('role','button');e.setAttribute('aria-label','移除'+content.textContent.trim());}});
 $$('input,textarea').forEach((e,i)=>{if(e.type!=='file')e.dataset.demoField=fieldLabel(e)||'field-'+i;});
 $$('.cm-content').forEach(e=>{e.contentEditable='true';e.setAttribute('role','textbox');e.setAttribute('aria-label','公式');e.spellcheck=false;});
 $$('input[placeholder="请编辑公式"]').forEach(e=>e.readOnly=true);
 restoreDraft();
 $$('.last-title__NB0wR').forEach(e=>{e.setAttribute('role','button');e.setAttribute('aria-expanded',String(e.classList.contains('open__XuhEF')));e.tabIndex=0;});
 if(p.deepState&&!isPicker&&sessionStorage.getItem('blacklake-mode-'+root)==='edit'&&!sessionStorage.getItem(draftKey(p.id))){
  const selected=db.records?.find(r=>r.id===sessionStorage.getItem('blacklake-selected-'+root));
  if(selected)$$('input,textarea').filter(e=>!e.disabled&&!['file','password','checkbox','radio'].includes(e.type)&&!e.closest('.kodo-new-layout-sider')).forEach(e=>{const key=fieldLabel(e);if(key in selected.fields){if(e.getAttribute('role')==='combobox')assignSelect(e,selected.fields[key],false);else e.value=selected.fields[key];}});
  if(selected)$$('input[type="radio"]').forEach(e=>{const value=selected.fields[fieldLabel(e)];if(value)e.checked=norm(e.closest('label')?.textContent)===norm(value);syncField(e);});
 }
 if(p.id==='D-BOARD-aggregate-formula'){
  const editor=$('.cm-content');const append=value=>{editor.textContent+=value;editor.focus();draft();};
  $$('[class*="singleOption__"]').forEach(e=>{e.setAttribute('role','button');e.tabIndex=0;e.onclick=event=>{event.preventDefault();event.stopPropagation();append(e.firstElementChild.textContent);};});
  $$('.ant-tree-node-content-wrapper').forEach(e=>{e.onclick=()=>{const text=norm(e.textContent);if(/^(SUM|COUNT|AVG|MAX|MIN)\(/.test(text))append(text);else if(text&&!/常用函数|工单|产品/.test(text))append('${工单.'+text+'}');};});
 }
 document.addEventListener('input',e=>{if(e.target.closest('.demo-nested-layer,.demo-native-column-wrap,.demo-v3-portal,.demo-dialog'))return;if(e.target.matches('input,textarea,[contenteditable="true"]')&&!e.target.closest('.demo-v3-portal,.demo-native-column-wrap,.demo-dialog,.demo-drawer')){const wrap=e.target.closest('.ant-input-affix-wrapper,.kodo-new-input-affix-wrapper');if(wrap)$$('.ant-input-clear-icon,.kodo-new-input-clear-icon',wrap).forEach(x=>{x.classList.toggle('ant-input-clear-icon-hidden',!e.target.value);x.classList.toggle('kodo-new-input-clear-icon-hidden',!e.target.value);x.setAttribute('aria-label','清空输入');});draft();}});
 document.addEventListener('change',e=>{if(e.target.closest('.demo-nested-layer,.demo-native-column-wrap,.demo-v3-portal,.demo-dialog'))return;if(e.target.matches('input')){syncField(e.target);draft();}});

 // A coherent, isolated demo dataset drives list interactions. No production requests are made.
 const business=isPicker||auxTable||!p.deepState&&(/^BL-(02|03|04|05|06|07|08|09|11)-/.test(p.id)||p.id==='BL-12-01');
 const activeScope=p.deepState?$$('[role="dialog"]').at(-1)||document:document;
 const table=business?$('.kodo-art-table',activeScope):null,tbody=table&&$('.kodo-art-table-tbody',table),head=table&&$('.kodo-art-table-thead .kodo-art-table-row',table);
 let fields=[],templates=[],allRecords=db.records||[],filtered=[];
 const date=(delta=0)=>{const d=new Date();d.setDate(d.getDate()+delta);return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');};
 if(tbody&&head){
  const leaf=Array.from(head.children).flatMap(h=>h.hasAttribute('colspan')?$$('.kodo-art-table-header-cell-child',h):[h]);fields=leaf.map(h=>{const group=h.classList.contains('kodo-art-table-header-cell-child')?h.parentElement.closest('[colspan]'):null;return(group?$('.kodo-art-table-filter-title',group)?.textContent.trim()+' / ':'')+h.textContent.trim();});
  templates=$$('.kodo-art-table-row:not(.kodo-art-table-measure-row)',tbody).map(e=>e.cloneNode(true));
  if(p.id==='BL-04-02'&&!templates.length){const t=document.querySelector('template[data-task-row]');if(t)templates=[t.content.firstElementChild.cloneNode(true)];if(!db.records){db.records=[];allRecords=db.records;}}
  if(!db.records&&templates.length){
   db.records=Array.from({length:isPicker?(A.pickerTotals?.[p.id]||35):auxTable?templates.length:35},(_,i)=>{const row=templates[i%templates.length],data={};fields.forEach((name,j)=>{if(!name||name==='操作')return;let value=row.children[j]?.textContent.trim()||'—';if(isPicker&&name==='工序名称'){data[name]=value.replace(/[\uE000-\uF8FF]/g,'').trim();return;}if(/编号|编码|单号/.test(name))value='DEMO-'+String(i+1).padStart(4,'0');else if(/名称|客户|供应商|申请人|人员|创建人|更新人/.test(name))value='演示条目 '+String.fromCharCode(65+i%26)+(i>=26?' '+(i+1):'');else if(/日期|时间/.test(name))value=date(i%15-7)+(/时间/.test(name)?' 08:00':'');else if(/数|量|价|金额|成本|工资|工时/.test(name))value=String(120+i*10);else if(/状态/.test(name))value=(value.match(/未开始|执行中|进行中|已结束|已取消|待审批|已审批|未生成|已生成/)||['—'])[0];data[name]=value;});return{id:'DEMO-'+String(i+1).padStart(4,'0'),fields:data,template:i%templates.length};});save();
  }
  allRecords=db.records||[];
 }

 function acceptRelatedCustomers26(){if(root!=='BL-09-09'||!Array.isArray(db.records))return;const key='blacklake-v26-related-customers';let queue;try{queue=JSON.parse(localStorage.getItem(key)||'[]');}catch{return;}if(!Array.isArray(queue)||!queue.length)return;for(const record of queue)if(!db.records.some(r=>r.id===record.id))db.records.unshift(record);allRecords=db.records;save();const consumed=new Set(queue.map(r=>r.id));const latest=read(key,[]);localStorage.setItem(key,JSON.stringify(latest.filter(r=>!consumed.has(r.id))));}
 acceptRelatedCustomers26();window.addEventListener('storage',e=>{if(root==='BL-09-09'&&e.key==='blacklake-v26-related-customers'){acceptRelatedCustomers26();render();}});
 if(!db.records&&p.deepState&&!isPicker&&!auxTable&&(/^BL-(02|03|04|05|06|07|08|09|11)-/.test(root)||root==='BL-12-01')){const background=$('.kodo-art-table'),h=background&&$('.kodo-art-table-thead .kodo-art-table-row',background),row=background&&$('.kodo-art-table-tbody .kodo-art-table-row:not(.kodo-art-table-measure-row)',background);if(h&&row){const names=[...h.children].map(e=>e.textContent.trim());db.records=Array.from({length:35},(_,i)=>({id:'DEMO-'+String(i+1).padStart(4,'0'),template:0,fields:Object.fromEntries(names.map((name,j)=>[name,/编号|编码|单号/.test(name)?'DEMO-'+String(i+1).padStart(4,'0'):row.children[j]?.textContent.trim()||'—']).filter(([name])=>name&&name!=='操作'))}));allRecords=db.records;save();}}
 if(p.deepState&&db.records?.length&&!sessionStorage.getItem('blacklake-selected-'+root)&&(/编辑/.test(p.label)||/^D-SO-|^D-WO-real-view/.test(p.id)))sessionStorage.setItem('blacklake-selected-'+root,db.records[0].id);
 const readValues=()=>{
  const result={};$$('input,textarea',activeScope).filter(e=>!e.closest('.kodo-new-layout-sider,.kodo-art-table-tbody,.demo-bar,.demo-v3-portal,.ant-pagination,.kodo-new-pagination')&&!e.disabled&&!e.readOnly&&e.type!=='checkbox'&&e.type!=='radio').forEach(e=>{if(e.value.trim())result[fieldLabel(e)]=e.value.trim();});return result;
 };
 function matchesQuery(record,c){const values=Array.isArray(c.fields)?c.fields.map(name=>record.fields[name]):c.field in record.fields?[record.fields[c.field]]:Object.values(record.fields),q=Array.isArray(c.value)?c.value:[c.value];return q.some(value=>values.some(raw=>{const text=String(raw??'');return c.op==='exact'?text===String(value):c.op==='lt'?text.slice(0,String(value).length)<value:c.op==='lte'?text.slice(0,String(value).length)<=value:c.op==='gt'?text.slice(0,String(value).length)>value:c.op==='gte'?text.slice(0,String(value).length)>=value:c.op==='eq'?text.slice(0,String(value).length)===value:c.op==='min'?Number(text)>=Number(value):c.op==='max'?Number(text)<=Number(value):c.op==='after'?text>=value:c.op==='before'?text.slice(0,10)<=value:text.includes(value);}));}
 function matches(record,c){const v=String(record.fields[c.field]??''),q=String(c.value??'');return c.op==='为空'?!v||v==='—':c.op==='不为空'?!!v&&v!=='—':c.op==='不等于'?v!==q:c.op==='包含'?v.includes(q):c.op==='不包含'?!v.includes(q):v===q;}
 function render(){
  if(!tbody||!templates.length)return;
  filtered=allRecords.filter(r=>(view.queryTerms||[]).every(c=>matchesQuery(r,c))).filter(r=>!view.text||Object.values(r.fields).some(v=>String(v).includes(view.text))).filter(r=>!view.status||Object.entries(r.fields).some(([k,v])=>/状态/.test(k)&&v===view.status)).filter(r=>(view.conditions||[]).every(c=>matches(r,c)));
  if(view.recent==='近3天到期')filtered=filtered.filter(r=>Object.entries(r.fields).some(([k,v])=>/结束时间|交货日期/.test(k)&&String(v).slice(0,10)>=date()&&String(v).slice(0,10)<=date(3)));
  if(view.recent==='逾期工单')filtered=filtered.filter(r=>Object.entries(r.fields).some(([k,v])=>/结束时间|交货日期/.test(k)&&String(v).slice(0,10)<date()));
  for(const s of [...(view.sort||[])].reverse())filtered.sort((a,b)=>String(a.fields[s.field]??'').localeCompare(String(b.fields[s.field]??''),'zh-CN',{numeric:true})*(s.direction==='降序'?-1:1));
  const total=Math.max(1,Math.ceil(filtered.length/view.size));view.page=Math.max(1,Math.min(view.page,total));
  Array.from(tbody.children).filter(e=>!e.classList.contains('kodo-art-table-measure-row')).forEach(e=>e.remove());
  filtered.slice((view.page-1)*view.size,view.page*view.size).forEach((record,i)=>{
   const row=templates[record.template%templates.length].cloneNode(true);row.dataset.demoRecord=record.id;
   fields.forEach((name,j)=>{const cell=row.children[j];if(!cell)return;if(!name){
    let check=$('input[type=checkbox]',cell),label=$('.kodo-art-table-selection-column-text',cell);
    if(!check||!label){cell.innerHTML='<label class="kodo-art-table-selection-column-content" style="justify-content:center"><div class="kodo-new-space css-uy6x3o kodo-new-space-horizontal kodo-new-space-align-center" style="gap:4px"><div class="kodo-new-space-item"><div class="kodo-art-table-selection-column-children"><label class="kodo-new-checkbox-wrapper css-uy6x3o"><span class="kodo-new-checkbox ant-wave-target css-uy6x3o"><input class="kodo-new-checkbox-input" type="checkbox"><span class="kodo-new-checkbox-inner"></span></span></label></div><div class="kodo-art-table-selection-column-text"></div></div></div></label>';check=$('input[type=checkbox]',cell);label=$('.kodo-art-table-selection-column-text',cell);}
    label.textContent=String((view.page-1)*view.size+i+1);check.classList.add('demo-v3-row-check');check.disabled=false;check.checked=false;check.setAttribute('aria-label','选择'+record.id);syncField(check);
   }else if(name!=='操作'){
    const toggle=$('[role=switch]',cell);if(toggle){const checked=record.fields[name];if(typeof checked==='boolean'){toggle.setAttribute('aria-checked',String(checked));toggle.classList.toggle('kodo-new-switch-checked',checked);toggle.classList.toggle('ant-switch-checked',checked);}cell.dataset.demoColumn=name;return;}
    if(/状态|类型|属性/.test(name)){const text=record.fields[name];const span=cell.querySelector('[class*="max-width-limit"],.kodo-new-tag,.ant-tag')||$$('span',cell).find(e=>!e.children.length&&!e.className.includes('icon')&&!e.closest('.mask'));if(span&&text)span.textContent=text;else if(text)cell.textContent=text;}
    else cell.textContent=record.fields[name]??'—';
    cell.dataset.demoColumn=name;
   }});tbody.append(row);
  });
  const nativeEmpty=$('.kodo-art-table-empty-placeholder',table);if(nativeEmpty)nativeEmpty.style.display=filtered.length?'none':'';if(!filtered.length&&!nativeEmpty){const e=document.createElement('div');e.className='demo-empty demo-query-empty';e.setAttribute('role','status');e.textContent='暂无数据';tbody.append(e);}
  $$('[class*="button__1ScsC"]').filter(e=>!e.closest('.kodo-art-table-tbody')).forEach(e=>{const status=e.textContent.match(/^(全部|待审批|进行中|未开始|执行中|已结束|已取消)/)?.[0];if(status){e.setAttribute('role','button');e.tabIndex=0;e.textContent=status==='全部'?'全部':status+allRecords.filter(r=>Object.entries(r.fields).some(([k,v])=>/状态/.test(k)&&v===status)).length;e.classList.toggle('demo-v3-active-status',view.status===status||!view.status&&status==='全部');}});
  renderPager(total);ctx.applyPins?.(table,ctx.getLayoutPins?.());ctx.afterListRender?.(filtered);saveView();table.dispatchEvent(new Event('resize'));window.dispatchEvent(new Event('resize'));
 }
 function renderPager(total){
  const pager=$('.ant-pagination,.kodo-new-pagination',activeScope);if(!pager)return;
  const jumper=$('.ant-pagination-options-quick-jumper,.kodo-new-pagination-options-quick-jumper',pager);if(jumper){const text=Array.from(jumper.childNodes).filter(n=>n.nodeType===3&&n.textContent.trim());if(text.length){text[0].textContent='跳至';text.at(-1).textContent='页';}const input=$('input',jumper);if(input)input.setAttribute('aria-label','跳至页');}
  const prefix=pager.classList.contains('ant-pagination')?'ant':'kodo-new';
  $$('li',pager).filter(e=>e.classList.contains(prefix+'-pagination-item')||e.className.includes('pagination-jump')).forEach(e=>e.remove());
  const next=$('.'+prefix+'-pagination-next',pager),pages=total<=7?Array.from({length:total},(_,i)=>i+1):view.page<=3?[1,2,3,4,5,'next',total]:view.page>=total-2?[1,'prev',total-4,total-3,total-2,total-1,total]:[1,'prev',view.page-1,view.page,view.page+1,'next',total];
  for(const n of pages){const li=document.createElement('li');if(typeof n==='string'){li.className=prefix+'-pagination-jump-'+n;li.dataset.demoPage=String(Math.max(1,Math.min(total,view.page+(n==='next'?5:-5))));li.setAttribute('role','button');li.setAttribute('aria-label',n==='next'?'向后5页':'向前5页');li.textContent='•••';}else{li.className=prefix+'-pagination-item '+prefix+'-pagination-item-'+n+(n===view.page?' '+prefix+'-pagination-item-active':'');li.dataset.demoPage=String(n);li.setAttribute('aria-label','第'+n+'页');const a=document.createElement('a');a.textContent=String(n);li.append(a);}li.tabIndex=0;pager.insertBefore(li,next);}
  const count=$('.'+prefix+'-pagination-total-text',pager);if(count)count.textContent=filtered.length+' 条记录';
  const prev=$('.'+prefix+'-pagination-prev',pager);if(prev){prev.classList.toggle(prefix+'-pagination-disabled',view.page===1);prev.setAttribute('aria-disabled',String(view.page===1));const b=$('button',prev);if(b)b.disabled=view.page===1;}if(next){next.classList.toggle(prefix+'-pagination-disabled',view.page===total);next.setAttribute('aria-disabled',String(view.page===total));const b=$('button',next);if(b)b.disabled=view.page===total;}
  $$('.demo-count').forEach(e=>e.textContent=filtered.length+' 条演示记录');
 }
 function query(reset=false){
  const inputs=$$('input,textarea',activeScope).filter(e=>!e.closest('.kodo-new-layout-sider,.kodo-art-table,.demo-bar,.demo-v3-portal,.ant-pagination,.kodo-new-pagination,[data-reviewed-keyword-row],[data-reviewed-keyword-popup]')&&!(e.closest('.kodo-new-select-ghost')&&e.closest('.kodo-new-form-item,.ant-form-item,.ant-legacy-form-item,.kodo-lite-form-item')?.querySelector('.kodo-new-picker-range,.ant-picker-range'))&&!e.disabled&&!['checkbox','radio','file'].includes(e.type));
  if(reset){inputs.forEach(e=>{if(e.getAttribute('role')==='combobox')assignSelect(e,'',false);else e.value='';});view={...view,text:'',queryTerms:[],conditions:[],sort:[],status:'',recent:'',page:1};}
  else{view.text='';view.queryTerms=[];for(const input of inputs){const value=input.getAttribute('role')==='combobox'?ctx.getSelectValue(input):input.value.trim();if(!value||Array.isArray(value)&&!value.length)continue;const box=input.closest('.kodo-new-form-item,.ant-form-item,.ant-legacy-form-item,.kodo-lite-form-item'),label=box&&$('.kodo-new-form-item-label,.ant-form-item-label,.ant-legacy-form-item-label,.kodo-lite-form-item-label',box)?.textContent.trim(),field=norm(label||fieldLabel(input)).replace(/^请输入|^请选择/,'');const ph=input.getAttribute('placeholder')||'',range=input.closest('.kodo-new-picker-range,.ant-picker-range'),at=range?$$('input',range).indexOf(input):-1;view.queryTerms.push({field,value,op:ph==='最小值'?'min':ph==='最大值'?'max':range?(at===0?'after':'before'):'contains'});}view.page=1;}
  if(!reset&&ctx.transformQueryTerms)view.queryTerms=ctx.transformQueryTerms(view.queryTerms);if(reset&&ctx.reviewedKeywordQueryTerms)view.queryTerms=ctx.reviewedKeywordQueryTerms();render();draft();ctx.toast(reset?'查询条件已重置':'已查询本地演示数据：'+filtered.length+'条');
 }
 ctx.query=query;ctx.getFilteredRecords=()=>filtered.slice();
 function pickerColumns(){
  const schema=A.pickerSchemas?.[p.id]||A.pickerSchemas?.['D-OUTSOURCE-product-picker'];if(!schema?.length)return;
  ctx.columnDialog({schema,selected:fields.filter(x=>x&&x!=='操作'),onApply:(selected,pins)=>{
   const before=[...fields],headers=new Map(Array.from(head.children).map((e,i)=>[before[i],e.cloneNode(true)])),samples=templates.map(row=>new Map(Array.from(row.children).map((e,i)=>[before[i],e.cloneNode(true)])));
   fields=[...(before.includes('')?['']:[]),...selected,...(before.includes('操作')?['操作']:[])];head.replaceChildren();
   for(const name of fields){let h=headers.get(name)?.cloneNode(true);if(!h){h=[...headers.values()].find(e=>e.querySelector('.kodo-art-table-filter-title'))?.cloneNode(true);if(!h)continue;h.style.cssText='width:150px;min-width:150px;flex:0 0 auto';$('.kodo-art-table-filter-title',h).textContent=name;}head.append(h);}
   templates=samples.map(cells=>{const row=document.createElement('div');row.className='kodo-art-table-row';row.style.cssText='display:flex;height:32px;min-height:32px';fields.forEach((name,i)=>{let cell=cells.get(name)?.cloneNode(true);if(!cell){cell=document.createElement('div');cell.className='kodo-art-table-cell';cell.style.cssText=head.children[i]?.style.cssText||'width:150px;min-width:150px';}row.append(cell);});return row;});
   for(const record of allRecords)for(const name of fields)if(name&&name!=='操作'&&!(name in record.fields))record.fields[name]=/数量|库存|单价/.test(name)?'120':'—';save();render();Array.from(head.children).forEach((h,i)=>ctx.setColumnWidth(table,i,parseFloat(h.style.width)||150));ctx.applyPins?.(table,pins);ctx.wireNativeTables?.(table.parentElement);
  }});
 }
 function capturedColumns(hit,exactSchema){
  let wrap=hit.parentElement,target=null;while(wrap&&wrap!==document.body){target=$('.kodo-art-table',wrap);if(target)break;wrap=wrap.parentElement;}if(!target)return;
  const h=$('.kodo-art-table-thead .kodo-art-table-row',target);if(!h)return;const headers=Array.from(h.children),names=headers.map(x=>x.textContent.trim()),available=Object.values(A.nestedSchemas||{});
  const match=available.map(schema=>({schema,score:schema.filter(x=>names.includes(x.label)).length/Math.max(1,names.filter(x=>x&&x!=='操作').length)})).sort((a,b)=>b.score-a.score)[0];
  const schema=exactSchema||(match?.score>.75?match.schema:names.filter(x=>x&&x!=='操作').map(label=>({label,checked:true})));
  ctx.columnDialog({schema,selected:names.filter(x=>x&&x!=='操作'&&headers[names.indexOf(x)].style.display!=='none'),pins:Object.fromEntries(headers.map((h,i)=>[names[i],h.classList.contains('kodo-art-table-cell-fix-left')])),onApply:(selected,pins)=>{
   const rows=$$('.kodo-art-table-row',target);for(const name of selected){if(names.includes(name))continue;const original=headers.find(e=>$('.kodo-art-table-filter-title',e)&&e.textContent.trim()!=='操作');if(!original)continue;const index=names.length;names.push(name);rows.forEach(row=>{let cell;if(row===h){cell=original.cloneNode(true);$('.kodo-art-table-filter-title',cell).textContent=name;cell.className='kodo-art-table-cell';}else{cell=document.createElement('div');cell.className='kodo-art-table-cell';cell.textContent='—';}cell.style.cssText='width:150px;min-width:150px;flex:0 0 auto;height:32px';row.append(cell);});}
   rows.forEach(row=>{Array.from(row.children).forEach((cell,i)=>cell.style.display=!names[i]||names[i]==='操作'||selected.includes(names[i])?'':'none');const cells=Array.from(row.children),order=[...cells.filter((e,i)=>!names[i]),...selected.map(name=>cells[names.indexOf(name)]).filter(Boolean),...cells.filter((e,i)=>names[i]==='操作'),...cells.filter((e,i)=>names[i]&&names[i]!=='操作'&&!selected.includes(names[i]))];row.replaceChildren(...order);});ctx.applyPins?.(target,pins);window.dispatchEvent(new Event('resize'));
  }});
 }
 ctx.openCapturedColumns=capturedColumns;
 ctx.wireNativeTables=scope=>{$$('.kodo-art-table',scope).forEach(nativeTable=>{ctx.restoreDensity?.(nativeTable);const body=$('.scrollbar-container',nativeTable);if(!body)return;if(!body.dataset.demoNativeWired){body.dataset.demoNativeWired='1';body.addEventListener('scroll',()=>{Array.from($('.kodo-art-table-thead .kodo-art-table-row',nativeTable)?.children||[]).forEach(cell=>{if(!cell.classList.contains('kodo-art-table-cell-fix-sticky'))cell.style.transform='translateX('+(-body.scrollLeft)+'px)';});$$('.kodo-art-table-summary',nativeTable).forEach(row=>row.scrollLeft=body.scrollLeft);});ctx.wireScrollbars(nativeTable,body);}$$('.kodo-art-table-line-resize',nativeTable).forEach(handle=>{if(handle.dataset.demoNativeResize&&handle.onpointerdown)return;handle.dataset.demoNativeResize='1';handle.style.pointerEvents='auto';handle.style.cursor='col-resize';handle.onpointerdown=e=>{e.preventDefault();e.stopPropagation();const header=handle.closest('.kodo-art-table-cell'),index=Array.from(header.parentElement.children).indexOf(header),before=header.getBoundingClientRect().width,start=e.clientX;handle.setPointerCapture(e.pointerId);handle.onpointermove=move=>ctx.setColumnWidth(nativeTable,index,Math.max(80,Math.min(800,before+move.clientX-start)));handle.onpointerup=()=>handle.onpointermove=null;};});});window.dispatchEvent(new Event('resize'));};
 function selectMenu(anchor,options,onChoose){
  if(p.id==='D-CUSTOMREPORT-filter'&&anchor.closest('.filterItem__oSM3j')&&A.components.reportFieldOptions){
   const box=showPortal(A.components.reportFieldOptions,anchor,'report-field'),list=$('.rc-virtual-list-holder-inner',box),holder=$('.rc-virtual-list-holder',box),template=$('.kodo-new-select-item-option',box)?.cloneNode(true);if(list&&template){list.style.transform='none';const spacer=list.parentElement;spacer.style.height='auto';spacer.style.minHeight='0';box.style.width=(anchor.closest('.kodo-new-select')?.getBoundingClientRect().width||330)+'px';const input=$('input',box);function paint(){list.replaceChildren();options.filter(v=>!input?.value||v.includes(input.value)).forEach(value=>{const option=template.cloneNode(true);option.setAttribute('role','option');const content=$('.kodo-new-select-item-option-content',option)||option;content.textContent=value;option.onclick=e=>{e.stopPropagation();onChoose(value);closePortal();};list.append(option);});if(holder){holder.style.maxHeight='256px';holder.style.overflowY='auto';}}if(input)input.oninput=paint;paint();return;}
  }
  const box=showPortal('<div class="kodo-new-select-dropdown css-uy6x3o demo-v3-select"><input aria-label="搜索选项" placeholder="搜索"><div role="listbox"></div></div>',anchor.closest('.kodo-new-select,.ant-select')||anchor,'select');
  const list=$('[role="listbox"]',box),search=$('input',box);function fill(){list.replaceChildren();options.filter(x=>String(x).includes(search.value)).forEach(text=>{const o=document.createElement('div');o.className='kodo-new-select-item kodo-new-select-item-option';o.setAttribute('role','option');o.tabIndex=0;o.textContent=text;o.onclick=e=>{e.stopPropagation();onChoose(text);closePortal();};list.append(o);});}search.oninput=fill;fill();search.focus();
 }
 function isMultiSelect(wrap){return !!wrap&&(wrap.matches('.kodo-new-select-multiple,.ant-select-multiple')||!!$('.ant-select-selection--multiple',wrap));}
 function selectedValues(input){const wrap=input.closest('.kodo-new-select,.ant-select');if(!wrap)return[];return $$('.kodo-new-select-selection-item-content,.ant-select-selection-item-content,.ant-select-selection__choice__content',wrap).map(e=>e.textContent.trim()).filter(Boolean);}
 function prepareLegacySelects(scope){$$('.ant-select',scope).forEach(wrap=>{const selector=$('.ant-select-selection',wrap);if(!selector||$('.ant-select-selector',wrap))return;let input=$('input',selector);if(!input){input=document.createElement('input');input.type='search';input.className='ant-select-search__field';input.style.cssText='position:absolute;opacity:0;width:1px;height:1px;left:8px;top:4px;pointer-events:none';input.setAttribute('aria-hidden','true');selector.append(input);}input.setAttribute('role','combobox');input.disabled=wrap.classList.contains('ant-select-disabled');});}
 function assignSelect(input,value,persist=true){
  const wrap=input.closest('.kodo-new-select,.ant-select');if(!wrap){input.value=value;return;}
  if(isMultiSelect(wrap)){
   let values=Array.isArray(value)?[...value]:value?(persist?selectedValues(input):selectedValues(input)):[];if(!Array.isArray(value)&&value){const at=values.indexOf(String(value));if(at<0)values.push(String(value));else if(persist)values.splice(at,1);}
   values=[...new Set(values)];const legacy=!!$('.ant-select-selection--multiple',wrap),itemSelector=legacy?'.ant-select-selection__choice':'.kodo-new-select-selection-item,.ant-select-selection-item',old=$$(itemSelector,wrap),template=old[0]?.cloneNode(true),bucket=legacy?$('.ant-select-selection__rendered ul',wrap):$('.kodo-new-select-selection-overflow,.ant-select-selection-overflow',wrap)||$('.kodo-new-select-selector,.ant-select-selector',wrap);
   old.forEach(item=>{const outer=item.closest('.kodo-new-select-selection-overflow-item,.ant-select-selection-overflow-item');if(outer&&!$('input',outer))outer.remove();else item.remove();});
   for(const text of values){const item=template?template.cloneNode(true):document.createElement(legacy?'li':'span');if(!template)item.className=legacy?'ant-select-selection__choice':wrap.classList.contains('ant-select')?'ant-select-selection-item':'kodo-new-select-selection-item';let content=$('.kodo-new-select-selection-item-content,.ant-select-selection-item-content,.ant-select-selection__choice__content',item);if(!content){content=document.createElement('span');content.className=legacy?'ant-select-selection__choice__content':'kodo-new-select-selection-item-content';item.replaceChildren(content);}content.textContent=text;let remove=$('.kodo-new-select-selection-item-remove,.ant-select-selection-item-remove,.ant-select-selection__choice__remove',item);if(!remove){remove=document.createElement('span');remove.className=legacy?'ant-select-selection__choice__remove':'kodo-new-select-selection-item-remove';remove.textContent='×';item.append(remove);}remove.dataset.demoSelectRemove=text;remove.setAttribute('role','button');remove.setAttribute('aria-label','移除'+text);const entry=legacy?item:document.createElement('div');if(!legacy){entry.className=wrap.classList.contains('ant-select')?'ant-select-selection-overflow-item':'kodo-new-select-selection-overflow-item';entry.append(item);}const search=Array.from(bucket?.children||[]).find(e=>$('input',e));if(bucket)bucket.insertBefore(entry,search||null);}
   input.value='';input.dataset.demoSelection=JSON.stringify(values);$$('.kodo-new-select-selection-placeholder,.ant-select-selection-placeholder,.ant-select-selection__placeholder',wrap).forEach(e=>e.style.display=values.length?'none':'');
  }else{
   const text=String(value??'');input.value=text;input.dataset.demoSelection=text;let item=$('.kodo-new-select-selection-item,.ant-select-selection-item,.ant-select-selection-selected-value',wrap);const legacy=!!$('.ant-select-selection',wrap)&&!$('.ant-select-selector',wrap),selector=legacy?$('.ant-select-selection__rendered',wrap)||$('.ant-select-selection',wrap):$('.kodo-new-select-selector,.ant-select-selector',wrap);
   if(text){if(!item){item=document.createElement(legacy?'div':'span');item.className=legacy?'ant-select-selection-selected-value':wrap.classList.contains('ant-select')?'ant-select-selection-item':'kodo-new-select-selection-item';(selector&&($('.kodo-new-select-selection-wrap,.ant-select-selection-wrap',selector)||selector))?.append(item);}item.textContent=text;$$('.kodo-new-select-selection-placeholder,.ant-select-selection-placeholder,.ant-select-selection__placeholder',wrap).forEach(e=>e.style.display='none');}else{item?.remove();let placeholder=$('.kodo-new-select-selection-placeholder,.ant-select-selection-placeholder,.ant-select-selection__placeholder',wrap);if(!placeholder){placeholder=document.createElement('span');placeholder.className=legacy?'ant-select-selection__placeholder':'kodo-new-select-selection-placeholder';placeholder.textContent='请选择';(selector&&($('.kodo-new-select-selection-wrap,.ant-select-selection-wrap',selector)||selector))?.append(placeholder);}placeholder.style.display='';}
  }if(persist)draft();
 }
 ctx.assignSelect=assignSelect;ctx.prepareLegacySelects=prepareLegacySelects;ctx.fieldLabel=fieldLabel;
 ctx.getSelectValue=input=>isMultiSelect(input.closest('.kodo-new-select,.ant-select'))?selectedValues(input):input.value||$('.kodo-new-select-selection-item,.ant-select-selection-item,.ant-select-selection-selected-value',input.closest('.kodo-new-select,.ant-select'))?.textContent.trim()||'';
 function openSelect(input){
  const label=fieldLabel(input),known={状态:['未开始','执行中','已结束','已取消'],产品属性:['自制','外购','委外'],制造模式:['自制','外购','委外'],单位:['个','包','箱','KG'],是否启用:['是','否'],元素一:['固定文本','提交时间','表单字段','空值'],筛选方式:['筛选明细','筛选指标']};
  known['任务类型']=['首件确认','成品异常','物料异常','设备故障','工艺异常','仓库备料','模具维修','通用设备报修'];
  const values=p.id==='D-CUSTOMREPORT-filter'&&input.closest('.filterItem__oSM3j')?A.reportFilterFields:known[label]||(/仓库/.test(label)?['演示仓库A','演示仓库B']:/人员|负责人|关注人|业务员/.test(label)?['演示人员A','演示人员B']:/供应商/.test(label)?['演示供应商A','演示供应商B']:/客户/.test(label)?['演示客户A','演示客户B']:/产品/.test(label)?['演示产品A','演示产品B']:['演示选项A','演示选项B']);
  selectMenu(input,values,value=>{assignSelect(input,value);if(p.id==='D-COLLAB-create'&&value==='首件确认'){sessionStorage.setItem(draftKey('D-COLLAB-type-form'),JSON.stringify(draft()));navigate('D-COLLAB-type-form',false);}});
 }
 ctx.openSelectInput=openSelect;
 function filterPanel(anchor,options={}){
  const activeFields=options.fields||fields;
  const panel=showPortal(A.components.filter,anchor,'filter'),form=$('form',panel),sourceRow=$('[class*="row__"]',form),rows=document.createElement('div');rows.className='demo-v3-filter-rows';
  if(sourceRow)sourceRow.replaceWith(rows);else form?.prepend(rows);
  const conditions=(options.conditions||view.conditions||[]).map(x=>({...x}));
  function paint(){rows.replaceChildren();conditions.forEach((c,i)=>{const row=document.createElement('div');row.className='demo-v3-condition';const field=document.createElement('button'),op=document.createElement('button'),value=document.createElement('input'),remove=document.createElement('button');field.type=op.type=remove.type='button';field.className=op.className='kodo-new-btn';field.textContent=c.field;field.setAttribute('aria-label','筛选字段'+(i+1));op.textContent=c.op;op.setAttribute('aria-label','筛选运算符'+(i+1));value.value=c.value;value.placeholder='请输入';value.setAttribute('aria-label','筛选值'+(i+1));value.oninput=()=>c.value=value.value;remove.textContent='×';remove.setAttribute('aria-label','移除筛选条件'+(i+1));remove.onclick=()=>{conditions.splice(i,1);paint();};field.onclick=()=>selectWithin(field,activeFields.filter(x=>x&&x!=='操作'),v=>{c.field=v;field.textContent=v;});op.onclick=()=>selectWithin(op,['等于','不等于','包含','不包含','为空','不为空'],v=>{c.op=v;op.textContent=v;});row.append(field,op,value,remove);rows.append(row);});}
  $$('button',panel).forEach(b=>{if(/添加筛选条件/.test(b.textContent))b.onclick=e=>{e.stopPropagation();conditions.push({field:activeFields.find(Boolean)||'工单编号',op:'包含',value:''});paint();};if(/查询/.test(b.textContent))b.onclick=e=>{e.stopPropagation();const selected=conditions.filter(c=>c.value||/为空/.test(c.op));if(options.onApply)options.onApply(selected);else{view.conditions=selected;view.page=1;saveView();render();}closePortal();};});paint();
 }
 // Submenus keep the form panel alive; source widgets use the same layered pattern.
 function selectWithin(anchor,options,apply){
  const list=document.createElement('div');list.className='demo-v3-submenu kodo-new-select-dropdown css-uy6x3o';list.setAttribute('role','listbox');const r=anchor.getBoundingClientRect();list.style.left=Math.min(r.left,innerWidth-230)+'px';list.style.top=Math.min(r.bottom+4,innerHeight-260)+'px';
  for(const text of options){const o=document.createElement('div');o.className='kodo-new-select-item kodo-new-select-item-option';o.setAttribute('role','option');o.textContent=text;o.tabIndex=0;o.onclick=e=>{e.stopPropagation();apply(text);list.remove();};list.append(o);}document.body.append(list);
 }
 function sortPanel(anchor,options={}){
  const activeFields=options.fields||fields;
  const panel=showPortal(A.components.sort,anchor,'sort'),list=$('.kodo-art-table-toolbar-sort-list',panel),add=$('.kodo-art-table-toolbar-sort-create',panel);
  const sort=(options.sort||view.sort||[]).map(x=>({...x}));
  const apply=()=>{const next=sort.map(x=>({...x}));if(options.onApply)options.onApply(next);else{view.sort=next;view.page=1;saveView();render();}};
  function paint(){list.replaceChildren();sort.forEach((s,i)=>{
   const row=document.createElement('div');row.className='kodo-art-table-toolbar-sort-item';
   const drag=document.createElement('i');drag.className='kodo-art-table-toolbar-sort-drag icon-tuodong';
   const f=document.createElement('div');f.className='kodo-art-table-toolbar-sort-select';f.setAttribute('role','button');f.tabIndex=0;f.innerHTML='<div class="kodo-art-table-toolbar-sort-select-item"><span class="kodo-art-table-toolbar-sort-select-item-title"></span><i class="icon-zhankai01"></i></div>';$('.kodo-art-table-toolbar-sort-select-item-title',f).textContent=s.field;
   f.onclick=()=>selectWithin(f,[...new Set(activeFields.filter(n=>n&&n!=='操作'))],v=>{s.field=v;$('.kodo-art-table-toolbar-sort-select-item-title',f).textContent=v;apply();});
   const rule=document.createElement('div');rule.className='kodo-art-table-toolbar-sort-rule';rule.innerHTML='<div class="kodo-art-table-toolbar-sort-rule-switch-handle"></div><div class="kodo-art-table-toolbar-sort-rule-num-button"><span role="button" tabindex="0" class="kodo-art-table-toolbar-sort-rule-button-left">升序</span><span role="button" tabindex="0" class="kodo-art-table-toolbar-sort-rule-button-right">降序</span></div>';
   const update=()=>{$('.kodo-art-table-toolbar-sort-rule-switch-handle',rule).classList.toggle('kodo-art-table-toolbar-sort-rule-switch-handle-checked',s.direction==='降序');$$('[role="button"]',rule).forEach(b=>b.classList.toggle('kodo-art-table-toolbar-sort-rule-button-checked',b.textContent===s.direction));};
   $$('[role="button"]',rule).forEach(b=>b.onclick=()=>{s.direction=b.textContent;update();apply();});update();
   const x=document.createElement('i');x.className='kodo-art-table-toolbar-sort-delete icon-shanchu2';x.setAttribute('role','button');x.setAttribute('aria-label','删除排序条件'+(i+1));x.onclick=()=>{sort.splice(i,1);apply();paint();};
   row.draggable=true;row.ondragstart=e=>e.dataTransfer.setData('text/plain',String(i));row.ondragover=e=>e.preventDefault();row.ondrop=e=>{e.preventDefault();const from=+e.dataTransfer.getData('text/plain');if(Number.isInteger(from)&&sort[from]){sort.splice(i,0,sort.splice(from,1)[0]);apply();paint();}};
   row.append(drag,f,rule,x);list.append(row);
  });}
  add.setAttribute('role','button');add.tabIndex=0;add.onclick=e=>{e.stopPropagation();sort.push({field:activeFields.find(Boolean)||'工单编号',direction:'升序'});paint();apply();};paint();
 }
 ctx.openPortal=showPortal;ctx.openFilterPanel=filterPanel;ctx.openSortPanel=sortPanel;
 function batchQuery(anchor){
  const d=ctx.dialog('批量查询','<textarea aria-label="批量查询编号" placeholder="每行一个编号"></textarea><p><button data-batch-query>查询</button></p>');$('[data-batch-query]',d).onclick=()=>{const ids=$('textarea',d).value.split(/[\n,，]+/).map(x=>x.trim()).filter(Boolean);view.conditions=[];view.text='';filtered=allRecords.filter(r=>ids.some(id=>Object.values(r.fields).some(v=>String(v)===id)));const keep=allRecords;allRecords=filtered;view.page=1;render();allRecords=keep;d.close();ctx.toast('批量查询结果：'+filtered.length+'条');};
 }
 function calendarPanel(input,onDone=()=>draft()){
  const wrap=input.closest('.ant-picker,.kodo-new-picker,.ant-calendar-picker'),inputs=wrap?$$('input',wrap):[input],range=inputs.length>1;
  let chosen=null,month=new Date().getMonth(),year=new Date().getFullYear();const panel=showPortal(A.components.calendar,wrap||input,'calendar');
  const panes=$$('.ant-picker-date-panel,.kodo-new-picker-date-panel',panel);
  function paint(){panes.forEach((pane,index)=>{const d=new Date(year,month+index,1),yy=d.getFullYear(),mm=d.getMonth();const y=$('.ant-picker-year-btn,.kodo-new-picker-year-btn',pane),m=$('.ant-picker-month-btn,.kodo-new-picker-month-btn',pane);if(y)y.textContent=yy+'年';if(m)m.textContent=(mm+1)+'月';const start=new Date(yy,mm,1-((d.getDay()+6)%7));$$('tbody td',pane).forEach((cell,i)=>{const day=new Date(start);day.setDate(start.getDate()+i);const value=day.getFullYear()+'-'+String(day.getMonth()+1).padStart(2,'0')+'-'+String(day.getDate()).padStart(2,'0');cell.classList.toggle('ant-picker-cell-in-view',day.getMonth()===mm);cell.classList.remove('ant-picker-cell-range-start','ant-picker-cell-range-end','ant-picker-cell-in-range','ant-picker-cell-selected');cell.setAttribute('aria-label',value);const inner=$('.ant-picker-cell-inner',cell)||cell;inner.textContent=String(day.getDate());cell.onclick=e=>{e.stopPropagation();if(range&&!chosen){chosen=value;inputs[0].value=value;cell.classList.add('ant-picker-cell-range-start');}else{inputs[range?1:0].value=value;if(range&&inputs[1].value<inputs[0].value){const temp=inputs[0].value;inputs[0].value=inputs[1].value;inputs[1].value=temp;}onDone();closePortal();}};});});}
  $$('.ant-picker-header-prev-btn,.ant-picker-header-next-btn,.ant-picker-header-super-prev-btn,.ant-picker-header-super-next-btn',panel).forEach(b=>b.onclick=e=>{e.stopPropagation();const delta=b.className.includes('super')?12:1;month+=b.className.includes('prev')?-delta:delta;paint();});paint();
 }
 ctx.openCalendar=calendarPanel;ctx.dismissPortal=closePortal;
 function formSave(button){
  const formulaTarget={'D-CF-RW-price-editor':'计算公式','D-PROCESS-plan-formula':'计划数默认值','D-CF-WO-validation-condition':'校验规则'}[p.id];
  if(formulaTarget&&$('.cm-content')){const value=$('.cm-content').textContent.trim();if(!value){ctx.toast('请填写公式');return;}let data={};try{data=JSON.parse(sessionStorage.getItem(draftKey(parent()))||'{}');}catch{}data['__formula_'+formulaTarget]=value;sessionStorage.setItem(draftKey(parent()),JSON.stringify(data));navigate(parent(),false);return;}
  if(p.id==='D-BOARD-aggregate-formula'){
   const value=$('.cm-content')?.textContent||'',balance=[...value].reduce((n,c)=>n+(c==='('?1:c===')'?-1:0),0);
   if(!value.trim()||balance!==0){ctx.toast('公式不能为空，且括号需成对');return;}
   let data={};try{data=JSON.parse(sessionStorage.getItem(draftKey(parent()))||'{}');}catch{}
   data['公式']=value;data['请编辑公式']=value;sessionStorage.setItem(draftKey(parent()),JSON.stringify(data));navigate(parent(),false);return;
  }
  const modal=button.closest('[role="dialog"],.kodo-new-drawer-content')||document;
  const inputs=$$('input,textarea',modal).filter(e=>!e.disabled&&(!e.readOnly||e.closest('.ant-picker,.kodo-new-picker'))&&e.type!=='file'&&e.type!=='checkbox'&&e.type!=='radio'&&e.getAttribute('role')!=='combobox');let invalid=null;
  inputs.forEach(e=>{const item=e.closest('.kodo-new-form-item,.ant-form-item,.ant-legacy-form-item,.kodo-lite-form-item');const required=!!$('.kodo-new-form-item-required,.ant-form-item-required,.ant-legacy-form-item-required,.kodo-lite-form-item-required',item||e.parentElement);$('.demo-v3-error',item||e.parentElement)?.remove();if(required&&!e.value.trim()){const msg=document.createElement('div');msg.className='demo-v3-error';msg.textContent='请填写'+(fieldLabel(e)||'此项');(item||e.parentElement).append(msg);invalid??=e;}});
  $$('input[role="combobox"],input[type="radio"]',modal).filter(e=>!e.disabled).forEach(e=>{const item=e.closest('.kodo-new-form-item,.ant-form-item,.ant-legacy-form-item,.kodo-lite-form-item');if(!item||!$('.kodo-new-form-item-required,.ant-form-item-required,.ant-legacy-form-item-required,.kodo-lite-form-item-required',item)||$('.demo-v3-error',item))return;const selected=e.type==='radio'?!!$('input[type="radio"]:checked',item):e.value.trim()||$('.kodo-new-select-selection-item,.ant-select-selection-item',e.closest('.kodo-new-select,.ant-select'))?.textContent.trim();if(!selected){const msg=document.createElement('div');msg.className='demo-v3-error';msg.textContent='请选择'+fieldLabel(e);item.append(msg);invalid??=e;}});
  if(invalid){invalid.focus();ctx.toast('请先填写必填项');return;}
  ctx.beforeNativeSubmit?.(modal);const values=draft();if(reportFilters)values['筛选条件']=reportFilters.data();db.settings[p.id]=values;const selected=sessionStorage.getItem('blacklake-selected-'+root);const target=db.records?.find(r=>r.id===selected);
  if(p.id==='D-V3-BOM-child-new-product'){
   const key='blacklake-demo-v3-'+root+'-picker-1-D-V3-BOM-child-picker',picker=read(key,{records:[],settings:{},undo:[]});
   const record={id:'DEMO-'+String(Date.now()).slice(-6),fields:{...(picker.records?.[0]?.fields||{}),...values},template:0};record.fields['产品编号']=values['产品编号']||record.id;picker.records.unshift(record);localStorage.setItem(key,JSON.stringify(picker));sessionStorage.removeItem(draftKey(p.id));navigate(parent(),false);return;
  }
  if(target){Object.entries(values).forEach(([k,v])=>target.fields[k]=v);}
  else if(db.records&&(/创建|新建|新增/.test(p.label)||sessionStorage.getItem('blacklake-mode-'+root)==='create')){const record={id:'DEMO-'+String(Date.now()).slice(-6),fields:{...(db.records[0]?.fields||{})},template:0};Object.entries(values).forEach(([k,v])=>record.fields[k]=v);if(values['产品信息'])record.fields['产品名称']=values['产品信息'];Object.keys(record.fields).filter(k=>/编号|单号/.test(k)).forEach(k=>record.fields[k]=record.id);db.records.unshift(record);}
  save();if(norm(button.textContent)==='保存并继续维护'){ctx.toast('演示数据已保存，可以继续维护');return;}sessionStorage.removeItem(draftKey(p.id));if(parent()===root){view={...view,text:'',conditions:[],page:1,status:'',recent:''};saveView();}sessionStorage.setItem('blacklake-flash-'+root,'演示数据已保存');navigate(parent(),false);
 }
 function pickRows(button){
  const scope=button.closest('[role="dialog"]')||document;const checks=$$('input[type="checkbox"]:checked',scope).filter(e=>e.closest('.kodo-art-table-tbody'));const rows=checks.map(e=>e.closest('.kodo-art-table-row')).filter(Boolean);
  if(!rows.length){ctx.toast('请先选择一条演示记录');return;}
  const chosen=rows.map(r=>Array.from(r.children).map(c=>c.textContent.trim()));const h=$('.kodo-art-table-thead .kodo-art-table-row',scope),names=h?Array.from(h.children).map(c=>c.textContent.trim()):[];sessionStorage.setItem('blacklake-picked-'+root,JSON.stringify({from:p.id,to:parent(),fields:names,rows:chosen}));sessionStorage.removeItem(draftKey(p.id));navigate(parent(),false);
 }
 function actionRoute(hit,label){
  if(A.routes?.[p.id]?.[label]&&A.routes[p.id][label]!==p.id)return A.routes[p.id][label];
  const source=A.edges.filter(e=>e.from===p.id);
  const nearby=(text)=>{let el=hit;for(let i=0;el&&i<6;i++,el=el.parentElement){if(el.getAttribute('role')==='dialog')break;if(el.textContent.length<400&&el.textContent.includes(text))return true;}return false;};
  const match=source.find(e=>e.text===label&&(!e.section||nearby(e.section))&&(!e.field||nearby(e.field)));
  if(match)return match.to;
  if(/^D-SO-/.test(p.id)&&{'基本信息':1,'自定义信息':1,'销售明细':1,'其他信息':1}[label])return {'基本信息':'D-SO-basic','自定义信息':'D-SO-custom','销售明细':'D-SO-lines','其他信息':'D-SO-other'}[label];
  if(p.id.startsWith('D-WO-real-view-')&&{'基本信息':1,'自定义信息':1,'生产任务':1,'用料清单':1}[label])return {'基本信息':'D-WO-real-view-basic','自定义信息':'D-WO-real-view-custom','生产任务':'D-WO-real-view-tasks','用料清单':'D-WO-real-view-materials'}[label];
  if(!p.deepState&&label==='编辑'){
   const exact=cat.find(x=>x.parentId===p.id&&x.rendered&&/existingedit$/.test(x.id));if(exact)return exact.id;
   if(p.id==='BL-04-01')return 'D-WO-create-form';
   if(p.id==='BL-02-01')return 'D-PASSIVE-BL-02-01-blankform01';
  }
  if(!p.deepState&&label==='查看'&&p.id==='BL-06-01')return 'D-IQC-results';
  if(p.id==='D-V3-CF-price-form'&&label==='修改')return 'D-CF-RW-price-editor';
  if(p.id==='BL-10-04'&&label==='设置规则')return 'D-V3-WO-number-rule';
  if(p.id==='D-PROCESS-sop-row'&&hit.closest('.kodo-new-select-selector,.ant-select-selector,.ant-select-selection'))return 'D-PROCESS-sop-fields';
  if(p.id==='D-PASSIVE-BL-02-08-pricerule'&&hit.closest('.kodo-new-select-selector,.ant-select-selector,.ant-select-selection'))return 'D-PASSIVE-BL-02-08-pricerule-options';
  if(p.id==='D-V3-WO-number-rule'&&hit.closest('[role="combobox"],.kodo-new-select-selector'))return 'D-CF-WO-number-options';
  if(p.id==='D-BOARD-aggregate-edit'&&hit.matches('input[readonly]'))return 'D-BOARD-aggregate-formula';
  if(p.id==='D-BOARD-chart-config'&&label==='配置')return 'D-BOARD-raw-fields';
  if(p.id==='D-BOARD-chart-config'&&icon(hit)==='bianji'&&hit.closest('[class*="dragField"]'))return 'D-BOARD-aggregate-edit';
  if(p.id==='D-BOARD-content-config'&&hit.closest('[aria-roledescription="sortable"]'))return hit.dataset.chartAction==='edit'?'D-BOARD-chart-config':'D-V3-BOARD-drilldown';
  if(p.id==='D-SO-lines'&&label==='字段配置')return 'D-SO-columns-batch';
  if(!p.deepState&&!/排序|筛选|批量/.test(label)){const captured=cat.find(x=>x.kind==='PASSIVE_CONTROL'&&x.parentId===p.id&&x.trigger===label&&x.rendered);if(captured)return captured.id;}
  return null;
 }
 function localRowAction(label,row){
  if(!row)return false;const id=row.dataset.demoRecord;sessionStorage.setItem('blacklake-selected-'+root,id);
  if(label==='删除'){
   const d=ctx.dialog('删除演示记录','<p>仅删除本地演示记录，可撤销。</p><button data-remove-demo>删除</button>');$('[data-remove-demo]',d).onclick=()=>{const at=allRecords.findIndex(r=>r.id===id);if(at>=0){db.undo.push({record:allRecords[at],index:at});allRecords.splice(at,1);db.records=allRecords;save();render();}d.close();if(p.deepState)navigate(root,false);else ctx.toast('演示记录已删除，可在本地演示工具中撤销');};return true;
  }
  if(label==='复制'){const r=allRecords.find(r=>r.id===id);if(r){const copy=JSON.parse(JSON.stringify(r));copy.id='DEMO-'+String(Date.now()).slice(-6);Object.keys(copy.fields).filter(k=>/编号|单号/.test(k)).forEach(k=>copy.fields[k]=copy.id);allRecords.unshift(copy);save();render();if(p.deepState)navigate(root,false);else ctx.toast('已复制为新的演示记录');}return true;}
  return false;
 }
 const tools=$('.demo-bar');if(tools){const undo=document.createElement('button');undo.textContent='撤销上次演示删除';undo.dataset.demoUndo='';undo.disabled=!db.records?.length;undo.onclick=()=>{const last=db.undo.pop();if(last){allRecords.splice(last.index,0,last.record);save();render();ctx.toast('演示记录已恢复');}else ctx.toast('没有可撤销的演示删除');};tools.append(undo);const reset=document.createElement('button');reset.textContent='重置演示数据';reset.dataset.demoResetRecords='';reset.onclick=()=>{localStorage.removeItem(storeKey);localStorage.removeItem(storeKey+'-view');cat.filter(x=>x.id===root||x.parentId===root).forEach(x=>{sessionStorage.removeItem(draftKey(x.id));sessionStorage.removeItem('blacklake-ui-'+x.id);});sessionStorage.removeItem('blacklake-picked-'+root);sessionStorage.removeItem('blacklake-selected-'+root);sessionStorage.removeItem('blacklake-mode-'+root);location.href=root+'.html';};tools.append(reset);}
 if(tools){const clear=document.createElement('button');clear.textContent='重置全部本地演示';clear.onclick=()=>{for(const storage of [localStorage,sessionStorage])for(const key of Object.keys(storage))if(['blacklake-','readonly-demo-','demo-layout-'].some(prefix=>key.startsWith(prefix)))storage.removeItem(key);location.reload();};tools.append(clear);}
 ctx.getLocalRecord=id=>allRecords.find(r=>r.id===(id||sessionStorage.getItem('blacklake-selected-'+root)))||allRecords[0];ctx.performLocalRecordAction=(action,anchor)=>{const record=ctx.getLocalRecord();if(!record)return;if(['删除','复制'].includes(action)){localRowAction(action,{dataset:{demoRecord:record.id}});closePortal();return;}if(['开始','结束','取消','撤回'].includes(action)){const key=Object.keys(record.fields).find(k=>/状态/.test(k));if(key){record.fields[key]=({'开始':'执行中','结束':'已结束','取消':'已取消','撤回':'未开始'})[action];save();navigate(root,false);}return;}if(action==='优先级'){const panel=showPortal('<div role="listbox"><button role="option">高</button><button role="option">中</button><button role="option">低</button></div>',anchor);$$('button',panel).forEach(button=>button.onclick=()=>{record.fields['优先级']=button.textContent;save();closePortal();ctx.toast('演示优先级已更新');});return;}if(action==='打印'){const preview=ctx.dialog('演示打印预览','<p></p>');$('p',preview).textContent='本地工单 '+record.id;closePortal();}};
 ctx.commitLocalRecord=(values,id)=>{let record=allRecords.find(r=>r.id===id);if(!record){record={id:'DEMO-'+String(Date.now()).slice(-6),fields:{...(allRecords[0]?.fields||{})},template:0};allRecords.unshift(record);}Object.assign(record.fields,values);db.records=allRecords;save();render();};
 // V35: fee-only durable lifecycle. No other module uses this API.
 if(root==='BL-09-12'&&!p.deepState){
  const copy35=value=>JSON.parse(JSON.stringify(value));
  const normal35=fields=>({'费用名称':String(fields['费用名称']||'').trim(),'费用类型':fields['费用类型']==='减项'?'减项':'加项','是否启用':['否','关','false','0'].includes(String(fields['是否启用']))?'否':'是'});
  const token35=record=>JSON.stringify({id:record.id,revision:record.fields.__fee35Revision||0,fields:normal35(record.fields)});
  const load35=()=>{const value=JSON.parse(localStorage.getItem(storeKey));if(!value||!Array.isArray(value.records)||value.deletedFees35!==undefined&&!Array.isArray(value.deletedFees35))throw Error('费用演示存储异常，未执行操作');return value;};
  const write35=(next,reveal=false)=>{localStorage.setItem(storeKey,JSON.stringify(next));db=next;allRecords=db.records;if(reveal){view={...view,page:1,text:'',conditions:[],queryTerms:[],status:'',recent:''};}render();};
  const name35=value=>String(value||'').normalize('NFKC').trim().toLocaleLowerCase();
  const validate35=(next,values,id)=>{if(!values['费用名称']||values['费用名称'].length>200)throw Error('请填写1至200字的费用名称');if(next.records.some(r=>r.id!==id&&name35(r.fields['费用名称'])===name35(values['费用名称'])))throw Error('费用名称已存在，请使用其他名称');};
  const event35=(record,type,before,after)=>{const changes=Object.keys({...before,...after}).filter(field=>String(before[field]??'')!==String(after[field]??'')).map(field=>({field,before:before[field]??'—',after:after[field]??'—'}));if(type==='编辑'&&!changes.length)return false;record.fields.__v34History=[...(Array.isArray(record.fields.__v34History)?record.fields.__v34History:[]),{id:'LOCAL-'+crypto.randomUUID(),time:new Date().toISOString(),type,fieldType:'系统字段',operator:'本地演示用户',changes}];record.fields.__fee35Revision=(record.fields.__fee35Revision||0)+1;return true;};
  const current35=(next,id,expected)=>{const record=next.records.find(r=>r.id===id);if(!record)throw Error('费用记录已不存在，请返回列表刷新');if(expected!==undefined&&token35(record)!==expected)throw Error('费用已在另一处修改，请重新打开后操作');return record;};
  ctx.feeLifecycle35={
   list:()=>copy35(load35().records),
   deleted:()=>copy35((load35().deletedFees35||[]).filter(x=>!x.restored)),
   find:id=>{const next=load35(),r=next.records.find(x=>x.id===id)||(next.deletedFees35||[]).find(x=>x.record.id===id&&!x.restored)?.record;return r?copy35(r):null;},
   token:token35,
   create:input=>{const next=load35(),values=normal35(input);validate35(next,values);const record={id:'DEMO-FEE-'+crypto.randomUUID(),fields:{...values,__v34History:[]},template:0};event35(record,'新建',{},values);next.records.unshift(record);write35(next,true);return copy35(record);},
   update:(input,id,expected)=>{const next=load35(),record=current35(next,id,expected),before=normal35(record.fields),values=normal35({...before,...input});validate35(next,values,id);if(event35(record,'编辑',before,values)){Object.assign(record.fields,values);write35(next);}return copy35(record);},
   remove:(id,expected)=>{const next=load35(),record=current35(next,id,expected),index=next.records.indexOf(record);event35(record,'删除',normal35(record.fields),{});next.deletedFees35=next.deletedFees35||[];next.deletedFees35.push({record:copy35(record),index,restored:false});next.records.splice(index,1);write35(next);return copy35(record);},
   restore:id=>{const next=load35(),item=(next.deletedFees35||[]).find(x=>x.record.id===id&&!x.restored);if(!item)throw Error('已恢复或未找到该删除记录');if(next.records.some(r=>r.id===id))throw Error('该记录已存在，不能重复恢复');const record=copy35(item.record);validate35(next,normal35(record.fields),id);event35(record,'恢复',{},normal35(record.fields));next.records.splice(Math.min(item.index,next.records.length),0,record);item.restored=true;write35(next,true);return copy35(record);},
   refresh:()=>{const next=load35();db=next;allRecords=db.records;render();}
  };
 }

 // Capture phase prevents the old placeholder handlers from swallowing real interactions.
 document.addEventListener('click',e=>{
  if(e.target.closest('.demo-bar,.demo-native-column-wrap,.demo-dialog,.demo-drawer,.demo-density-menu,.demo-v3-portal,.demo-v3-submenu,.demo-v4-sort-options,.demo-nested-layer'))return;
  const density=e.target.closest('.kodo-art-table-toolbar-item');if(density?.querySelector('i[class*=hanggao]')){e.preventDefault();e.stopImmediatePropagation();ctx.densityMenu(density);return;}
  const removeTag=e.target.closest('[data-demo-select-remove]');if(removeTag){const wrap=removeTag.closest('.kodo-new-select,.ant-select'),input=wrap&&$('input',wrap);if(input&&!input.disabled){e.preventDefault();e.stopImmediatePropagation();assignSelect(input,selectedValues(input).filter(v=>v!==removeTag.dataset.demoSelectRemove),false);draft();}return;}
  const dateWrap=e.target.closest('.ant-picker,.kodo-new-picker');if(dateWrap&&!dateWrap.closest('.kodo-new-layout-sider')){const input=e.target.matches('input')?e.target:$('input',dateWrap);if(input&&!input.disabled){e.preventDefault();e.stopImmediatePropagation();calendarPanel(input);return;}}
  const hit=e.target.closest('button,a,[role="button"],[role="switch"],[role="menuitem"],.kodo-new-dropdown-menu-item,.ant-dropdown-menu-item,[role="option"],.kodo-new-select-item-option,.ant-select-item-option,[class*="btn__"],[role="tab"],.kodo-art-table-toolbar-item,.kodo-new-select-selector,.ant-select-selector,.ant-select-selection,input[readonly],[aria-roledescription="sortable"]');
  if(!hit)return;if(hit.matches('[disabled],[aria-disabled="true"]')||/disabled/i.test(hit.className))return;
  const label=hit.textContent.trim(),clean=norm(label),ico=icon(hit),row=hit.closest('[data-demo-record]');
  if(row)sessionStorage.setItem('blacklake-selected-'+root,row.dataset.demoRecord);
  if(row&&/查看|编辑/.test(label))sessionStorage.setItem('blacklake-mode-'+root,label==='编辑'?'edit':'view');
  if(!p.deepState&&/^(创建|新建|新增)/.test(label)){sessionStorage.removeItem('blacklake-selected-'+root);sessionStorage.setItem('blacklake-mode-'+root,'create');}
  const block=()=>{e.preventDefault();e.stopImmediatePropagation();};
  if(['quanjuchakan','suoxiaoshitu'].includes(ico)){const wrapper=hit.closest('.kodo-new-drawer-content-wrapper,.ant-drawer-content-wrapper');if(wrapper){block();const expanded=wrapper.dataset.demoExpanded==='1';if(!expanded)wrapper.dataset.demoNormalWidth=wrapper.style.width;wrapper.style.width=expanded?wrapper.dataset.demoNormalWidth:'100vw';wrapper.dataset.demoExpanded=expanded?'0':'1';hit.setAttribute('aria-label',expanded?'展开视图':'收起视图');hit.setAttribute('aria-pressed',String(!expanded));}return;}
  if(ico==='qiehuanbuju'){block();hit.title='切换布局';return;}
  if(p.id==='D-IQC-results'&&label==='更多'){block();hit.title='当前源页点击及悬停均未展开菜单';return;}
  if(reportFilters&&label==='添加筛选条件'){block();reportFilters.add();return;}
  if(hit.closest('.ant-input-clear-icon,.kodo-new-input-clear-icon')){block();const wrap=hit.closest('.ant-input-affix-wrapper,.kodo-new-input-affix-wrapper'),input=wrap&&$('input',wrap);if(input){input.value='';input.dispatchEvent(new Event('input',{bubbles:true}));if(business)query();}return;}
  if(ico==='qingchu'){block();if(business)query(true);else{const scope=$$('[role="dialog"]').at(-1)||document;$$('input,textarea',scope).filter(x=>!x.disabled&&!x.closest('.kodo-new-layout-sider')&&x.type!=='file').forEach(x=>{if(x.type==='checkbox'||x.type==='radio')return;if(x.getAttribute('role')==='combobox')assignSelect(x,'',false);else x.value='';});draft();}return;}
  if(hit.getAttribute('role')==='switch'){block();const checked=hit.getAttribute('aria-checked')!=='true';hit.setAttribute('aria-checked',String(checked));hit.classList.toggle('kodo-new-switch-checked',checked);hit.classList.toggle('ant-switch-checked',checked);const record=allRecords.find(record=>record.id===row?.dataset.demoRecord),field=hit.closest('.kodo-art-table-cell')?.dataset.demoColumn;if(record&&field){record.fields[field]=checked;save();}draft();return;}
  if(hit.matches('.last-title__NB0wR')){block();const open=hit.getAttribute('aria-expanded')!=='true';hit.setAttribute('aria-expanded',String(open));hit.classList.toggle('open__XuhEF',open);hit.nextElementSibling?.classList.toggle('open__XuhEF',open);return;}
  if(hit.hasAttribute('aria-expanded')&&hit.closest('.kodo-new-collapse,.ant-collapse')){block();const item=hit.closest('.kodo-new-collapse-item,.ant-collapse-item');const content=item&&$('[class*="collapse-content"]',item);if(content){const open=hit.getAttribute('aria-expanded')!=='true';hit.setAttribute('aria-expanded',String(open));content.style.display=open?'':'none';}return;}
  if(hit.closest('.ant-pagination,.kodo-new-pagination')&&!hit.closest('.ant-select,.kodo-new-select')){block();const item=hit.closest('li');if(item?.dataset.demoPage)view.page=+item.dataset.demoPage;else if(/prev/.test(item?.className||'')||ico==='left')view.page--;else if(/next/.test(item?.className||'')||ico==='right')view.page++;render();return;}
  const target=actionRoute(hit,label);if(target){block();if(row)sessionStorage.setItem('blacklake-selected-'+root,row.dataset.demoRecord);navigate(target);return;}
  if(isPicker&&label==='字段配置'){block();pickerColumns();return;}
  if(p.deepState&&label==='字段配置'){block();capturedColumns(hit);return;}
  if(p.deepState&&label==='操作'&&/^D-(?:BOM|PROD-tree)|D-PASSIVE-BL-09-02/.test(p.id)){
   const child=hit.closest('.kodo-art-table-row');if(child){block();const menu=showPortal('<ul class="kodo-new-dropdown-menu kodo-new-dropdown-menu-root kodo-new-dropdown-menu-vertical css-uy6x3o" role="menu"><li class="kodo-new-dropdown-menu-item" role="menuitem">向下添加</li><li class="kodo-new-dropdown-menu-item" role="menuitem">删除</li></ul>',hit);$$('[role=menuitem]',menu).forEach(item=>item.onclick=()=>{if(item.textContent==='删除')child.remove();else{const copy=child.cloneNode(true);$$('input',copy).forEach(e=>{if(e.type==='checkbox')e.checked=false;else e.value='';});child.after(copy);}closePortal();});return;}
  }
  if(label==='高级选择'){
   const item=hit.closest('.kodo-new-form-item,.ant-form-item,.ant-legacy-form-item,.kodo-lite-form-item')||hit.parentElement;const input=$('input[role="combobox"]',item)||$$('input[role="combobox"]',hit.closest('[role="dialog"]')||document).find(e=>!e.disabled);if(input){block();openSelect(input);}return;
  }
  if(/^选择文件/.test(label)){const upload=hit.closest('.kodo-new-upload,.ant-upload,.kodo-new-form-item,.ant-form-item,.ant-legacy-form-item,.kodo-lite-form-item')||hit.parentElement;const input=$('input[type="file"]',upload)||$('input[type="file"]',hit.closest('[role="dialog"]')||document);if(input){block();input.click();input.onchange=()=>{let info=$('.demo-v3-file-info',upload);if(!info){info=document.createElement('div');info.className='demo-v3-file-info';upload.append(info);}info.textContent=Array.from(input.files||[]).map(f=>f.name).join('、')+'（仅本地选择）';};}return;}
  if(label==='导出'&&db.records){
   block();const d=ctx.dialog('导出演示数据','<p>仅包含当前本地演示数据。</p><button data-demo-export>下载CSV</button>');$('[data-demo-export]',d).onclick=()=>{const data=business?filtered:db.records,names=Object.keys(data[0]?.fields||{}),cell=v=>'"'+String(v??'').replace(/^[=+@-]/,"'$&").replaceAll('"','""')+'"';const csv='\ufeff'+[names.map(cell).join(','),...data.map(r=>names.map(n=>cell(r.fields[n])).join(','))].join('\r\n');const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'}));a.download='演示数据-'+root+'.csv';a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000);d.close();};return;
  }
  if(label==='打印'&&db.records){block();const d=ctx.dialog('演示打印预览','<p>仅预览本地演示数据；未调用打印设备。</p><div class="demo-v3-print-preview"></div>'),preview=$('.demo-v3-print-preview',d);(business?filtered:db.records).slice(0,10).forEach(r=>{const line=document.createElement('p');line.textContent=Object.values(r.fields).join('　');preview.append(line);});return;}
  if(hit.closest('.kodo-new-select-item-option,.ant-select-item-option')&&p.deepState){block();const active=$('input[aria-expanded="true"],.kodo-new-select-open input,.ant-select-open input');const data=draft();if(active)data[active.dataset.demoField||fieldLabel(active)]=label;sessionStorage.setItem(draftKey(parent()),JSON.stringify(data));navigate(parent(),false);return;}
  if((/^(取消|关闭|返回|返回列表)$/.test(clean)&&!(p.id==='D-SO-more'&&hit.closest('.list-item')))||ico==='shanchu2'&&hit.closest('[class*="header"]')||hit.getAttribute('aria-label')==='Close'){
   block();sessionStorage.removeItem(draftKey(p.id));sessionStorage.removeItem('blacklake-ui-'+p.id);if(parent().startsWith('BL-'))cat.filter(x=>x.parentId===root).forEach(x=>{sessionStorage.removeItem(draftKey(x.id));sessionStorage.removeItem('blacklake-ui-'+x.id);});sessionStorage.removeItem('blacklake-picked-'+root);navigate(parent(),false);return;
  }
  if(p.id==='D-SO-columns-batch'&&clean==='确定')return;
  if(label==='创建'&&hit.closest('.kodo-new-form-item,.ant-form-item,.ant-legacy-form-item,.kodo-lite-form-item')){const item=hit.closest('.kodo-new-form-item,.ant-form-item,.ant-legacy-form-item,.kodo-lite-form-item'),input=$('input[role=combobox]',item);if(input&&ctx.openFieldCreator){block();ctx.openFieldCreator(fieldLabel(input),hit,values=>assignSelect(input,values));return;}}
  if(p.id==='D-V3-BOM-child-new-warehouse'&&clean==='创建'){
   block();const modal=hit.closest('[role="dialog"]'),name=$$('input',modal).find(x=>/名称/.test(fieldLabel(x)))?.value.trim();if(!name){ctx.toast('请填写仓库名称');return;}let data={};try{data=JSON.parse(sessionStorage.getItem(draftKey(parent()))||'{}');}catch{}data['默认仓库']=name;sessionStorage.setItem(draftKey(parent()),JSON.stringify(data));navigate(parent(),false);return;
  }
  if(p.id==='D-COLLAB-type-form'&&clean==='生成'){
   block();const title=$('input[placeholder*="填写表单后"]');if(title){const values=draft();title.value=[values['任务类型']||'首件确认',values['产品信息']||values['工单编号']||'演示任务'].join(' · ');draft();}return;
  }
  if(/^(保存|确定|创建|提交申请|提交并开始|保存并同步|保存工艺路线|保存并继续维护|保存并确认|提交并审批)$/.test(clean)&&p.deepState){block();if(/picker/.test(p.id)&&/确定/.test(clean))pickRows(hit);else formSave(hit);return;}
  if(/^(Increase Value|Decrease Value)$/.test(hit.getAttribute('aria-label')||'')){block();const input=$('input',hit.closest('.kodo-new-input-number,.ant-input-number'));if(input&&!input.readOnly&&!input.disabled){input.value=String(Math.max(0,Number(input.value||0)+(hit.getAttribute('aria-label')==='Increase Value'?1:-1)));input.dispatchEvent(new Event('input',{bubbles:true}));}return;}
  if(hit.matches('.kodo-new-select-selector,.ant-select-selector,.ant-select-selection')){const input=$('input',hit)||$('input',hit.parentElement);if(input&&!input.disabled){block();if(hit.closest('.ant-pagination,.kodo-new-pagination'))selectMenu(input,['10 条/页','20 条/页','50 条/页'],v=>{view.size=parseInt(v);view.page=1;assignSelect(input,v);render();});else openSelect(input);}return;}
  if(business&&/^\d*筛选$/.test(clean)){block();filterPanel(hit);return;}
  if(business&&/排序$/.test(clean)){block();sortPanel(hit);return;}
  if(business&&/^\d*查询(?:\(Enter\))?$/.test(clean)){block();query();return;}
  if(business&&label==='批量'){block();batchQuery(hit);return;}
  if(label==='刷新'||!label&&/shuaxin|refresh|reload/.test(ico)){block();if(business)query();else{draft();location.reload();}return;}
  if(!label&&/sousuo|search/.test(ico)&&business){block();query();return;}
  if(row&&localRowAction(label,row)){block();return;}
  if(p.id.startsWith('D-MENU-')||p.id==='D-SO-more'){
   const id=sessionStorage.getItem('blacklake-selected-'+root)||db.records?.[0]?.id,r=db.records?.find(x=>x.id===id);
   if(r&&['开始','结束','取消','撤回'].includes(label)){block();const key=Object.keys(r.fields).find(k=>/状态/.test(k));if(key){r.fields[key]=({'开始':'执行中','结束':'已结束','取消':'已取消','撤回':'未开始'})[label];save();sessionStorage.setItem('blacklake-flash-'+root,'演示记录状态已更新');navigate(root,false);}return;}
   if(r&&['删除','复制'].includes(label)){block();localRowAction(label,{dataset:{demoRecord:id}});return;}
  }
  if(!p.deepState&&!hit.closest('.kodo-art-table-tbody')&&/^(全部|待审批|进行中|未开始|执行中|已结束|已取消)(\s*\d+)?$/.test(label)){block();view.status=label.match(/^(全部|待审批|进行中|未开始|执行中|已结束|已取消)/)[0].replace('全部','');view.page=1;render();return;}
  if(/^(最近工单|近3天到期|逾期工单)$/.test(label)){block();view.recent=label;view.page=1;render();return;}
  if(hit.getAttribute('role')==='tab'&&!actionRoute(hit,label)){
   block();if(activateTab(hit))return;const group=hit.closest('.kodo-new-tabs,.ant-tabs');if(group){$$('[role="tab"]',group).forEach(t=>{const active=t===hit;t.setAttribute('aria-selected',String(active));t.closest('.kodo-new-tabs-tab,.ant-tabs-tab')?.classList.toggle(t.closest('.ant-tabs-tab')?'ant-tabs-tab-active':'kodo-new-tabs-tab-active',active);});const scope=hit.closest('[role="dialog"]')||document,text=$$('h2,h3,h4,[class*="section"],[class*="title"]',scope).find(n=>!n.closest('[role="tablist"],.kodo-new-tabs-nav,.ant-tabs-nav')&&n.textContent.trim()===label);text?.scrollIntoView({block:'start',behavior:'smooth'});}return;
  }
 },true);
 document.addEventListener('keydown',e=>{
  if(e.key==='Escape'&&!e.isComposing&&document.querySelector('.demo-density-menu')){e.preventDefault();e.stopImmediatePropagation();document.querySelector('.demo-density-menu').remove();return;}if(e.isComposing||document.querySelector('.demo-nested-layer')||e.key==='Escape'&&document.querySelector('.demo-native-column-wrap'))return;
  if(e.key==='Escape'){const had=!!portal;closePortal();$$('.demo-v3-submenu').forEach(n=>n.remove());if(!had&&p.deepState&&!e.target.closest('.demo-native-column-wrap,.demo-dialog,.demo-drawer')){e.preventDefault();e.stopImmediatePropagation();sessionStorage.removeItem(draftKey(p.id));sessionStorage.removeItem('blacklake-ui-'+p.id);if(parent().startsWith('BL-'))cat.filter(x=>x.parentId===root).forEach(x=>{sessionStorage.removeItem(draftKey(x.id));sessionStorage.removeItem('blacklake-ui-'+x.id);});navigate(parent(),false);return;}}
  if(e.key==='Enter'&&e.target.closest('.ant-pagination-options-quick-jumper,.kodo-new-pagination-options-quick-jumper')){e.preventDefault();view.page=Number(e.target.value)||1;render();}
  else if(e.key==='Enter'&&business&&e.target.matches('input')&&!e.target.closest('.kodo-art-table-tbody,.demo-v3-portal,.demo-native-column-wrap')){e.preventDefault();e.stopImmediatePropagation();query();}
 },true);
 document.addEventListener('submit',e=>{e.preventDefault();if(business)query();});
 document.addEventListener('change',e=>{
  if(e.target.closest('.demo-nested-layer,.demo-native-column-wrap,.demo-v3-portal,.demo-dialog'))return;
  const input=e.target;
  if(input.type==='radio'){const group=input.closest('.kodo-new-radio-group,.ant-radio-group');if(group)$$('input[type="radio"]',group).forEach(x=>{if(x!==input)x.checked=false;syncField(x);});draft();}
  if(input.type==='checkbox'&&!table&&input.closest('.kodo-art-table-thead')){const ownTable=input.closest('.kodo-art-table');$$('.kodo-art-table-tbody input[type=checkbox]',ownTable).forEach(x=>{x.checked=input.checked;syncField(x);});}
  if(input.type==='checkbox'&&table&&input.closest('.kodo-art-table-thead')){$$('.demo-v3-row-check',table).forEach(x=>{x.checked=input.checked;syncField(x);x.closest('.kodo-art-table-row')?.classList.toggle('kodo-art-table-row-selected',x.checked);});}
  if(input.type==='checkbox'&&table){syncField(input);const row=input.closest('.kodo-art-table-tbody .kodo-art-table-row');if(row)row.classList.toggle('kodo-art-table-row-selected',input.checked);let summary=$('.demo-v3-selection-summary');const selected=$$('.demo-v3-row-check:checked',table).length;if(selected&&!summary){summary=document.createElement('div');summary.className='demo-v3-selection-summary';summary.setAttribute('role','status');table.parentElement.prepend(summary);}if(summary){summary.textContent='已选 '+selected+' 条演示记录';summary.style.display=selected?'':'none';}}
 });
 // Keep captured configuration labels intact while making its custom tree checkboxes operable.
 document.addEventListener('click',e=>{const box=e.target.closest('[role="checkbox"]');if(!box||box.matches('[aria-disabled="true"]')||box.closest('.demo-native-column-wrap'))return;if(!box.querySelector('input')){const value=box.getAttribute('aria-checked')!=='true';box.setAttribute('aria-checked',String(value));box.classList.toggle('kodo-new-tree-checkbox-checked',value);box.classList.toggle('ant-tree-checkbox-checked',value);}});
 // The real sidebar hierarchy is retained; opening a section reveals observed local pages.
 document.addEventListener('click',e=>{
  const link=e.target.closest('.kodo-new-layout-sider a[href]');if(link){const id=link.getAttribute('href').replace(/\.html$/,'');if(byId(id)){e.preventDefault();e.stopImmediatePropagation();navigate(id);return;}}
  const title=e.target.closest('.kodo-new-menu-submenu-title,.ant-menu-submenu-title');if(!title)return;e.preventDefault();e.stopImmediatePropagation();const name=title.textContent.trim(),li=title.parentElement;
  let sub=$(':scope > ul',li);const prefix=title.classList.contains('ant-menu-submenu-title')?'ant':'kodo-new';
  if(!sub){sub=document.createElement('ul');sub.className=prefix+'-menu '+prefix+'-menu-sub '+prefix+'-menu-inline';sub.setAttribute('role','menu');cat.filter(x=>!x.deepState&&x.module===name).forEach(x=>{const item=document.createElement('li');item.className=prefix+'-menu-item';item.setAttribute('role','menuitem');const a=document.createElement('a');a.textContent=x.label;if(x.rendered)a.href=x.id+'.html';else{a.setAttribute('aria-disabled','true');a.title='此源页面未进入，当前不模拟该操作';}item.append(a);sub.append(item);});li.append(sub);}
  const open=sub.style.display==='none'||!li.classList.contains(prefix+'-menu-submenu-open');sub.style.display=open?'block':'none';li.classList.toggle(prefix+'-menu-submenu-open',open);title.setAttribute('aria-expanded',String(open));
 },true);
 function fillNativeRow(row,headers,values){
  let previous={};try{previous=JSON.parse(row.dataset.demoRowValues||'{}');}catch{}row.dataset.demoRowValues=JSON.stringify({...previous,...values});
  Array.from(row.children).forEach((cell,index)=>{const name=headers[index]?.textContent.trim();if(!name||name==='操作'||!(name in values))return;const value=Array.isArray(values[name])?values[name].join('、'):String(values[name]??'');const input=$('input:not([type=checkbox]):not([type=radio]),textarea',cell);if(input){if(input.getAttribute('role')==='combobox')assignSelect(input,value,false);else input.value=value;return;}const box=$('.basic-cell,.simple-cell-box',cell)||cell,walker=document.createTreeWalker(box,NodeFilter.SHOW_TEXT),texts=[];while(walker.nextNode()){const node=walker.currentNode;if(node.textContent.trim()&&!node.parentElement.closest('svg,iconpark-icon,button,[class*=btn__]'))texts.push(node);}if(texts.length){texts[0].textContent=value;for(const text of texts.slice(1))if(!['KG','kg','个','包','箱','%'].includes(text.textContent.trim()))text.textContent='';}else box.textContent=value;});
 }
 function appendNativeRows(target,records,kind){
  const body=$('.kodo-art-table-tbody',target),head=$('.kodo-art-table-thead .kodo-art-table-row',target);if(!body||!head)return;const headers=Array.from(head.children),names=headers.map(h=>h.textContent.trim()),captured=(p.id==='D-WO-create-form'||target.closest('.demo-nested-layer[data-native-form=woEdit]'))?A.rowTemplates?.[kind]:null,existing=$('.kodo-art-table-row:not(.kodo-art-table-measure-row)',body)?.cloneNode(true);$$('.kodo-art-table-empty-placeholder,.demo-empty',target).forEach(e=>e.remove());
  for(const values of records){let row;if(captured){const holder=document.createElement('div');holder.innerHTML=captured.html;const sample=holder.firstElementChild;row=sample.cloneNode(false);headers.forEach((h,i)=>{const source=captured.headers.indexOf(names[i]);let cell=source>=0?sample.children[source]?.cloneNode(true):null;if(!cell){cell=document.createElement('div');cell.className='kodo-art-table-cell';cell.textContent=/数|量/.test(names[i])?'0':'—';}cell.style.width=h.style.width;cell.style.minWidth=h.style.minWidth;cell.style.flex=h.style.flex;cell.style.height=sample.style.height||cell.style.height||'32px';row.append(cell);});row.dataset.demoRowKind=kind;}else if(existing)row=existing.cloneNode(true);else{row=document.createElement('div');row.className='kodo-art-table-row';row.style.cssText='display:flex;min-height:32px;height:32px';headers.forEach(h=>{const cell=document.createElement('div');cell.className='kodo-art-table-cell';cell.style.cssText=h.style.cssText;cell.style.height='32px';cell.textContent=/数|量|配比/.test(h.textContent)?'0':'—';row.append(cell);});}fillNativeRow(row,headers,values);body.append(row);inlineActions(row);}
  window.dispatchEvent(new Event('resize'));
 }
 ctx.fillNativeRow=fillNativeRow;ctx.appendNativeRows=appendNativeRows;
 const picked=(()=>{try{return JSON.parse(sessionStorage.getItem('blacklake-picked-'+root)||'null');}catch{return null;}})();
 if(picked&&picked.to===p.id){
  sessionStorage.removeItem('blacklake-picked-'+root);const scope=$$('[role="dialog"]').at(-1)||document;
  if(p.id==='D-IQC-create-form'){
   const input=$$('input[role="combobox"]',scope).find(e=>!e.disabled);if(input)assignSelect(input,'演示产品A');
  }else{
   const required=/D-WO-add-/.test(picked.from)?/工序名称/:/产品编号/;
   const target=$$('.kodo-art-table',scope).find(t=>$$('.kodo-art-table-filter-title',t).some(e=>required.test(e.textContent)))||$('.kodo-art-table',scope);
   const body=target&&$('.kodo-art-table-tbody',target),h=target&&$('.kodo-art-table-thead .kodo-art-table-row',target);
   if(body&&h)appendNativeRows(target,picked.rows.map(values=>Object.fromEntries(picked.fields.map((name,i)=>[name,values[i]]))),/D-WO-add-/.test(picked.from)?'task':'material');
  }
  ctx.toast('已带回 '+picked.rows.length+' 条演示选择');
 }
 document.addEventListener('click',e=>{if(portal&&!e.target.closest('.demo-v3-portal,.demo-v3-submenu'))closePortal();});
 if(business)render();
 document.addEventListener('demo-columns-changed',()=>{
  if(!business||!head||!tbody)return;
  fields=Array.from(head.children).map(e=>e.textContent.trim());
  templates=$$('.kodo-art-table-row:not(.kodo-art-table-measure-row)',tbody).map(e=>e.cloneNode(true));
  if(p.id==='BL-04-02'&&!templates.length){const t=document.querySelector('template[data-task-row]');if(t)templates=[t.content.firstElementChild.cloneNode(true)];if(!db.records){db.records=[];allRecords=db.records;}}
  for(const record of allRecords)for(const field of fields)if(field&&field!=='操作'&&!(field in record.fields))record.fields[field]='—';
  render();
 });
 document.addEventListener('demo-picker-created',event=>{if(!isPicker)return;const values=event.detail||{},record={id:'DEMO-'+String(Date.now()).slice(-6),fields:{...(allRecords[0]?.fields||{}),...values},template:0};for(const name of Object.keys(record.fields))if(/编号/.test(name))record.fields[name]=values[name]||record.id;allRecords.unshift(record);db.records=allRecords;save();view.page=1;view.text='';render();ctx.toast('已创建，可在选择器中继续勾选');});
 if(p.id==='D-SO-columns-batch'){
  const modal=$$('[role="dialog"]').at(-1),left=$('.kodo-new-tree-list-holder-inner',modal),right=$('.kodo-art-fct-field-list',modal);
  const options=Array.from(left.children).map(e=>({name:$('.option-label',e)?.textContent.trim(),node:e.cloneNode(true)})).filter(x=>x.name);
  const templates=new Map(Array.from(right.children).map(e=>[$('.kodo-art-fct-item-label',e)?.textContent.trim(),e.cloneNode(true)]));
  let selected=Array.from(templates.keys()),layout=read('demo-layout-D-SO-lines',{widths:{}});if(layout.columns)selected=[...layout.columns];
  const searches=$$('input[type="text"]',modal);searches[0]?.setAttribute('aria-label','搜索全部字段');searches[1]?.setAttribute('aria-label','搜索已选字段');
  const renderFields=()=>{
   left.replaceChildren();right.replaceChildren();
   options.filter(x=>x.name.includes(searches[0]?.value||'')).forEach(x=>{const row=x.node.cloneNode(true),box=$('[role="checkbox"]',row),checked=selected.includes(x.name);box.setAttribute('aria-checked',String(checked));box.setAttribute('aria-label',x.name);box.classList.toggle('kodo-new-tree-checkbox-checked',checked);row.onclick=e=>{e.preventDefault();e.stopPropagation();selected=selected.includes(x.name)?selected.filter(n=>n!==x.name):[...selected,x.name];renderFields();};left.append(row);});
   selected.filter(n=>n.includes(searches[1]?.value||'')).forEach(n=>{const row=(templates.get(n)||templates.values().next().value).cloneNode(true);$('.kodo-art-fct-item-label',row).textContent=n;row.draggable=true;row.ondragstart=e=>e.dataTransfer.setData('text/plain',n);row.ondragover=e=>e.preventDefault();row.ondrop=e=>{e.preventDefault();const from=e.dataTransfer.getData('text/plain'),at=selected.indexOf(from),to=selected.indexOf(n);if(at>=0){selected.splice(at,1);selected.splice(to,0,from);renderFields();}};const remove=$('.icon-shanchu4',row);if(remove){remove.setAttribute('role','button');remove.setAttribute('aria-label','移除'+n);remove.onclick=e=>{e.stopPropagation();selected=selected.filter(x=>x!==n);renderFields();};}const top=$('.icon-to-top',row);if(top){top.setAttribute('role','button');top.setAttribute('aria-label','置顶'+n);top.onclick=e=>{e.stopPropagation();selected=[n,...selected.filter(x=>x!==n)];renderFields();};}const pin=$('.icon-guding',row);if(pin){pin.setAttribute('role','button');pin.setAttribute('aria-label','固定'+n);pin.removeAttribute('aria-disabled');pin.classList.toggle('kodo-art-fct-item-action-fixed',!!layout.pins?.[n]);pin.classList.toggle('kodo-art-fct-item-action-hover',!layout.pins?.[n]);pin.onclick=e=>{e.preventDefault();e.stopPropagation();layout.pins??={};layout.pins[n]=!layout.pins[n];renderFields();};}right.append(row);});
   $('.kodo-art-fct-left .kodo-art-fct-title',modal).textContent='全部字段（'+options.length+'）';$('.kodo-art-fct-right .kodo-art-fct-title',modal).textContent='已选字段（'+selected.length+'）';
  };
  searches.forEach(e=>e.oninput=renderFields);const all=$('.kodo-new-multi-search-tree-checkbox input',modal);if(all)all.onchange=()=>{selected=all.checked?options.map(x=>x.name):[];renderFields();};
  modal.addEventListener('click',e=>{if(norm(e.target.closest('button')?.textContent)==='确定'){e.preventDefault();e.stopImmediatePropagation();layout.columns=[...selected];localStorage.setItem('demo-layout-D-SO-lines',JSON.stringify(layout));navigate('D-SO-lines',false);}},true);renderFields();
 }
 if(business)$$('.tabTitle__ymZpM').forEach(e=>{e.setAttribute('role','button');e.tabIndex=0;});
 if(p.id==='D-BOARD-content-config')$$('[aria-roledescription="sortable"]').forEach((card,i)=>{
  const actions=document.createElement('div');actions.className='demo-v3-chart-actions';
  const edit=document.createElement('button');edit.type='button';edit.setAttribute('aria-label','编辑图表'+(i+1));edit.title='编辑图表';edit.dataset.chartAction='edit';edit.textContent='✎';
  edit.onclick=e=>{e.preventDefault();e.stopPropagation();navigate('D-BOARD-chart-config');};actions.append(edit);card.style.position='relative';card.append(actions);
 });
 // Chart pixels use synthetic values. The source canvas never supplies business data.
 if(p.id==='D-CUSTOMREPORT-filter'){
  const first=$('.preview__TuhuC'),area=first?.parentElement,editor=$('.editFilter__MYaAx'),template=first?.cloneNode(true),nameInput=editor&&$('input[placeholder="请输入筛选条件名称"]',editor),fieldInput=editor&&$('input[role="combobox"]',editor);
  if(first&&area&&editor&&template){
   let active=0,items=Array.isArray(db.settings[p.id]?.['筛选条件'])?db.settings[p.id]['筛选条件']:[{name:$('.filterName__hMREz',first)?.textContent||'工单编号',field:'工单编号(工单)',value:''}];
   function remember(){if(!items[active])return;items[active]={...items[active],name:nameInput?.value.trim()||'未命名筛选项',field:fieldInput?.value||items[active].field,value:$('input[placeholder="请输入"]',editor)?.value||''};}
   function show(){editor.style.visibility=items.length?'visible':'hidden';const item=items[active];if(!item)return;if(nameInput)nameInput.value=item.name==='未命名筛选项'?'':item.name;if(fieldInput)assignSelect(fieldInput,item.field||'',false);const value=$('input[placeholder="请输入"]',editor);if(value)value.value=item.value||'';}
   function paint(){area.replaceChildren();items.forEach((item,i)=>{const card=template.cloneNode(true);card.classList.toggle('active__ggp5v',i===active);$('.filterName__hMREz',card).textContent=item.name;card.setAttribute('role','button');card.tabIndex=0;card.onclick=e=>{e.stopPropagation();remember();active=i;paint();show();};const remove=$('.delete__zUf1N',card);if(remove){remove.setAttribute('role','button');remove.setAttribute('aria-label','删除筛选条件'+(i+1));remove.onclick=e=>{e.preventDefault();e.stopPropagation();remember();items.splice(i,1);active=Math.max(0,Math.min(active,items.length-1));paint();show();};}area.append(card);});}
   reportFilters={add(){if(items.length>=10){ctx.toast('最多可添加10个');return;}remember();items.push({name:'未命名筛选项',field:'',value:''});active=items.length-1;paint();show();nameInput?.focus();},data(){remember();return items;}};
   nameInput?.addEventListener('input',()=>{if(items[active]){items[active].name=nameInput.value||'未命名筛选项';const card=area.children[active];if(card)$('.filterName__hMREz',card).textContent=items[active].name;}});paint();show();
  }
 }
 function drawCharts(kind='双轴图'){
  $$('canvas').forEach(canvas=>{
   const width=parseFloat(canvas.style.width)||canvas.width||600,height=parseFloat(canvas.style.height)||canvas.height||300;
   canvas.width=width;canvas.height=height;const c=canvas.getContext('2d');if(!c)return;c.clearRect(0,0,width,height);c.font='12px sans-serif';c.fillStyle='#777';
   const l=50,r=width-28,t=30,b=height-44,values=[.18,.32,.26,.42,.37,.55];
   if(kind==='饼状图'){let start=-Math.PI/2;const colors=['#5b8ff9','#5ad8a6','#5d7092','#f6bd16'];[.38,.28,.2,.14].forEach((v,i)=>{c.beginPath();c.moveTo(width/2,height/2);c.arc(width/2,height/2,Math.min(width,height)*.32,start,start+2*Math.PI*v);c.closePath();c.fillStyle=colors[i];c.fill();start+=2*Math.PI*v;});return;}
   for(let i=0;i<6;i++){const y=t+(b-t)*i/5;c.strokeStyle='#eee';c.beginPath();c.moveTo(l,y);c.lineTo(r,y);c.stroke();c.fillStyle='#777';c.textAlign='right';c.fillText((100-i*20)+'%',l-8,y+4);}
   c.textAlign='center';values.forEach((v,i)=>{const x=l+(r-l)*(i+.5)/6,y=b-(b-t)*v;c.fillStyle='#777';c.fillText((i+1)+'月',x,b+22);if(kind==='条形图'){c.fillStyle='#5b8ff9';c.fillRect(x-13,y,26,b-y);}else{c.strokeStyle='#5b8ff9';c.lineWidth=2;if(i){const px=l+(r-l)*(i-.5)/6,py=b-(b-t)*values[i-1];c.beginPath();c.moveTo(px,py);c.lineTo(x,y);c.stroke();}c.fillStyle='#5b8ff9';c.beginPath();c.arc(x,y,3,0,Math.PI*2);c.fill();}});
  });
 }
 if(p.id.startsWith('D-BOARD-')){
  drawCharts();
  $$('.tagGroup__wCCLj .tag__59BjE').forEach(tag=>{if(/disabled/i.test(tag.className))return;tag.setAttribute('role','button');tag.tabIndex=0;tag.addEventListener('click',e=>{e.preventDefault();e.stopImmediatePropagation();$$('.tagGroup__wCCLj .tag__59BjE').forEach(x=>{x.classList.toggle('active__jbbxq',x===tag);x.setAttribute('aria-pressed',String(x===tag));});drawCharts(tag.textContent.trim());},true);});
 }
 const flash=sessionStorage.getItem('blacklake-flash-'+root);if(flash){sessionStorage.removeItem('blacklake-flash-'+root);ctx.toast(flash);}
 requestAnimationFrame(()=>{restoreUI();const offset={'D-WO-real-view-custom':517,'D-WO-real-view-tasks':696,'D-WO-real-view-materials':1090}[p.id],body=$('.content-box__23uXX');if(body&&offset!==undefined&&!sessionStorage.getItem('blacklake-ui-'+p.id))body.scrollTop=offset;});
 document.documentElement.dataset.demoInteractionsReady='1';
})();
