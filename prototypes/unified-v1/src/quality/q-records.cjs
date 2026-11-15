'use strict';
// 第八批（DECISIONS 第十七节，R37 / R41）：质量闭环补充记录。
// 黑湖没有 CCP、退回、废弃、召回、标签拍检这类数据，这里只存 Cretas 补充记录（来源「Cretas 补充」），不回写黑湖，不拿别的数据顶替。
// 类型：ccp 监控记录、return 质检退回单、disposal 废弃处理、label 包装标签拍检、recall 批次召回。
// 判定由代码按限值和检查项计算；记录不能改，只能关闭（有处理结果）或作废（有原因）。
const TYPES = {
  ccp: { label: 'CCP 监控记录', prefix: 'CCP', life: 'record' },
  return: { label: '质检退回单', prefix: 'QR', life: 'flow' },
  disposal: { label: '废弃处理', prefix: 'QD', life: 'flow' },
  label: { label: '包装标签拍检', prefix: 'LQ', life: 'record' },
  recall: { label: '批次召回', prefix: 'RC', life: 'flow' }
};
const DISPOSAL_METHODS = ['报废', '销毁', '退供应商', '回炉返工'];
const RECALL_LEVELS = ['一级', '二级', '三级'];
const LABEL_ITEMS = ['品名', '配料表', '保质期', '生产日期', '批号', '净含量', '储存条件'];
const LABEL_VALUES = ['合格', '不合格', '不适用'];
const s = (v, max) => typeof v === 'string' ? v.trim().slice(0, max || 200) : '';
const num2 = (v, label, { min } = {}) => {
  const t = v == null ? '' : String(v).trim(); if (t === '') return null;
  if (!/^-?\d+(\.\d{1,2})?$/.test(t)) throw Object.assign(Error('「' + label + '」必须是数字，最多两位小数'), { status: 400 });
  const n = Number(t); if (min != null && n < min) throw Object.assign(Error('「' + label + '」不能小于 ' + min), { status: 400 }); return n;
};
const bad = (m, status) => Object.assign(Error(m), { status: status || 400 });
const need = (v, label) => { if (!v) throw bad('请填写' + label); return v; };
// 时间只存北京时间本地值「YYYY-MM-DDTHH:mm」，不许晚于现在（北京时间）
function bjNow() { return new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 16); }
function when(v, label) {
  const t = s(v, 20); if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(t)) throw bad('请填写' + label + '（日期和时间）');
  if (t > bjNow()) throw bad(label + '不能晚于现在'); return t;
}
const ensure = st => { st.records = st.records || []; return st.records; };

// ctx: { processes:[{code,name}], batchFind(no)->batch|null, batchesAvailable, batchesReason, lsm:boolean, inspections }
function clean(type, b, ctx) {
  const f = {};
  if (type === 'ccp') {
    const pc = s(b.processCode, 40), pn = s(b.processName, 60);
    if (ctx.lsm) { const hit = ctx.processes.find(p => p.code === pc); if (!hit) throw bad('工序不在黑湖工序目录中，请从列表选择'); f.processCode = hit.code; f.processName = hit.name; }
    else { f.processCode = pc; f.processName = need(pn || pc, '工序'); }
    f.point = need(s(b.point, 60), '监控点（例如：中心温度）');
    f.lower = num2(b.lower, '下限'); f.upper = num2(b.upper, '上限');
    if (f.lower == null && f.upper == null) throw bad('关键限值至少填下限或上限之一');
    if (f.lower != null && f.upper != null && f.lower > f.upper) throw bad('下限不能大于上限');
    f.value = num2(b.value, '实测值'); if (f.value == null) throw bad('请填写实测值');
    f.unit = s(b.unit, 20); f.projectCode = s(b.projectCode, 60);
    f.monitoredAt = when(b.monitoredAt, '监控时间'); f.monitor = need(s(b.monitor, 40), '监控人');
    f.note = s(b.note, 200);
    const out = (f.lower != null && f.value < f.lower) || (f.upper != null && f.value > f.upper);
    f.result = out ? 'DEVIATION' : 'PASS';
    return f;
  }
  if (type === 'return') {
    f.object = need(s(b.object, 80), '退回对象（工单、批次或物料）');
    f.inspectionNo = s(b.inspectionNo, 40);
    if (f.inspectionNo && !(ctx.inspections || []).some(q => q.no === f.inspectionNo && q.status !== 'VOID')) throw bad('检验单 ' + f.inspectionNo + ' 不存在或已作废');
    f.reason = need(s(b.reason, 200), '退回原因');
    f.qty = num2(b.qty, '退回数量', { min: 0 }); if (f.qty == null || f.qty <= 0) throw bad('退回数量必须大于 0');
    f.unit = need(s(b.unit, 20), '数量单位'); f.returnTo = need(s(b.returnTo, 60), '退回给（工序或供应商）');
    return f;
  }
  if (type === 'disposal') {
    f.object = need(s(b.object, 80), '废弃对象');
    f.batchNo = s(b.batchNo, 64);
    if (f.batchNo) { if (!ctx.batchesAvailable) throw bad(ctx.batchesReason || 'C 入库批次接口未就绪，不能挂批次'); if (!ctx.batchFind(f.batchNo)) throw bad('入库批次 ' + f.batchNo + ' 不存在（以 C 入库批次为准）'); }
    f.inspectionNo = s(b.inspectionNo, 40);
    if (f.inspectionNo && !(ctx.inspections || []).some(q => q.no === f.inspectionNo && q.status !== 'VOID')) throw bad('检验单 ' + f.inspectionNo + ' 不存在或已作废');
    f.qty = num2(b.qty, '废弃数量', { min: 0 }); if (f.qty == null || f.qty <= 0) throw bad('废弃数量必须大于 0');
    f.unit = need(s(b.unit, 20), '数量单位');
    if (f.batchNo) { const hit = ctx.batchFind(f.batchNo); f.productName = hit.productName || ''; if (hit.unit && hit.unit.toLowerCase() !== f.unit.toLowerCase()) throw bad('单位要和批次单位一致：批次 ' + f.batchNo + ' 的单位是 ' + hit.unit); }
    f.method = s(b.method, 20); if (!DISPOSAL_METHODS.includes(f.method)) throw bad('请选择废弃方式');
    f.reason = need(s(b.reason, 200), '废弃原因');
    return f;
  }
  if (type === 'label') {
    f.product = need(s(b.product, 80), '产品'); f.batchNo = s(b.batchNo, 64);
    if (f.batchNo && ctx.batchesAvailable && !ctx.batchFind(f.batchNo)) throw bad('入库批次 ' + f.batchNo + ' 不存在（以 C 入库批次为准）');
    const checks = {}; let judged = 0, fail = [];
    for (const k of LABEL_ITEMS) { const v = s((b.checks || {})[k], 10); if (!LABEL_VALUES.includes(v)) throw bad('请选择「' + k + '」的结果（合格、不合格或不适用）'); checks[k] = v; if (v !== '不适用') judged++; if (v === '不合格') fail.push(k); }
    if (!judged) throw bad('七项检查都是不适用，没有可判定的内容');
    f.checks = checks; f.failItems = fail; f.result = fail.length ? 'FAIL' : 'PASS';
    f.photoNote = s(b.photoNote, 200); f.inspector = need(s(b.inspector, 40), '检查人'); f.checkedAt = when(b.checkedAt, '检查时间');
    return f;
  }
  if (type === 'recall') {
    if (!ctx.batchesAvailable) throw bad(ctx.batchesReason || 'C 入库批次接口未就绪，不能发起召回');
    f.batchNo = need(s(b.batchNo, 64), '入库批次'); const hit = ctx.batchFind(f.batchNo); if (!hit) throw bad('入库批次 ' + f.batchNo + ' 不存在（以 C 入库批次为准）');
    f.batch = { productCode: hit.productCode, productName: hit.productName, productionDate: hit.productionDate, expiryDate: hit.expiryDate, qty: hit.qty, unit: hit.unit, kind: hit.kind };
    f.level = s(b.level, 10); if (!RECALL_LEVELS.includes(f.level)) throw bad('请选择召回等级');
    f.reason = need(s(b.reason, 200), '召回原因'); f.scope = s(b.scope, 200);
    return f;
  }
  throw bad('记录类型不正确');
}

