const {JSDOM}=require(require.resolve('jsdom', { paths: [__dirname + '/../../web-admin'] }));
const html=require('fs').readFileSync(__dirname+'/demo-full.html','utf8');
const errs=[];const d=new JSDOM('<!doctype html><html><body>'+html+'</body></html>',{runScripts:'dangerously',url:'https://x.test/#proto-map',pretendToBeVisual:true,beforeParse(w){w.addEventListener('error',e=>errs.push(e.message))}});
const w=d.window,q=s=>w.document.querySelector(s),qa=s=>w.document.querySelectorAll(s),o=[];
function hash(h){w.location.hash=h;w.dispatchEvent(new w.HashChangeEvent('hashchange'))}
hash('proto-map');o.push('h1='+q('h1').textContent+' rows='+qa('[data-tbrow]').length+' kpis='+qa('.kpi').length+' groupOpen='+!!q('.sb[aria-current]'));
const inp=q('[data-tbq]');inp.value='称重';inp.dispatchEvent(new w.Event('input',{bubbles:true}));o.push('search rows='+qa('[data-tbrow]').length);
q('[data-tbrow]').click();const go=q('.dlg [data-go]');o.push('dialog go='+(go&&go.dataset.go));go.click();hash(w.location.hash.slice(1));
o.push('after go h1='+q('h1').textContent+' dialog='+!!q('.dlg'));
hash('proto-lib');o.push('swatches='+qa('.sw > div').length);
hash('proto-open');o.push('copybtn='+qa('[data-copy]').length);
o.push('errs='+JSON.stringify(errs));console.log(o.join('\n'));
