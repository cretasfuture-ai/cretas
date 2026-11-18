const { JSDOM } = require(require.resolve('jsdom', { paths: [__dirname + '/../../web-admin'] }));
const fs = require('fs');
const dir = __dirname + '/';
const html = '<!doctype html><html><body>' + fs.readFileSync(dir + 'demo-full.html', 'utf8') + '</body></html>';
const errors = [];
const dom = new JSDOM(html, { runScripts: 'dangerously', url: 'https://x.test/#today', pretendToBeVisual: true,
  beforeParse(w) { w.addEventListener('error', e => errors.push(String(e.message))); } });
const w = dom.window, d = w.document, out = [];
const q = s => d.querySelector(s), qa = s => d.querySelectorAll(s);
function c(s) { const e = q(s); if (!e) { out.push('MISSING ' + s); return false; } e.click(); return true; }
function hash(h) { w.location.hash = h; w.dispatchEvent(new w.HashChangeEvent('hashchange')); }
out.push('today h1=' + q('h1').textContent + ' nav=' + qa('#nav button').length + ' kpis=' + qa('.kpi').length);
out.push('nav text=' + q('#nav').textContent.replace(/\s+/g, ' ').slice(0, 70));
c('[data-go=todo]'); hash('todo');
out.push('todo h1=' + q('h1').textContent + ' toWeigh=' + qa('[data-weigh]').length);
c('[data-weigh=t3]'); hash('weigh');
out.push('weigh h1=' + q('h1').textContent + ' hash=' + w.location.hash);
q('#f-in').value = '1100'; q('#f-gross').value = '912'; q('#f-tare').value = '2';
q('#f-in').dispatchEvent(new w.Event('input', { bubbles: true }));
out.push('preview=' + q('#preview').textContent.replace(/\s+/g, ' ') + ' saveDisabled=' + q('#save').disabled);
c('[data-save]');
out.push('t3 records=' + qa('[data-revoke]').length);
// 校验：出料多于投入
q('#f-in').value = '100'; q('#f-gross').value = '150'; q('#f-in').dispatchEvent(new w.Event('input', { bubbles: true }));
out.push('bad preview=' + q('#preview').textContent.replace(/\s+/g, ' ').slice(-60) + ' saveDisabled=' + q('#save').disabled);
hash('todo');
out.push('todo after save toWeigh=' + qa('[data-weigh]').length);
hash('issues');
out.push('issues=' + qa('.issue').length);
c('[data-closing=i1]'); c('[data-conc="误报"]'); q('#note').value = '测试'; c('[data-close-ok=i1]');
out.push('issues after close=' + qa('.issue').length);
c('[data-totodo=i2]');
hash('todo'); out.push('confirm items=' + qa('[data-confirm]').length);
hash('cost');
out.push('cost rows=' + qa('tr[data-cost]').length + ' tiles=' + qa('.kpi strong').length);
c('[data-cost=GD-1115-05]'); out.push('detail=' + q('.notice') .textContent.slice(0, 40));
c('[data-cf=no]'); out.push('no rows=' + qa('tr[data-cost]').length);
hash('today'); c('[data-order=GD-1116-01]'); out.push('dialog=' + !!q('.dlg'));
out.push('errors=' + JSON.stringify(errors));
console.log(out.join('\n'));
