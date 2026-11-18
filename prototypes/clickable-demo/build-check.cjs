// 用法：node build-check.cjs build            拼出 demo-full.html（基础页 + parts/menu.js + parts/data-*.js）
//       node build-check.cjs check [前缀...]   用 jsdom 逐页渲染，报告异常 / undefined / NaN / 表格列缺字段 / 未登记页面
const fs = require('fs'), path = require('path');
const dir = __dirname + '/';
const { JSDOM } = require(require.resolve('jsdom', { paths: [__dirname + '/../../web-admin'] }));
function build() {
  let base = fs.readFileSync(dir + 'demo-base.html', 'utf8');
  const menu = fs.readFileSync(dir + 'parts/menu.js', 'utf8');
  const data = fs.readdirSync(dir + 'parts').filter(f => /^data-.*\.js$/.test(f)).sort().map(f => '/* ' + f + ' */\n' + fs.readFileSync(dir + 'parts/' + f, 'utf8')).join('\n');
  base = base.split('/*__MENU__*/').join(menu).split('/*__DATA__*/').join(data);
  fs.writeFileSync(dir + 'demo-full.html', base);
  return base;
}
const mode = process.argv[2];
const html = build();
if (mode === 'build') { console.log('built', html.length, 'bytes'); process.exit(0); }
const only = process.argv.slice(3).map(p => p.split('\\').join('/')).map(p => { const i = p.indexOf('Program Files/Git'); return i >= 0 ? p.slice(i + 'Program Files/Git'.length) : p; }).map(p => p.startsWith('/') ? p : '/' + p);
const errors = [];
const dom = new JSDOM('<!doctype html><html><body>' + html + '</body></html>', { runScripts: 'dangerously', url: 'https://x.test/#today', pretendToBeVisual: true,
  beforeParse(w) { w.addEventListener('error', e => errors.push('JS 错误: ' + e.message)); } });
const w = dom.window;
if (errors.length) { console.log(errors.join('\n')); process.exit(1); }
const MENU = w.eval('MENU'), REG = w.eval('REG'), pid = w.eval('pid'), BLOCKS = w.eval('BLOCKS');
let bad = 0, ok = 0, missing = [];
const seen = new Set();
MENU.forEach(g => g[1].forEach(([title, route]) => {
  if (seen.has(route)) return; seen.add(route);
  if (only.length && !only.some(p => route.startsWith(p) || route === p)) return;
  const id = pid(route);
  if (!REG[route] && !['today', 'todo', 'issues', 'cost', 'weigh'].includes(id)) { missing.push(route + ' ' + title); return; }
  w.location.hash = id; w.dispatchEvent(new w.HashChangeEvent('hashchange'));
  const txt = w.document.querySelector('#page').textContent;
  const probs = [];
  if (/undefined|NaN|\[object/.test(txt)) probs.push('页面文字里出现 undefined / NaN / [object');
  if (!w.document.querySelector('#page h1')) probs.push('没有标题');
  if (REG[route]) {
    const sp = REG[route];
    if (!sp.title || !sp.blocks || !sp.blocks.length) probs.push('缺 title 或 blocks');
    (function walk(blocks, k) { (blocks || []).forEach((b, i) => {
      const key = k + '.' + i;
      if (b.t === 'tabs') b.tabs.forEach((t, j) => walk(t.blocks, key + '.' + j));
      if (b.t === 'table') {
        if (!b.cols || !b.rows || !b.rows.length) probs.push('表格 ' + key + ' 没有 cols/rows');
        else b.cols.forEach(c => { const miss = b.rows.filter(r => r[c.k] === undefined).length; if (miss > b.rows.length / 2) probs.push('表格 ' + key + ' 列 ' + c.k + ' 在 ' + miss + ' 行里没有字段'); });
      }
    }); })(sp.blocks, id);
  }
  // 点第一个表格行，应能弹出详情
  const row = w.document.querySelector('[data-tbrow]');
  if (row) { row.click(); if (!w.document.querySelector('.dlg')) probs.push('点表格行没弹出详情'); w.document.querySelector('#layer').innerHTML = ''; }
  if (probs.length) { bad++; console.log('✗ ' + route + ' ' + title + '\n   ' + probs.join('\n   ')); } else ok++;
}));
if (errors.length) console.log(errors.join('\n'));
console.log('通过 ' + ok + ' 页，有问题 ' + bad + ' 页' + (missing.length ? '，未登记 ' + missing.length + ' 页：\n  ' + missing.join('\n  ') : ''));
process.exit(bad || errors.length ? 1 : 0);
