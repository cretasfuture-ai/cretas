'use strict';
// 第六批（DECISIONS 第十三节 E 行，R23）：来料 / 入库检验挂到 C 线的六膳门入库批次上。
// 批次本身由 C 线建、C 线存（Cretas 主来源）；这里只读。批次「待处置」状态由 E 的检验单推出，不另存一份：
//   同一批次最新一张已提交（未作废）的来料 / 入库检验 —
//     合格                              → 不扣
//     不合格，尚无批次处置结论            → HOLD   待处置
//     不合格，批次处置结论 放行 / 让步放行 → 不扣
//     不合格，批次处置结论 退货 / 报废     → BLOCKED 不可用
// C 的「先到期先出」建议跳过 holds() 返回的所有批次（HOLD 与 BLOCKED）。
const path = require('path');

const BATCH_STAGES = ['incoming', 'warehousing'];
const CLOSE_RESULTS = { '放行': 'RELEASED', '让步放行': 'RELEASED', '退货': 'BLOCKED', '报废': 'BLOCKED' };

// C 线批次接口：优先直接 require C 的模块函数（同进程，不走网络）。C 没就绪时返回 available:false，页面写明。
// C 线 src/supply/supply-batch.cjs：read(session) → { batches: [...] }（撤销的批次带 revokedAt）。只调用读函数，不调用 create / revoke / write。
function cModule() {
  try { const m = require(path.join(__dirname, '..', 'supply', 'supply-batch.cjs')); if (m && typeof m.listBatches === 'function') return { list: (root, sess) => m.listBatches(root, sess) }; if (m && typeof m.read === 'function') return { list: (root, sess) => ((m.read(sess === 'default' ? null : sess) || {}).batches || []).filter(b => !b.revokedAt) }; } catch { /* C 未就绪 */ }
  return null;
}
const s = v => v == null ? '' : String(v);
function normalize(b) {
  return {
    batchNo: s(b.batchNo), kind: b.kind === 'production' ? 'production' : 'purchase',
    productCode: s(b.productCode), productName: s(b.productName), productType: b.kind === 'production' ? 'product' : 'material',
    productionDate: s(b.productionDate).slice(0, 10), expiryDate: s(b.expiryDate).slice(0, 10),
    qty: b.qty == null || b.qty === '' ? null : Number(b.qty), unit: s(b.unit), warehouse: s(b.warehouse || b.warehouseId),
    lakeInboundNo: s(b.lakeInboundNo || b.blOrderNo), createdAt: s(b.createdAt || b.at), storage: s(b.storage), projectCode: s(b.projectCode)
  };
}
function list(root, session) {
  const m = cModule();
  if (!m) return { available: false, reason: 'C 入库批次接口未就绪', rows: [] };
  try {
    const raw = m.list(root, session);
    const rows = (Array.isArray(raw) ? raw : (raw && raw.rows) || []).map(normalize).filter(b => b.batchNo);
    return { available: true, source: 'C 入库批次（Cretas）', rows };
  } catch (e) { return { available: false, reason: 'C 入库批次读取失败：' + e.message, rows: [] }; }
}
function find(root, session, batchNo) { const l = list(root, session); return { l, hit: l.rows.find(b => b.batchNo === batchNo) || null }; }

function holds(st) {
  const latest = new Map();
  for (const q of st.inspections || []) {
    if (!q.batchRef || q.status !== 'SUBMITTED' || !BATCH_STAGES.includes(q.stage)) continue;
    const p = latest.get(q.batchRef.batchNo);
    if (!p || (q.submittedAt || '') > (p.submittedAt || '')) latest.set(q.batchRef.batchNo, q);
  }
  const out = [];
  for (const [batchNo, q] of latest) {
    if (q.verdict !== 'FAIL') continue;
    const c = q.batchClose;
    if (c && CLOSE_RESULTS[c.result] === 'RELEASED') continue;
    const blocked = c && CLOSE_RESULTS[c.result] === 'BLOCKED';
    out.push({ batchNo, status: blocked ? 'BLOCKED' : 'HOLD', label: blocked ? '不可用（' + c.result + '）' : '待处置', inspectionNo: q.no, inspectionId: q.id, stage: q.stage, productCode: q.batchRef.productCode, reason: (q.failItems || []).join('、') || '检验不合格', since: q.submittedAt, closedAt: c ? c.at : '' });
  }
  // 第八批：召回中的批次也扣留（E 的批次召回记录，进行中即 HOLD；已有 BLOCKED / HOLD 的不重复）
  for (const h of require('./q-records.cjs').recallHolds(st)) if (!out.some(x => x.batchNo === h.batchNo)) out.push(h);
  return out.sort((a, b) => (a.since < b.since ? 1 : -1));
}
module.exports = { BATCH_STAGES, CLOSE_RESULTS, list, find, holds };
