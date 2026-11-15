'use strict';
// 六膳门成本分析适配：把六膳门已核验的黑湖来源快照（只读）投影成与味美「产品成本」同一份报表结构，
// 使产品汇总 -> 产品详情 -> 单工单、人工与工序成本补充（Excel 模板/导入/重算）共用同一套组件。
// 只写 unified-v1/private/lsm-cost-workspace.json；绝不写黑湖、味美、六膳门自身的来源或工作区文件。
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const ROOT = path.resolve(__dirname, '..');
const ACCOUNTS = path.resolve(ROOT, '..');
const LSM = path.join(ACCOUNTS, 'liushanmen-v1');
const YD_TOOLS = path.join(ACCOUNTS, 'ydwm-v1', 'tools');
const XLSX = require(path.join(ACCOUNTS, '..', 'node_modules', 'xlsx'));
const { groupEligibleCostProjects, buildUnitCostChart } = require(path.join(YD_TOOLS, 'cost-costOverview.cjs'));
const { allocateLaborByPersonHours } = require(path.join(YD_TOOLS, 'cost-laborCostAnalysis.cjs'));
const costFilters = require(path.join(YD_TOOLS, 'cost-filters.cjs'));

const FACTORY = 'LIUSHANMEN';
const CURRENCY = '币种待确认';
const SOURCE_PROCESS = '黑湖任务工时';
const STATE_FILE = path.join(ROOT, 'private', 'lsm-cost-workspace.json');
const KEY_FILE = path.join(ROOT, 'private', 'lsm-hmac.key');
const hash = x => crypto.createHash('sha256').update(JSON.stringify(x)).digest('hex');
const known = x => typeof x === 'number' && Number.isFinite(x) && x >= 0;
const num = v => (typeof v === 'number' && Number.isFinite(v)) ? v : (typeof v === 'string' && /^-?\d+(?:\.\d+)?$/.test(v.trim()) ? Number(v) : null);
const emptyState = () => ({ factoryId: FACTORY, version: 0, supplements: {}, followups: {}, audit: [], wageRules: [], valuationPrices: {} });

// ---- BL16 工价规则（Cretas 补充，不改黑湖报工）：计件＝良品量×单价（单位须一致），计时＝有效工时(小时)×时薪 ----
// 匹配：同工序；指定产品的规则优先于通用规则；生效日不晚于报工结束日；同级取生效日最新者。匹配不到/单位不一致/无时长 → 金额为空并写原因。
const day = v => typeof v === 'string' ? v.slice(0, 10) : null;
function matchRule(rules, r) {
  const at = day(r.endtimeofReport) || day(r.starttimeofReport);
  const live = (rules || []).filter(x => !x.revokedAt && String(x.processId) === String(r.processId) && (!x.effectiveFrom || !at || x.effectiveFrom <= at));
  const pick = list => list.sort((a, b) => String(b.effectiveFrom || '').localeCompare(String(a.effectiveFrom || '')))[0];
  return pick(live.filter(x => x.productCode && x.productCode === r.productCode)) || pick(live.filter(x => !x.productCode));
}
function reportLaborRow(rules, r) {
  const rule = matchRule(rules, r), base = { reportId: String(r.id), taskId: String(r.taskId), projectId: String(r.projectId), processId: String(r.processId), source: 'cretas' };
  if (!rule) return { ...base, mode: null, rate: null, basisQty: null, basisUnit: null, amount: null, ruleId: null, reason: '无适用工价规则' };
  if (rule.mode === 'piece') {
    const q = num(r.numberofgoodProducts);
    if (q === null) return { ...base, mode: 'piece', rate: rule.rate, basisQty: null, basisUnit: r.stockUnit || null, amount: null, ruleId: rule.id, reason: '报工良品量缺失' };
    if (rule.unit && rule.unit !== r.stockUnit) return { ...base, mode: 'piece', rate: rule.rate, basisQty: q, basisUnit: r.stockUnit || null, amount: null, ruleId: rule.id, reason: '单位不一致（规则 ' + rule.unit + ' / 报工 ' + (r.stockUnit || '未填') + '），不换算' };
    return { ...base, mode: 'piece', rate: rule.rate, basisQty: q, basisUnit: r.stockUnit || null, amount: Math.round(q * rule.rate * 100) / 100, ruleId: rule.id, reason: null };
  }
  // 时长优先取黑湖记录的 effectiveTime；没有时才按报工起止时间推算，并单独标出，不与黑湖工时混同（与 A 对齐）
  const recorded = num(r.effectiveTime) > 0, sec = recorded ? num(r.effectiveTime) : (Date.parse(r.endtimeofReport) - Date.parse(r.starttimeofReport)) / 1000;
  if (!(sec > 0)) return { ...base, mode: 'time', rate: rule.rate, basisQty: null, basisUnit: '小时', durationSource: null, amount: null, ruleId: rule.id, reason: '报工无有效时长' };
  const h = sec / 3600;
  return { ...base, mode: 'time', rate: rule.rate, basisQty: Math.round(h * 100) / 100, basisUnit: '小时', durationSource: recorded ? 'blacklake' : 'derived', note: recorded ? null : '时长由起止时间推算', amount: Math.round(h * rule.rate * 100) / 100, ruleId: rule.id, reason: null };
}
function laborByTask(source, state) {
  const m = new Map();
  for (const r of source.resources.output || []) { const row = reportLaborRow(state.wageRules, r), k = row.taskId; const g = m.get(k) || { rows: [], amount: 0, complete: true }; g.rows.push(row); if (row.amount === null) g.complete = false; else g.amount += row.amount; m.set(k, g); }
  return m;
}
// ---- BL14 进销存估值口径（B 出口径，C 显示）：原料取最近一次黑湖正价领料单价 → Cretas 补充单价 → 未估值；成品取已识别直接成本单价 ----
function valuation(source, state, asOf, codes) {
  const limit = asOf || '9999-12-31', want = codes && codes.length ? new Set(codes) : null, out = new Map();
  const lines = source.costs.flatMap(c => (c.materialLinesDetail || []).map(m => ({ ...m, projectCode: c.projectCode }))).filter(m => num(m.unitPrice) > 0 && (day(m.date) || '') <= limit).sort((a, b) => String(b.date).localeCompare(String(a.date)));
  for (const m of lines) { const k = m.materialCode + '|' + m.unit; if (!out.has(k)) out.set(k, { productCode: m.materialCode, name: m.material, unit: m.unit, unitPrice: Math.round(num(m.unitPrice) * 1e6) / 1e6, basis: '黑湖领料单价 · ' + m.orderCode + ' · ' + day(m.date), source: 'blacklake', at: m.date, reason: null }); }
  for (const [k, p] of Object.entries(state.valuationPrices || {})) { if (p.revokedAt || out.has(k) || (p.effectiveFrom && p.effectiveFrom > limit)) continue; out.set(k, { productCode: p.productCode, name: p.name || null, unit: p.unit, unitPrice: p.unitPrice, basis: 'Cretas 补充单价 · ' + p.evidence, source: 'cretas', at: p.at, reason: null }); }
  const groups = new Map();
  for (const o of orders(source, state)) { if (!o.unitCostAvailable || !o.productCode || (day(o.at) || '') > limit) continue; const k = o.productCode + '|' + o.unit; const g = groups.get(k) || { cost: 0, qty: 0, n: 0, name: o.product, code: o.productCode, unit: o.unit, sup: false }; g.cost += o.directCost; g.qty += o.finishedGood; g.n++; g.sup = g.sup || (o.supplementFields || []).some(x => /材料|人工/.test(x)); groups.set(k, g); }
  for (const [k, g] of groups) if (!out.has(k) && g.qty > 0) out.set(k, { productCode: g.code, name: g.name, unit: g.unit, unitPrice: Math.round(g.cost / g.qty * 1e6) / 1e6, basis: '已识别直接成本（材料＋人工，不含制造费用）· ' + g.n + ' 张工单' + (g.sup ? ' · 含 Cretas 补充' : ''), source: g.sup ? 'cretas' : 'blacklake', at: null, reason: null });
  let rows = [...out.values()];
  if (want) { rows = rows.filter(r => want.has(r.productCode)); for (const c of want) if (!rows.some(r => r.productCode === c)) rows.push({ productCode: c, name: null, unit: null, unitPrice: null, basis: null, source: 'missing', at: null, reason: '未估值：无黑湖正价领料、无 Cretas 补充单价、无可核算工单' }); }
  return { rule: '原料/包材：最近一次黑湖正价领料单价 → Cretas 补充单价 → 未估值；成品/半成品：同编码同单位可核算工单的已识别直接成本单价。金额＝数量×单价，仅单位相同才乘，不换算、不补 0。', asOf: asOf || null, rows };
}

