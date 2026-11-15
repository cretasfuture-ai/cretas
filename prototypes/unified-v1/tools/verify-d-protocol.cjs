'use strict';
// D 线共享件自测：fmtMoney/fmtQty 与 AI 协议（数字绑定、矛盾检查、缓存键、排队文案）。只读，不写任何业务文件。
const fs = require('fs'), path = require('path'), vm = require('vm'), assert = require('assert');
const src = fs.readFileSync(path.resolve(__dirname, '../../ydwm-v1/public/design-system.js'), 'utf8');
const store = {};
const win = { innerWidth: 1440, innerHeight: 900 };
const ctx = { window: win, document: { querySelector: () => null, addEventListener() {}, body: { dataset: {} } }, crypto: { randomUUID: () => 'x' }, matchMedia: () => ({ matches: false }), getComputedStyle: () => ({ getPropertyValue: () => '' }), addEventListener() {}, localStorage: { getItem: k => store[k] ?? null, setItem: (k, v) => { store[k] = v; } }, innerWidth: 1440, innerHeight: 900, performance: { now: () => 0 }, console };
ctx.window.addEventListener = () => {};
vm.createContext(ctx); vm.runInContext(src, ctx);
const UI = ctx.window.CretasUI; let n = 0; const ok = (name, fn) => { fn(); n++; console.log('PASS', name); };

