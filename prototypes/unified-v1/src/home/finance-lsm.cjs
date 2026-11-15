'use strict';
// D 线第三批：六膳门财务口径（应收、已收、待收、待开票、逾期）与黑湖操作日志的只读接口。
// 只读 liushanmen-v1/private/extras-current.json（organizer 的 read_extras.py 导出，已做哈希与工厂绑定校验），按 revision 缓存；不写任何文件。
// 口径全部由代码计算：缺失不当 0，负数待收（多收/冲减）单列，不跨币种合计（黑湖未给币种，标「币种待确认」）。
const fs = require('fs'), path = require('path');
const FILE = path.resolve(__dirname, '../../../liushanmen-v1/private/extras-current.json');
let cache = { mtime: 0, data: null };
function load() {
  const st = fs.statSync(FILE);
  if (cache.data && cache.mtime === st.mtimeMs) return cache.data;
  const d = JSON.parse(fs.readFileSync(FILE, 'utf8'));
  cache = { mtime: st.mtimeMs, data: d };
  return d;
}
const num = v => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v))) ? null : Number(v);
const round2 = n => Math.round(n * 100) / 100;
// items：逐张单据（或账本行）的金额，口径弹层逐项相加核对；超过 40 条只给合计
function sum(rows, f, name) { let s = 0, n = 0, missing = 0; const items = []; for (const r of rows) { const v = num(f(r)); if (v === null) missing++; else { s += v; n++; if (name) items.push({ label: name(r), value: v }); } } return { value: n ? round2(s) : null, count: n, missing, items: items.length <= 40 ? items : null }; }

function finance() {
  const d = load(), R = d.resources || {};
  const rec = Array.isArray(R.receivable) ? R.receivable : [], rcp = Array.isArray(R.receipt) ? R.receipt : [], cl = Array.isArray(R.customer_ledger) ? R.customer_ledger : [], inv = Array.isArray(R.invoicing) ? R.invoicing : [];
  const pendingPos = rec.filter(r => (num(r.pendingReceiveAmount) || 0) > 0), pendingNeg = rec.filter(r => (num(r.pendingReceiveAmount) || 0) < 0);
  const overdue = pendingPos.filter(r => Number(r.overdueStatus) === 1);
  const metrics = {
    receivable: { label: '应收（应收单合计）', ...sum(rec, r => r.realReceivableAmount, r => r.orderNo + ' · ' + (r.customerName || '客户未返回')), docs: rec.length, source: '黑湖应收单的「实际应收金额」' },
    received: { label: '已收（收款单合计）', ...sum(rcp, r => r.realReceiveAmount, r => r.code + ' · ' + (r.customerName || '客户未返回')), docs: rcp.length, source: '黑湖收款单的「实收金额」' },
    pending: { label: '待收', ...sum(pendingPos, r => r.pendingReceiveAmount, r => r.orderNo + ' · ' + (r.customerName || '客户未返回')), docs: pendingPos.length, source: '黑湖应收单中「待收金额」大于 0 的单据' },
    overpaid: { label: '多收或冲减（待收为负）', ...sum(pendingNeg, r => r.pendingReceiveAmount, r => r.orderNo + ' · ' + (r.customerName || '客户未返回')), docs: pendingNeg.length, source: '黑湖应收单中「待收金额」小于 0 的单据' },
    overdue: { label: '逾期未收', ...sum(overdue, r => r.pendingReceiveAmount, r => r.orderNo + ' · 逾期 ' + (num(r.overdueDays) ?? '—') + ' 天'), docs: overdue.length, maxDays: overdue.reduce((m, r) => Math.max(m, num(r.overdueDays) || 0), 0) || null, source: '黑湖应收单中已标逾期且「待收金额」大于 0 的单据' },
    toInvoice: { label: '待开票（客户账本）', ...sum(cl, r => r.pendingInvoiceAmount, r => r.name || r.code), docs: cl.length, source: '黑湖客户账本的「待开票金额」' },
    ledgerPending: { label: '待收（客户账本）', ...sum(cl, r => r.pendingReceiveAmount), docs: cl.length, source: '黑湖客户账本的「待收金额」' },
    ledgerReceived: { label: '已收（客户账本）', ...sum(cl, r => r.receivedAmount), docs: cl.length, source: '黑湖客户账本的「已收金额」' }
  };
  // 两个来源互相核对：应收单待收（含负数）= 客户账本待收；收款单 = 客户账本已收。只报一致/不一致，不替任何一方改数
  const net = round2((metrics.pending.value || 0) + (metrics.overpaid.value || 0));
  const checks = [
    { label: '应收单待收（含负数冲减）与客户账本待收', a: net, b: metrics.ledgerPending.value, ok: metrics.ledgerPending.value !== null && Math.abs(net - metrics.ledgerPending.value) < 0.01 },
    { label: '收款单合计与客户账本已收', a: metrics.received.value, b: metrics.ledgerReceived.value, ok: metrics.received.value !== null && metrics.ledgerReceived.value !== null && Math.abs(metrics.received.value - metrics.ledgerReceived.value) < 0.01 }
  ];
  const receivables = rec.map(r => ({ no: r.orderNo, customer: r.customerName || null, type: r.receivableTypeDesc || null, amount: num(r.realReceivableAmount), pending: num(r.pendingReceiveAmount), due: r.receiveEndDate ? String(r.receiveEndDate).slice(0, 10) : null, overdue: Number(r.overdueStatus) === 1, overdueDays: num(r.overdueDays) }));
  const toInvoiceTop = cl.filter(r => (num(r.pendingInvoiceAmount) || 0) > 0).sort((a, b) => num(b.pendingInvoiceAmount) - num(a.pendingInvoiceAmount)).slice(0, 10).map(r => ({ code: r.code, name: r.name, pendingInvoice: num(r.pendingInvoiceAmount), pendingShipment: num(r.pendingShipmentAmount), pendingReceive: num(r.pendingReceiveAmount), received: num(r.receivedAmount) }));
  const invoicingByType = {}; for (const i of inv) { const k = (i.master && i.master.bizTypeDisplay) || '未标类型'; invoicingByType[k] = (invoicingByType[k] || 0) + 1; }
  // 第六批 D④：最近一次录入与「疑似停用」（BL-second-analysis 建议；默认超过 14 天没有录入即标，Steve 可改为撤下）
  // 应收：应收单、收款单的创建时间取最新；待开票：黑湖没有返回开票单，看不到任何开票录入，写「未返回」并按「14 天内没有可见录入」标疑似停用
  // R28：「今天」= 快照日（checkedAt 换算北京时间的日期），不随电脑时钟漂移
  const STALE_DAYS = 14, bjDay = t => new Date(t + 8 * 3600e3).toISOString().slice(0, 10), snapT = Date.parse(d.checkedAt), today = bjDay(Number.isFinite(snapT) ? snapT : Date.now());
  const lastOf = list => { let m = null; for (const x of list) { const t = Date.parse(String(x.createdAt || '').replace(' ', 'T') + '+08:00'); if (Number.isFinite(t) && (m === null || t > m)) m = t; } return m; };
  // 第六批审计退回 D-2：看不到数据时写「无法判断」，不打「疑似停用」；只有确有录入且超过 14 天才标
  const act = (t, what) => { if (t === null) return { last: null, days: null, stale: false, unknown: true, staleDays: STALE_DAYS, basis: what, today, note: '黑湖没有返回' + what }; const days = Math.round((Date.parse(today) - Date.parse(bjDay(t))) / 864e5); return { last: bjDay(t), days, stale: days > STALE_DAYS, staleDays: STALE_DAYS, basis: what, today }; };
  const activity = { receivable: act(lastOf([...rec, ...rcp]), '应收单、收款单'), toInvoice: act(Array.isArray(R.invoice) && R.invoice.length ? lastOf(R.invoice) : null, '开票单') };
  return { activity, revision: d.revision, checkedAt: d.checkedAt, currency: '币种待确认（黑湖未返回）', metrics, checks, receivables, toInvoiceTop, invoicingByType, notes: ['金额直接取黑湖字段，不估算；字段缺失计入「缺失」而不是 0。', '待收为负的应收单（多收或冲减）单独列出，不抵减逾期。', '待开票取黑湖客户账本的「待开票金额」；出入库单只统计张数，不用出入库单推算开票额。'] };
}

