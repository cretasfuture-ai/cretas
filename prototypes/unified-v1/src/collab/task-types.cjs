'use strict';
// 协同任务类型（BL-borrow 第 19 条）：固定 7 类，不开放自定义字段设计器。第六批补「急单」「费料核对」（缺口 B7，配合 F-16：F 发起这两类不再落到「其他」）。
// 每类：颜色、默认负责岗位、标题规则（代码生成，可改）、专属字段（代码校验）。
const TYPES = {
  quality: { label: '质量异常', color: 'danger', role: '质量经理', fields: [
    { key: 'failItems', label: '不合格项', kind: 'text', max: 120 },
    { key: 'qty', label: '涉及数量', kind: 'number' }, { key: 'unit', label: '单位', kind: 'text', max: 10 }] },
  first: { label: '首件确认', color: 'info', role: '品控', fields: [
    { key: 'product', label: '产品', kind: 'text', max: 60 },
    { key: 'process', label: '工序', kind: 'text', max: 40 },
    { key: 'qty', label: '送检数量', kind: 'number' }, { key: 'unit', label: '单位', kind: 'text', max: 10 }] },
  equipment: { label: '设备故障', color: 'warning', role: '生产主管', fields: [
    { key: 'device', label: '故障设备', kind: 'text', max: 40, required: true },
    { key: 'symptom', label: '故障表现', kind: 'text', max: 120 },
    { key: 'downMinutes', label: '停机时长（分钟）', kind: 'number' }] },
  shortage: { label: '缺料跟进', color: 'primary', role: '采购', fields: [
    { key: 'material', label: '物料', kind: 'text', max: 60, required: true },
    { key: 'qty', label: '缺口数量', kind: 'number' }, { key: 'unit', label: '单位', kind: 'text', max: 10 }] },
  rush: { label: '急单', color: 'warning', role: '生产主管', fields: [
    { key: 'order', label: '订单 / 工单号', kind: 'text', max: 40, required: true },
    { key: 'product', label: '产品', kind: 'text', max: 60 },
    { key: 'qty', label: '数量', kind: 'number' }, { key: 'unit', label: '单位', kind: 'text', max: 10 },
    { key: 'needBy', label: '要求交期', kind: 'text', max: 20 }] },
  materialCheck: { label: '费料核对', color: 'primary', role: '生产主管', fields: [
    { key: 'material', label: '物料', kind: 'text', max: 60, required: true },
    { key: 'order', label: '工单号', kind: 'text', max: 40 },
    { key: 'standardQty', label: '标准用量', kind: 'number' }, { key: 'actualQty', label: '实际用量', kind: 'number' }, { key: 'unit', label: '单位', kind: 'text', max: 10 }] },
  other: { label: '其他', color: 'neutral', role: '', fields: [] }
};
// 标题规则：类型 · 关联项 · 关键字段（缺的部分跳过），总长 ≤ 60
function title(type, f, related) {
  const t = TYPES[type] || TYPES.other, rel = (related || [])[0];
  const key = { quality: f.failItems, first: f.product && f.process ? f.product + ' ' + f.process : f.product, equipment: f.device, shortage: f.material, rush: [f.order, f.product].filter(Boolean).join(' '), materialCheck: [f.material, f.order].filter(Boolean).join(' '), other: '' }[type] || '';
  return [t.label, rel ? rel.code : '', key].filter(Boolean).join(' · ').slice(0, 60);
}
function cleanFields(type, raw, bad) {
  const t = TYPES[type]; if (!t) throw bad('任务类型不正确');
  const out = {};
  for (const fd of t.fields) {
    const v = raw ? raw[fd.key] : undefined;
    if (fd.kind === 'number') {
      if (v === '' || v == null) { out[fd.key] = null; continue; }
      const n = Number(v); if (!Number.isFinite(n) || n < 0) throw bad('「' + fd.label + '」必须是非负数字'); out[fd.key] = n;
    } else { out[fd.key] = typeof v === 'string' ? v.trim().slice(0, fd.max || 60) : ''; }
    if (fd.required && (out[fd.key] === '' || out[fd.key] == null)) throw bad(t.label + '必须填写「' + fd.label + '」');
  }
  if (['qty', 'standardQty', 'actualQty'].some(k => out[k] != null) && 'unit' in out && !out.unit) throw bad('填写了数量就要填写单位');
  return out;
}
// 响应时长：创建 → 第一次「开始」或第一条非发起人评论（取早者）；处理时长：创建 → 完成。都由代码按记录时间算，单位分钟
function timings(t) {
  const created = Date.parse(t.createdAt);
  const starts = t.log.filter(l => l.action === '状态' && (l.changes || []).some(c => c.after === '进行中' || c.after === '已完成')).map(l => Date.parse(l.at));
  const comments = (t.comments || []).filter(c => c.by !== t.createdBy).map(c => Date.parse(c.at));
  const first = [...starts, ...comments].filter(Number.isFinite).sort((a, b) => a - b)[0];
  return { respondedAt: first ? new Date(first).toISOString() : null, responseMin: first ? Math.round((first - created) / 60000) : null, handleMin: t.doneAt ? Math.round((Date.parse(t.doneAt) - created) / 60000) : null };
}
module.exports = { TYPES, title, cleanFields, timings };