ok('fmtMoney 两位小数+币种；缺失显示—；0 保留', () => {
  assert.equal(UI.fmtMoney(1234.5), '1,234.50 元');
  assert.equal(UI.fmtMoney(0), '0.00 元');
  assert.equal(UI.fmtMoney(null), '—');
  assert.equal(UI.fmtMoney(''), '—');
  assert.equal(UI.fmtMoney('abc'), '格式无效');
  assert.equal(UI.fmtMoney(3, { currency: 'USD' }), '3.00 USD');
});
ok('fmtQty 单位与缺单位', () => {
  assert.equal(UI.fmtQty(100, 'kg'), '100.00 kg');
  assert.equal(UI.fmtQty('18', 'pcs'), '18.00 pcs');
  assert.equal(UI.fmtQty(5, ''), '5.00（单位未填）');
  assert.equal(UI.fmtQty(undefined, 'kg'), '—');
});
ok('safeRatio 零分母不除', () => {
  assert.deepEqual({ ...UI.safeRatio(10, 0) }, { value: null, reason: '分母为 0' });
  assert.equal(UI.safeRatio(null, 3).reason, '缺少数据');
  assert.equal(UI.safeRatio(10, 4).value, 2.5);
});
const facts = [{ label: '实耗', value: 0, unit: 'kg' }, { label: '已发', value: 20, unit: 'kg' }, { label: '单价', value: null }, { label: '销售额', value: 1200, unit: '元', delta: -300 }, { label: '取消单', value: 30, unit: 'kg', status: 'CANCELLED' }];
ok('数字绑定：事实内数字通过，编造数字被拒', () => {
  const r = UI.ai.checkParagraphs('已发 20 kg，实耗 0 kg（已记录）。\n\n预计还要 35 kg。', facts);
  assert.equal(r.paragraphs[0].ok, true);
  assert.equal(r.paragraphs[1].ok, false);
});
ok('单位不符被拒', () => { assert.equal(UI.ai.checkParagraphs('已发 20 g', facts).paragraphs[0].ok, false); });
ok('日期和单号里的数字不参与绑定', () => { assert.equal(UI.ai.checkParagraphs('计划 PLAN-0012 于 2026-10-30 已发 20 kg', facts).paragraphs[0].ok, true); });
ok('矛盾：0 说成缺失', () => { assert.equal(UI.ai.checkParagraphs('实耗 缺失，需要补录', facts).paragraphs[0].ok, false); });
ok('矛盾：缺失说成 0', () => { assert.equal(UI.ai.checkParagraphs('单价为 0', facts).paragraphs[0].ok, false); });
ok('矛盾：方向写反', () => { assert.equal(UI.ai.checkParagraphs('销售额 本月上升', facts).paragraphs[0].ok, false); });
ok('矛盾：取消单建议补发', () => { assert.equal(UI.ai.checkParagraphs('取消单 建议补发', facts).paragraphs[0].ok, false); });
ok('两段互相矛盾都标出', () => { const r = UI.ai.checkParagraphs('销售额 有所下降\n\n销售额 明显增长', facts); assert.equal(r.paragraphs.every(p => p.conflict), true); });
ok('renderChecked 把未通过段落折叠', () => { const h = UI.ai.renderChecked(UI.ai.checkParagraphs('已发 20 kg\n\n多出 99 kg', facts)); assert.match(h, /未核实内容 1 段/); });
ok('缓存键稳定且问题空白不敏感', () => {
  const a = UI.ai.cacheKey({ workspace: 'ydwm', objectType: 'plan', objectId: 'P1', sourceHash: 'h', ruleVersion: 'r1', question: '解读  这条计划' });
  const b = UI.ai.cacheKey({ workspace: 'ydwm', objectType: 'plan', objectId: 'P1', sourceHash: 'h', ruleVersion: 'r1', question: '解读 这条计划' });
  assert.equal(a, b);
  assert.notEqual(a, UI.ai.cacheKey({ workspace: 'lsm', objectType: 'plan', objectId: 'P1', sourceHash: 'h', ruleVersion: 'r1', question: '解读 这条计划' }));
});
ok('排队文案：10 分钟后提示仍在排队，不判失败', () => {
  assert.equal(UI.ai.queueText({ position: 2, waitedMs: 1000 }), '排队中，前面还有 2 个');
  assert.match(UI.ai.queueText({ position: 1, waitedMs: 11 * 60000 }), /仍在排队（已等 11 分钟）.*不会判为失败/);
});
ok('位置按工作区保存并夹回视口', () => {
  UI.ai.savePosition('lsm', { right: 99999, bottom: -5, width: 5000 });
  const p = UI.ai.loadPosition('lsm');
  assert.ok(p.width <= 864 && p.width >= 360); assert.ok(p.right <= 1440 - p.width); assert.equal(p.bottom, 0);
  assert.equal(UI.ai.loadPosition('ydwm').width, 460);
});
ok('三种状态文案分开', () => {
  const s = UI.ai.STATES; assert.notEqual(s.answered.label, s.draft.label); assert.notEqual(s.draft.label, s.done.label);
  assert.equal(s.done.tone, 'success'); assert.equal(s.answered.tone, 'info'); assert.equal(s.draft.tone, 'warning');
});
ok('B6 diffFields 字段级前后值，只比较列出字段', () => {
  const c = UI.diffFields({ qty: 10, reason: '', note: 'a' }, { qty: 12, reason: '盘亏', note: 'a' }, { qty: '数量', reason: '原因' });
  assert.equal(c.length, 2); assert.equal(c[0].label, '数量'); assert.equal(c[0].before, '10'); assert.equal(c[0].after, '12'); assert.equal(c[1].before, '（空）');
});
ok('B6 changeLogHTML 新的在前、转义、空态', () => {
  const h = UI.changeLogHTML([{ at: '2026-11-10T01:00:00Z', by: '甲', action: '新建' }, { at: '2026-11-11T01:00:00Z', by: '<b>乙</b>', action: '修改', changes: [{ field: 'qty', label: '数量', before: '10', after: '12' }] }]);
  assert.ok(h.indexOf('修改') < h.indexOf('新建')); assert.match(h, /&lt;b&gt;乙/); assert.match(UI.changeLogHTML([]), /还没有操作记录/);
});
ok('BL21 parseNos 换行逗号空格分隔、去重保序', () => {
  assert.deepEqual([...UI.parseNos('PO-1' + String.fromCharCode(10) + 'PO-2, PO-1  PO-3；PO-4、PO-2')], ['PO-1', 'PO-2', 'PO-3', 'PO-4']);
});
ok('BL21 matchNos 精确匹配并列出未找到', () => {
  const r = UI.matchNos([{ no: 'PO-1' }, { no: 'PO-10' }, { no: 'PO-3' }], ['PO-1', 'PO-3', 'PO-9'], x => x.no);
  assert.equal(r.found.length, 2); assert.deepEqual([...r.missing], ['PO-9']);
});
ok('BL23 relativeRange 本周从周一、本月从 1 日、近 7 天含今天', () => {
  const t = new Date(2026, 10, 11); // 2026-11-11 周三
  assert.deepEqual({ ...UI.relativeRange('today', t) }, { key: 'today', label: '今天', from: '2026-11-11', to: '2026-11-11' });
  assert.equal(UI.relativeRange('week', t).from, '2026-11-09'); assert.equal(UI.relativeRange('week', t).to, '2026-11-15'); // R18：本周到周日
  assert.equal(UI.relativeRange('month', t).from, '2026-11-01'); assert.equal(UI.relativeRange('month', t).to, '2026-11-30'); // R18：本月到月末
  assert.equal(UI.relativeRange('last7', t).to, '2026-11-11'); assert.equal(UI.relativeRange('last30', t).to, '2026-11-11'); // 近 N 天仍到今天
  assert.equal(UI.relativeRange('month', new Date(2028, 1, 10)).to, '2028-02-29'); assert.equal(UI.relativeRange('week', new Date(2026, 10, 15)).from, '2026-11-09'); // 闰年月末；周日仍属本周
  assert.equal(UI.relativeRange('last7', t).from, '2026-11-05');
  assert.equal(UI.relativeRange('last30', t).from, '2026-10-13');
  assert.equal(UI.relativeRange('bogus', t), null);
});
ok('第六批 reconcile 四类、容差、缺值不当 0、同键重复', () => {
  const c = [{ t: 'T1', good: 10.004 }, { t: 'T2', good: 8 }, { t: 'T3', good: 5 }, { t: 'T5', good: null }, { t: 'T6', good: 1 }, { t: 'T6', good: 2 }];
  const b = [{ id: 'T1', qty: 10 }, { id: 'T2', qty: 7.5 }, { id: 'T4', qty: 3 }, { id: 'T5', qty: 4 }, { id: 'T6', qty: 1 }];
  const r = UI.reconcile({ cretas: c, blacklake: b, key: { cretas: x => x.t, blacklake: x => x.id }, fields: [{ key: 'good', label: '良品', unit: 'kg', cretas: x => x.good, blacklake: x => x.qty }], owner: row => row.state === 'blacklakeOnly' ? '车间（补称重）' : '车间' });
  const st = Object.fromEntries(r.rows.map(x => [x.key, x.state]));
  assert.equal(st.T1, 'match'); // 差 0.004 在默认容差 0.005 内
  assert.equal(st.T2, 'diff'); assert.equal(r.rows.find(x => x.key === 'T2').diffs[0].delta, 0.5);
  assert.equal(st.T3, 'cretasOnly'); assert.equal(st.T4, 'blacklakeOnly');
  assert.equal(st.T5, 'diff'); assert.equal(r.rows.find(x => x.key === 'T5').diffs[0].note, 'Cretas 未记录'); // 缺值不当 0
  assert.equal(r.rows.find(x => x.key === 'T6').dupCretas, 2); assert.equal(st.T6, 'match'); // 按第一条比，并标重复
  assert.deepEqual({ ...r.counts }, { all: 6, match: 2, diff: 2, cretasOnly: 1, blacklakeOnly: 1 });
  assert.equal(r.rows[0].state, 'diff'); // 差额排最前
  const csv = UI.reconcileCSV(r, { fields: [{ key: 'good', label: '良品', unit: 'kg', cretas: x => x.good, blacklake: x => x.qty }], title: '测试', asOf: '2026-11-12 08:00' });
  assert.ok(csv.startsWith('﻿')); assert.ok(/黑湖数据时点：2026-11-12 08:00/.test(csv)); assert.ok(/"有差额","T2"/.test(csv));
});
ok('第六批 reconcile 文字字段（C 反馈：单位相同不应报格式无效）', () => {
  const r = UI.reconcile({ cretas: [{ k: 'A', u: 'kg', q: 5 }, { k: 'B', u: 'kg', q: 1 }, { k: 'C', u: '001', q: 2 }, { k: 'D', u: 'kg', q: 3 }], blacklake: [{ k: 'A', u: 'kg', q: 5 }, { k: 'B', u: '箱', q: 1 }, { k: 'C', u: '1', q: 2 }, { k: 'D', u: 12, q: 3 }], key: x => x.k,
    fields: [{ key: 'u', label: '单位' }, { key: 'q', label: '数量' }] });
  const st = Object.fromEntries(r.rows.map(x => [x.key, x]));
  assert.equal(st.A.state, 'match'); // 两边都是 kg：一致，不再报格式无效
  assert.equal(st.B.diffs[0].note, '内容不同'); // kg ↔ 箱
  assert.equal(st.D.diffs[0].note, '内容不同（一边是数字一边是文字）');
  const t = UI.reconcile({ cretas: [{ k: 'C', u: '001' }], blacklake: [{ k: 'C', u: '1' }], key: x => x.k, fields: [{ key: 'u', label: '编码', type: 'text' }] });
  assert.equal(t.rows[0].state, 'diff'); // 显式 text：'001' 与 '1' 不同，不按数值相等
  const csv = UI.reconcileCSV(t, { fields: [{ key: 'u', label: '编码', type: 'text' }] });
  assert.ok(/"001","1","内容不同"/.test(csv)); // 显示原文，不格式化成 1.00
});
console.log('ALL', n, 'PASS');