function opLog(u) {
  const d = load(), rows = Array.isArray((d.resources || {}).operation_log) ? d.resources.operation_log : [];
  const code = String(u.searchParams.get('code') || '').trim(), id = String(u.searchParams.get('id') || '').trim(), obj = String(u.searchParams.get('object') || '').trim();
  const limit = Math.min(500, Math.max(1, Number(u.searchParams.get('limit')) || 200));
  const hit = rows.filter(r => (!code || String(r.instanceCode) === code) && (!id || String(r.instanceId) === id) && (!obj || r.objectName === obj || r.objectCode === obj));
  hit.sort((a, b) => (Number(b.tsMs) || 0) - (Number(a.tsMs) || 0));
  return { revision: d.revision, checkedAt: d.checkedAt, total: hit.length, rows: hit.slice(0, limit), note: '黑湖操作日志只有对象、动作、时间和字段类别；没有字段级前后值，人员字段已在采集时剔除。' };
}

async function handle({ req, res, u, send }) {
  if (req.method !== 'GET') { send(res, 405, { success: false, message: '只读接口' }); return; }
  try {
    if (u.pathname === '/__unified/d/lsm-finance') { send(res, 200, { success: true, data: finance() }); return; }
    if (u.pathname === '/__unified/d/lsm-oplog') { send(res, 200, { success: true, data: opLog(u) }); return; }
    send(res, 404, { success: false, message: '未知接口' });
  } catch (e) { send(res, e.code === 'ENOENT' ? 503 : 500, { success: false, message: e.code === 'ENOENT' ? '黑湖扩展快照未导出（extras-current.json 不存在）' : '读取失败：' + e.message }); }
}
module.exports = { handle, finance, opLog };