function readSource() {
  const s = JSON.parse(fs.readFileSync(path.join(LSM, 'private', 'source-current.json'), 'utf8'));
  const b = JSON.parse(fs.readFileSync(path.join(LSM, 'private', 'binding.json'), 'utf8'));
  if (s.factoryId !== FACTORY || String(s.sourceFactoryId) !== b.sourceFactoryId) throw Error('工厂来源绑定不一致');
  return s;
}
// 演示写入按会话隔离（W2：只认 test- 开头）；不合规名称显式报错，不回落到默认保存区
function stateFile(session) {
  if (!session) return STATE_FILE;
  if (!/^test-[a-z0-9-]{1,60}$/i.test(session)) throw Object.assign(Error('验收会话名称不合规（须以 test- 开头）：' + session), { status: 400 });
  return path.join(ROOT, 'private', 'lsm-cost-workspace.' + session + '.json');
}
// ---- 第三批：黑湖成本接口 project_cost / project_cost_detail（只读 liushanmen-v1/private/extras-current.json，按 revision 缓存） ----
// 口径（已用数据核对）：实际材料＝明细 costSourceType 12（实际领退料，退料为负）之和；预估材料＝来源 11 之和；人工＝costType 2（报工工资）之和；委外＝costType 3。
let extrasCache = null;
function readExtras() {
  const file = path.join(LSM, 'private', 'extras-current.json');
  if (!fs.existsSync(file)) return null;
  const st = fs.statSync(file);
  if (extrasCache && extrasCache.mtime === st.mtimeMs) return extrasCache.value;
  const x = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (x.factoryId !== FACTORY) throw Error('黑湖扩展快照的工厂与六膳门不一致');
  const byProject = new Map(), details = new Map();
  for (const d of x.resources.project_cost_detail || []) { const g = details.get(d.projectCostId) || []; g.push(d); details.set(d.projectCostId, g); }
  for (const p of x.resources.project_cost || []) if (!p.deleted) byProject.set(String(p.projectId), p);
  const value = { revision: x.revision, checkedAt: x.checkedAt, byProject, details, count: byProject.size };
  extrasCache = { mtime: st.mtimeMs, value };
  return value;
}
const r2 = v => v == null ? null : Math.round(v * 100) / 100;
const TYPE_NAME = { 1: '材料', 2: '人工', 3: '委外', 4: '其他' }, SRC_NAME = { 10: 'BOM 需求', 11: '预估单价', 12: '实际领退料', 21: '报工工资', 31: '委外' };
// 差额原因分类（逐项）：由代码按事实判断，不由模型猜
function diffReason(field, bl, cr, o, extra) {
  if (bl === null && cr === null) return { code: 'BOTH_MISSING', text: '两边都没有' };
  if (bl === null) return { code: 'BL_MISSING', text: '黑湖成本接口未返回该项' };
  if (bl < 0) return { code: 'NEGATIVE', text: '黑湖净额为负（退料大于领料），待核对' };
  if (cr === null && bl === 0) return { code: 'BOTH_NONE', text: field === '材料' ? 'Cretas 未关联领退料、黑湖为 0：两边都没有有效' + field : '两边都没有有效' + field };
  if (cr === null) return { code: 'CRETAS_MISSING', text: field === '材料' ? 'Cretas 未关联到领退料（主快照缺关联），黑湖已计入' : 'Cretas 主快照缺该项，黑湖已计入' };
  const d = bl - cr;
  if (Math.abs(d) < 0.005) return { code: 'MATCH', text: '一致' };
  if (extra && extra.supplemented) return bl === 0 ? { code: 'BL_ZERO_SUPPLEMENT', text: '黑湖记为 0，按 Cretas 补充计算' } : { code: 'SUPPLEMENT', text: 'Cretas 值来自补充，已被黑湖来源值取代' };
  if (cr === 0 && bl > 0) return { code: 'SOURCE_ZERO', text: '主快照为源端零值，黑湖成本接口已估值' };
  if (bl === 0 && cr > 0) return { code: 'BL_ZERO', text: '黑湖记为 0（未计价），采用 Cretas 计算' };
  if (extra && extra.timing) return { code: 'TIMING', text: '快照时间不同，期间领退料或报工有变动（黑湖成本更新于 ' + String(extra.timing).slice(0, 16).replace('T', ' ') + '）' };
  return { code: 'OTHER', text: '差异待核对（口径或归集范围不同）' };
}
// 第六批 R23：金额来源标签按实际来源拼写，避免和「Cretas」（主来源：称重、批次）混淆
const MONEY_NAMES = { blacklake: '黑湖', supplement: 'Cretas 补充', cretas: 'Cretas 计算' };
const moneyLabel = set => Object.keys(MONEY_NAMES).filter(k => set.has(k)).map(k => MONEY_NAMES[k]).join('＋');
function blacklakeCost(o, raw, extras, sourceAt) {
  const p = extras && extras.byProject.get(o.id);
  if (!p) return { available: false, reason: extras ? '黑湖成本接口没有返回该工单' : '尚未导入黑湖成本接口数据' };
  const ds = extras.details.get(p.projectCostId) || [];
  const breakdown = Object.entries(ds.reduce((m, d) => { const k = d.costType + ':' + d.costSourceType; const g = m[k] || { type: TYPE_NAME[d.costType] || String(d.costType), source: SRC_NAME[d.costSourceType] || String(d.costSourceType), count: 0, amount: 0 }; g.count++; g.amount += d.amount || 0; m[k] = g; return m; }, {})).map(([, g]) => ({ ...g, amount: r2(g.amount) }));
  const lines = ds.filter(d => d.costSourceType === 12 || d.costType === 2 || d.costType === 3).slice(0, 60).map(d => ({ type: TYPE_NAME[d.costType], source: SRC_NAME[d.costSourceType], name: d.productName || d.processName || d.outsourceProcessName || '—', qty: d.costType === 2 ? r2(d.fineAmount) : r2(d.stockChangeQty ?? d.outsourceQty), unit: d.unitName || null, unitPrice: d.costUnitPrice ?? d.taxInclusiveUnitPrice ?? null, hours: d.costType === 2 && d.outputDuration ? r2(d.outputDuration / 3600) : null, biz: d.stockBizTypeName || null, amount: r2(d.amount) }));
  const timing = p.updatedAt && sourceAt && Date.parse(p.updatedAt) > Date.parse(sourceAt) ? p.updatedAt : null;
  const sup = (o.supplementFields || []);
  const items = [
    ['材料', r2(p.actualMaterialCost), r2(o.materialCost), { supplemented: sup.includes('材料费'), timing }],
    ['人工', r2(p.actualLaborCost), r2(o.laborCost), { supplemented: sup.some(x => /人工费/.test(x)), timing }],
    ['委外', r2(p.actualOutsourceCost), null, {}],
    ['其他', r2(p.actualOtherCost), null, {}]
  ].map(([field, bl, cr, extra]) => {
    if ((field === '委外' || field === '其他')) return { field, blacklake: bl, cretas: null, delta: null, reason: bl ? { code: 'BL_ONLY', text: field + '成本只在黑湖口径中（Cretas 直接成本不含）' } : { code: 'MATCH', text: '两边都为 0' } };
    return { field, blacklake: bl, cretas: cr, delta: bl != null && cr != null ? r2(bl - cr) : null, reason: diffReason(field, bl, cr, o, extra) };
  });
  const blTotal = r2(p.totalActualCost), crTotal = o.directCost == null ? null : r2(o.directCost);
  const output = { blacklake: r2(p.realAmount), cretas: o.finishedGood == null ? null : r2(o.finishedGood), unitBlacklake: p.unitName || null, unitCretas: o.unit };
  const unitMismatch = output.unitBlacklake && o.unit && output.unitBlacklake !== o.unit;
  const blUnit = p.realAmount > 0 && p.totalActualCost > 0 ? p.totalActualCost / p.realAmount : null;
  const unitCostReason = unitMismatch ? { code: 'UNIT', text: '单位不同（黑湖 ' + output.unitBlacklake + ' / Cretas ' + o.unit + '），不比较' } : (output.blacklake != null && output.cretas != null && Math.abs(output.blacklake - output.cretas) >= 0.005) ? { code: 'OUTPUT', text: '产出口径不同（黑湖实际产量 ' + output.blacklake + ' / 工单良品 ' + output.cretas + '）' } : null;
  const pick = (bl, cr, supplemented, label) => {
    if (bl !== null && bl <= -0.005) return { value: null, source: null, blocked: true, note: '无法计算：黑湖' + label.replace('费', '') + '为负（' + bl.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + '，可能是退料冲减），请核对领退料' };
    if (bl !== null && Math.abs(bl) >= 0.005) return { value: bl, source: 'blacklake', note: supplemented ? label + '补充已被黑湖来源值取代' : null };
    if (cr !== null) return { value: cr, source: supplemented ? 'supplement' : 'cretas', note: bl === 0 ? (supplemented ? '黑湖记为 0，按 Cretas 补充计算' : '黑湖记为 0，采用 Cretas 计算') : '黑湖无该项，采用 Cretas 计算' };
    return { value: null, source: null, note: bl === 0 ? '黑湖记为 0（未计价），Cretas 也无值' : '两边都没有' };
  };
  const supM = sup.includes('材料费'), supL = sup.some(x => /人工费/.test(x));
  const aM = pick(r2(p.actualMaterialCost), r2(o.materialCost), supM, '材料费'), aL = pick(r2(p.actualLaborCost), r2(o.laborCost), supL, '人工费');
  const extraCost = (r2(p.actualOutsourceCost) || 0) + (r2(p.actualOtherCost) || 0);
  const aQ = { value: r2(p.realAmount), unit: p.unitName || o.unit, source: 'blacklake', note: p.realAmount === 0 ? '黑湖实际产量为 0（数量类以黑湖为准）' : null };
  const blocked = [aM, aL].find(x => x.blocked) || (extraCost < -0.005 ? { note: '无法计算：黑湖委外/其他为负（' + extraCost.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + '），请核对' } : null);
  const aTotal = !blocked && aM.value !== null && aL.value !== null ? r2(aM.value + aL.value + extraCost) : null;
  const sources = new Set([aM.source, aL.source].filter(Boolean));
  const adoptedView = { material: aM, labor: aL, outsourceOther: extraCost ? { value: r2(extraCost), source: 'blacklake' } : null, output: aQ, total: aTotal, unitCost: aTotal !== null && aQ.value > 0 ? aTotal / aQ.value : null, label: blocked || aTotal === null ? '—' : moneyLabel(sources), blockedReason: blocked ? blocked.note : null };
  const superseded = [supM && aM.source === 'blacklake' ? '材料费' : null, supL && aL.source === 'blacklake' ? '人工费' : null].filter(Boolean), active = [supM && aM.source === 'supplement' ? '材料费' : null, supL && aL.source === 'supplement' ? '人工费' : null].filter(Boolean);
  const supplementState = superseded.length || active.length ? [superseded.length ? superseded.join('、') + ' 的补充已被黑湖来源值取代（不参与采用值）' : '', active.length ? active.join('、') + ' 的补充生效（黑湖记为 0 或无该项）' : ''].filter(Boolean).join('；') : null;
  const usable = p.totalActualCost > 0;
  return {
    available: true, projectCostId: p.projectCostId, status: p.projectStatusName, updatedAt: p.updatedAt, items,
    total: { blacklake: blTotal, cretas: crTotal, delta: blTotal != null && crTotal != null ? r2(blTotal - crTotal) : null },
    output, unitCost: { blacklake: blUnit, cretas: o.unitCostAvailable && o.finishedGood > 0 ? o.directCost / o.finishedGood : null, reason: unitCostReason },
    estimate: { total: r2(p.totalEstimatedCost), unit: r2(p.estimatedUnitCost), material: r2(p.estimatedMaterialCost), labor: r2(p.estimatedLaborCost), differenceAmount: r2(p.differenceAmount), differenceRate: p.differenceRate },
    breakdown, lines, detailCount: ds.length, adoptedView, supplementState, supersededFields: superseded,
    adopted: adoptedView.label === '—' ? null : sources.has('blacklake') ? 'blacklake' : 'cretas', adoptedLabel: adoptedView.label,
    adoptedNote: adoptedView.blockedReason ? adoptedView.blockedReason : usable ? '黑湖成本接口有实际成本，非零项以黑湖为准；黑湖记为 0 的金额项按 Cretas 计算或补充（R13）' + (supplementState ? '。' + supplementState : '') : (crTotal != null ? '黑湖成本接口没有实际成本（' + (p.projectStatusName || '') + '），采用 Cretas 计算' : '两边都没有可用的直接成本')
  };
}
function readState(session) { const file = stateFile(session); return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : emptyState(); }
function writeState(state, session) {
  const file = stateFile(session);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = file + '.' + crypto.randomUUID() + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(state), { flag: 'wx', mode: 0o600 });
  fs.renameSync(tmp, file);
}
function secret() {
  if (!fs.existsSync(KEY_FILE)) { fs.mkdirSync(path.dirname(KEY_FILE), { recursive: true }); fs.writeFileSync(KEY_FILE, crypto.randomBytes(32).toString('hex'), { mode: 0o600 }); }
  return fs.readFileSync(KEY_FILE, 'utf8');
}
const statusName = s => ({ '0': 'DRAFT', '10': 'IN_PROGRESS', '20': 'COMPLETED', '30': 'CANCELLED' }[String(s)] || String(s));

