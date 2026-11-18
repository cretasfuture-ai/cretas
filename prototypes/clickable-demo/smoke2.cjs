const {JSDOM}=require(require.resolve('jsdom', { paths: [__dirname + '/../../web-admin'] }));
const html=require('fs').readFileSync(__dirname+'/demo-full.html','utf8');
const errs=[];const d=new JSDOM('<!doctype html><html><body>'+html+'</body></html>',{runScripts:'dangerously',url:'https://x.test/#today',pretendToBeVisual:true,beforeParse(w){w.addEventListener('error',e=>errs.push(e.message))}});
const w=d.window,q=s=>w.document.querySelector(s),qa=s=>w.document.querySelectorAll(s),o=[];
function hash(h){w.location.hash=h;w.dispatchEvent(new w.HashChangeEvent('hashchange'))}
o.push('nav groups='+qa('.grp').length);
q('[data-grp="仓储管理"]').click();o.push('open group items='+qa('.sb').length);
q('.sb[data-go="warehouse-inventory-query"]').click();hash('warehouse-inventory-query');o.push('h1='+q('h1').textContent+' rows='+qa('[data-tbrow]').length);
const inp=q('[data-tbq]');inp.value='牛';inp.dispatchEvent(new w.Event('input',{bubbles:true}));o.push('search rows='+qa('[data-tbrow]').length);
const f=q('[data-tbf]');if(f){f.click();o.push('filter click ok rows='+qa('[data-tbrow]').length)}
hash('crew-settings');const sw=q('[data-sw]');if(sw){sw.checked=!sw.checked;sw.dispatchEvent(new w.Event('change',{bubbles:true}));o.push('toggle ok')}
hash('lsm-reconcile');const t=q('[data-tab]');o.push('tabs='+qa('[data-tab]').length);qa('[data-tab]')[1].click();o.push('tab2 ok');
hash('production-kanban');o.push('board cols='+qa('.board .col').length);
hash('smart-bi-dashboard');o.push('svg='+qa('svg').length);
hash('todo');hash('today');o.push('today ok '+q('h1').textContent);
o.push('errs='+JSON.stringify(errs));console.log(o.join('\n'));
