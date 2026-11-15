'use strict';
// C 线 · stock / document 解读（第二批③，DECISIONS 三：数量类一律严格模式）。
// 事实与允许的核对代码由代码从黑湖只读快照算出；模型只能从允许的代码里选最多 3 个，不能写任何数字或自由文本。
// 返回的正文由本文件按代码渲染。模型不可用、输出不合规时，整次拒绝并给规则结果。界面不显示设备名和模型名。
// 本机解读服务与六膳门、E 线同一条转发路径（ssh → 本机 11434），排队按缓存键去重。
const { spawn } = require('child_process'), crypto = require('crypto');
const MODEL = 'qwen3:8b', HOSTS = ['canada-mini', 'canada-mini-backup'];
const RULE_VERSION = 'supply-rules-v1';
const CODES = {
  NEG_PICK_FIRST: { text: '负库存所在仓有领料出库、没有入库：先核对是否漏入库或调拨入库未过账', who: '线边仓管理员' },
  NEG_WRONG_WH: { text: '同产品在其他仓有正余额：核对是否先领后调（错仓）', who: '仓库主管' },
  UNIT_MULTI: { text: '同产品出现多个单位：核对单位换算，换算前不合计', who: '黑湖对接人' },
  ZERO_MANY: { text: '零库存行较多：核对是否已停用、长期未动或只是未清理的档案', who: '仓管' },
  FIELDS_MISSING: { text: '可用、占用、待检、冻结黑湖未返回：不能据在库数判断可用量', who: '黑湖对接人' },
  SNAPSHOT_OLD: { text: '库存快照超过 24 小时：先更新来源快照再下结论', who: '系统管理员' },
  DOC_NO_RELATED: { text: '有明细行没有关联单号：核对来源工单或采购单', who: '仓管' },
  DOC_NO_COST: { text: '黑湖未返回金额：要核成本时请在 Cretas 补金额或向财务确认', who: '财务' },
  DOC_NO_BATCH: { text: '黑湖没有批次号：需要追溯时在 Cretas 补批次号', who: '仓管' },
  DOC_PICK_NO_PROJECT: { text: '领料单的关联号对不上工单：核对是否跨工单借料', who: '生产主管' },
  // 第四批⑥ lsm-extra（黑湖新接口只读记录）
  EX_PRICE_DEV: { text: '有明细单价与客户价目表不一致：核对是否特价或价目表未更新（价目表只作参考，不改黑湖单价）', who: '销售内勤' },
  EX_ZERO_PRICE: { text: '有明细黑湖单价为 0（可能为赠品或未定价）：请核对', who: '销售内勤' },
  EX_NO_PRICE: { text: '有明细没有匹配的客户价目：如需对照，请在黑湖维护该客户的价目表', who: '销售内勤' },
  EX_LATE: { text: '计划交货已过且订单未结束：核对发货安排或在黑湖更新交期', who: '销售内勤' },
  EX_UNSHIPPED: { text: '订单还有未发数量：核对库存与发货计划', who: '仓库主管' },
  EX_OVERPAID: { text: '待收为负（多收或冲减）：核对是否重复收款或需退款、冲减', who: '财务' },
  EX_OVERDUE: { text: '应收已逾期且待收大于 0：按账期催收', who: '财务' },
  EX_PO_OPEN: { text: '采购还有未收数量：核对到货计划', who: '采购员' },
  EX_REQ_LACK: { text: '采购需求仍有缺料：核对是否已下达采购', who: '采购员' },
  EX_OUT_PENDING: { text: '委外任务还有待发料或待收货：跟进委外方', who: '委外跟单' },
  EX_READONLY: { text: '这是黑湖只读记录：开单、审批、收付款在黑湖办理，Cretas 不回写', who: '黑湖对接人' }
};
let chain = Promise.resolve();
const cache = new Map(), inflight = new Map();
function transportOnce(payload, host) {
  return new Promise((resolve, reject) => {
    const child = spawn('ssh', ['-T', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=6', host, "curl --fail-with-body -sS --max-time 100 -H 'Content-Type: application/json' --data-binary @- http://127.0.0.1:11434/api/chat"], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let out = '', done = false;
    const timer = setTimeout(() => finish(Object.assign(Error('解读服务超时'), { code: 'TIMEOUT' })), 105000);
    function finish(e, v) { if (done) return; done = true; clearTimeout(timer); if (child.exitCode === null) child.kill(); e ? reject(e) : resolve(v); }
    child.on('error', () => finish(Object.assign(Error('解读连接暂不可用'), { code: 'CONNECT' })));
    child.stdout.on('data', b => { out += b; if (out.length > 2e6) finish(Error('解读响应过大')); });
    child.on('close', code => {
      if (done) return;
      if (code === 255) return finish(Object.assign(Error('解读连接暂不可用'), { code: 'CONNECT' }));
      if (code !== 0) return finish(Error('解读服务失败'));
      try { const v = JSON.parse(out); if (typeof v.message?.content !== 'string') throw Error(); finish(null, v); } catch { finish(Error('解读结果未通过格式校验')); }
    });
    child.stdin.end(JSON.stringify(payload));
  });
}
async function transport(payload) { let last; for (const h of HOSTS) { try { return await transportOnce(payload, h); } catch (e) { last = e; if (e.code !== 'CONNECT') break; } } throw last; }
function run(key, payload) {
  if (cache.has(key)) return Promise.resolve({ ...cache.get(key), cached: true });
  if (inflight.has(key)) return inflight.get(key);
  const p = (chain = chain.catch(() => {}).then(async () => { const t0 = Date.now(); try { const v = await transport(payload); const r = { content: JSON.parse(v.message.content), ms: Date.now() - t0 }; cache.set(key, r); return r; } finally { inflight.delete(key); } }));
  inflight.set(key, p); return p;
}
const sha = v => crypto.createHash('sha256').update(JSON.stringify(v)).digest('hex').slice(0, 24);
const toNum = v => (typeof v === 'number' && Number.isFinite(v)) ? v : (typeof v === 'string' && /^\s*-?\d+(?:\.\d+)?\s*$/.test(v) ? Number(v) : undefined);
const unitKey = u => u == null || u === '' ? '（无单位）' : String(u).trim();

// ---- 事实（服务端从快照算，不信任前端传来的数字） ----
function stockFacts(inv, id) {
  const f = {}; for (const p of String(id || '').split('|')) { const i = p.indexOf('='); if (i > 0) f[p.slice(0, i)] = p.slice(i + 1); }
  const state = q => { const n = toNum(q); return n === undefined ? 'invalid' : n > 0 ? 'positive' : n < 0 ? 'negative' : 'zero'; };
  const rows = inv.stock.filter(s => (!f.wh || String(s.warehouseId) === f.wh) && (!f.state || state(s.qtyInWarehouse) === f.state) && (!f.q || (String(s.productCode) + String(s.productName)).toLowerCase().includes(String(f.q).toLowerCase())));
  const neg = rows.filter(s => state(s.qtyInWarehouse) === 'negative'), zero = rows.filter(s => state(s.qtyInWarehouse) === 'zero');
  let pickNoIn = 0, wrongWh = 0, multiUnit = 0;
  for (const s of neg) {
    let picked = 0, inbound = 0;
    for (const o of inv.orders) for (const l of o.lines) { if (String(l.productId) !== String(s.productId) || String(l.detailWarehouseId) !== String(s.warehouseId)) continue; const t = String(o.bizTypeDisplay); if (t === '生产领料出库') picked++; if (t.endsWith('入库')) inbound++; }
    if (picked && !inbound) pickNoIn++;
    if (inv.stock.some(x => String(x.productId) === String(s.productId) && String(x.warehouseId) !== String(s.warehouseId) && (toNum(x.qtyInWarehouse) || 0) > 0)) wrongWh++;
    const units = new Set(inv.stock.filter(x => String(x.productId) === String(s.productId)).map(x => unitKey(x.unitName))); if (units.size > 1) multiUnit++;
  }
  const ageH = (Date.now() - Date.parse(inv.materialAt)) / 36e5;
  const allowed = [];
  if (pickNoIn) allowed.push('NEG_PICK_FIRST'); if (wrongWh) allowed.push('NEG_WRONG_WH'); if (multiUnit) allowed.push('UNIT_MULTI');
  if (rows.length && zero.length / rows.length >= 0.3) allowed.push('ZERO_MANY');
  allowed.push('FIELDS_MISSING'); if (ageH > 24) allowed.push('SNAPSHOT_OLD');
  return { kind: 'stock', scope: f, rows: rows.length, negative: neg.length, zero: zero.length, pickNoIn, wrongWh, multiUnit, snapshotAgeHours: Math.round(ageH), allowed,
    conclusion: '当前范围 ' + rows.length + ' 行：负库存 ' + neg.length + '、零库存 ' + zero.length + '；其中负库存有领料无入库 ' + pickNoIn + ' 行、其他仓有正余额 ' + wrongWh + ' 行。快照已过 ' + Math.round(ageH) + ' 小时。' };
}
function documentFacts(inv, id, projects) {
  const o = inv.orders.find(x => String(x.orderNo) === String(id)); if (!o) return null;
  const noRel = o.lines.filter(l => !l.associatedBizOrderCode).length, noCost = o.lines.filter(l => l.realCostAmount == null || l.realCostAmount === '').length;
  const isPick = String(o.bizTypeDisplay) === '生产领料出库';
  const codes = new Set((projects || []).map(p => String(p.projectCode)));
  const pickNoProject = isPick ? o.lines.filter(l => l.associatedBizOrderCode && !codes.has(String(l.associatedBizOrderCode))).length : 0;
  const allowed = []; if (noRel) allowed.push('DOC_NO_RELATED'); if (noCost) allowed.push('DOC_NO_COST'); allowed.push('DOC_NO_BATCH'); if (pickNoProject) allowed.push('DOC_PICK_NO_PROJECT');
  return { kind: 'document', orderNo: o.orderNo, type: o.bizTypeDisplay, lines: o.lines.length, noRel, noCost, pickNoProject, allowed,
    conclusion: '单据 ' + o.orderNo + '（' + o.bizTypeDisplay + '）共 ' + o.lines.length + ' 行：无关联单号 ' + noRel + ' 行、黑湖未返回金额 ' + noCost + ' 行' + (isPick ? '、关联号对不上工单 ' + pickNoProject + ' 行' : '') + '。' };
}
// 第四批⑥：lsm-extra 事实。id 形如 '<页面>:<记录 ID>'，事实从黑湖新接口只读快照算，不信任前端数字
function extraFacts(X, id) {
  const i = String(id || '').indexOf(':'); if (i < 1) return null;
  const page = id.slice(0, i), rid = id.slice(i + 1), R = (X && X.resources) || {};
  const num = v => (typeof v === 'number' && Number.isFinite(v)) ? v : (typeof v === 'string' && /^s*-?d+(?:.d+)?s*$/.test(v) ? Number(v) : undefined);
  const ageH = (Date.now() - Date.parse(X.checkedAt)) / 36e5, allowed = [], base = { kind: 'extra', page, snapshotAgeHours: Math.round(ageH) };
  const tail = () => { allowed.push('EX_READONLY'); if (ageH > 24) allowed.push('SNAPSHOT_OLD'); };
  if (page === 'sales') {
    const o = (R.sale_order || []).find(x => String(x.id) === rid); if (!o) return null;
    const prices = new Map(); for (const p of R.customer_price || []) { const k = String(p.customerId) + '|' + String(p.productId); prices.set(k, [...(prices.get(k) || []), p]); }
    // R19：同一客户＋产品多条价目时不取任何一条，不计偏离也不计无价目，单列「多条价目」
    const lines = o.saleManageOrderDetailRowApiVOList || []; let dev = 0, none = 0, amb = 0, zero = 0;
    // R20：黑湖单价为 0 不算偏离，单独计数
    for (const l of lines) { const ps = prices.get(String(o.customerId) + '|' + String(l.productId)) || []; if (num(l.unitPrice) === 0) zero++; else if (ps.length > 1) amb++; else if (!ps.length) none++; else if (Math.abs(num(l.unitPrice) - num(ps[0].price)) >= 0.005) dev++; }
    const ended = /结束|取消|关闭|完成/.test(String(o.statusDesc || '')), late = !!o.isOverdue && !ended, unshipped = !ended && (num(o.totalQty) || 0) > (num(o.shipmentQty) || 0);
    if (dev) allowed.push('EX_PRICE_DEV'); if (zero) allowed.push('EX_ZERO_PRICE'); if (none) allowed.push('EX_NO_PRICE'); if (late) allowed.push('EX_LATE'); if (unshipped) allowed.push('EX_UNSHIPPED'); tail();
    return { ...base, orderNo: o.orderNo, status: o.statusDesc, lines: lines.length, priceDeviationLines: dev, noPriceLines: none, ambiguousPriceLines: amb, zeroPriceLines: zero, late, unshipped, allowed,
      conclusion: '销售订单 ' + o.orderNo + '（' + (o.statusDesc || '状态未返回') + '）共 ' + lines.length + ' 行：与客户价目表不一致 ' + dev + ' 行、无匹配价目 ' + none + ' 行' + (amb ? '、有多条价目无法确定参考价 ' + amb + ' 行' : '') + (zero ? '、黑湖单价为 0 ' + zero + ' 行' : '') + (late ? '；计划交货已过、订单未结束' : '') + (unshipped ? '；还有未发数量' : '') + '。' };
  }
  if (page === 'finance') {
    const r = (R.receivable || []).find(x => String(x.id) === rid); if (!r) return null;
    const p = num(r.pendingReceiveAmount), over = p !== undefined && p < 0, due = Number(r.overdueStatus) === 1 && p > 0;
    if (over) allowed.push('EX_OVERPAID'); if (due) allowed.push('EX_OVERDUE'); tail();
    return { ...base, orderNo: r.orderNo, pending: p ?? null, overpaid: over, overdue: due, allowed,
      conclusion: '应收单 ' + r.orderNo + '：待收 ' + (p === undefined ? '未返回' : p.toFixed(2)) + (over ? '（为负，属多收或冲减，不算逾期）' : due ? '，已逾期 ' + (num(r.overdueDays) ?? '—') + ' 天' : '') + '。金额币种黑湖未返回。' };
  }
  if (page === 'purchase') {
    const o = (R.purchase_order || []).find(x => String(x.id) === rid); if (!o) return null;
    const open = (o.purchaseSubOrderVOS || []).filter(l => (num(l.unClearNum) || 0) > 0).length; if (open) allowed.push('EX_PO_OPEN'); tail();
    return { ...base, orderNo: o.orderCode, openLines: open, allowed, conclusion: '采购订单 ' + o.orderCode + '：未收清 ' + open + ' 行。' };
  }
  if (page === 'requirement') {
    const o = (R.purchase_requirement || []).find(x => String(x.materialRequirementsId) === rid); if (!o) return null;
    const lack = num(o.lackAmount) || 0; if (lack > 0) allowed.push('EX_REQ_LACK'); tail();
    return { ...base, orderNo: o.code, lack, allowed, conclusion: '采购需求 ' + o.code + '：缺料 ' + lack.toFixed(2) + ' ' + (o.productUnitName || '') + '。' };
  }
  if (page === 'outsource') {
    const o = (R.outsource_task || []).find(x => String(x.id) === rid); if (!o) return null;
    const pi = num(o.pendingIssueAmount) || 0, pr = num(o.pendingReceiveAmount) || 0; if (pi > 0 || pr > 0) allowed.push('EX_OUT_PENDING'); tail();
    return { ...base, orderNo: o.taskCode, pendingIssue: pi, pendingReceive: pr, allowed, conclusion: '委外任务 ' + o.taskCode + '：待发料 ' + pi.toFixed(2) + '、待收货 ' + pr.toFixed(2) + ' ' + (o.productUnit || '') + '。' };
  }
  const map = { customers: ['customer', 'code'], vendors: ['vendor', 'code'], prices: ['customer_price', 'productCode'] }[page]; if (!map) return null;
  const o = (R[map[0]] || []).find(x => String(x.id) === rid); if (!o) return null; tail();
  return { ...base, orderNo: o[map[1]], allowed, conclusion: '黑湖只读记录 ' + String(o[map[1]] ?? rid) + '，本对象没有需要核对的数量差异。' };
}
const render = (codes) => codes.map(c => ({ code: c, text: CODES[c].text, who: CODES[c].who }));
async function explain(facts, question, rulesOnly) {
  const base = { mode: 'strict', ruleVersion: RULE_VERSION, at: new Date().toISOString(), conclusion: facts.conclusion };
  const defaults = facts.allowed.slice(0, 3);
  if (rulesOnly) return { ...base, model: 'rules', codes: defaults, actions: render(defaults) };
  if (!facts.allowed.length) return { ...base, model: 'skipped', codes: [], actions: [], modelNote: '当前事实没有可选核对项。' };
  const { allowed, conclusion, ...shown } = facts;
  const payload = { model: MODEL, format: 'json', stream: false, options: { temperature: 0 }, messages: [
    { role: 'system', content: '只读核对库存或出入库单据。不生成任何数字，不改事实，不执行操作。' },
    { role: 'user', content: '仅返回 JSON {"reviewCodes":[]}，按优先顺序最多选 3 个允许的核对代码：' + JSON.stringify(allowed.map(k => ({ code: k, text: CODES[k].text }))) + '。已核实事实：' + JSON.stringify(shown) + '。用户问题：' + String(question || '').slice(0, 200) } ] };
  try {
    const r = await run(sha([facts, question, RULE_VERSION]), payload);
    const codes = r.content && Array.isArray(r.content.reviewCodes) ? r.content.reviewCodes : null;
    const extra = r.content && Object.keys(r.content).some(k => k !== 'reviewCodes');
    if (!codes || !codes.length || extra || codes.some(c => !allowed.includes(c)) || new Set(codes).size !== codes.length || codes.length > 3)
      return { ...base, model: 'rejected', modelNote: '解读结果含未允许的核对项或多余内容，已整次拒绝，以下为规则结果。', codes: defaults, actions: render(defaults) };
    return { ...base, model: 'ok', cached: !!r.cached, ms: r.ms, codes, actions: render(codes) };
  } catch (e) { return { ...base, model: 'unavailable', modelNote: '解读服务暂不可用，以下为规则结果。', codes: defaults, actions: render(defaults) }; }
}
module.exports = { explain, stockFacts, documentFacts, extraFacts, CODES, RULE_VERSION };