function orders(source, state) {
  const ruleIndex = laborByTask(source, state);
  const projects = new Map(source.resources.project.map(p => [String(p.id), p]));
  const products = new Map();
  for (const p of source.resources.product) { const list = products.get(p.productCode) || []; list.push(p); products.set(p.productCode, list); }
  return source.costs.filter(c => String(c.status) !== '30').map(c => {
    const proj = projects.get(String(c.projectId)) || {};
    const prodRows = products.get(c.productCode) || [];
    const productTypeId = prodRows.length === 1 ? String(prodRows[0].id) : (c.productCode ? 'code:' + c.productCode : 'project:' + c.projectId);
    const sourceRow = { projectId: c.projectId, finishedGood: c.finishedGood, materialCost: c.materialCost, laborCost: c.laborCost, processes: c.processes, material: c.materialLinesDetail };
    const sourceHash = hash(sourceRow);
    const supplement = state.supplements[String(c.projectId)], current = !!supplement && supplement.sourceHash === sourceHash;
    // 来源原则（DECISIONS「四之前」）：黑湖有值以黑湖为准；黑湖缺失或为源端零值时，才用 Cretas 补充，并标明「含 Cretas 补充」。
    const sourceMaterial = known(c.materialCost) ? c.materialCost : null, sourceLabor = known(c.laborCost) ? c.laborCost : null;
    const suppMaterial = current && known(supplement.materialCost) ? supplement.materialCost : null;
    const materialFromSupplement = suppMaterial !== null && (sourceMaterial === null || sourceMaterial === 0);
    const materialSuperseded = !!supplement && known(supplement.materialCost) && known(c.materialCost) && c.materialCost > 0;
    const taskRules = (c.processes || []).map(p => ruleIndex.get(String(p.taskId))), ruleLabor = taskRules.length && taskRules.every(g => g && g.complete) ? Math.round(taskRules.reduce((n, g) => n + g.amount, 0) * 100) / 100 : null;
    const suppLabor = current && known(supplement.laborCost) ? supplement.laborCost : null;
    const material = materialFromSupplement ? suppMaterial : sourceMaterial, labor = sourceLabor ?? suppLabor ?? ruleLabor;
    const laborFromSupplement = sourceLabor === null && labor !== null, laborFromRule = sourceLabor === null && suppLabor === null && ruleLabor !== null;
    const MATERIAL_ISSUE = /未关联|全部为源端零值|材料净额为零|部分领料|单价/, LABOR_ISSUE = /工资|人工成本覆盖/;
    const issues = (c.issues || []).filter(i => !(materialFromSupplement && MATERIAL_ISSUE.test(i)) && !(laborFromSupplement && LABOR_ISSUE.test(i)));
    const quantity = known(c.finishedGood) ? c.finishedGood : null, unit = typeof c.unit === 'string' && c.unit.trim() ? c.unit : null;
    const base = (c.processes || []).map(p => ({ id: String(p.taskId), name: p.process || '未命名工序', personHours: known(p.hours) ? p.hours : null, source: SOURCE_PROCESS }));
    const processes = base.map(p => { const extra = current ? supplement.processes.find(x => x.id === p.id) : null; return { ...p, personHours: p.personHours ?? extra?.personHours ?? null, participants: extra?.participants ?? null, source: extra ? (p.personHours !== null ? p.source + ' · 名单为 Cretas 补充' : 'Cretas 补充') : p.source }; });
    const shares = allocateLaborByPersonHours(labor, processes.map(p => p.personHours));
    for (const p of processes) { const g = ruleIndex.get(p.id); p.ruleLabor = g && g.complete ? Math.round(g.amount * 100) / 100 : null; p.ruleReason = !g ? '无报工' : g.complete ? null : (g.rows.find(x => x.amount === null) || {}).reason; }
    const participants = current ? supplement.participants : null;
    const missing = [...(!processes.length ? ['工序对应关系'] : []), ...(material === null ? ['材料费'] : []), ...(labor === null ? ['人工费'] : []), ...(quantity === null ? ['实际良品产出'] : []), ...(!unit ? ['产出单位'] : []), ...(processes.some(p => p.personHours === null) ? ['有效人时'] : []), ...(participants === null ? ['实际参与名单'] : [])];
    const readinessIssues = [...issues, ...(quantity === 0 && !(c.issues || []).some(i => /良品/.test(i)) ? ['产出已记录为0，不能计算单位成本'] : []), ...(processes.length && processes.every(p => p.personHours === 0) ? ['人时合计已记录为0，不能按人时分摊'] : [])];
    const o = {
      id: String(c.projectId), projectId: String(c.projectId), projectCode: c.projectCode, product: c.product || '未命名产品', productCode: c.productCode || null, productTypeId, stage: '待核对', unit, currency: state.currency ? state.currency.value : CURRENCY,
      finishedGood: quantity, materialCost: material, laborCost: labor, sourceLaborCost: sourceLabor, sourceMaterialCost: sourceMaterial, directCost: material !== null && labor !== null ? material + labor : null,
      // 可核算：黑湖原判定可算；或仅因材料/人工缺口被阻断、且这些缺口已由有效补充填上（其余阻断原因必须为空）
      unitCostAvailable: (c.unitCostAvailable === true || ((materialFromSupplement || laborFromSupplement) && issues.length === 0)) && material !== null && labor !== null && quantity > 0 && !!unit,
      ruleLabor, ruleLaborPartial: taskRules.some(g => !g || !g.complete), ruleLaborDelta: sourceLabor !== null && ruleLabor !== null ? Math.round((ruleLabor - sourceLabor) * 100) / 100 : null,
      supplementFields: [...(materialFromSupplement ? ['材料费'] : []), ...(laborFromSupplement ? [laborFromRule ? '人工费（工价规则）' : '人工费'] : []), ...(current && supplement.processes?.length && base.some(p => p.personHours === null) ? ['有效人时'] : []), ...(current && supplement.processes?.length && supplement.participants !== null ? ['实际参与名单'] : [])],
      supplementNote: materialSuperseded ? '材料费补充值已被来源值取代' : null,
      status: statusName(c.status), synthetic: false, workflowProductTypeId: productTypeId, planId: String(c.projectId), planNumber: c.projectCode,
      at: proj.actualEndTime || proj.actualStartTime || proj.planStartTime || proj.createTime || null, sourceHash,
      materialTrace: (c.materialLinesDetail || []).map((m, i) => ({ id: String(c.projectId) + ':' + i, documentId: String(m.orderCode), document: String(m.orderCode), status: 'RECORDED', scope: 'plan', material: m.material || '未命名物料', unit: m.unit || null, required: null, issued: null, consumed: num(m.netConsumption), returned: null, recordedLineCost: num(m.recognizedCost), batches: [] })),
      supplementEvidence: current ? supplement.evidence : null, supplementAt: current ? supplement.at : null, supplementStatus: supplement ? (current ? 'CURRENT' : 'STALE') : 'NONE',
      participants, perPerson: known(labor) && participants > 0 ? labor / participants : null, processes: processes.map((p, i) => ({ ...p, allocatedLabor: shares[i] })), missing, readinessIssues,
      requests: missing.map(field => ({ field, owner: /材料/.test(field) ? '仓库 / 成本核对人' : /名单|人时/.test(field) ? '班组长 / 车间主管' : /工序|产出|单位/.test(field) ? '生产负责人 / 报工人' : '车间主管 / 成本核对人', action: /工序/.test(field) ? '核对本工单与实际工序任务的对应关系' : /名单/.test(field) ? '确认逐人参与代码，代报人不能替代实际名单' : /人时/.test(field) ? '核对每段实际作业时长、人数并扣除休息停机' : /材料/.test(field) ? '回原领退料与历史估值核对；黑湖缺失或为零时可在 Cretas 补充已核实估值（系统不代填）' : '补齐当前字段的原始依据后再核对' })),
      followup: state.followups[String(c.projectId)] || null, reportLineage: null
    };
    return o;
  });
}

