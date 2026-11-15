/* Cretas System runtime: shared by the real prototype and the interactive catalog. */
(() => {
  'use strict';
  const escape=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const uid=()=>crypto.randomUUID();
  const motion=()=>{if(document.body.dataset.motion==='off'||matchMedia('(prefers-reduced-motion: reduce)').matches)return 0;if(document.body.dataset.motion==='light')return 100;const raw=getComputedStyle(document.body).getPropertyValue('--cs-duration').trim(),n=parseFloat(raw);return Number.isFinite(n)?Math.min(400,Math.max(0,n*(raw.endsWith('ms')?1:1000))):220};
  function animate(el,frames,duration=motion()){if(!duration||!el.animate)return Promise.resolve();/* 页面在后台时动画可能不推进，finished 一直不结束会让弹层关不掉：最多等 duration+150ms */return Promise.race([el.animate(frames,{duration,easing:'cubic-bezier(.22,1,.36,1)'}).finished,new Promise(r=>setTimeout(r,duration+150))]).catch(()=>{})}
  function status(label,tone='neutral'){const el=document.createElement('span');el.className='cs-badge';el.dataset.tone=tone;const dot=document.createElement('span');dot.className='cs-status-dot';dot.setAttribute('aria-hidden','true');el.append(dot,document.createTextNode(label));return el}
  function toast({message,tone='success',action,duration=5000,className=''}){
    let region=document.querySelector('.cs-toast-region');if(!region){region=document.createElement('div');region.className='cs-toast-region';region.setAttribute('aria-label','操作结果');document.body.append(region)}
    const el=document.createElement('div');el.className='cs-toast '+className;el.dataset.tone=tone;el.setAttribute('role',tone==='danger'?'alert':'status');el.innerHTML='<span class="cs-toast-mark" aria-hidden="true">'+(tone==='danger'?'!':tone==='warning'?'!':'✓')+'</span><span class="cs-toast-message">'+escape(message)+'</span>';
    let timer,remaining=duration,start=performance.now(),closed=false;const close=async()=>{if(closed)return;closed=true;clearTimeout(timer);await animate(el,[{opacity:1,transform:'translateY(0)'},{opacity:0,transform:'translateY(6px)'}],Math.min(140,motion()));el.remove()};
    if(action){const b=document.createElement('button');b.className='cs-button';b.dataset.variant='ghost';b.textContent=action.label;b.onclick=async()=>{await action.onClick();close()};el.append(b)}
    const x=document.createElement('button');x.className='cs-button cs-icon-button';x.dataset.variant='ghost';x.setAttribute('aria-label','关闭提示');x.textContent='×';x.onclick=close;el.append(x);region.append(el);animate(el,[{opacity:0,transform:'translateY(10px)'},{opacity:1,transform:'translateY(0)'}]);
    const resume=()=>{if(remaining>0){start=performance.now();timer=setTimeout(close,remaining)}};const pause=()=>{clearTimeout(timer);remaining=Math.max(0,remaining-(performance.now()-start))};el.addEventListener('mouseenter',pause);el.addEventListener('mouseleave',resume);el.addEventListener('focusin',pause);el.addEventListener('focusout',resume);resume();return {element:el,close};
  }
  function overlay(options,drawer=false){
    const {title,content,actions=[],onClose,beforeClose,className='',size}=options,el=document.createElement('dialog'),titleId='cs-title-'+uid(),anchor=document.activeElement;
    el.className=(drawer?'cs-drawer':'cs-modal')+' '+className;el.setAttribute('aria-labelledby',titleId);if(size)el.style.width=typeof size==='number'?Math.min(size,innerWidth-32)+'px':size;
    el.innerHTML='<header><h2 id="'+titleId+'">'+escape(title)+'</h2><button type="button" class="cs-button cs-icon-button cs-overlay-close" data-variant="ghost" aria-label="关闭弹窗">×</button></header><main></main><footer></footer>';
    const main=el.querySelector('main');if(typeof content==='string')main.innerHTML=content;else if(content)main.append(content);
    const error=document.createElement('p');error.className='cs-overlay-error chain-dialog-error';error.setAttribute('role','alert');main.append(error);let busy=false,closed=false;
    const handle={element:el,close:async({immediate=false,force=false}={})=>{if(closed)return true;if(!force&&beforeClose&&await beforeClose(handle)===false)return false;closed=true;el.classList.remove('chain-preview-dialog');el.dataset.closing='true';if(!immediate)await animate(el,drawer?[{opacity:1,transform:'translateX(0)'},{opacity:0,transform:'translateX(24px)'}]:[{opacity:1,transform:'scale(1)'},{opacity:0,transform:'scale(.98)'}],Math.min(160,motion()));el.close();el.remove();onClose?.();if(anchor?.isConnected)anchor.focus({preventScroll:true});return true}};
    const x=el.querySelector('.cs-overlay-close');x.onclick=()=>handle.close();
    for(const a of actions){const b=document.createElement('button');b.type='button';b.className='cs-button';b.dataset.variant=a.variant||'secondary';b.textContent=a.label;el.querySelector('footer').append(b);b.onclick=async()=>{if(busy)return;busy=true;error.textContent='';b.setAttribute('aria-busy','true');const siblings=[...el.querySelectorAll('footer button,.cs-overlay-close')];siblings.forEach(x=>x.disabled=true);try{if(a.onClick)await a.onClick(handle);else await handle.close()}catch(e){error.textContent=e.message||'操作未完成，请重试。'}finally{busy=false;b.removeAttribute('aria-busy');siblings.forEach(x=>x.disabled=false)}}}
    el.addEventListener('cancel',e=>{e.preventDefault();if(!busy)handle.close()});document.body.append(el);el.showModal();animate(el,drawer?[{opacity:0,transform:'translateX(24px)'},{opacity:1,transform:'translateX(0)'}]:[{opacity:0,transform:'scale(.98)'},{opacity:1,transform:'scale(1)'}]);return handle;
  }
  function renderAnswer(text){
    const labels={selectedWorkflowDefinitionVersion:'工序版本',selectedWorkflowVersion:'工序版本',selectedBomVersion:'配方版本',issuedQty:'已发数量',requiredQty:'需求数量',pickedQty:'拣货数量',consumedQty:'实际耗用',returnedQty:'退回数量'};
    const inline=s=>escape(s).replace(/`([^`\n]+)`/g,(_,v)=>'<code'+(labels[v]?' title="原字段：'+v+'"':'')+'>'+(labels[v]?escape(labels[v]):v)+'</code>').replace(/\*\*([^*\n]+)\*\*/g,'<strong>$1</strong>');
    const lines=String(text||'').replace(/[（(]`(selectedWorkflowDefinitionVersion|selectedWorkflowVersion|selectedBomVersion|issuedQty|requiredQty|pickedQty|consumedQty|returnedQty)`[）)]/g,'').replace(/\t/g,'    ').split(/\r?\n/),parts=[],stack=[];let code=false,paragraph=[];
    const flush=()=>{if(paragraph.length){parts.push('<p>'+paragraph.map((s,i)=>inline(s.trimEnd())+(s.endsWith('  ')?'<br>':i<paragraph.length-1?' ':'' )).join('')+'</p>');paragraph=[]}};
    const closeList=()=>{while(stack.length){const item=stack.pop();parts.push('</li></'+item.tag+'>')}};
    const cells=line=>line.trim().replace(/^\|/,'').replace(/\|$/,'').split('|').map(s=>s.trim());
    for(let i=0;i<lines.length;i++){
      const line=lines[i];
      if(/^\s*```/.test(line)){flush();closeList();parts.push(code?'</code></pre>':'<pre><code>');code=!code;continue}
      if(code){parts.push(escape(line)+'\n');continue}
      if(!line.trim()){flush();continue}
      const item=line.match(/^(\s*)([-*+]|\d+[.)、])\s+(.+)$/);
      if(item){flush();const indent=item[1].length,tag=/^\d/.test(item[2])?'ol':'ul';while(stack.length&&stack.at(-1).indent>indent){parts.push('</li></'+stack.pop().tag+'>')}
        if(stack.length&&stack.at(-1).indent===indent&&stack.at(-1).tag!==tag){parts.push('</li></'+stack.pop().tag+'>')}
        if(!stack.length||stack.at(-1).indent<indent){parts.push('<'+tag+'>');stack.push({indent,tag})}else parts.push('</li>');
        parts.push('<li>'+inline(item[3]));continue
      }
      if(stack.length&&line.match(/^\s*/)[0].length>stack.at(-1).indent){parts.push(' '+inline(line.trim()));continue}
      closeList();
      if(i+1<lines.length&&line.includes('|')&&/^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(lines[i+1])){flush();const heads=cells(line);parts.push('<div class="cs-answer-table"><table><thead><tr>'+heads.map(h=>'<th scope="col">'+inline(h)+'</th>').join('')+'</tr></thead><tbody>');i+=2;while(i<lines.length&&lines[i].trim()&&lines[i].includes('|')){const row=cells(lines[i]);parts.push('<tr>'+heads.map((_,j)=>'<td>'+inline(row[j]||'')+'</td>').join('')+'</tr>');i++}i--;parts.push('</tbody></table></div>');continue}
      const heading=line.match(/^(#{1,6})\s+(.+)$/),section=line.trim().match(/^\*\*([^*]{1,36})\*\*\s*$/);
      if(heading||section){flush();const level=heading?Math.min(heading[1].length+2,5):4;parts.push('<h'+level+'>'+inline(heading?heading[2]:section[1])+'</h'+level+'>');continue}
      paragraph.push(line)
    }
    flush();closeList();if(code)parts.push('</code></pre>');return parts.join('');
  }
  function tabs(root,onChange){const buttons=[...root.querySelectorAll('[role=tab],button')];buttons.forEach((b,i)=>{b.setAttribute('role','tab');b.tabIndex=b.getAttribute('aria-selected')==='true'?0:-1;b.addEventListener('click',()=>{buttons.forEach(x=>{x.setAttribute('aria-selected',String(x===b));x.tabIndex=x===b?0:-1});onChange?.(b.dataset.value||b.dataset.panelTab||b.textContent.trim(),b)});b.addEventListener('keydown',e=>{if(!['ArrowLeft','ArrowRight','Home','End'].includes(e.key))return;e.preventDefault();const n=e.key==='Home'?0:e.key==='End'?buttons.length-1:(i+(e.key==='ArrowRight'?1:-1)+buttons.length)%buttons.length;buttons[n].focus();buttons[n].click()})});root.setAttribute('role','tablist')}
  const icons={plan:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><rect x="5" y="4" width="14" height="17" rx="2"/><path d="M9 2v4m6-4v4M8 10h8m-8 4h8m-8 4h5"/></svg>',canvas:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><rect x="2" y="8" width="5" height="8" rx="1"/><rect x="17" y="2" width="5" height="6" rx="1"/><rect x="17" y="16" width="5" height="6" rx="1"/><path d="M7 12h5V5h5m-5 7v7h5"/></svg>',bom:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="m12 3 9 5-9 5-9-5 9-5Zm-9 9 9 5 9-5M3 16l9 5 9-5"/></svg>'};
  let helpTip=null,helpAnchor=null;
  function dismissHelp(){helpTip?.remove();helpTip=null;helpAnchor?.removeAttribute('aria-describedby');helpAnchor=null}
  function help(parent,{label,text}){const b=document.createElement('button');b.type='button';b.className='cs-purpose-help';b.textContent='?';b.setAttribute('aria-label',label+'说明');b.title=text;
    const show=()=>{dismissHelp();const tip=document.createElement('div');tip.className='cs-purpose-tooltip';tip.id='cs-help-'+uid();tip.role='tooltip';tip.textContent=text;(b.closest('dialog[open]')||document.body).append(tip);const r=b.getBoundingClientRect();tip.style.left=Math.max(12,Math.min(innerWidth-tip.offsetWidth-12,r.left))+'px';tip.style.top=Math.min(innerHeight-tip.offsetHeight-12,r.bottom+8)+'px';b.setAttribute('aria-describedby',tip.id);helpTip=tip;helpAnchor=b};
    b.onmouseenter=b.onfocus=show;b.onmouseleave=b.onblur=dismissHelp;b.onclick=e=>{e.stopPropagation();show()};parent.append(b);return b;
  }
  help.dismiss=dismissHelp;document.addEventListener('keydown',e=>{if(e.key==='Escape')dismissHelp()});document.addEventListener('pointerdown',e=>{if(!e.target.closest('.cs-purpose-help'))dismissHelp()});window.addEventListener('resize',dismissHelp);
  let currentMenu=null;
  function menu({anchor,items}){currentMenu?.close(false);const box=document.createElement('div');box.className='cs-action-menu';box.role='menu';box.id='cs-menu-'+uid();let closed=false;
    const close=(focus=true)=>{if(closed)return;closed=true;box.remove();anchor.setAttribute('aria-expanded','false');anchor.removeAttribute('aria-controls');document.removeEventListener('pointerdown',outside,true);document.removeEventListener('keydown',keys,true);window.removeEventListener('resize',resize);if(focus&&anchor.isConnected)anchor.focus({preventScroll:true});if(currentMenu===handle)currentMenu=null};
    const outside=e=>{if(!box.contains(e.target)&&!anchor.contains(e.target))close(false)},resize=()=>close(false),keys=e=>{const buttons=[...box.querySelectorAll('button:not(:disabled)')];if(e.key==='Escape'){e.preventDefault();close()}else if(['ArrowDown','ArrowUp','Home','End'].includes(e.key)){e.preventDefault();const i=buttons.indexOf(document.activeElement),next=e.key==='Home'?0:e.key==='End'?buttons.length-1:(i+(e.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length;buttons[next]?.focus()}};
    const handle={element:box,close};for(const item of items){const b=document.createElement('button');b.type='button';b.role='menuitem';b.textContent=item.label;b.disabled=!!item.disabled;if(item.ariaLabel)b.setAttribute('aria-label',item.ariaLabel);if(item.description)b.title=item.description;b.dataset.variant=item.variant||'secondary';b.onclick=()=>{close(false);try{Promise.resolve(item.onSelect?.()).catch(e=>toast({message:e.message,tone:'danger'}))}catch(e){toast({message:e.message,tone:'danger'})}};box.append(b)}
    (anchor.closest('dialog[open]')||document.body).append(box);const r=anchor.getBoundingClientRect();box.style.left=Math.max(8,Math.min(innerWidth-box.offsetWidth-8,r.right-box.offsetWidth))+'px';box.style.top=Math.max(8,Math.min(innerHeight-box.offsetHeight-8,r.bottom+4))+'px';anchor.setAttribute('aria-haspopup','menu');anchor.setAttribute('aria-expanded','true');anchor.setAttribute('aria-controls',box.id);document.addEventListener('pointerdown',outside,true);document.addEventListener('keydown',keys,true);window.addEventListener('resize',resize);currentMenu=handle;return handle;
  }
  /* ---- 共享数值格式（DECISIONS T5）：缺失≠0，两位小数，带单位/币种 ---- */
  const MISSING='—';
  function valueKind(v){if(v===null||v===undefined)return'missing';if(typeof v==='string'){const t=v.trim();if(!t)return'missing';if(!/^[-+]?\d+(?:\.\d+)?(?:e[-+]?\d+)?$/i.test(t.replace(/,/g,'')))return'invalid';v=Number(t.replace(/,/g,''))}if(typeof v==='boolean'||typeof v!=='number'||!Number.isFinite(v))return'invalid';return v===0?'zero':'value'}
  function toNumber(v){return typeof v==='string'?Number(v.trim().replace(/,/g,'')):v}
  function fmtNumber(v,{digits=2,missing=MISSING,invalid='格式无效'}={}){const k=valueKind(v);if(k==='missing')return missing;if(k==='invalid')return invalid;return toNumber(v).toLocaleString('zh-CN',{minimumFractionDigits:digits,maximumFractionDigits:digits})}
  function fmtMoney(v,{currency='元',digits=2,missing=MISSING}={}){const k=valueKind(v);if(k==='missing'||k==='invalid')return fmtNumber(v,{digits,missing});return fmtNumber(v,{digits})+(currency?' '+currency:'')}
  function fmtQty(v,unit,{digits=2,missing=MISSING}={}){const k=valueKind(v);if(k==='missing'||k==='invalid')return fmtNumber(v,{digits,missing});const u=String(unit??'').trim();return fmtNumber(v,{digits})+(u?' '+u:'（单位未填）')}
  function fmtPct(v,{digits=2,missing=MISSING}={}){const k=valueKind(v);if(k==='missing'||k==='invalid')return fmtNumber(v,{digits,missing});return fmtNumber(v,{digits})+'%'}
  // 零分母不做除法：返回 {value:null, reason}；调用方显示「无法计算：原因」
  function safeRatio(num,den,reason='分母为 0'){const kn=valueKind(num),kd=valueKind(den);if(kn==='missing'||kd==='missing')return{value:null,reason:'缺少数据'};if(kn==='invalid'||kd==='invalid')return{value:null,reason:'格式无效'};if(kd==='zero')return{value:null,reason};return{value:toNumber(num)/toNumber(den),reason:''}}

  /* ---- 跨页 AI 协议（D-home-analytics.md 第一部分 / DECISIONS 第三节）---- */
  const AI_STATES={
    answered:{label:'解读已完成 · 未改动业务数据',tone:'info'},
    draft:{label:'草稿已保存 · 尚未执行',tone:'warning'},
    done:{label:'已执行 · 回读一致',tone:'success'},
    mismatch:{label:'已提交 · 回读不一致',tone:'danger'},
    queued:{label:'排队中',tone:'neutral'},
    running:{label:'正在核对',tone:'neutral'},
    stale:{label:'依据已变化 · 需重新解读',tone:'warning'},
    missing:{label:'缺少数据',tone:'warning'},
    forbidden:{label:'当前账号无此权限',tone:'danger'},
    unavailable:{label:'解读服务暂不可用 · 已显示规则分析',tone:'warning'},
    sourceError:{label:'来源返回错误',tone:'danger'},
    rejected:{label:'解读未采用 · 以下为系统按规则给出的结论',tone:'warning'}
  };
  const AI_TEXT={
    unavailable:'解读服务暂不可用：下面是系统按规则算出的结果，可以直接使用；稍后可重试解读。',
    stillQueued:'仍在排队，不会判为失败；可以离开本页，结果会保存在历史里。',
    stopWaiting:'已停止等待；如果解读已经开始，完成后会保存到历史。',
    noObject:'还没有选中对象。请在列表点选一行，或打开一条记录的详情。',
    readOnly:'只读解读：不会修改任何单据；需要改动时会先给预览，由你确认。'
  };
  function aiBadge(state,extra){const s=AI_STATES[state]||{label:String(state),tone:'neutral'};const el=status(extra?s.label+' · '+extra:s.label,s.tone);el.dataset.aiState=state;return el}
  function aiBadgeHTML(state,extra){const s=AI_STATES[state]||{label:String(state),tone:'neutral'};return'<span class="cs-badge" data-tone="'+s.tone+'" data-ai-state="'+escape(state)+'"><span class="cs-status-dot" aria-hidden="true"></span>'+escape(extra?s.label+' · '+extra:s.label)+'</span>'}
  function queueText({position,waitedMs=0}={}){const min=Math.floor(waitedMs/60000);if(waitedMs>=600000)return'仍在排队（已等 '+min+' 分钟）'+(position>0?'，前面还有 '+position+' 个':'')+'；不会判为失败。';if(position>0)return'排队中，前面还有 '+position+' 个';return'正在核对…'}
  function sourceErrorText(statusCode,name='来源接口'){return name+'返回 '+statusCode+(statusCode===404?'（接口不存在）':statusCode===503?'（未开放或暂不可用）':statusCode===502?'（上游未响应）':'')+'；未用演示数据或 0 代替。'}
  function stableHash(value){const s=typeof value==='string'?value:JSON.stringify(value,Object.keys(value||{}).sort());let h=0x811c9dc5;for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,0x01000193)>>>0}return h.toString(16).padStart(8,'0')}
  function cacheKey({workspace='ydwm',objectType,objectId='',filter,sourceHash='',ruleVersion='',question=''}){return[workspace,objectType,objectId||'',filter?stableHash(filter):'',sourceHash,ruleVersion,stableHash(String(question).trim().replace(/\s+/g,' '))].join('|')}
  // 悬浮窗位置：按工作区记在浏览器，刷新恢复；视口变小时夹回可见区域。
  const POS_DEFAULT={right:20,bottom:18,width:460};
  function clampPosition(p){const w=Math.min(Math.max(360,Number(p.width)||460),Math.max(360,Math.round(innerWidth*.6)));return{width:w,right:Math.max(0,Math.min(innerWidth-Math.min(w,innerWidth)-0,Number(p.right)||0)),bottom:Math.max(0,Math.min(innerHeight-120,Number(p.bottom)||0))}}
  function loadPosition(workspace='ydwm'){try{const raw=JSON.parse(localStorage.getItem('cretas-ai-float:'+workspace)||'null');return clampPosition({...POS_DEFAULT,...(raw||{})})}catch{return clampPosition(POS_DEFAULT)}}
  function savePosition(workspace='ydwm',p){try{localStorage.setItem('cretas-ai-float:'+workspace,JSON.stringify(clampPosition(p)))}catch{/* 位置偏好未保存不影响业务 */}}
  // 解释模式校验：数字绑定 + 互相矛盾检查。facts: [{label, value, unit?, kind?('zero'|'missing'|'value'), delta?, status?}]
  const NUM_RE=/[-+]?\d[\d,]*(?:\.\d+)?\s*(%|％|kg|KG|千克|公斤|g|克|pcs|件|个|张|条|批|次|元|万元|小时|天|吨|t|L|升)?/g;
  function numbersIn(text){const out=[];for(const m of String(text).matchAll(NUM_RE)){const raw=m[0].trim(),n=Number(raw.replace(/[^\d.+-]/g,''));if(!Number.isFinite(n))continue;out.push({raw,value:n,unit:(m[1]||'').toLowerCase()})}return out}
  const unitAlias=u=>({'千克':'kg','公斤':'kg','克':'g','％':'%','件':'pcs'}[u]||u||'').toLowerCase();
  function checkParagraphs(text,facts=[],{allow=[]}={}){
    const paras=String(text||'').split(/\n\s*\n|\n(?=[-*•]|\d+[.、])/).map(s=>s.trim()).filter(Boolean);
    const allowed=new Set(allow.map(Number));
    const known=facts.flatMap(f=>{const k=valueKind(f.value);return k==='value'||k==='zero'?[{n:toNumber(f.value),unit:unitAlias(String(f.unit||'').trim()),f}]:[]});
    const near=(a,b)=>Math.abs(a-b)<=Math.max(0.005,Math.abs(b)*0.0005);
    const results=paras.map(p=>{const reasons=[];
      // 日期、编号里的数字不参与绑定
      const scrubbed=p.replace(/\d{4}[-/.年]\d{1,2}([-/.月]\d{1,2}日?)?/g,' ').replace(/[A-Za-z]+[-_]?[A-Za-z0-9-]*\d[A-Za-z0-9-]*/g,' ').replace(/[第]\s*\d+\s*[条项步]/g,' ');
      for(const x of numbersIn(scrubbed)){if(allowed.has(x.value))continue;const hit=known.find(k=>near(x.value,k.n)&&(!x.unit||!k.unit||unitAlias(x.unit)===k.unit));if(!hit)reasons.push('数字 '+x.raw+' 不在系统事实里')}
      for(const f of facts){if(!f.label||!p.includes(f.label))continue;const k=valueKind(f.value);
        if(k==='zero'&&/缺失|未填|没有填|未记录|缺少/.test(p))reasons.push(f.label+' 是已记录的 0，不是缺失');
        if(k==='missing'&&/(为|是|等于)\s*0(?![.\d])|为零|是零/.test(p))reasons.push(f.label+' 未填写，不能说成 0');
        if(typeof f.delta==='number'&&f.delta<0&&/上升|增加|增长|提高/.test(p))reasons.push(f.label+' 实际下降，文字写成上升');
        if(typeof f.delta==='number'&&f.delta>0&&/下降|减少|降低/.test(p))reasons.push(f.label+' 实际上升，文字写成下降');
        if(/CANCELLED|CLOSED|已取消|已关闭/.test(String(f.status||''))&&/补发|继续执行|重新下发/.test(p))reasons.push(f.label+' 已取消/关闭，不能建议补发或继续执行');}
      return{text:p,ok:reasons.length===0,reasons,conflict:false}});
    // 同一对象两段方向相反 → 两段都标互相矛盾
    for(const f of facts){if(!f.label)continue;const ups=results.filter(r=>r.text.includes(f.label)&&/上升|增加|增长/.test(r.text)),downs=results.filter(r=>r.text.includes(f.label)&&/下降|减少|降低/.test(r.text));if(ups.length&&downs.length)for(const r of[...ups,...downs]){r.conflict=true;r.ok=false;if(!r.reasons.includes('与另一段结论相反'))r.reasons.push('与另一段结论相反，待人工判断')}}
    return{paragraphs:results,passed:results.filter(r=>r.ok).length,rejected:results.filter(r=>!r.ok).length};
  }
  function renderChecked(result){const ok=result.paragraphs.filter(p=>p.ok),bad=result.paragraphs.filter(p=>!p.ok);let html=ok.length?renderAnswer(ok.map(p=>p.text).join('\n\n')):'<p class="cs-ai-empty">没有通过核对的结论。</p>';if(bad.length)html+='<details class="cs-ai-unverified"><summary>未核实内容 '+bad.length+' 段（不作结论）</summary>'+bad.map(p=>'<div class="cs-ai-unverified-item"><p>'+escape(p.text)+'</p><small>'+escape(p.reasons.join('；'))+'</small></div>').join('')+'</details>';return html}
  const ai=Object.freeze({STATES:AI_STATES,TEXT:AI_TEXT,badge:aiBadge,badgeHTML:aiBadgeHTML,queueText,sourceErrorText,cacheKey,hash:stableHash,loadPosition,savePosition,clampPosition,checkParagraphs,renderChecked,numbersIn});
  /* ---- 来源标记与补充入口（DECISIONS「六膳门数据来源原则」）：黑湖取得的用黑湖；缺的就地补充，标来源，不回写黑湖，可撤销 ---- */
  const SOURCES={blacklake:{label:'黑湖',tone:'info',title:'来自黑湖只读来源，Cretas 不回写'},cretas:{label:'Cretas 补充',tone:'warning',title:'在 Cretas 本机补充，留痕可撤销；不回写黑湖'},cretasMain:{label:'Cretas',tone:'success',title:'Cretas 主来源（R23：六膳门报工称重、入库批次以 Cretas 录入为准）；黑湖值只作对照，不回写黑湖'},ydwm:{label:'味美',tone:'neutral',title:'味美工作区记录'},missing:{label:'未返回',tone:'danger',title:'来源未返回，未估填'},system:{label:'系统计算',tone:'neutral',title:'由代码按口径计算'}};
  function sourceTag(source,{at,by}={}){const s=SOURCES[source]||{label:String(source),tone:'neutral',title:''};const el=status(s.label,s.tone);el.classList.add('cs-source-tag');el.dataset.source=source;el.title=s.title+(by?' · '+by:'')+(at?' · '+at:'');return el}
  function sourceTagHTML(source,o={}){return sourceTag(source,o).outerHTML}
  // 缺口提示：说清缺什么、影响什么、找谁；就地给「补充」和「Excel 导入」
  function gapNotice(parent,{title,detail='',owner='',onSupplement,onImport,supplementLabel='补充',importLabel='Excel 导入'}){
    const box=document.createElement('div');box.className='cs-gap-notice';box.setAttribute('role','note');
    box.innerHTML='<span class="cs-gap-mark" aria-hidden="true">!</span><div class="cs-gap-copy"><strong>'+escape(title)+'</strong>'+(detail?'<p>'+escape(detail)+'</p>':'')+(owner?'<small>找谁补：'+escape(owner)+'</small>':'')+'</div><div class="cs-gap-actions"></div>';
    const act=box.querySelector('.cs-gap-actions');
    if(onSupplement){const b=document.createElement('button');b.type='button';b.className='cs-button';b.dataset.variant='secondary';b.textContent=supplementLabel;b.onclick=onSupplement;act.append(b)}
    if(onImport){const b=document.createElement('button');b.type='button';b.className='cs-button';b.dataset.variant='ghost';b.textContent=importLabel;b.onclick=onImport;act.append(b)}
    parent?.append(box);return box;
  }
  // 单条补充：表单 → 预览「来源值 → 补充值」→ 确认 → 回读；onSubmit 必须返回回读后的值
  function supplementForm({title,object='',fields,onSubmit,note='只写 Cretas 本机补充，不回写黑湖；保存后可在历史中撤销。'}){
    const form=document.createElement('form');form.className='cs-supplement-form';form.noValidate=true;
    form.innerHTML='<p class="cs-supplement-object">'+escape(object)+'</p>'+fields.map((f,i)=>'<label class="cs-field"><span>'+escape(f.label)+(f.required?' <i aria-hidden="true">*</i>':'')+(f.unit?' <small>（'+escape(f.unit)+'）</small>':'')+'</span><span class="cs-supplement-source">来源值：'+(f.sourceValue===undefined||f.sourceValue===null||f.sourceValue===''?'<em>未返回</em>':escape(f.sourceValue))+'</span>'+(f.type==='select'?'<select name="f'+i+'">'+(f.options||[]).map(o=>'<option value="'+escape(o.value??o)+'"'+(String(o.value??o)===String(f.value??'')?' selected':'')+'>'+escape(o.label??o)+'</option>').join('')+'</select>':f.type==='textarea'?'<textarea name="f'+i+'" rows="3">'+escape(f.value??'')+'</textarea>':'<input name="f'+i+'" '+(f.type==='number'?'inputmode="decimal"':'')+' value="'+escape(f.value??'')+'">')+'<small class="cs-field-error" aria-live="polite"></small></label>').join('')+'<label class="cs-field"><span>补充原因 <i aria-hidden="true">*</i></span><input name="reason" placeholder="例如：黑湖未返回该工单的参与人"></label><p class="cs-supplement-note">'+escape(note)+'</p>';
    const read=()=>{const values={},errors=[];fields.forEach((f,i)=>{const el=form.elements['f'+i],raw=el.value.trim(),err=el.closest('label').querySelector('.cs-field-error');err.textContent='';let v=raw;if(f.required&&!raw){err.textContent='必填';errors.push(f.label)}else if(f.type==='number'&&raw){const k=valueKind(raw);if(k==='invalid'||(f.min!==undefined&&Number(raw)<f.min)){err.textContent=k==='invalid'?'请输入数字':'不能小于 '+f.min;errors.push(f.label)}else v=Number(raw)}values[f.key]=raw===''?null:v});const reason=form.elements.reason.value.trim();if(!reason)errors.push('补充原因');return{values,reason,errors}};
    let stage='edit';
    const h=overlay({title,content:form,className:'cs-supplement-drawer',beforeClose:()=>stage==='saved'||!form.querySelector('input,textarea')?.value||confirmAction({title:'放弃未保存的补充？',message:'补充内容尚未保存，关闭后这次填写的内容不会保留。',okLabel:'放弃并关闭',cancelLabel:'继续填写',danger:true}),actions:[{label:'取消',variant:'secondary',onClick:x=>x.close({force:true})},{label:'预览',variant:'primary',onClick:async x=>{
      const r=read();if(r.errors.length){toast({message:'请先填写：'+r.errors.join('、'),tone:'warning'});return}
      const diff=document.createElement('div');diff.innerHTML='<table class="cs-diff-table"><thead><tr><th>字段</th><th>来源值</th><th>补充后</th></tr></thead><tbody>'+fields.map(f=>'<tr><td>'+escape(f.label)+'</td><td>'+(f.sourceValue==null||f.sourceValue===''?'未返回':escape(f.sourceValue))+'</td><td>'+(r.values[f.key]==null?'—':escape(r.values[f.key]))+(f.unit?' '+escape(f.unit):'')+'</td></tr>').join('')+'</tbody></table><p>原因：'+escape(r.reason)+'</p><p class="cs-supplement-note">'+escape(note)+'</p>';
      overlay({title:'确认补充 · '+object,content:diff,actions:[{label:'返回修改',variant:'secondary',onClick:d=>d.close({force:true})},{label:'确认保存',variant:'primary',onClick:async d=>{try{const back=await onSubmit({values:r.values,reason:r.reason});stage='saved';d.close({force:true});x.close({force:true});toast({message:'已保存补充 · 回读一致',action:back&&back.viewHistory?{label:'查看记录',onClick:back.viewHistory}:undefined})}catch(e){toast({message:'保存失败：'+e.message,tone:'danger'})}}}]});
    }}]},true);
    return h;
  }
  // Excel 补充导入：选文件 → 预检（行数、错误）→ 确认导入 → 回读
  function supplementImport({title,templateHref,templateLabel='下载模板',onParse,onConfirm,note='只写 Cretas 本机补充，不回写黑湖；整批可撤销。'}){
    const box=document.createElement('div');box.className='cs-supplement-import';
    box.innerHTML=(templateHref?'<p><a class="cs-button" data-variant="ghost" href="'+escape(templateHref)+'" download>'+escape(templateLabel)+'</a></p>':'')+'<label class="cs-field"><span>选择 Excel / CSV</span><input type="file" accept=".xlsx,.xls,.csv"></label><div class="cs-import-preview" aria-live="polite"><p class="cs-supplement-note">'+escape(note)+'</p></div>';
    let parsed=null;const preview=box.querySelector('.cs-import-preview');
    box.querySelector('input[type=file]').onchange=async e=>{const file=e.target.files[0];if(!file)return;preview.innerHTML='<p>正在预检…</p>';try{parsed=await onParse(file);const rows=parsed.rows||[],errs=parsed.errors||[];preview.innerHTML='<p>可导入 <strong>'+rows.length+'</strong> 行'+(errs.length?'，<span class="cs-text-danger">'+errs.length+' 行有问题，不会导入</span>':'')+'</p>'+(errs.length?'<ul class="cs-import-errors">'+errs.slice(0,20).map(x=>'<li>'+escape(x)+'</li>').join('')+'</ul>':'')+'<p class="cs-supplement-note">'+escape(note)+'</p>'}catch(err){parsed=null;preview.innerHTML='<p class="cs-text-danger">预检失败：'+escape(err.message)+'；未导入任何数据。</p>'}};
    return overlay({title,content:box,className:'cs-supplement-drawer',actions:[{label:'取消',variant:'secondary',onClick:x=>x.close({force:true})},{label:'确认导入',variant:'primary',onClick:async x=>{if(!parsed||!(parsed.rows||[]).length){toast({message:'没有可导入的行',tone:'warning'});return}try{const back=await onConfirm(parsed);x.close({force:true});toast({message:'已导入 '+(back&&back.count!=null?back.count:parsed.rows.length)+' 行 · 回读一致',action:back&&back.viewHistory?{label:'查看记录',onClick:back.viewHistory}:undefined})}catch(e){toast({message:'导入失败：'+e.message,tone:'danger'})}}}]},true);
  }
  /* ---- B3 派生数量的只读「口径」弹层：加项、减项、一行公式；代码核对加减结果与显示值是否一致 ---- */
  let openFormulaBox=null;
  function closeFormula(){if(openFormulaBox){openFormulaBox.box.remove();openFormulaBox.anchor.setAttribute('aria-expanded','false');document.removeEventListener('pointerdown',openFormulaBox.outside,true);document.removeEventListener('keydown',openFormulaBox.keys,true);const a=openFormulaBox.anchor;openFormulaBox=null;a.focus?.({preventScroll:true})}}
  // spec: {title, result:{value,unit}, plus:[{label,value,unit?,source?}], minus:[...], formula:'可用 = 现存 − 预留', note?}
  function openFormula(anchor,spec){
    if(openFormulaBox&&openFormulaBox.anchor===anchor){closeFormula();return}closeFormula();
    const unit=spec.result?.unit??'',fq=v=>unit?fmtQty(v,unit):fmtNumber(v);
    const rows=(list,sign)=>(list||[]).map(x=>'<tr><td class="cs-formula-sign">'+sign+'</td><td>'+escape(x.label)+(x.source?' '+sourceTagHTML(x.source):'')+'</td><td class="cs-formula-num">'+escape(x.unit&&x.unit!==unit?fmtQty(x.value,x.unit):fq(x.value))+'</td></tr>').join('');
    const all=[...(spec.plus||[]).map(x=>[x,1]),...(spec.minus||[]).map(x=>[x,-1])];
    let check='';const units=new Set(all.map(([x])=>String(x.unit||'').toLowerCase()).filter(Boolean)),sameUnit=units.size<=1,itemUnit=[...units][0]||'',ratio=!!unit&&!!itemUnit&&String(unit).toLowerCase()!==itemUnit,missing=all.filter(([x])=>valueKind(x.value)==='missing'||valueKind(x.value)==='invalid');
    if(!sameUnit)check='<p class="cs-formula-warn">各项单位不同，系统不相加，结果按业务页口径显示。</p>';
    else if(missing.length)check='<p class="cs-formula-warn">缺少：'+escape(missing.map(([x])=>x.label).join('、'))+'；结果不完整。</p>';
    else if(ratio)check='<p class="cs-formula-note">结果是比率（'+escape(unit)+'），按上面的公式计算；系统只核对各项单位一致。</p>';else if(valueKind(spec.result?.value)!=='missing'){const sum=all.reduce((n,[x,s])=>n+s*toNumber(x.value),0),res=toNumber(spec.result.value);check=Math.abs(sum-res)<=Math.max(0.005,Math.abs(res)*1e-6)?'<p class="cs-formula-ok">系统核对：加减结果与显示值一致。</p>':'<p class="cs-formula-warn">系统核对：加减结果 '+escape(fq(sum))+' 与显示值不一致，请按业务页核对。</p>'}
    const box=document.createElement('div');box.className='cs-formula-pop';box.setAttribute('role','dialog');box.setAttribute('aria-label',(spec.title||'口径')+'说明');
    box.innerHTML='<header><strong>'+escape(spec.title||'口径')+'</strong><span>'+escape(fq(spec.result?.value))+'</span></header>'+(spec.formula?'<p class="cs-formula-line">'+escape(spec.formula)+'</p>':'')+'<table><tbody>'+rows(spec.plus,'+')+rows(spec.minus,'−')+'</tbody></table>'+check+(spec.note?'<p class="cs-formula-note">'+escape(spec.note)+'</p>':'')+'<p class="cs-formula-note">只读说明，不改数据。</p>';
    document.body.append(box);const r=anchor.getBoundingClientRect();box.style.left=Math.max(8,Math.min(innerWidth-box.offsetWidth-8,r.left))+'px';box.style.top=(r.bottom+6+box.offsetHeight>innerHeight?Math.max(8,r.top-box.offsetHeight-6):r.bottom+6)+'px';
    anchor.setAttribute('aria-expanded','true');
    const outside=e=>{if(!box.contains(e.target)&&!anchor.contains(e.target))closeFormula()},keys=e=>{if(e.key==='Escape'){e.preventDefault();closeFormula()}};
    document.addEventListener('pointerdown',outside,true);document.addEventListener('keydown',keys,true);openFormulaBox={box,anchor,outside,keys};
  }
  // 在数字旁放一个「口径」小按钮；返回按钮
  function formula(parent,spec){const b=document.createElement('button');b.type='button';b.className='cs-formula-trigger';b.textContent='口径';b.setAttribute('aria-haspopup','dialog');b.setAttribute('aria-expanded','false');b.title=(spec.title||'')+'怎么算的';b.onclick=e=>{e.stopPropagation();openFormula(b,typeof spec==='function'?spec():spec)};parent?.append(b);return b}

  /* ---- B6 单据操作日志：字段级变更前后值 ---- */
  // 由两个对象算字段级变化；labels: {field:'中文名'}；只比较 labels 里列出的字段（不列则比较全部顶层字段）
  function diffFields(before={},after={},labels){const keys=labels?Object.keys(labels):[...new Set([...Object.keys(before||{}),...Object.keys(after||{})])];const show=v=>v===null||v===undefined||v===''?'（空）':typeof v==='object'?JSON.stringify(v):String(v);return keys.filter(k=>show(before?.[k])!==show(after?.[k])).map(k=>({field:k,label:labels?.[k]||k,before:show(before?.[k]),after:show(after?.[k])}))}
  // entries: [{at, by, action, reason?, source?, changes:[{field,label?,before,after}]}]；新的在前
  function changeLogHTML(entries,{empty='还没有操作记录。'}={}){const ts=x=>{const v=Date.parse(x.at);return Number.isNaN(v)?-Infinity:v},list=[...(entries||[])].sort((a,b)=>ts(b)-ts(a));/* 第七批：按真实时刻排序（带时区的时间串不能按字符串比） */if(!list.length)return'<p class="cs-log-empty">'+escape(empty)+'</p>';return'<ol class="cs-change-log">'+list.map(e=>{const t=Number.isNaN(Date.parse(e.at))?escape(e.at||'时间未记录'):new Date(e.at).toLocaleString('zh-CN',{hour12:false,timeZone:'Asia/Shanghai'});/* R22：操作记录固定按北京时间显示，不随浏览器时区 */return'<li><div class="cs-log-head"><strong>'+escape(e.action||'修改')+'</strong><span>'+escape(e.by||'未记录操作人')+'</span><time title="北京时间（UTC+8）">'+t+'</time>'+(e.source?' '+sourceTagHTML(e.source):'')+'</div>'+(e.reason?'<p class="cs-log-reason">原因：'+escape(e.reason)+'</p>':'')+(e.note?'<p class="cs-log-note">'+escape(e.note)+'</p>':'')+((e.changes||[]).length?'<table class="cs-log-changes"><thead><tr><th>字段</th><th>修改前</th><th>修改后</th></tr></thead><tbody>'+e.changes.map(c=>'<tr><td>'+escape(c.label||c.field)+'</td><td class="cs-log-before">'+escape(c.before??'（空）')+'</td><td class="cs-log-after">'+escape(c.after??'（空）')+'</td></tr>').join('')+'</tbody></table>':'')+'</li>'}).join('')+'</ol>'}
  function changeLog(parent,entries,o){const el=document.createElement('div');el.className='cs-change-log-wrap';el.innerHTML=changeLogHTML(entries,o);parent?.append(el);return el}
  // 抽屉：title、object（单号）、load()→Promise<entries>
  // 第三批②：黑湖 operation_log → B6 日志条目。黑湖只给对象、动作、时间和字段类别，没有字段前后值与操作人：如实标注，不编造
  function blacklakeLog(rows){return (rows||[]).map(r=>({at:r.operateTimeString?String(r.operateTimeString).replace(' ','T')+'+08:00':(Number(r.tsMs)?new Date(Number(r.tsMs)).toISOString():''),by:'黑湖未返回操作人',action:String(r.operateType||'操作')+(r.objectName?' · '+r.objectName:''),source:'blacklake',note:(r.instanceCode?r.instanceCode+' · ':'')+'改动类别：'+(r.fieldType||'未返回')+'；黑湖未返回字段前后值。',changes:[]}))}
  async function loadBlacklakeLog(q,{url='/__unified/d/lsm-oplog'}={}){const p=new URLSearchParams();for(const k of ['code','id','object','limit'])if(q&&q[k]!=null&&q[k]!=='')p.set(k,String(q[k]));const r=await fetch(url+'?'+p),b=await r.json().catch(()=>({}));if(!r.ok||!b.success)throw Error(b.message||'黑湖操作日志读取失败');return blacklakeLog(b.data.rows)}
  function blacklakeLogDrawer({code,id,object,title='操作日志（黑湖）',label}={}){return changeLogDrawer({title,object:label||code||'',load:()=>loadBlacklakeLog({code,id,object})})}
  function changeLogDrawer({title='操作日志',object='',load}){const el=document.createElement('div');el.innerHTML='<p class="cs-log-object">'+escape(object)+'</p><div class="cs-log-body" role="status">正在读取…</div>';const h=overlay({title,content:el,className:'cs-change-log-drawer',actions:[{label:'关闭',variant:'secondary',onClick:x=>x.close()}]},true);Promise.resolve().then(load).then(list=>{el.querySelector('.cs-log-body').innerHTML=changeLogHTML(list)}).catch(e=>{el.querySelector('.cs-log-body').innerHTML='<p class="cs-text-danger">读取失败：'+escape(e.message)+'</p>'});return h}

  /* ---- BL 21 批量单号查询：粘贴多值（换行、逗号、空格分隔）→ 精确匹配 → 列出未找到的单号 ---- */
  function parseNos(text,{max=200}={}){const seen=new Set(),out=[];for(const raw of String(text||'').split(/[\s,，;；、]+/)){const v=raw.trim();if(v&&!seen.has(v)){seen.add(v);out.push(v);if(out.length>=max)break}}return out}
  function matchNos(rows,nos,getNo){const want=new Set(nos),hit=new Set(),found=[];for(const r of rows||[]){const n=String(getNo(r)??'').trim();if(want.has(n)){found.push(r);hit.add(n)}}return{found,missing:nos.filter(n=>!hit.has(n))}}
  // 在搜索框旁放「批量」按钮；onSearch(nos) 由页面执行精确匹配，返回 {found:number, missing:string[]} 以显示结果；onClear 恢复原列表
  function batchNoSearch(parent,{onSearch,onClear,label='批量',placeholder='每行一个单号，也可用逗号、空格分隔'}){
    const wrap=document.createElement('span');wrap.className='cs-batch-no';
    const b=document.createElement('button');b.type='button';b.className='cs-button';b.dataset.variant='ghost';b.textContent=label;b.title='一次粘贴多张单号精确查询，列出没找到的单号';b.setAttribute('aria-expanded','false');wrap.append(b);
    // 审计轻微项④（C 第三批）：查询成功后弹层自动收起，结果摘要留在按钮旁，不挡列表；再点「批量」可改单号
    let box=null,last='',sum=null;const close=()=>{box?.remove();box=null;b.setAttribute('aria-expanded','false')};
    const summary=(n,r,miss)=>{sum?.remove();sum=null;if(!n){b.textContent=label;return}b.textContent=label+' · '+n+' 个';sum=document.createElement('span');sum.className='cs-batch-summary';sum.setAttribute('role','status');sum.innerHTML='找到 '+escape(r.found??'—')+' 条'+(miss.length?'；<b title="'+escape(miss.join('、'))+'">未找到 '+miss.length+' 个</b>：'+escape(miss.slice(0,5).join('、'))+(miss.length>5?' 等':''):'；全部找到')+' <button type="button" data-act="x">清除</button>';sum.querySelector('[data-act=x]').onclick=()=>{last='';summary(0);onClear?.()};wrap.append(sum)};
    b.onclick=()=>{if(box){close();return}box=document.createElement('div');box.className='cs-batch-pop';box.setAttribute('role','dialog');box.setAttribute('aria-label','批量单号查询');
      box.innerHTML='<label>批量单号<textarea rows="6" placeholder="'+escape(placeholder)+'"></textarea></label><p class="cs-batch-count">0 个单号</p><div class="cs-batch-actions"><button type="button" class="cs-button" data-variant="secondary" data-act="clear">清除</button><button type="button" class="cs-button" data-variant="primary" data-act="go">查询</button></div><div class="cs-batch-result" aria-live="polite"></div>';
      wrap.append(box);b.setAttribute('aria-expanded','true');const ta=box.querySelector('textarea'),cnt=box.querySelector('.cs-batch-count'),res=box.querySelector('.cs-batch-result');ta.value=last;cnt.textContent=parseNos(last).length+' 个单号';ta.focus();
      ta.oninput=()=>{cnt.textContent=parseNos(ta.value).length+' 个单号（已去重）'};
      box.querySelector('[data-act=clear]').onclick=()=>{ta.value='';last='';cnt.textContent='0 个单号';res.innerHTML='';summary(0);onClear?.()};
      box.querySelector('[data-act=go]').onclick=async()=>{const nos=parseNos(ta.value);if(!nos.length){res.innerHTML='<p class="cs-text-danger">请先粘贴单号</p>';return}let r;try{r=await onSearch(nos)||{}}catch(e){res.innerHTML='<p class="cs-text-danger">查询失败：'+escape(e&&e.message||'请重试')+'</p>';return}const miss=r.missing||[];last=ta.value;summary(nos.length,r,miss);close();return;res.innerHTML='<p>找到 '+(r.found??'—')+' 条'+(miss.length?'；<b>未找到 '+miss.length+' 个</b>':'；全部找到')+'</p>'+(miss.length?'<p class="cs-batch-missing">'+miss.map(escape).join('、')+'</p>':'')};
      box.addEventListener('keydown',e=>{if(e.key==='Escape'){e.preventDefault();close();b.focus()}})};
    parent?.append(wrap);return{element:wrap,close};
  }

  /* ---- BL 23 相对日期：今天、本周、本月、近 7 天、近 30 天；保存的是快捷项而不是具体日期，打开时按当天滚动 ---- */
  const ymd=d=>d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');
  const REL={today:'今天',week:'本周',month:'本月',last7:'近 7 天',last30:'近 30 天'};
  function relativeRange(key,today=new Date()){const t=new Date(today.getFullYear(),today.getMonth(),today.getDate());/* R18：本周 = 本周一至本周日，本月 = 1 日至月末（截止日、到期日筛选不漏今天之后）；今天、近 7 天、近 30 天仍到今天为止 */let from=new Date(t),to=new Date(t);if(key==='week'){from.setDate(t.getDate()-((t.getDay()+6)%7));to=new Date(from);to.setDate(from.getDate()+6)}else if(key==='month'){from=new Date(t.getFullYear(),t.getMonth(),1);to=new Date(t.getFullYear(),t.getMonth()+1,0)}else if(key==='last7')from.setDate(t.getDate()-6);else if(key==='last30')from.setDate(t.getDate()-29);else if(key!=='today')return null;return{key,label:REL[key],from:ymd(from),to:ymd(to)}}
  function relativeDate(parent,{value='',onChange,keys=Object.keys(REL),allowAll=true}){const box=document.createElement('div');box.className='cs-rel-date';box.setAttribute('role','group');box.setAttribute('aria-label','相对日期');
    const paint=()=>{box.innerHTML=(allowAll?'<button type="button" data-k="">全部</button>':'')+keys.map(k=>'<button type="button" data-k="'+k+'">'+REL[k]+'</button>').join('');box.querySelectorAll('button').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.k===value)))};
    box.addEventListener('click',e=>{const b=e.target.closest('button[data-k]');if(!b)return;value=b.dataset.k;paint();onChange?.(value?relativeRange(value):null)});paint();parent?.append(box);return{element:box,get value(){return value},set(v){value=v||'';paint()}}}

  /* ---- BL 22 列表已存视图页签 + 状态计数小标签：内置 2–3 个岗位视图，用户另存最多 5 个，按账号存浏览器（与侧栏常用同一套） ---- */
  function viewsKey(page){let acc='account';try{const u=JSON.parse(localStorage.getItem('cretas_user')||'{}');acc=u.id||u.username||acc}catch{}return'cretas-views:'+acc+':'+page}
  function readViews(page){try{const v=JSON.parse(localStorage.getItem(viewsKey(page))||'[]');return Array.isArray(v)?v.filter(x=>x&&x.id&&x.name):[]}catch{return[]}}
  function writeViews(page,list){try{localStorage.setItem(viewsKey(page),JSON.stringify(list.slice(0,5)));return true}catch{toast({message:'视图未保存：浏览器存储不可用',tone:'danger'});return false}}
  // opts: {page, builtins:[{id,name,state}], getState():object, apply(state), counts?:[{key,label,count}], countKey?, onCount?(key)}
  // 第五批审计退回 1（R21）：替换浏览器原生 prompt/confirm 的两个共享小弹层。返回 Promise：确认得到文字，取消得到 null
  function askText({title='请输入',label='名称',placeholder='',value='',hint='',maxLength=12,required=true,okLabel='保存'}={}){return new Promise(resolve=>{let done=false;const f=document.createElement('form');f.className='cs-ask-form';const id='cs-ask-'+uid();
    f.innerHTML='<label for="'+id+'">'+escape(label)+(required?' <span class="cs-req" aria-hidden="true">*</span>':'')+'</label><input id="'+id+'" type="text" maxlength="'+maxLength+'" placeholder="'+escape(placeholder)+'" autocomplete="off"><p class="cs-ask-hint"><span>'+escape(hint||('最多 '+maxLength+' 个字'))+'</span><span class="cs-ask-count">0 / '+maxLength+'</span></p>';
    const input=f.querySelector('input'),count=f.querySelector('.cs-ask-count');input.value=String(value||'').slice(0,maxLength);const upd=()=>{count.textContent=input.value.length+' / '+maxLength};input.oninput=upd;upd();
    const finish=(v,h)=>{if(done)return;done=true;resolve(v);h&&h.close({force:true})};
    const ok=async h=>{const v=input.value.trim();if(required&&!v){input.focus();throw Error('请填写'+label)}if(v.length>maxLength)throw Error(label+'最多 '+maxLength+' 个字');finish(v,h)};
    const h=overlay({title,content:f,className:'cs-ask-dialog',size:420,onClose:()=>finish(null),actions:[{label:'取消',variant:'secondary',onClick:x=>finish(null,x)},{label:okLabel,variant:'primary',onClick:ok}]});
    f.onsubmit=e=>{e.preventDefault();h.element.querySelector('footer [data-variant=primary]')?.click()};setTimeout(()=>input.focus(),30)})}
  // R22：不需要填原因的确认框（替换原生 confirm）。返回 Promise<boolean>：确认 true，取消、×、Esc 都是 false
  function confirmAction({title='确认',message='',okLabel='确认',cancelLabel='取消',danger=false}={}){return new Promise(resolve=>{let done=false;const p=document.createElement('p');p.className='cs-ask-message';p.textContent=message;
    const finish=(v,h)=>{if(done)return;done=true;resolve(v);h&&h.close({force:true})};
    overlay({title,content:p,className:'cs-ask-dialog cs-confirm-dialog'+(danger?' cs-ask-danger':''),size:420,onClose:()=>finish(false),actions:[{label:cancelLabel,variant:'secondary',onClick:x=>finish(false,x)},{label:okLabel,variant:danger?'danger':'primary',onClick:x=>finish(true,x)}]});
    setTimeout(()=>{const b=document.querySelector('dialog.cs-confirm-dialog:last-of-type footer [data-variant=secondary]');b&&b.focus()},30)})}
  function confirmReason({title='确认操作',message='',object='',reasonLabel='原因',placeholder='写明为什么这样做，会记入操作记录',minLength=2,maxLength=200,required=true,okLabel='确认',danger=false}={}){return new Promise(resolve=>{let done=false;const f=document.createElement('form');f.className='cs-ask-form';const id='cs-reason-'+uid();
    f.innerHTML=(object?'<p class="cs-ask-object">'+escape(object)+'</p>':'')+(message?'<p class="cs-ask-message">'+escape(message)+'</p>':'')+'<label for="'+id+'">'+escape(reasonLabel)+(required?' <span class="cs-req" aria-hidden="true">*</span>':'')+'</label><textarea id="'+id+'" rows="3" maxlength="'+maxLength+'" placeholder="'+escape(placeholder)+'"></textarea><p class="cs-ask-hint"><span>'+(required?'必填，至少 '+minLength+' 个字':'可选')+'</span><span class="cs-ask-count">0 / '+maxLength+'</span></p>';
    const ta=f.querySelector('textarea'),count=f.querySelector('.cs-ask-count');ta.oninput=()=>{count.textContent=ta.value.length+' / '+maxLength};
    const finish=(v,h)=>{if(done)return;done=true;resolve(v);h&&h.close({force:true})};
    const ok=async h=>{const v=ta.value.trim();if(required&&v.length<minLength){ta.focus();throw Error('请填写'+reasonLabel+'（至少 '+minLength+' 个字）')}finish(v,h)};
    overlay({title,content:f,className:'cs-ask-dialog'+(danger?' cs-ask-danger':''),size:480,onClose:()=>finish(null),actions:[{label:'取消',variant:'secondary',onClick:x=>finish(null,x)},{label:okLabel,variant:danger?'danger':'primary',onClick:ok}]});
    f.onsubmit=e=>e.preventDefault();setTimeout(()=>ta.focus(),30)})}
  // ---- 第六批（R23）共享对账：Cretas（主来源）↔ 黑湖（对照）。只比对、只读，不改任何一边，不回写黑湖 ----
  // reconcile(o) 纯函数：o = { cretas:[], blacklake:[], key:r=>键 或 {cretas:r=>键, blacklake:r=>键}, fields:[{key,label,unit?,digits?,tolerance?,cretas:r=>值,blacklake:r=>值}], owner?:(行)=>'车间'|'仓库'|…, label?:r=>名称 }
  // 输出 { rows:[{key,state:'match'|'diff'|'cretasOnly'|'blacklakeOnly',label,owner,cretas,blacklake,diffs:[{key,label,unit,c,b,delta}],dupCretas,dupBlacklake}], counts }
  // 规则：同一键两边都有 → 逐字段比，|差| > tolerance（默认 0.005）即差额；任一边缺值（null/空）算差额并写「一边未记录」，不当 0；同一键在一边出现多条 → 标重复，按第一条比并在行上提示
  const badgeHTML=(label,tone)=>'<span class="cs-badge" data-tone="'+tone+'"><span class="cs-status-dot" aria-hidden="true"></span>'+escape(label)+'</span>';
  const RECON_STATES={match:{label:'一致',tone:'success'},diff:{label:'有差额',tone:'warning'},cretasOnly:{label:'只在 Cretas',tone:'info'},blacklakeOnly:{label:'只在黑湖',tone:'neutral'}};
  function reconcile(o){const kc=typeof o.key==='function'?o.key:o.key.cretas,kb=typeof o.key==='function'?o.key:o.key.blacklake,fields=o.fields||[];
    const group=(list,k)=>{const m=new Map();for(const r of list||[]){const key=k(r);if(key==null||key==='')continue;const id=String(key);if(!m.has(id))m.set(id,[]);m.get(id).push(r)}return m};
    const C=group(o.cretas,kc),B=group(o.blacklake,kb),keys=[...new Set([...C.keys(),...B.keys()])],rows=[];
    for(const key of keys){const cs=C.get(key)||[],bs=B.get(key)||[],c=cs[0]||null,b=bs[0]||null;let state,diffs=[];
      if(c&&b){for(const f of fields){const cv=f.cretas?f.cretas(c):c[f.key],bv=f.blacklake?f.blacklake(b):b[f.key],ck=valueKind(cv),bk=valueKind(bv);
          if(ck==='missing'&&bk==='missing')continue;
          if(ck==='missing'||bk==='missing'){diffs.push({key:f.key,label:f.label,unit:f.unit||'',c:cv,b:bv,delta:null,note:ck==='missing'?'Cretas 未记录':'黑湖未记录'});continue}
          /* C 反馈：文字字段（单位、规格等）按文字比较；type:'text' 显式声明，或两边都不是数字时自动按文字比；一边数字一边文字才算「内容不同」 */
          if(f.type==='text'||(ck==='invalid'&&bk==='invalid')){if(String(cv).trim()!==String(bv).trim())diffs.push({key:f.key,label:f.label,unit:f.unit||'',c:cv,b:bv,delta:null,note:'内容不同'});continue}
          if(ck==='invalid'||bk==='invalid'){diffs.push({key:f.key,label:f.label,unit:f.unit||'',c:cv,b:bv,delta:null,note:'内容不同（一边是数字一边是文字）'});continue}
          const cn=toNumber(cv),bn=toNumber(bv);if(Number.isFinite(cn)&&Number.isFinite(bn)){const d=Math.round((cn-bn)*1e6)/1e6;if(Math.abs(d)>(f.tolerance??0.005))diffs.push({key:f.key,label:f.label,unit:f.unit||'',c:cn,b:bn,delta:d})}
          else if(String(cv).trim()!==String(bv).trim())diffs.push({key:f.key,label:f.label,unit:f.unit||'',c:cv,b:bv,delta:null,note:'内容不同'})}
        state=diffs.length?'diff':'match'}else state=c?'cretasOnly':'blacklakeOnly';
      const row={key,state,cretas:c,blacklake:b,diffs,dupCretas:cs.length>1?cs.length:0,dupBlacklake:bs.length>1?bs.length:0};
      row.label=o.label?o.label(c||b,row):key;row.owner=o.owner?o.owner(row):(state==='blacklakeOnly'?'黑湖录入人':state==='cretasOnly'?'Cretas 录入人':'');rows.push(row)}
    const order={diff:0,cretasOnly:1,blacklakeOnly:2,match:3};rows.sort((x,y)=>order[x.state]-order[y.state]||String(x.key).localeCompare(String(y.key)));
    const counts={all:rows.length,match:0,diff:0,cretasOnly:0,blacklakeOnly:0};rows.forEach(r=>counts[r.state]++);return{rows,counts}}
  const fmtVal=(v,unit,digits=2,type)=>valueKind(v)==='missing'?'—':type==='text'?String(v).trim():valueKind(v)==='value'||valueKind(v)==='zero'?fmtNumber(v,{digits})+(unit?' '+unit:''):String(v);
  function reconcileCSV(result,o={}){const cell=v=>{let t=String(v??'');if(/^[=+@-]/.test(t)&&!/^-?\d/.test(t))t="'"+t;return '"'+t.replace(/"/g,'""')+'"'};const fields=o.fields||[];
    const head=['结果','匹配键','名称','找谁',...fields.flatMap(f=>[f.label+'（Cretas）',f.label+'（黑湖）',f.label+'（差）']),'说明'];
    const lines=result.rows.map(r=>[RECON_STATES[r.state].label,r.key,r.label,r.owner||'',...fields.flatMap(f=>{const d=r.diffs.find(x=>x.key===f.key),cv=r.cretas?(f.cretas?f.cretas(r.cretas):r.cretas[f.key]):null,bv=r.blacklake?(f.blacklake?f.blacklake(r.blacklake):r.blacklake[f.key]):null;return[fmtVal(cv,f.unit,f.digits,f.type),fmtVal(bv,f.unit,f.digits,f.type),d&&d.delta!=null?fmtNumber(d.delta,{digits:f.digits??2}):d?d.note:'']}),[r.dupCretas?'Cretas 同键 '+r.dupCretas+' 条':'',r.dupBlacklake?'黑湖同键 '+r.dupBlacklake+' 条':''].filter(Boolean).join('；')].map(cell).join(','));
    const meta=[cell('对账：'+(o.title||'Cretas ↔ 黑湖')),cell('黑湖数据时点：'+(o.asOf||'未知')),cell('导出时间：'+new Date().toLocaleString('zh-CN',{hour12:false,timeZone:'Asia/Shanghai'}))].join(',');
    return '\ufeff'+meta+'\n'+head.map(cell).join(',')+'\n'+lines.join('\n')}
  // reconcileView(parent,o)：o = reconcile 的参数 + { title, asOf（黑湖最近一次采集时点，ISO 或文字）, cretasAt?, keyLabel?, fileName? }；返回 { element, result, refresh(newO) }
  function reconcileView(parent,o){const box=document.createElement('section');box.className='cs-recon';let cur=o,result=null,tab='all';
    const asOfText=v=>{if(!v)return '未知（没有读到黑湖采集时点）';const t=Date.parse(v);return Number.isFinite(t)?new Date(t).toLocaleString('zh-CN',{hour12:false,timeZone:'Asia/Shanghai'})+'（北京时间）':String(v)};
    const draw=()=>{result=reconcile(cur);const fields=cur.fields||[],rows=result.rows.filter(r=>tab==='all'||r.state===tab);
      box.innerHTML='<header class="cs-recon-head"><strong>'+escape(cur.title||'对账：Cretas ↔ 黑湖')+'</strong><span class="cs-recon-asof">黑湖数据时点：'+escape(asOfText(cur.asOf))+' · 每天 08:00 采集一次，不实时'+(cur.cretasAt?' · Cretas 截至 '+escape(asOfText(cur.cretasAt)):'')+'</span><button type="button" class="cs-button" data-variant="ghost" data-export>导出 CSV</button></header>'+
        '<div class="cs-recon-tabs" role="tablist">'+['all','diff','cretasOnly','blacklakeOnly','match'].map(k=>'<button type="button" role="tab" data-t="'+k+'" aria-selected="'+(k===tab)+'">'+(k==='all'?'全部':RECON_STATES[k].label)+' <b>'+result.counts[k]+'</b></button>').join('')+'</div>'+
        (rows.length?'<div class="cs-recon-scroll"><table class="cs-recon-table"><thead><tr><th>结果</th><th>'+escape(cur.keyLabel||'匹配键')+'</th>'+fields.map(f=>'<th class="num">'+escape(f.label)+'<small>Cretas / 黑湖 / 差</small></th>').join('')+'<th>找谁</th></tr></thead><tbody>'+rows.map(r=>'<tr data-s="'+r.state+'"><td>'+badgeHTML(RECON_STATES[r.state].label,RECON_STATES[r.state].tone)+'</td><td>'+(r.label&&r.label!==r.key?'<span title="内部编号：'+escape(r.key)+'">'+escape(r.label)+'</span>':escape(r.key))+(r.dupCretas||r.dupBlacklake?'<small class="cs-recon-dup">'+[r.dupCretas?'Cretas 同键 '+r.dupCretas+' 条':'',r.dupBlacklake?'黑湖同键 '+r.dupBlacklake+' 条':''].filter(Boolean).join('；')+'，按第一条比</small>':'')+'</td>'+fields.map(f=>{const d=r.diffs.find(x=>x.key===f.key),cv=r.cretas?(f.cretas?f.cretas(r.cretas):r.cretas[f.key]):null,bv=r.blacklake?(f.blacklake?f.blacklake(r.blacklake):r.blacklake[f.key]):null;return '<td class="num'+(d?' cs-recon-bad':'')+'">'+escape(r.cretas?fmtVal(cv,f.unit,f.digits,f.type):'—')+' / '+escape(r.blacklake?fmtVal(bv,f.unit,f.digits,f.type):'—')+(d?'<small>'+(d.delta!=null?'差 '+(d.delta>0?'+':'')+escape(fmtNumber(d.delta,{digits:f.digits??2}))+(f.unit?' '+escape(f.unit):''):escape(d.note))+'</small>':'')+'</td>'}).join('')+'<td>'+escape(r.owner||'—')+'</td></tr>').join('')+'</tbody></table></div>':'<p class="cs-recon-empty">'+(tab==='all'?'两边都没有记录。':'这一类没有记录。')+'</p>')+
        '<p class="cs-recon-note">Cretas 是主来源，黑湖值只作对照；差异由人核对后决定是否回黑湖修改，本页不改任何一边。</p>'};
    box.addEventListener('click',e=>{const t=e.target.closest('[data-t]');if(t){tab=t.dataset.t;draw();return}
      if(e.target.closest('[data-export]')){const csv=reconcileCSV(result,cur),url=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'})),a=document.createElement('a');a.href=url;a.download=(cur.fileName||'对账')+'-'+new Date(Date.now()+8*3600e3).toISOString().slice(0,10)+'.csv';document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);toast({message:'已导出 '+result.rows.length+' 行'})}});
    draw();parent?.append(box);return{element:box,get result(){return result},refresh(n){cur={...cur,...n};draw()}}}
  function viewTabs(parent,o){const box=document.createElement('div');box.className='cs-view-tabs';let active=o.active||(o.builtins[0]?.id)||'';
    const render=()=>{const user=readViews(o.page),all=[...o.builtins.map(v=>({...v,builtin:true})),...user];
      box.innerHTML='<div class="cs-view-row" role="tablist" aria-label="已存视图">'+all.map(v=>'<button type="button" role="tab" data-v="'+escape(v.id)+'" aria-selected="'+(v.id===active)+'">'+escape(v.name)+(v.builtin?'':'<span class="cs-view-del" data-del="'+escape(v.id)+'" title="删除这个视图" aria-label="删除视图 '+escape(v.name)+'">×</span>')+'</button>').join('')+'<button type="button" class="cs-view-add" data-add title="'+(user.length<5?'把当前筛选另存为视图（按账号保存，最多 5 个）':'已存满 5 个视图，先删掉一个再另存')+'">＋ 另存视图</button>'+'</div>'+(o.counts&&o.counts.length?'<div class="cs-count-row" role="group" aria-label="按状态">'+o.counts.map(c=>'<button type="button" data-c="'+escape(c.key)+'" aria-pressed="'+(c.key===(o.countKey??''))+'">'+escape(c.label)+' <b>'+(c.count==null?'—':c.count)+'</b></button>').join('')+'</div>':'')};
    box.addEventListener('click',e=>{const del=e.target.closest('[data-del]');if(del){e.stopPropagation();const id=del.dataset.del;writeViews(o.page,readViews(o.page).filter(v=>v.id!==id));if(active===id){/* E 反馈：删掉当前所在视图后，页签回到第一个内置视图，筛选也一起回去，避免页签和列表对不上 */const b=o.builtins[0];active=b?.id||'';if(b)o.apply(JSON.parse(JSON.stringify(b.state||{})))}render();toast({message:'已删除视图'});return}
      const t=e.target.closest('[data-v]');if(t){const v=[...o.builtins,...readViews(o.page)].find(x=>x.id===t.dataset.v);if(v){active=v.id;o.apply(JSON.parse(JSON.stringify(v.state||{})));render()}return}
      if(e.target.closest('[data-add]')){if(readViews(o.page).length>=5){toast({message:'已存满 5 个视图：先点视图名旁的 × 删掉一个，再另存。',tone:'warning'});return}const state=o.getState();askText({title:'另存为视图',label:'视图名称',placeholder:'如「我负责的」「今天到期」',hint:'按账号保存；最多 12 个字，最多 5 个视图',maxLength:12}).then(name=>{if(!name)return;const list=readViews(o.page);if(list.length>=5){toast({message:'已存满 5 个视图，没有保存',tone:'warning'});return}if(list.some(v=>v.name===name)){toast({message:'已有同名视图「'+name+'」，换个名字再存',tone:'warning'});return}const v={id:'u'+Date.now().toString(36),name,state};list.push(v);if(writeViews(o.page,list)){active=v.id;render();toast({message:'已保存视图：'+name})}});return}
      const c=e.target.closest('[data-c]');if(c){o.countKey=c.dataset.c;o.onCount?.(c.dataset.c);render()}});
    render();parent?.append(box);return{element:box,refresh(counts,countKey){if(counts)o.counts=counts;if(countKey!==undefined)o.countKey=countKey;render()},get active(){return active}}}

  window.CretasUI=Object.freeze({version:'1.8.0',reconcile,reconcileView,reconcileCSV,RECON_STATES,askText,confirmReason,confirmAction,parseNos,matchNos,batchNoSearch,relativeRange,relativeDate,viewTabs,readViews,formula,openFormula,diffFields,changeLog,changeLogHTML,changeLogDrawer,blacklakeLog,loadBlacklakeLog,blacklakeLogDrawer,sourceTag,sourceTagHTML,gapNotice,supplementForm,supplementImport,SOURCES,escape,status,toast,dialog:o=>overlay(o),drawer:o=>overlay(o,true),renderAnswer,tabs,help,menu,icons,motion,valueKind,fmtNumber,fmtMoney,fmtQty,fmtPct,safeRatio,ai});
})();
