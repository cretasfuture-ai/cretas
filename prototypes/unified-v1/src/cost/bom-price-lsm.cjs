'use strict';
// 第八批 · BOM 调价建议（六膳门）：原料价变动 → 哪些产品成本受影响、影响多少 → 调价建议。
// 只读：读黑湖成本快照（工单领退料明细）、黑湖采购订单、黑湖客户价目表、黑湖销售订单行（取售价单位）；不写任何价格，不回写黑湖。
// 不猜、不造数：缺什么写什么（compute 返回 gaps）；单位不同不换算；价格为 0 不当有值；价格忽高忽低的料不出建议。
const fs = require('fs'), path = require('path');
const LSM = path.resolve(__dirname, '..', '..', '..', 'liushanmen-v1');
const num = v => (typeof v === 'number' && Number.isFinite(v)) ? v : null;
const r2 = v => v == null ? null : Math.round(v * 100) / 100;
const r4 = v => v == null ? null : Math.round(v * 10000) / 10000;
const day = v => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}/.test(v) ? v.slice(0, 10) : null;
const CHANGE_PCT = 1;      // 变动幅度 ≥1% 才算「变」
const USAGE_MAX = 3;        // 同单位（如 KG 对 KG）每单位产出领料超过 3，视为口径未对齐，不计入影响额
const OUTLIER = 3;          // 最新价与此前均价相差 3 倍以上，视为价格不稳定，先核价、不出建议

let cache = null;
function readExtras() {
  const f = path.join(LSM, 'private', 'extras-current.json');
  if (!fs.existsSync(f)) return null;
  const st = fs.statSync(f);
  if (cache && cache.mtime === st.mtimeMs) return cache.value;
  const x = JSON.parse(fs.readFileSync(f, 'utf8'));
  if (x.factoryId !== 'LIUSHANMEN') throw Error('黑湖扩展快照的工厂与六膳门不一致');
  const R = x.resources || {};
  const value = { revision: x.revision, checkedAt: x.checkedAt, purchase: R.purchase_order || [], customerPrice: R.customer_price || [], saleOrder: R.sale_order || [], vendorPrice: R.vendor_price || [] };
  cache = { mtime: st.mtimeMs, value };
  return value;
}

// 把若干 (day, qty, price) 按日折成加权均价序列
function daily(points) {
  const m = new Map();
  for (const p of points) { const g = m.get(p.day) || { day: p.day, qty: 0, amt: 0, n: 0, refs: [] }; const q = p.qty > 0 ? p.qty : 1; g.qty += q; g.amt += q * p.price; g.n++; if (p.ref && g.refs.length < 3) g.refs.push(p.ref); m.set(p.day, g); }
  return [...m.values()].map(g => ({ day: g.day, price: g.amt / g.qty, qty: g.qty, n: g.n, refs: g.refs })).sort((a, b) => a.day.localeCompare(b.day));
}
// 变动判断：最新一天 vs 此前各天的加权均价。只有 1 天的价格点，不判断
function change(series) {
  if (!series.length) return { state: 'none' };
  const latest = series[series.length - 1];
  if (series.length < 2) return { state: 'single', latest: r4(latest.price), latestDay: latest.day, days: 1 };
  const prev = series.slice(0, -1), base = prev.reduce((n, s) => n + s.price * s.qty, 0) / prev.reduce((n, s) => n + s.qty, 0);
  const delta = latest.price - base, pct = base > 0 ? delta / base * 100 : null;
  const all = series.map(s => s.price), spread = Math.max(...all) / Math.min(...all);
  const outlier = base > 0 && (latest.price / base >= OUTLIER || latest.price / base <= 1 / OUTLIER || spread >= OUTLIER * 2);
  return { state: outlier ? 'outlier' : Math.abs(pct) >= CHANGE_PCT ? 'changed' : 'flat', base: r4(base), latest: r4(latest.price), delta: r4(delta), pct: r2(pct), latestDay: latest.day, baseFrom: prev[0].day, baseTo: prev[prev.length - 1].day, days: series.length, spread: r2(spread), series: series.map(s => ({ day: s.day, price: r4(s.price), qty: r2(s.qty), n: s.n })) };
}