// 第五批审计 B-1：产品级「采用值」汇总（逐单取 adoptedView；无黑湖成本的单取 Cretas 计算 / 补充），并列出已被黑湖取代的补充
function adoptedAgg(rows) {
  const part = (o, k, key, fld) => { const v = o.adopted || (o.blacklake && o.blacklake.available && o.blacklake.adoptedView); if (v) { const p = v[k]; return { value: p.value, source: p.blocked ? null : p.source }; } const val = o[key]; return { value: val, source: val === null ? null : ((o.supplementFields || []).some(x => fld.test(x)) ? 'supplement' : 'cretas') }; };
  const M = o => part(o, 'material', 'materialCost', /^材料费$/), L = o => part(o, 'labor', 'laborCost', /人工费/);
  const agg = f => { const ps = rows.map(f), known = ps.filter(p => p.value !== null); return { value: known.length ? r2(known.reduce((n, p) => n + p.value, 0)) : null, partial: known.length < ps.length, blacklake: known.filter(p => p.source === 'blacklake').length, cretas: known.filter(p => p.source === 'cretas').length, supplement: known.filter(p => p.source === 'supplement').length, missing: ps.length - known.length }; };
  const direct = rows.map(o => { const a = M(o), b = L(o); return a.value !== null && b.value !== null ? a.value + b.value : null; });
  const wo = rows.filter(o => o.weigh && o.weigh.mainCost !== null && o.weigh.goodKg > 0), weighAgg = { pending: rows.filter(o => o.weigh && !o.weigh.complete).length, count: rows.filter(o => o.weigh && o.weigh.complete).length, costed: wo.length, goodKg: rows.some(o => o.weigh && o.weigh.complete) ? r2(rows.filter(o => o.weigh && o.weigh.complete).reduce((n, o) => n + o.weigh.goodKg, 0)) : null, unitCost: wo.length ? wo.reduce((n, o) => n + o.weigh.mainCost, 0) / wo.reduce((n, o) => n + o.weigh.goodKg, 0) : null, materialUnit: wo.length && wo.every(o => o.weigh.materialUnit !== null) ? wo.reduce((n, o) => n + o.weigh.materialUnit * o.weigh.goodKg, 0) / wo.reduce((n, o) => n + o.weigh.goodKg, 0) : null, byproductKg: r2(rows.reduce((n, o) => n + (o.weigh && o.weigh.complete ? o.weigh.byproductKg : 0), 0)) };
  const superseded = rows.flatMap(o => ((o.blacklake && o.blacklake.supersededFields) || []).map(f => ({ orderId: o.id, projectCode: o.projectCode, field: f, value: r2(f === '材料费' ? o.materialCost : o.laborCost), adopted: f === '材料费' ? o.blacklake.adoptedView.material.value : o.blacklake.adoptedView.labor.value })));
  return { adoptedMaterial: agg(M), adoptedLabor: agg(L), adoptedDirect: { value: direct.some(x => x !== null) ? r2(direct.filter(x => x !== null).reduce((n, x) => n + x, 0)) : null, partial: direct.some(x => x === null) }, supersededSupplements: superseded, weighAgg };
}
// 第六批 R23：报工称重以 Cretas 为主来源，数据从 A 线读（只读，按会话隔离）
let weighingProvider = null;
function readWeighing(session) {
  try {
    const fn = weighingProvider || (() => { const a = require(path.join(__dirname, '..', 'src', 'production', 'a-api.cjs')); return typeof a.weighingSummary === 'function' ? a.weighingSummary : null; })();
    if (!fn) return { ready: false, reason: 'A 线称重接口未就绪', projects: {} };
    const r = fn('lsm', session || 'default') || {};
    return { ready: true, revision: r.revision || null, at: r.at || null, projects: r.projects || {} };
  } catch (e) { return { ready: false, reason: '读取 A 线称重失败：' + e.message, projects: {} }; }
}
const BYPRODUCT_MODES = { none: '不分摊', weight: '按重量', ratio: '按固定比例' };
function byproductRuleOf(state) { const r = state.byproductRule; return r && BYPRODUCT_MODES[r.mode] ? r : { mode: 'none', mainShare: null }; }
function byproductRuleText(r) { return r.mode === 'none' ? '副产品不分摊：成本全部记在主产品良品上' : r.mode === 'weight' ? '副产品按重量分摊：主产品承担 良品 ÷（良品＋副产品）' : '副产品按固定比例分摊：主产品承担 ' + r.mainShare + '%'; }
function routeOf(source, productCode) {
  const p = (source.resources.product || []).find(x => x.productCode === productCode), r = p && (source.resources.routing || []).find(x => x.processRoutingCode === p.prcessRoutingCode);
  const steps = Array.isArray(r && r.processRoutingSteps) ? r.processRoutingSteps : [], names = new Map((source.resources.process || []).map(x => [x.code, x.name]));
  return steps.map(x => ({ code: x.code, name: x.name || names.get(x.code) || x.code }));
}
function weighView(o, bl, w, rule, source) {
  if (!w) return null;
  // 第六批口径修正：产出量、单位成本只用路线最后一道工序（完工工序）的良品，等于入库数；完工工序没称重时不拿中间工序顶替
  const route = source ? routeOf(source, o.productCode) : [], aFinal = w.finalProcess && typeof w.finalProcess.weighed === 'boolean' ? w.finalProcess : null, aFirst = w.firstProcess && typeof w.firstProcess.weighed === 'boolean' ? w.firstProcess : null, routeFinal = aFinal ? { code: aFinal.code, name: aFinal.name } : w.routeFinal !== undefined ? w.routeFinal : (route.length ? route[route.length - 1] : null);
  const procs = (w.processes || []).map(p => ({ code: p.code, name: p.name, inputKg: p.inputKg == null ? null : r2(Number(p.inputKg)), goodKg: p.goodKg == null ? null : r2(Number(p.goodKg)), defectKg: p.defectKg == null ? null : r2(Number(p.defectKg)), records: p.records ?? null,
    yieldRate: p.yieldRate != null ? Number(p.yieldRate) : (Number(p.inputKg) > 0 ? Number(p.goodKg) / Number(p.inputKg) : null), isFirst: !!p.isFirst, isLast: !!p.isLast, routeIndex: p.routeIndex !== undefined ? p.routeIndex : (route.findIndex(x => x.code === p.code) >= 0 ? route.findIndex(x => x.code === p.code) : null) }))
    .sort((a, b) => (a.routeIndex ?? 999) - (b.routeIndex ?? 999));
  const finalWeighed = aFinal ? aFinal.weighed : w.finalWeighed !== undefined ? !!w.finalWeighed : routeFinal ? procs.some(p => p.code === routeFinal.code) : w.goodKg != null;
  const finalProc = routeFinal ? procs.find(p => p.code === routeFinal.code) : null;
  const finalGood = !finalWeighed ? null : aFinal ? w.goodKg : w.finalGoodKg !== undefined ? w.finalGoodKg : finalProc ? finalProc.goodKg : (w.goodKg == null ? null : Number(w.goodKg));
  const basis = aFinal ? (w.finalBasis || '完工、首道按产品路线认定（A 线）') : routeFinal ? '完工工序按产品路线认定：' + routeFinal.name + (w.routeFinal !== undefined ? '（A 线给出）' : '（A 线未给，B 按黑湖路线认定）') : '产品没有路线：完工工序按 A 线时间规则认定（' + (w.finalProcess ? w.finalProcess.name : '—') + '）';
  const incomplete = finalGood == null ? '完工工序（' + (routeFinal ? routeFinal.name : '未认定') + '）还没有称重，产出量和单位成本暂按原来源；已称工序只算各自的出成率' : null;
  const good = finalGood == null ? null : r2(Number(finalGood)), by = (w.byproducts || []).filter(b => Number(b.kg) > 0).map(b => ({ productCode: b.productCode || null, name: b.name || '未命名副产品', kg: r2(Number(b.kg)) })), byKg = r2(by.reduce((n, b) => n + b.kg, 0));
  const main = good === null ? null : rule.mode === 'weight' ? (good + byKg > 0 ? good / (good + byKg) : null) : rule.mode === 'ratio' ? rule.mainShare / 100 : 1;
  const av = bl.available ? bl.adoptedView : null, sup = o.supplementFields || [];
  const mat = av ? av.material : { value: o.materialCost, source: o.materialCost === null ? null : sup.includes('材料费') ? 'supplement' : 'cretas', note: o.materialCost === null ? 'Cretas 未关联到领退料' : null };
  const lab = av ? av.labor : { value: o.laborCost, source: o.laborCost === null ? null : sup.some(x => /人工费/.test(x)) ? 'supplement' : 'cretas', note: o.laborCost === null ? 'Cretas 主快照缺人工' : null };
  const extra = av && av.outsourceOther ? av.outsourceOther.value : 0, blocked = av ? av.blockedReason : null;
  const total = !blocked && mat.value !== null && lab.value !== null ? r2(mat.value + lab.value + extra) : null;
  const per = v => v === null || v === undefined || blocked || main === null || !(good > 0) ? null : v * main / good;
  const firstIn = w.inputKg == null ? null : Number(w.inputKg);
  return {
    source: 'cretas-primary', complete: good !== null, incomplete, basis, routeFinal, route, records: w.records || null, goodKg: good, inputKg: firstIn == null ? null : r2(firstIn), defectKg: w.defectKg == null ? null : r2(Number(w.defectKg)),
    yieldRate: good !== null && firstIn > 0 ? good / firstIn : null, firstProcess: aFirst ? { code: aFirst.code, name: aFirst.name, weighed: aFirst.weighed } : (route[0] ? { ...route[0], weighed: procs.some(p => p.code === route[0].code) } : null), missing: w.missing || null, byproducts: by, byproductKg: byKg, finalProcess: finalProc ? { code: finalProc.code, name: finalProc.name } : null, processes: procs, startAt: w.startAt || null, endAt: w.endAt || null, lastAt: w.lastAt || null,
    rule: { mode: rule.mode, mainShare: main === null ? null : r2(main * 100), text: byproductRuleText(rule) }, total, mainCost: total === null || main === null || good === null ? null : r2(total * main), byproductCost: total === null || main === null || good === null ? null : r2(total * (1 - main)),
    unitCost: per(total), materialUnit: per(mat.value), laborUnit: per(lab.value), blockedReason: blocked || null,
    note: incomplete || (good > 0 ? null : '完工工序称重良品为 0，不能算每 kg 成本'),
    compare: { blacklake: bl.available ? { value: bl.output.blacklake, unit: bl.output.unitBlacklake } : null, cretasReport: { value: o.finishedGood, unit: o.unit } },
    adoptedView: av ? null : { material: mat, labor: lab, outsourceOther: null, output: null, total, unitCost: null, label: total === null ? '—' : moneyLabel(new Set([mat.source, lab.source].filter(Boolean))), blockedReason: null }
  };
}
function applyWeighing(o, wv) {
  // 采用值：产出量、单位成本改用 Cretas 称重（kg）；金额来源规则不变（R13 / R15）
  const base = o.blacklake.available ? o.blacklake.adoptedView : wv.adoptedView;
  const bk = wv.compare.blacklake, note = '称重良品（Cretas 主来源）' + (bk && bk.value != null ? '；黑湖实际产量 ' + bk.value + ' ' + (bk.unit || '') + ' 只作对照' : '') + '；' + wv.rule.text;
  return { ...base, output: { value: wv.goodKg, unit: 'kg', source: 'cretas-primary', note }, unitCost: wv.unitCost, weighed: true };
}
function build(source, state, filters = {}, session = '') {
  const weighing = readWeighing(session), bpRule = byproductRuleOf(state);
  const extras = readExtras();
  const all = orders(source, state).map(o => { const bl = blacklakeCost(o, null, extras, source.checkedAt); const neg = bl.available && bl.adoptedView && bl.adoptedView.blockedReason; const wv = weighView(o, bl, weighing.projects[o.id], bpRule, source), row = { ...o, readinessIssues: neg ? [...o.readinessIssues, bl.adoptedView.blockedReason.replace('无法计算：', '')] : o.readinessIssues, missing: o.missing, blacklake: bl, supplementNote: bl.available && bl.supplementState ? bl.supplementState : o.supplementNote, weigh: wv }; if (wv) { const { adoptedView: _x, ...rest } = wv; row.weigh = rest; row.adopted = wv.complete ? applyWeighing(row, wv) : (bl.available ? bl.adoptedView : null); } else row.adopted = bl.available ? bl.adoptedView : null; return row; });
  const dateContext = costFilters.range(all, filters), inRange = all.filter(r => costFilters.matchesDate(r, dateContext) && (!filters.unit || r.unit === filters.unit));
  const productKey = o => hash([o.productTypeId, o.productCode, o.product, o.unit, o.stage, o.currency]);
  const filterOptions = { products: [...new Map(all.map(o => [productKey(o), { key: productKey(o), label: o.product + ' · ' + (o.productCode || '编码待核对') + ' · ' + (o.unit || '单位待核对')}])).values()] };
  const groups = new Map();
  for (const o of inRange) { const key = productKey(o); let g = groups.get(key); if (!g) { g = { key, product: o.product, productCode: o.productCode, productTypeId: o.productTypeId, stage: o.stage, unit: o.unit, currency: o.currency, orders: [] }; groups.set(key, g); } g.orders.push(o); }
  let view = [...groups.values()].map(g => {
    const eligible = groupEligibleCostProjects(g.orders), samples = eligible.reduce((n, r) => n + r.sampleCount, 0), output = eligible.reduce((n, r) => n + r.finishedGood, 0), amount = eligible.reduce((n, r) => n + r.materialCost + r.laborCost, 0);
    const sum = (rows, k) => rows.some(o => o[k] !== null) ? rows.filter(o => o[k] !== null).reduce((n, o) => n + o[k], 0) : null;
    return { ...g, linkedPlanCount: new Set(g.orders.map(o => o.planId).filter(Boolean)).size, unlinkedBatchCount: 0, orderCount: g.orders.length, eligibleCount: samples, blockedCount: g.orders.length - samples,
      knownOutput: sum(g.orders, 'finishedGood'), outputPartial: g.orders.some(o => o.finishedGood === null), materialPartial: g.orders.some(o => o.materialCost === null), laborPartial: g.orders.some(o => o.laborCost === null),
      knownMaterialCost: sum(g.orders, 'materialCost'), knownLaborCost: sum(g.orders, 'laborCost'), knownDirectCost: sum(g.orders, 'directCost'), directPartial: g.orders.some(o => o.directCost === null), ...adoptedAgg(g.orders),
      weightedMaterialUnitCost: samples && output > 0 ? eligible.reduce((n, r) => n + r.materialCost, 0) / output : null, weightedLaborUnitCost: samples && output > 0 ? eligible.reduce((n, r) => n + r.laborCost, 0) / output : null, weightedUnitCost: samples && output > 0 ? amount / output : null,
      partial: samples < g.orders.length, missing: [...new Set(g.orders.flatMap(o => o.missing))],
      ...(() => { const bl = g.orders.filter(o => o.blacklake && o.blacklake.available && o.blacklake.unitCost.blacklake != null && !(o.blacklake.unitCost.reason && o.blacklake.unitCost.reason.code === 'UNIT')); const amt = bl.reduce((n, o) => n + o.blacklake.total.blacklake, 0), qty = bl.reduce((n, o) => n + o.blacklake.output.blacklake, 0); return { blCovered: bl.length, blWeightedUnitCost: qty > 0 ? amt / qty : null, blMaterialUnitCost: qty > 0 ? bl.reduce((n, o) => n + o.blacklake.items[0].blacklake, 0) / qty : null, blLaborUnitCost: qty > 0 ? bl.reduce((n, o) => n + o.blacklake.items[1].blacklake, 0) / qty : null }; })() };
  });
  view = view.filter(g => (!filters.scopeProductKey || g.key === filters.scopeProductKey) && costFilters.focus(g, filters.attention));
  const q = String(filters.keyword || '').trim().toLowerCase();
  if (q) view = view.filter(g => [g.product, g.productCode, ...g.orders.map(o => o.projectCode)].some(v => String(v || '').toLowerCase().includes(q)));
  const scopes = new Map();
  for (const p of view.filter(p => p.eligibleCount > 0)) { const k = JSON.stringify([p.currency, p.unit]); if (!scopes.has(k)) scopes.set(k, []); scopes.get(k).push(p); }
  const charts = [...scopes].map(([key, products]) => ({ key, label: products[0].currency + ' / ' + products[0].unit, keys: products.map(p => p.key), config: buildUnitCostChart(products.map(p => ({ ...p, displayIdentity: (p.productCode || '编码待核对') }))) }));
  return {
    factoryId: FACTORY, source: '六膳门已核验黑湖来源快照（只读）；补充资料单独留痕，不回写黑湖', dateContext, filterOptions,
    scopeHash: hash({ orders: view.flatMap(g => g.orders), filters }), version: state.version, weighing: { ready: weighing.ready, reason: weighing.reason || null, revision: weighing.revision, at: weighing.at, orders: all.filter(o => o.weigh && o.weigh.complete).length, pending: all.filter(o => o.weigh && !o.weigh.complete).length }, byproductRule: { ...bpRule, text: byproductRuleText(bpRule), modes: BYPRODUCT_MODES }, filters, charts, products: view,
    orderCount: view.reduce((n, g) => n + g.orderCount, 0), eligibleCount: view.reduce((n, g) => n + g.eligibleCount, 0),
    boundary: '已记录材料费＋人工费为直接成本；不是完整制造成本或利润；币种' + (state.currency ? '为 ' + state.currency.value + '（Cretas 补充）' : '待确认') + '，已取消工单不计入。按产品和单位隔离，缺失不计零；来源快照 ' + String(source.revision).slice(0, 12) + ' · 工单 ' + source.counts.project + '、任务 ' + source.counts.task + '、报工 ' + source.counts.output + '。',
    cancelledCount: source.costs.filter(c => String(c.status) === '30').length,
    blacklakeCost: extras ? (() => { const os = view.flatMap(g => g.orders), av = os.filter(o => o.blacklake.available), usable = av.filter(o => o.blacklake.adopted === 'blacklake'), cmp = usable.filter(o => o.blacklake.total.delta !== null); const reasons = {}; for (const o of usable) for (const it of o.blacklake.items) if (it.reason.code !== 'MATCH') reasons[it.field + '：' + it.reason.text.replace(/（.*$/, '')] = (reasons[it.field + '：' + it.reason.text.replace(/（.*$/, '')] || 0) + 1; return { revision: String(extras.revision).slice(0, 12), checkedAt: extras.checkedAt, orders: os.length, returned: av.length, withActual: usable.length, unitCostUsable: os.filter(o => o.blacklake.available && o.blacklake.unitCost.blacklake != null).length, totalMatch: cmp.filter(o => Math.abs(o.blacklake.total.delta) < 0.005).length, compared: cmp.length, bothZero: av.filter(o => !(o.blacklake.total.blacklake > 0) && o.blacklake.total.cretas === 0).length, reasons: Object.entries(reasons).sort((a, b) => b[1] - a[1]).map(([reason, count]) => ({ reason, count })) }; })() : null, analysisJob: null, sourceRevision: source.revision, sourceCheckedAt: source.checkedAt, currencySupplement: state.currency || null, wageRuleCount: (state.wageRules || []).filter(r => !r.revokedAt).length
  };
}

