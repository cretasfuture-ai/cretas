'use strict';
// 第八批（DECISIONS R37/R41）：六膳门黑湖没有数据的核心页（报损管理、源头个体档案、车辆字典）按「四之前」就地留补充入口。
// 只写 unified-v1/private/c/lsm-gap/<会话>.json（无会话时 main.json）；记录来源一律是 Cretas 补充，不回写黑湖，撤销必须写原因。
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const DIR = path.join(path.resolve(__dirname, '..', '..'), 'private', 'c', 'lsm-gap');
const isDate = s => /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(Date.parse(s + 'T00:00:00Z'));
const today = () => { const n = new Date(); return n.getFullYear() + '-' + String(n.getMonth() + 1).padStart(2, '0') + '-' + String(n.getDate()).padStart(2, '0'); };
const str = v => (v == null ? '' : String(v)).trim();
const fail = (field, message) => { throw Object.assign(Error(message), { status: 422, field }); };
// 每类记录的字段：key、中文名、是否必填、类型
const KINDS = {
  wastage: { label: '报损', fields: [['date', '报损日期', 1, 'date'], ['name', '物料或产品', 1], ['batchNo', '批次号', 0], ['qty', '报损数量', 1, 'num'], ['unit', '单位', 1], ['warehouse', '仓库', 0], ['reason', '报损原因', 1]] },
  source: { label: '源头个体', fields: [['code', '个体编号', 1], ['indKind', '类型（活体/胴体）', 1], ['species', '品种', 0], ['origin', '来源（养殖场/供应商）', 1], ['inDate', '进场日期', 1, 'date'], ['batchNo', '关联入库批次', 0]] },
  vehicle: { label: '车辆', fields: [['plate', '车牌号', 1], ['type', '车型', 0], ['driver', '司机', 0], ['load', '载重（吨）', 0, 'num'], ['note', '备注', 0]] }
};
const empty = () => ({ version: 0, records: [] });
function file(session) {
  if (session && !/^test-[a-z0-9-]{8,64}$/.test(session)) throw Object.assign(Error('验收会话名称不合规（须以 test- 开头，小写）'), { status: 400 });
  return path.join(DIR, (session || 'main') + '.json');
}
function read(session) { const f = file(session); return fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')) : empty(); }
function write(st, session) { const f = file(session); fs.mkdirSync(DIR, { recursive: true }); const t = f + '.' + crypto.randomUUID() + '.tmp'; fs.writeFileSync(t, JSON.stringify(st), { flag: 'wx', mode: 0o600 }); fs.renameSync(t, f); }
function create(st, body, by) {
  const kind = str(body.kind), K = KINDS[kind]; if (!K) fail('kind', '未知的记录类型');
  const d = {};
  for (const [k, label, req, type] of K.fields) {
    const v = str(body[k]);
    if (req && !v) fail(k, '请填写' + label);
    if (v && type === 'date' && !isDate(v)) fail(k, label + '格式应为 年-月-日');
    if (v && type === 'date' && v > today()) fail(k, label + '晚于今天（' + today() + '），不能保存');
    if (v && type === 'num' && !(Number(v) > 0)) fail(k, label + '要大于 0');
    d[k] = v;
  }
  if (kind === 'vehicle' && st.records.some(r => r.kind === 'vehicle' && !r.revokedAt && r.data.plate.toUpperCase() === d.plate.toUpperCase())) fail('plate', '车牌号 ' + d.plate + ' 已存在');
  if (kind === 'source' && st.records.some(r => r.kind === 'source' && !r.revokedAt && r.data.code === d.code)) fail('code', '个体编号 ' + d.code + ' 已存在');
  const r = { id: 'G' + crypto.randomUUID().slice(0, 8), kind, data: d, source: 'Cretas 补充', createdAt: new Date().toISOString(), createdBy: str(by) || '本机原型' };
  st.records.push(r); st.version++; return r;
}
function revoke(st, body, by) {
  const r = st.records.find(x => x.id === str(body.id)); if (!r) fail('id', '找不到这条记录');
  if (r.revokedAt) fail('id', '这条记录已撤销');
  const reason = str(body.reason); if (!reason) fail('reason', '撤销必须写原因');
  r.revokedAt = new Date().toISOString(); r.revokedBy = str(by) || '本机原型'; r.revokeReason = reason; st.version++; return r;
}
module.exports = { KINDS, read, write, create, revoke };
