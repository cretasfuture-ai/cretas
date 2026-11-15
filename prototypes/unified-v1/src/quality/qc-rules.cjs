'use strict';
// 质量检验确定性规则：阶段定义、单项与总判定、处置约束、解读事实与核对代码。代码定口径，模型只选代码。
const STAGES = {
  incoming: { label: '来料', objectTypes: ['material'], objectLabel: '原材料', hint: '供应商到货验收：证件、外观、到货温度等，对象为原材料。' },
  production: { label: '生产', objectTypes: ['project'], objectLabel: '工单 / 批次', hint: '工序过程检验：对象为执行中的工单（味美为计划/批次）。' },
  warehousing: { label: '入库', objectTypes: ['material', 'product'], objectLabel: '原材料 / 成品', hint: '入库前检验：存储条件、包装、保质期等。六膳门可挂到 C 的入库批次（采购入库或生产入库）。' },
  shipping: { label: '发货', objectTypes: ['product'], objectLabel: '成品', hint: '成品出厂检验：感官、包装标签、温度等。' },
  other: { label: '其他', objectTypes: ['material', 'product', 'project', 'other'], objectLabel: '任意对象', hint: '不属于以上阶段的专项，如留样复检。' }
};
const VERDICT = { PASS: '合格', FAIL: '不合格', INCOMPLETE: '未完成' };
const DISPOSALS = { PASS: ['放行', '复检'], FAIL: ['让步接收', '拒收', '返工', '复检'], INCOMPLETE: [] };
const disposals = v => DISPOSALS[v] || [];
const f2 = n => Number(n).toFixed(2);
function range(it) { if (it.kind === 'option') return '合格选项：' + (it.passOptions || []).join('、'); if (it.kind === 'judge') return '判定项'; if (it.lower != null && it.upper != null) return f2(it.lower) + ' ~ ' + f2(it.upper) + ' ' + it.unit; if (it.lower != null) return '≥ ' + f2(it.lower) + ' ' + it.unit; return '≤ ' + f2(it.upper) + ' ' + it.unit; }
// 数值项不合格说明（BATCH6-AUDIT E-1）：先写实测值，再写超出多少，避免把差值读成实测值。例：「中心温度实测 -8.00 ℃，高出上限 7.00 ℃」
function itemResult(it, value) {
  if (value == null || String(value).trim() === '') return { result: 'MISSING' };
  if (it.kind === 'judge') return { result: value === '合格' ? 'PASS' : 'FAIL' };
  // 选项项：按标准里勾选的「合格选项」判定（BL-borrow 第 18 条）
  if (it.kind === 'option') return (it.passOptions || []).includes(value) ? { result: 'PASS' } : { result: 'FAIL', deviation: '选项「' + value + '」不在合格选项内' };
  const v = Number(value);
  if (it.lower != null && v < it.lower) return { result: 'FAIL', deviation: '实测 ' + f2(v) + ' ' + it.unit + '，低于下限 ' + f2(it.lower - v) + ' ' + it.unit };
  if (it.upper != null && v > it.upper) return { result: 'FAIL', deviation: '实测 ' + f2(v) + ' ' + it.unit + '，高出上限 ' + f2(v - it.upper) + ' ' + it.unit };
  return { result: 'PASS' };
}
function judge(items, results) {
  const fails = [], missing = [], rows = [];
  for (const it of items) {
    const r = (results || []).find(x => x.itemId === it.id), ir = itemResult(it, r ? r.value : null);
    rows.push({ ...it, value: r ? r.value : null, ...ir });
    if (ir.result === 'FAIL') fails.push({ name: it.name, deviation: ir.deviation || '判定不合格' });
    if (ir.result === 'MISSING' && it.required) missing.push({ name: it.name });
  }
  const overall = fails.length ? 'FAIL' : missing.length ? 'INCOMPLETE' : 'PASS';
  return { overall, fails, missing, rows };
}
// 核对代码表：代码 → 渲染文字、找谁；适用条件由代码判断
const CODES = {
  FILL_MISSING: { text: '补录必检缺项后再判定', who: '品控', when: f => f.missing.length > 0 },
  RECHECK_SAMPLE: { text: '复检留样，确认偏离是否稳定', who: '品控', when: f => f.verdict === 'FAIL' },
  ISOLATE_LOT: { text: '先隔离该对象，等处置结论后再放行', who: '仓库', when: f => f.verdict === 'FAIL' && ['incoming', 'warehousing', 'shipping'].includes(f.stage) },
  CHECK_SUPPLIER_CERT: { text: '核对供应商证件与到货单', who: '采购', when: f => f.verdict === 'FAIL' && f.stage === 'incoming' },
  NOTIFY_PURCHASE: { text: '通知采购与供应商沟通退换或扣款', who: '采购', when: f => f.verdict === 'FAIL' && ['incoming', 'warehousing'].includes(f.stage) },
  CHECK_STORAGE: { text: '核对运输与存储条件（温度、包装）', who: '仓库', when: f => f.verdict === 'FAIL' && ['incoming', 'warehousing', 'shipping'].includes(f.stage) },
  CHECK_PROCESS_PARAM: { text: '核对工序参数与报工记录', who: '生产主管', when: f => f.verdict === 'FAIL' && f.stage === 'production' },
  HOLD_SHIPMENT: { text: '暂缓发货，确认返工或重新包装', who: '仓库', when: f => f.verdict === 'FAIL' && f.stage === 'shipping' },
  CONFIRM_STANDARD: { text: '确认检验标准是否适用于该对象', who: '质量经理', when: f => f.verdict === 'FAIL' || f.disposal === '让步接收' },
  NO_ACTION: { text: '判定合格，无需额外核对', who: '', when: f => f.verdict === 'PASS' }
};
function inspectionFacts(q, std, sv) {
  const items = sv ? sv.items : [];
  const j = judge(items, q.results);
  const facts = {
    no: q.no, kind: q.kind || 'routine', processCode: q.processCode || '', stage: q.stage, stageLabel: STAGES[q.stage].label, status: q.status, verdict: j.overall, verdictLabel: VERDICT[j.overall],
    object: (q.objectRef ? (q.objectRef.code + ' ' + q.objectRef.name).trim() + (q.objectRef.verified ? '（' + q.objectRef.source + '）' : '（未核实引用）') : '未填写'),
    standard: sv ? sv.name + ' v' + sv.v : '标准缺失', disposal: q.disposal || '', disposalReason: q.disposalReason || '',
    rows: j.rows.map(r => ({ name: r.name, standard: range(r) + (r.target != null ? '（标准值 ' + f2(r.target) + ' ' + r.unit + '）' : ''), value: r.value == null ? '未填写' : (r.kind === 'numeric' ? f2(r.value) + ' ' + r.unit : r.value), result: { PASS: '合格', FAIL: '不合格', MISSING: '未填写' }[r.result], deviation: r.deviation || '' })),
    fails: j.fails, missing: j.missing, related: (q.related || []).map(r => r.code || r.id), rev: q.rev
  };
  facts.allowed = Object.keys(CODES).filter(k => CODES[k].when(facts));
  facts.defaultCodes = facts.verdict === 'PASS' ? ['NO_ACTION'] : facts.missing.length && facts.verdict !== 'FAIL' ? ['FILL_MISSING'] : facts.allowed.filter(k => k !== 'NO_ACTION').slice(0, 2);
  return facts;
}
function renderInspection(f, codes) {
  const head = f.verdict === 'PASS' ? f.stageLabel + '检验 ' + f.no + ' 判定合格（' + f.rows.length + ' 项）。'
    : f.verdict === 'FAIL' ? f.stageLabel + '检验 ' + f.no + ' 判定不合格：' + f.fails.map(x => x.name + (/^实测/.test(x.deviation) ? '' : ' ') + x.deviation).join('；') + '。'
      : f.stageLabel + '检验 ' + f.no + ' 未完成，缺少：' + f.missing.map(x => x.name).join('、') + '。';
  return { conclusion: head, actions: codes.map(c => ({ code: c, text: CODES[c].text, who: CODES[c].who })) };
}
module.exports = { STAGES, VERDICT, DISPOSALS, disposals, range, itemResult, judge, CODES, inspectionFacts, renderInspection, f2 };