function validate(order, input) {
  if (input.sourceHash !== order.sourceHash) throw Object.assign(Error('源记录已变化，请重新加载后补充'), { status: 409 });
  if (typeof input.evidence !== 'string' || !input.evidence.trim()) throw Error('请填写核对依据');
  if (order.sourceLaborCost !== null && input.laborCost != null && input.laborCost !== '' && input.laborCost !== order.sourceLaborCost) throw Error('已有人工费直接复用，不能在补充入口改写');
  let materialCost = null;
  if (input.materialCost != null && input.materialCost !== '') {
    if (!(order.sourceMaterialCost === null || order.sourceMaterialCost === 0)) throw Error('黑湖已有材料费，以来源为准，不能在补充入口改写');
    if (!known(input.materialCost) || !Number.isSafeInteger(Math.round(input.materialCost * 100))) throw Error('材料费估值须为已核实的非负金额');
    materialCost = input.materialCost;
  }
  // 只补材料估值：工序栏全部未动时，允许单独保存材料费（来源原则：单项补充，不强迫一并补名单）
  const untouched = !Array.isArray(input.processes) || input.processes.every(x => !String(x.workerCodes || '').trim() && (x.personHours == null || order.processes.some(p => p.id === x.id && p.personHours === x.personHours)));
  if (materialCost !== null && untouched && (input.laborCost == null || input.laborCost === '' || order.sourceLaborCost !== null)) return { sourceHash: order.sourceHash, laborCost: order.sourceLaborCost, materialCost, processes: [], participants: null, evidence: String(input.evidence).trim().slice(0, 1000), at: new Date().toISOString(), materialOnly: true };
  const labor = order.sourceLaborCost ?? input.laborCost;
  if (!known(labor) || !Number.isSafeInteger(Math.round(labor * 100))) throw Error('人工费须为已核实的非负金额，零值也需明确填写');
  if (!order.processes.length) throw Error('本工单尚无确定的工序任务对应，不能分摊到虚构工序');
  if (!Array.isArray(input.processes) || input.processes.length !== order.processes.length) throw Error('请逐项核对当前工单的全部工序任务');
  const keySecret = secret(), workerKeys = new Set();
  const processes = order.processes.map(p => {
    const a = input.processes.find(x => x.id === p.id);
    if (!a || !known(a.personHours) || a.personHours === 0 && a.zeroConfirmed !== true) throw Error('有效人时须明确填写；零人时需单独确认');
    const codes = String(a.workerCodes || '').split(/[,，\s]+/).filter(Boolean);
    if (codes.length > 200 || codes.some(c => c.length > 64)) throw Error('每道工序最多200个人员代码，每个不超过64字');
    if (!codes.length && !(a.personHours === 0 && a.zeroConfirmed === true)) throw Error('请填写实际参与人员代码，不能用报工人代替');
    const keys = codes.map(code => crypto.createHmac('sha256', keySecret).update(FACTORY + '|' + code).digest('hex'));
    keys.forEach(k => workerKeys.add(k));
    if (p.personHours !== null && p.source === SOURCE_PROCESS && Math.abs(p.personHours - a.personHours) > 0.000001) throw Error('已有来源工时直接复用，不能在补充入口改写（工序 ' + p.name + '）');
    return { id: p.id, personHours: a.personHours, workerKeys: keys, participants: new Set(keys).size, rosterComplete: keys.length > 0, zeroConfirmed: a.zeroConfirmed === true };
  });
  return { sourceHash: order.sourceHash, laborCost: labor, materialCost, processes, participants: processes.every(p => p.rosterComplete) ? workerKeys.size : null, evidence: String(input.evidence).trim().slice(0, 1000), at: new Date().toISOString() };
}