function compute({ source, report }) {
  const ex = readExtras();
  const gaps = [];
  if (!ex) gaps.push({ code: 'NO_EXTRAS', text: '尚未导入黑湖扩展快照（采购订单、客户价目表），只能用工单领料价判断原料价变动，也无法给出售价建议。' });
  const key = (code, unit) => String(code) + '|' + String(unit || '');
  const mats = new Map();   // key -> {code,name,unit, issue:[points], purchase:[points], zeroPurchase}
  const mat = (code, name, unit) => { const k = key(code, unit); if (!mats.has(k)) mats.set(k, { key: k, code, name, unit: unit || null, issue: [], purchase: [], zeroPurchase: 0, vendors: new Set() }); return mats.get(k); };
  const usageByOrder = new Map();   // projectId -> Map(key -> qty)
  for (const c of source.costs || []) {
    const per = usageByOrder.get(String(c.projectId)) || new Map();
    for (const l of c.materialLinesDetail || []) {
      if (!l.materialCode) continue;
      const m = mat(l.materialCode, l.material, l.unit), q = num(l.netConsumption), p = num(l.unitPrice), d = day(l.date);
      if (q !== null) per.set(m.key, (per.get(m.key) || 0) + q);
      if (q !== null && q > 0 && p !== null && p > 0 && d) m.issue.push({ day: d, qty: q, price: p, ref: l.orderCode });
    }
    usageByOrder.set(String(c.projectId), per);
  }
  if (ex) for (const o of ex.purchase) {
    const d = day(o.purchaseTime);
    for (const s of o.purchaseSubOrderVOS || []) {
      if (!s.productCode) continue;
      const m = mat(s.productCode, s.productName, s.unitName), p = num(s.purchasePrice);
      if (p === null || p <= 0) { m.zeroPurchase++; continue; }
      if (d) { m.purchase.push({ day: d, qty: num(s.purchaseNum) || 0, price: p, ref: o.orderCode }); if (o.shortName) m.vendors.add(o.shortName); }
    }
  }
  // ---- 每种原料：采购价变动、领料成本价变动，采用哪一个 ----
  const materials = [];
  for (const m of mats.values()) {
    const pur = change(daily(m.purchase)), iss = change(daily(m.issue));
    let basis = null, adopt = { state: 'none' };
    if (pur.state === 'changed' || pur.state === 'flat') { basis = 'purchase'; adopt = pur; }
    else if (iss.state === 'changed' || iss.state === 'flat') { basis = 'issue'; adopt = iss; }
    else if (pur.state === 'outlier') { basis = 'purchase'; adopt = pur; }
    else if (iss.state === 'outlier') { basis = 'issue'; adopt = iss; }
    else if (pur.state === 'single') { basis = 'purchase'; adopt = pur; }
    else if (iss.state === 'single') { basis = 'issue'; adopt = iss; }
    materials.push({ key: m.key, code: m.code, name: m.name, unit: m.unit, purchase: pur, issue: iss, basis, state: adopt.state, base: adopt.base ?? null, latest: adopt.latest ?? null, delta: adopt.delta ?? null, pct: adopt.pct ?? null, latestDay: adopt.latestDay || null, zeroPurchase: m.zeroPurchase, vendors: [...m.vendors].slice(0, 4), purchaseLines: m.purchase.length });
  }
  // 原料类别：有采购订单行 → 外购（采购价）；否则编码是本厂产出的产品（半成品）→ 内部半成品，其「领料价」是上游工单成本的传导，不是原料价；其余 → 原料/包材（只有领料成本价）
  const ownCodes = new Set(report.products.map(p => p.productCode).filter(Boolean));
  for (const m of materials) { m.kind = m.purchaseLines ? 'purchased' : (ownCodes.has(m.code) || /^(BCP|CP|DJG)/.test(String(m.code).replace(/s+/g, ''))) ? 'semi' : 'raw'; if (m.kind === 'semi' && (m.state === 'changed' || m.state === 'flat')) { m.internalState = m.state; m.state = 'internal'; } }
  const matByKey = new Map(materials.map(m => [m.key, m]));
  // ---- 售价与售价单位（只认同产品编码、销售订单行写明的单位；对不上不猜） ----
  const saleUnits = new Map();
  if (ex) for (const so of ex.saleOrder) for (const l of so.saleManageOrderDetailRowApiVOList || []) if (l.productCode && l.productUnitName) { const s = saleUnits.get(l.productCode) || new Set(); s.add(l.productUnitName); saleUnits.set(l.productCode, s); }
  const prices = new Map();
  if (ex) for (const c of ex.customerPrice) { const g = prices.get(c.productCode) || []; g.push(c); prices.set(c.productCode, g); }
  // ---- 产品 × 原料：每单位良品的领料量（实际领料，不是 BOM 标准用量） ----
  const products = [], usage = new Map();
  let zeroPriceRows = 0, noUnitProducts = 0;
  for (const p of report.products) {
    const orders = p.orders.filter(o => o.status === 'COMPLETED' && num(o.finishedGood) > 0 && o.unit === p.unit);
    const used = new Map();   // material key -> {qty, fg, n}
    for (const o of orders) {
      const per = usageByOrder.get(String(o.projectId)); if (!per) continue;
      for (const [k, q] of per) if (q > 0) { const g = used.get(k) || { qty: 0, fg: 0, n: 0, orders: [] }; g.qty += q; g.fg += o.finishedGood; g.n++; if (g.orders.length < 6) g.orders.push(o.projectCode); used.set(k, g); }
    }
    const labels = [...new Set(p.orders.map(o => (o.adopted && o.adopted.label) || (o.blacklake && o.blacklake.adoptedLabel)).filter(x => x && x !== '—'))];
    const unitCost = num(p.weightedUnitCost);
    const rows = [];
    for (const [k, g] of used) {
      const m = matByKey.get(k); if (!m) continue;
      const per = g.qty / g.fg, row = { materialKey: k, code: m.code, name: m.name, unit: m.unit, usagePerUnit: r4(per), orders: g.n, orderCodes: g.orders };
      rows.push(row);
      const u = usage.get(k) || []; u.push({ productKey: p.key, productCode: p.productCode, product: p.product, unit: p.unit, usagePerUnit: per, orders: g.n, unitCost }); usage.set(k, u);
    }
    const impacts = rows.map(r => { const m = matByKey.get(r.materialKey), odd = m.unit && m.unit === p.unit && r.usagePerUnit > USAGE_MAX; const imp = m.state === 'changed' && !odd ? r.usagePerUnit * m.delta : null, chain = m.state === 'internal' && m.internalState === 'changed' && !odd ? r.usagePerUnit * m.delta : null; return { ...r, state: odd && m.state === 'changed' ? 'usage-odd' : m.state, kind: m.kind, delta: m.delta, pct: m.pct, impact: r4(imp), chain: r4(chain), note: odd ? '每' + p.unit + '产出领 ' + r4(r.usagePerUnit) + ' ' + m.unit + '，同单位下超过 ' + USAGE_MAX + '，疑似产出或领料口径未对齐，不计入影响额' : null }; });
    const changed = impacts.filter(r => r.impact !== null);
    const outlierN = impacts.filter(r => r.state === 'outlier').length, chainRows = impacts.filter(r => r.chain !== null), chainTotal = chainRows.length ? chainRows.reduce((n, r) => n + r.chain, 0) : null;
    const total = changed.length ? changed.reduce((n, r) => n + r.impact, 0) : null;
    // 调价建议：对该产品每条客户价目表（价 >0、售价单位核实与成本单位一致）算建议价
    const suggestions = [];
    let priceNote = null;
    const adj = total !== null || chainTotal !== null ? (total || 0) + (chainTotal || 0) : null, adjBasis = total !== null && chainTotal !== null ? 'both' : total !== null ? 'direct' : chainTotal !== null ? 'chain' : null;
    if (adj !== null) {
      const rowsP = prices.get(p.productCode) || [], units = saleUnits.get(p.productCode);
      if (!rowsP.length) priceNote = '客户价目表没有该产品的价格行';
      else for (const c of rowsP) {
        const price = num(c.price);
        if (!(price > 0)) { zeroPriceRows++; suggestions.push({ customer: c.customerName, price, reason: '价目表价格为 0（常见于品控留样），不参与建议' }); continue; }
        if (!units) { suggestions.push({ customer: c.customerName, price, reason: '售价单位没有核实（销售订单里没有该产品的行），不比较、不出建议' }); continue; }
        if (units.size > 1 || !units.has(p.unit)) { suggestions.push({ customer: c.customerName, price, reason: '售价单位（' + [...units].join('、') + '）与成本单位（' + p.unit + '）不同，不换算、不出建议' }); continue; }
        const keepMargin = price + adj, keepRatio = unitCost > 0 ? price * (1 + adj / unitCost) : null;
        suggestions.push({ customer: c.customerName, price: r2(price), priceAt: c.updatedAt || c.createdAt || null, keepMargin: r2(keepMargin), keepMarginPct: r2(adj / price * 100), keepRatio: r2(keepRatio), keepRatioPct: unitCost > 0 ? r2(adj / unitCost * 100) : null, ratioNow: unitCost > 0 ? r4(unitCost / price) : null, ratioAfter: unitCost > 0 ? r4((unitCost + adj) / price) : null, costChange: r4(adj), basis: adjBasis, unit: p.unit, reason: null });
      }
    }
    if (!unitCost && changed.length) noUnitProducts++;
    products.push({ key: p.key, productCode: p.productCode, product: p.product, unit: p.unit, orders: orders.length, unitCost: r4(unitCost), unitCostSource: labels.join('；') || null, impacts, changedCount: changed.length, chainCount: chainRows.length, chainImpact: r4(chainTotal), chainImpactPct: chainTotal !== null && unitCost > 0 ? r2(chainTotal / unitCost * 100) : null, outlierCount: outlierN, totalImpact: r4(total), totalImpactPct: total !== null && unitCost > 0 ? r2(total / unitCost * 100) : null, suggestions, priceNote });
  }
  const chainOnly = products.filter(p => p.changedCount === 0 && p.chainCount > 0);
  const affected = products.filter(p => p.changedCount > 0).sort((a, b) => Math.abs(b.totalImpactPct ?? 0) - Math.abs(a.totalImpactPct ?? 0));
  const inUse = new Set(); for (const p of products) for (const r of p.impacts) inUse.add(r.materialKey);
  const used = materials.filter(m => inUse.has(m.key));
  const count = s => used.filter(m => m.state === s).length;
  const kindCount = k => used.filter(m => m.kind === k).length;
  // ---- 缺什么（逐项，不猜） ----
  if (ex && !ex.vendorPrice.length) gaps.push({ code: 'NO_VENDOR_PRICE', text: '黑湖没有供应商价目表数据（vendor_price 0 条），原料「现行采购价」只能取采购订单行；没有采购订单的料，改用工单领料的成本单价（库存加权成本，不等于采购价）。' });
  gaps.push({ code: 'NO_BOM_STANDARD', text: '用量按「已完工工单的实际领料量 ÷ 工单良品」估算，不是 BOM 标准用量：黑湖工单的 BOM 需求行是该单需求总量，不是单位用量。有 BOM 标准用量的产品，补进来后影响额可按标准重算。' });
  if (ex) gaps.push({ code: 'PURCHASE_WINDOW', text: '采购订单只有 ' + ex.purchase.length + ' 张（' + (ex.purchase.map(o => day(o.purchaseTime)).filter(Boolean).sort()[0] || '—') + ' 至 ' + (ex.purchase.map(o => day(o.purchaseTime)).filter(Boolean).sort().slice(-1)[0] || '—') + '），多数原料只有一次采购价，无法判断价格是否变动（' + count('single') + ' 种在用原料只有 1 个价格点）。' });
  const noPrice = affected.filter(p => !p.suggestions.some(s => s.keepMargin != null));
  if (noPrice.length) gaps.push({ code: 'NO_SALE_PRICE', text: noPrice.length + ' 个受影响产品没有可用的售价建议（客户价目表没有价格行、价格为 0 或售价单位无法核实）。' });
  if (affected.some(p => !p.unitCost)) gaps.push({ code: 'NO_UNIT_COST', text: '部分受影响产品没有可计算的单位直接成本，只给影响额，不给占比。' });
  if (ex && ex.customerPrice.length) gaps.push({ code: 'CUSTOMER_PRICE_NO_UNIT', text: '客户价目表行本身不带单位；售价单位取自销售订单行（同产品编码），对不上的不出建议。' });
  return {
    revision: ex ? ex.revision : source.revision, checkedAt: ex ? ex.checkedAt : source.checkedAt, sourceRevision: source.revision, sourceCheckedAt: source.checkedAt,
    sources: { purchase: '黑湖采购订单', issue: '黑湖领退料成本单价', customer: '黑湖客户价目表', saleUnit: '黑湖销售订单行', unitCost: '产品采用值（黑湖＋Cretas 计算 / 补充，按各单实际来源）' },
    rule: '原料价变动：优先取采购订单行单价（最新一次 vs 此前加权均价）；没有 2 次以上采购价时，取工单领料成本单价（最新一天 vs 此前加权均价）。变动 ≥ ' + CHANGE_PCT + '% 才算「变」；最新价与此前均价相差 ' + OUTLIER + ' 倍以上或价格点跨度过大的料标「价格不稳定」，先核价、不出建议。影响额 ＝ 每单位良品领料量 × 价格变化；调价建议 ＝ 客户价目表现价 ＋ 影响额（保持每单位毛利额），另给保持成本占比的算法。只作建议，不回写任何价格。',
    summary: { materialsInUse: used.length, internal: count('internal'), purchasedN: kindCount('purchased'), semiN: kindCount('semi'), rawN: kindCount('raw'), changed: count('changed'), flat: count('flat'), single: count('single'), outlier: count('outlier'), affectedProducts: affected.length, chainProducts: products.filter(p => p.chainCount > 0).length, products: products.length, zeroPriceRows, noUnitProducts },
    materials: used.sort((a, b) => ({ changed: 0, outlier: 1, flat: 2, single: 3, internal: 4, none: 5 }[a.state] - { changed: 0, outlier: 1, flat: 2, single: 3, internal: 4, none: 5 }[b.state]) || Math.abs(b.pct ?? 0) - Math.abs(a.pct ?? 0)),
    products: products.filter(p => p.impacts.length).sort((a, b) => (b.changedCount > 0) - (a.changedCount > 0) || Math.abs(b.totalImpactPct ?? 0) - Math.abs(a.totalImpactPct ?? 0)),
    usage: Object.fromEntries([...usage].map(([k, v]) => [k, v.map(x => ({ ...x, usagePerUnit: r4(x.usagePerUnit), unitCost: r4(x.unitCost) }))])),
    salePrices: Object.fromEntries([...new Set(products.map(p => p.productCode).filter(Boolean))].map(code => [code, (prices.get(code) || []).map(c => { const units = saleUnits.get(code), pr = products.find(p => p.productCode === code), price = num(c.price); return { customer: c.customerName, price: r2(price), priceAt: c.updatedAt || c.createdAt || null, unit: units && units.size === 1 ? [...units][0] : null, ok: price > 0 && !!units && units.size === 1 && !!pr && units.has(pr.unit), reason: !(price > 0) ? '价目表价格为 0，不参与' : !units ? '售价单位没有核实' : (units.size > 1 || !pr || !units.has(pr.unit)) ? '售价单位与成本单位不同，不换算' : null }; })])),
    gaps
  };
}
module.exports = { compute };