function save(st, b, ctx, helpers) {
  const type = s(b.type, 20); if (!TYPES[type]) throw bad('记录类型不正确');
  const f = clean(type, b.fields || {}, ctx);
  const at = helpers.now(), by = helpers.actorOf(b), life = TYPES[type].life;
  if (type === 'recall' && ensure(st).some(r => r.type === 'recall' && r.status === 'OPEN' && r.fields.batchNo === f.batchNo)) throw bad('批次 ' + f.batchNo + ' 已有进行中的召回，请先关闭或作废');
  const r = { id: 'QL-' + helpers.rand(), no: helpers.nextNo(st, TYPES[type].prefix), type, status: life === 'record' ? 'RECORDED' : 'OPEN', source: 'Cretas 补充', fields: f, createdAt: at, createdBy: by, updatedAt: at, log: [{ at, by, action: '新建', detail: '' }] };
  ensure(st).push(r); return r;
}
function find(st, id) { const r = ensure(st).find(x => x.id === id); if (!r) throw bad('记录不存在', 404); return r; }
function close(st, b, helpers) {
  const r = find(st, b.id); if (r.status !== 'OPEN') throw bad('只有进行中的记录可以关闭');
  const result = s(b.result, 200); if (!result) throw bad('关闭必须填写处理结果');
  const at = helpers.now(), by = helpers.actorOf(b);
  r.status = 'CLOSED'; r.closeResult = result; r.closedAt = at; r.closedBy = by; r.updatedAt = at; r.log.push({ at, by, action: '关闭', detail: result }); return r;
}
function voidRec(st, b, helpers) {
  const r = find(st, b.id); if (r.status === 'VOID') throw bad('已作废');
  const reason = s(b.reason, 200); if (!reason) throw bad('作废必须填写原因');
  const at = helpers.now(), by = helpers.actorOf(b);
  r.log.push({ at, by, action: '作废', detail: reason, before: r.status }); r.status = 'VOID'; r.voidReason = reason; r.updatedAt = at; return r;
}
// 召回中的批次扣留：供 batches.holds 合并（C 的先到期先出跳过）
function recallHolds(st) {
  return (st.records || []).filter(r => r.type === 'recall' && r.status === 'OPEN').map(r => ({ batchNo: r.fields.batchNo, status: 'HOLD', label: '召回中（' + r.fields.level + '）', inspectionNo: r.no, inspectionId: '', stage: 'recall', productCode: r.fields.batch.productCode, reason: r.fields.reason, since: r.createdAt, recordNo: r.no }));
}
module.exports = { TYPES, DISPOSAL_METHODS, RECALL_LEVELS, LABEL_ITEMS, LABEL_VALUES, save, close, voidRec, recallHolds, ensure };