// 同一工单的补充按字段合并：只补材料时保留已有人工/名单，只补人工时保留已有材料估值
function merge(prev, next) {
  if (!prev || prev.sourceHash !== next.sourceHash) return next;
  if (next.materialOnly) return { ...prev, materialCost: next.materialCost, evidence: next.evidence, at: next.at };
  return { ...next, materialCost: next.materialCost ?? prev.materialCost ?? null };
}

function templateBuffer(report) {
  const rows = report.products.flatMap(p => p.orders.flatMap(o => o.processes.map(s => ({ 工单ID: o.id, 工单号: o.projectCode, 任务ID: s.id, 工序: s.name, 源版本: o.sourceHash, 人工费: o.laborCost ?? '', 材料费估值补充: (o.sourceMaterialCost === null || o.sourceMaterialCost === 0) ? (o.supplementFields?.includes('材料费') ? o.materialCost : '') : '来源已有', 有效人时: s.personHours ?? '', 人员代码: '', 零人时确认: s.personHours === 0 ? '是' : '否', 核对依据: o.supplementEvidence || '' }))));
  const book = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(book, XLSX.utils.json_to_sheet(rows.length ? rows : [{ 工单ID: '', 工单号: '', 任务ID: '', 工序: '', 源版本: '', 人工费: '', 材料费估值补充: '', 有效人时: '', 人员代码: '', 零人时确认: '', 核对依据: '' }]), '成本补充');
  return XLSX.write(book, { type: 'buffer', bookType: 'xlsx' });
}

