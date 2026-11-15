'use strict';
// 黑湖检验单（DECISIONS 第十节 E①）：只读 liushanmen-v1/private/extras-current.json 的 inspection_order，按 revision 缓存；不写任何六膳门文件。
// 阶段归类由代码按来源事实判定，不按 orderType 数字猜含义：
//   有工序或工单（processId / projectId） → 生产；
//   来源单据在库存快照里是「采购入库」 → 来料；「生产入库」→ 入库；「销售出库」 → 发货；
//   其他或查不到 → 其他，并写明原因。
const fs = require('fs'), path = require('path');
const DIR = path.resolve(__dirname, '..', '..', '..', 'liushanmen-v1', 'private');
let cache = { key: '', data: null };
function readJson(f) { const p = path.join(DIR, f); if (!fs.existsSync(p)) return null; return { mtime: fs.statSync(p).mtimeMs, json: JSON.parse(fs.readFileSync(p, 'utf8')) }; }
const BIZ_STAGE = { '采购入库': 'incoming', '生产入库': 'warehousing', '销售出库': 'shipping' };
const num = v => v == null || v === '' ? null : Number(v);
function load() {
  const ex = readJson('extras-current.json');
  if (!ex) return { available: false, reason: '黑湖扩展快照（extras-current.json）不存在', rows: [], counts: {} };
  const inv = readJson('inventory-current.json'), src = readJson('source-current.json');
  const key = [ex.json.revision, ex.mtime, inv && inv.mtime, src && src.mtime].join('|');
  if (cache.key === key) return cache.data;
  const r = ex.json.resources || {};
  const orders = new Map(((inv && inv.json.orders) || []).map(o => [String(o.id), o]));
  const products = (((src && (src.json.source || src.json).resources) || {}).product) || [];
  const rows = (r.inspection_order || []).map(o => {
    let stage = 'other', reason = '';
    const doc = orders.get(String(o.sourceId));
    if (o.processId != null || o.projectId != null) { stage = 'production'; reason = '黑湖检验单带工序 / 工单'; }
    else if (doc && BIZ_STAGE[doc.bizTypeDisplay]) { stage = BIZ_STAGE[doc.bizTypeDisplay]; reason = '来源单据 ' + doc.orderNo + ' 是黑湖「' + doc.bizTypeDisplay + '」'; }
    else if (doc) reason = '来源单据 ' + doc.orderNo + ' 是「' + (doc.bizTypeDisplay || '未返回类型') + '」，没有对应阶段';
    else reason = '来源单据（' + (o.sourceOrderNO || o.sourceId || '未返回') + '）不在库存快照里，无法判断阶段';
    const p = products.find(x => x.productCode === o.productCode);
    const hasResult = !!o.hasInspectionOrderResultFlag || o.inspectionOrderResult != null;
    return {
      id: 'lake-' + o.id, lakeId: String(o.id), no: String(o.code), stage, stageReason: reason, source: '黑湖',
      object: { code: String(o.productCode || ''), name: String(o.productName || ''), spec: o.productSpec || '', unit: p ? String(p.unit || '') : '' },
      sourceDoc: doc ? { no: doc.orderNo, type: doc.bizTypeDisplay || '', status: doc.orderStatusDisplay || '' } : { no: o.sourceOrderNO || '', type: '', status: '' },
      spec: o.inspectionSpecificationsName || '', process: o.processName || '',
      // 数量：黑湖记录值原样给出；没有检验结果时「已检 / 合格 / 不合格」是黑湖记的 0，前端写明「未检验」，不当作检验结论
      submittedQty: num(o.submittedInspectionQty), inspectedQty: num(o.inspectionQty), qualifiedQty: num(o.qualifiedQty), unqualifiedQty: num(o.unqualifiedQty),
      orderStatus: o.orderStatus, hasResult, result: o.inspectionOrderResult, items: o.inspectionOrderItemVOS || [],
      planFinishedTime: o.planFinishedTime || '', createdAt: o.createdAt || '', updatedAt: o.updatedAt || ''
    };
  });
  const counts = Object.fromEntries(['inspection_order', 'inspection_item', 'inspection_spec', 'inspection_result', 'collaborative_task'].map(k => [k, Array.isArray(r[k]) ? r[k].length : null]));
  cache = { key, data: { available: true, revision: String(ex.json.revision || ''), checkedAt: ex.json.checkedAt || '', rows, counts } };
  return cache.data;
}
module.exports = { load };