async function handle({ req, res, u, method, send, rawBody }) {
  const sub = u.pathname.replace(/^\/__lsm\/product-cost/, '');
  try {
    const session = req.headers['x-acceptance-session'] || u.searchParams.get('acceptanceSession') || '';
    const source = readSource(), state = readState(session);
    const f = k => u.searchParams.get(k);
    const filters = { keyword: f('keyword') || '', unit: f('unit') || '', startDate: f('startDate') || '', endDate: f('endDate') || '', ...(u.searchParams.has('dateMode') ? { dateMode: f('dateMode') } : {}), ...(u.searchParams.has('attention') ? { attention: f('attention') } : {}), ...(u.searchParams.has('scopeProductKey') ? { scopeProductKey: f('scopeProductKey') } : {}) };
    costFilters.validate(filters);
    const report = build(source, state, filters, session);
    if (method === 'GET' && sub === '') { send(res, 200, { success: true, data: report }); return true; }
    // 第八批：BOM 调价建议（只读计算，不写任何价格）
    if (method === 'GET' && sub === '/bom-price') { send(res, 200, { success: true, data: require('../src/cost/bom-price-lsm.cjs').compute({ source, report }) }); return true; }
    if (method === 'GET' && sub === '/wage-rules') { const procs = new Map((source.resources.process || []).map(p => [String(p.id), p.name])), all = (source.resources.output || []).map(r => reportLaborRow(state.wageRules, r)); send(res, 200, { success: true, data: { version: state.version, prices: Object.entries(state.valuationPrices || {}).map(([key, p]) => ({ key, ...p })), rules: (state.wageRules || []).map(r => ({ ...r, processName: procs.get(String(r.processId)) || null })), processes: [...procs].map(([id, name]) => ({ id, name })), coverage: { reports: all.length, matched: all.filter(x => x.amount !== null).length, reasons: Object.entries(all.filter(x => x.amount === null).reduce((m, x) => (m[x.reason] = (m[x.reason] || 0) + 1, m), {})).map(([reason, count]) => ({ reason, count })) } } }); return true; }
    if (method === 'GET' && sub === '/report-labor') { const pid = f('project'), ids = new Set(String(f('reports') || '').split(',').filter(Boolean)); const rows = (source.resources.output || []).filter(r => (pid && String(r.projectId) === pid) || ids.has(String(r.id))).map(r => reportLaborRow(state.wageRules, r)); send(res, 200, { success: true, data: { version: state.version, note: '工价规则为 Cretas 补充；黑湖任务累计工资有值时以黑湖为准，本结果只作对照或补缺', rows } }); return true; }
    if (method === 'GET' && sub === '/valuation') { const asOf = f('asOf'); if (asOf && !/^\d{4}-\d{2}-\d{2}$/.test(asOf)) throw Error('asOf 须为 YYYY-MM-DD'); send(res, 200, { success: true, data: valuation(source, state, asOf, String(f('codes') || '').split(',').map(x => x.trim()).filter(Boolean)) }); return true; }
    if (method === 'GET' && sub === '/template') { send(res, 200, templateBuffer(report), 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', { 'Content-Disposition': 'attachment; filename="lsm-cost-supplement.xlsx"' }); return true; }
    if (method !== 'POST' || req.headers['x-prototype-user-action'] !== 'local-edit') throw Object.assign(Error('请通过明确的分析操作提交'), { status: 403 });
    const input = JSON.parse(rawBody || '{}');
    if (readState(session).version !== state.version || input.expectedVersion !== state.version) throw Object.assign(Error('资料已被更新，请刷新后再保存'), { status: 409 });
    if (sub === '/analyze-all') throw Object.assign(Error('六膳门工作区暂未接入批量逐单分析；请在单个工单中用右下角悬浮解读。'), { status: 501 });
    const everything = build(source, state, {}, session).products.flatMap(p => p.orders), find = id => { const row = everything.find(o => o.id === String(id)); if (!row) throw Error('请选择当前六膳门来源中存在的工单'); return row; };
    if (sub === '/supplement') { const row = find(input.orderId); state.supplements[row.id] = merge(state.supplements[row.id], validate(row, input)); }
    else if (sub === '/supplement-revoke') { const row = find(input.orderId); if (!state.supplements[row.id]) throw Error('本工单没有可撤销的补充'); if (!String(input.reason || '').trim()) throw Error('撤销必须填写原因'); state.audit.push({ at: new Date().toISOString(), operation: 'revoke-snapshot', orderId: row.id, reason: String(input.reason).trim().slice(0, 300), revoked: { ...state.supplements[row.id], processes: (state.supplements[row.id].processes || []).map(p => ({ id: p.id, personHours: p.personHours, participants: p.participants })) } }); delete state.supplements[row.id]; }
    else if (sub === '/wage-rule') {
      const procIds = new Set((source.resources.process || []).map(p => String(p.id))), codes = new Set((source.resources.product || []).map(p => p.productCode));
      if (!procIds.has(String(input.processId))) throw Error('请选择来源中存在的工序');
      if (input.productCode && !codes.has(input.productCode)) throw Error('产品编码在来源中不存在');
      if (!['piece', 'time'].includes(input.mode)) throw Error('请选择计件或计时');
      if (!known(input.rate) || Math.abs(input.rate * 100 - Math.round(input.rate * 100)) > 1e-6) throw Error('单价须为非负金额，最多两位小数');
      if (input.mode === 'piece' && !String(input.unit || '').trim()) throw Error('计件规则须写明计件单位（与报工单位一致才计算）');
      if (input.effectiveFrom && !/^\d{4}-\d{2}-\d{2}$/.test(input.effectiveFrom)) throw Error('生效日期格式应为 YYYY-MM-DD');
      if (!String(input.evidence || '').trim()) throw Error('请填写工价依据（如工资制度、班组确认）');
      state.wageRules = state.wageRules || [];
      state.wageRules.push({ id: 'WR-' + crypto.randomUUID().slice(0, 8), processId: String(input.processId), productCode: input.productCode || null, mode: input.mode, rate: input.rate, unit: input.mode === 'piece' ? String(input.unit).trim() : '小时', effectiveFrom: input.effectiveFrom || null, evidence: String(input.evidence).trim().slice(0, 300), at: new Date().toISOString() });
    }
    else if (sub === '/wage-rule-revoke') { const r = (state.wageRules || []).find(r => r.id === input.ruleId && !r.revokedAt); if (!r) throw Error('没有可撤销的工价规则'); if (!String(input.reason || '').trim()) throw Error('撤销必须填写原因'); r.revokedAt = new Date().toISOString(); r.revokeReason = String(input.reason).trim().slice(0, 300); }
    else if (sub === '/valuation-price') {
      if (!String(input.productCode || '').trim() || !String(input.unit || '').trim()) throw Error('请填写物料编码和单位');
      if (!known(input.unitPrice) || input.unitPrice <= 0) throw Error('补充单价须为正数（零价不能当作估值）');
      if (!String(input.evidence || '').trim()) throw Error('请填写估值依据');
      const k = input.productCode.trim() + '|' + input.unit.trim();
      state.valuationPrices = state.valuationPrices || {};
      state.valuationPrices[k] = { productCode: input.productCode.trim(), unit: input.unit.trim(), name: input.name || null, unitPrice: input.unitPrice, effectiveFrom: input.effectiveFrom || null, evidence: String(input.evidence).trim().slice(0, 300), at: new Date().toISOString() };
    }
    else if (sub === '/valuation-price-revoke') { const p = (state.valuationPrices || {})[input.key]; if (!p || p.revokedAt) throw Error('没有可撤销的补充单价'); if (!String(input.reason || '').trim()) throw Error('撤销必须填写原因'); p.revokedAt = new Date().toISOString(); p.revokeReason = String(input.reason).trim().slice(0, 300); }
    else if (sub === '/currency') { if (!['CNY', 'USD', 'EUR'].includes(input.value)) throw Error('请选择币种'); if (!String(input.evidence || '').trim()) throw Error('请填写核实依据'); state.currency = { value: input.value, evidence: String(input.evidence).trim().slice(0, 500), at: new Date().toISOString() }; }
    else if (sub === '/byproduct-rule') { if (!BYPRODUCT_MODES[input.mode]) throw Error('请选择副产品分摊口径'); let share = null; if (input.mode === 'ratio') { share = Number(input.mainShare); if (!Number.isFinite(share) || share <= 0 || share > 100) throw Error('主产品承担比例须在 0 到 100 之间（不含 0）'); share = Math.round(share * 100) / 100; } const prev = byproductRuleOf(state); state.byproductRule = { mode: input.mode, mainShare: share, at: new Date().toISOString(), previous: { mode: prev.mode, mainShare: prev.mainShare } }; }
    else if (sub === '/followup') { const row = find(input.orderId); if (!['OPEN', 'MISSING', 'CHECKED'].includes(input.status) || !String(input.note || '').trim()) throw Error('请选择跟进状态并填写说明'); state.followups[row.id] = { status: input.status, note: String(input.note).trim().slice(0, 1000), at: new Date().toISOString(), sourceHash: row.sourceHash }; }
    else if (sub === '/import-preview' || sub === '/import-confirm') {
      if (typeof input.base64 !== 'string' || !/^[A-Za-z0-9+/=]+$/.test(input.base64)) throw Error('请选择有效Excel文件');
      const bytes = Buffer.from(input.base64, 'base64');
      if (bytes.length > 5000000 || !['504b0304', 'd0cf11e0'].includes(bytes.subarray(0, 4).toString('hex'))) throw Error('请选择5MB以内的Excel工作簿');
      const book = XLSX.read(bytes, { type: 'buffer', cellFormula: true, sheetRows: 2002 });
      if (book.SheetNames.length !== 1) throw Error('请使用单工作表模板');
      const sheet = book.Sheets[book.SheetNames[0]];
      if (Object.entries(sheet).some(([k, v]) => !k.startsWith('!') && v.f)) throw Error('导入不接受公式单元格');
      const entries = XLSX.utils.sheet_to_json(sheet, { defval: '' });
      if (!entries.length || entries.length > 2000) throw Error('请导入1至2000行');
      const groups = new Map();
      for (const r of entries) { const id = String(r['工单ID']); if (!groups.has(id)) groups.set(id, []); groups.get(id).push(r); }
      const changes = {};
      for (const [id, rs] of groups) {
        const row = find(id), number0 = v => v === '' ? null : typeof v === 'number' ? v : typeof v === 'string' && /^\d+(?:\.\d+)?$/.test(v.trim()) ? Number(v) : NaN, number = v => v === '来源已有' ? null : number0(v), first = rs[0];
        if (rs.some(r => r['工单号'] !== row.projectCode || r['源版本'] !== row.sourceHash || number(r['人工费']) !== number(first['人工费']) || number(r['材料费估值补充'] ?? '') !== number(first['材料费估值补充'] ?? '') || r['核对依据'] !== first['核对依据'])) throw Error('同一工单的单号、版本、金额和依据必须一致（' + id + '）');
        if (rs.some(r => !row.processes.some(p => p.id === String(r['任务ID']) && p.name === r['工序']))) throw Error('任务ID与工序名称不对应（工单 ' + row.projectCode + '）');
        changes[id] = merge(state.supplements[id], validate(row, { sourceHash: first['源版本'], laborCost: number(first['人工费']), materialCost: number(first['材料费估值补充'] ?? ''), evidence: first['核对依据'], processes: rs.map(r => ({ id: String(r['任务ID']), personHours: number(r['有效人时']), workerCodes: r['人员代码'], zeroConfirmed: r['零人时确认'] === '是' })) }));
      }
      const digest = hash({ base64: input.base64, version: state.version, scopeHash: build(source, state, {}, session).scopeHash });
      if (sub === '/import-preview') {
        const after = build(source, { ...state, supplements: { ...state.supplements, ...changes } }, {}, session).products.flatMap(p => p.orders), money = v => v === null ? '未提供' : v.toFixed(2);
        const describe = o => '材料 ' + money(o.materialCost) + ' · 人工 ' + money(o.laborCost) + ' · 人数 ' + (o.participants ?? '未提供') + ' · 缺 ' + (o.missing.join('、') || '无');
        const diff = Object.keys(changes).map(id => { const b = everything.find(o => o.id === id), a = after.find(o => o.id === id); return { orderId: id, projectCode: b.projectCode, before: describe(b), after: describe(a) }; });
        send(res, 200, { success: true, data: { digest, rows: entries.length, batches: Object.keys(changes).length, expectedVersion: state.version, changes: diff } }); return true;
      }
      if (input.digest !== digest) throw Object.assign(Error('预检依据已变化，请重新预检'), { status: 409 });
      Object.assign(state.supplements, changes);
    } else throw Object.assign(Error('操作未开放'), { status: 404 });
    state.version++; state.audit.push({ at: new Date().toISOString(), operation: sub, version: state.version, orderId: input.orderId || null });
    writeState(state, session);
    send(res, 200, { success: true, data: build(source, state, filters, session), prototypeLocalOnly: true });
    return true;
  } catch (e) { send(res, e.status || 422, { success: false, message: e.message }); return true; }
}

module.exports = { _setWeighingProvider: fn => { weighingProvider = fn; }, build, orders, validate, readSource, readState, handle, STATE_FILE, reportLaborRow, matchRule, valuation };
