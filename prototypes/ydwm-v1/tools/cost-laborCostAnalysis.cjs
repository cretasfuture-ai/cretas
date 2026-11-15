var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);
var stdin_exports = {};
__export(stdin_exports, {
  allocateLaborByPersonHours: () => allocateLaborByPersonHours,
  analyzeLaborCosts: () => analyzeLaborCosts,
  mesLaborRows: () => mesLaborRows
});
module.exports = __toCommonJS(stdin_exports);
const finite = (v) => typeof v === "number" && Number.isFinite(v);
const sum = (values) => {
  if (!values.length || !values.every(finite)) return null;
  const result = values.reduce((a, b) => a + b, 0);
  return Number.isFinite(result) ? result : null;
};
const divide = (a, b) => finite(a) && finite(b) && b > 0 ? a / b : null;
function allocateLaborByPersonHours(total, weights) {
  const unavailable = () => weights.map(() => null);
  if (!finite(total) || total < 0 || !weights.length || weights.some((w) => !finite(w) || w < 0)) return unavailable();
  const totalWeight = sum(weights);
  if (totalWeight === null || totalWeight <= 0) return unavailable();
  const cents = Math.round((total + Number.EPSILON) * 100);
  if (!Number.isSafeInteger(cents)) return unavailable();
  let last = -1;
  for (let index = weights.length - 1; index >= 0; index--) {
    if (finite(weights[index]) && weights[index] > 0) {
      last = index;
      break;
    }
  }
  let used = 0;
  return weights.map((w, index) => {
    const share = index === last ? cents - used : Math.min(cents - used, Math.round(cents * w / totalWeight));
    used += share;
    return share / 100;
  });
}
function analyzeLaborCosts(input) {
  const rows = input.rows;
  const detailTotal = sum(rows.map((row) => row.amount));
  const total = finite(input.total) ? input.total : null;
  const reconciled = total !== null && total >= 0 && detailTotal !== null && rows.every((r) => finite(r.amount) && r.amount >= 0) && Math.round((total + Number.EPSILON) * 100) === Math.round((detailTotal + Number.EPSILON) * 100);
  const personHours = sum(rows.map((row) => finite(row.personHours) && row.personHours >= 0 ? row.personHours : null));
  const rosterReady = rows.length > 0 && rows.every((row) => row.rosterComplete === true && !!row.workerIds?.length && row.workerIds.every((id) => typeof id === "string" && id.trim().length > 0));
  const participants = rosterReady ? new Set(rows.flatMap((row) => row.workerIds?.map((id) => id.trim()) || [])).size : null;
  const preview = reconciled ? allocateLaborByPersonHours(total, rows.map((row) => row.personHours)) : rows.map(() => null);
  const sourceAvailable = rows.some((row) => row.registeredCoverageComplete !== void 0);
  const registeredKeys = new Set(rows.flatMap((row) => row.registeredWorkerKeys || []));
  const registeredCoverage = sourceAvailable && rows.every((row) => row.registeredCoverageComplete === true);
  const registeredParticipants = registeredKeys.size || null;
  return {
    total,
    detailTotal,
    reconciled,
    personHours,
    participants,
    sourceAvailable,
    registeredParticipants,
    registeredCoverage,
    registeredPerPerson: registeredCoverage && reconciled ? divide(total, registeredParticipants) : null,
    sourceEffectiveHours: sum(rows.map((row) => row.sourceEffectiveHours)),
    registeredReports: rows.reduce((a, r) => a + (r.registeredReports || 0), 0),
    zeroTimeReports: rows.reduce((a, r) => a + (r.zeroTimeReports || 0), 0),
    overlapReports: rows.reduce((a, r) => a + (r.overlapReports || 0), 0),
    perPerson: reconciled ? divide(total, participants) : null,
    perPersonHour: reconciled ? divide(total, personHours) : null,
    perUnit: input.unit ? divide(total, input.output) : null,
    canAllocate: preview.length > 0 && preview.every(finite),
    rows: rows.map((row, index) => ({
      ...row,
      perUnit: input.unit ? divide(row.amount, input.output) : null,
      share: reconciled ? divide(row.amount, total) : null,
      segmentPerPerson: Number.isInteger(row.segmentWorkers) && row.segmentWorkers > 0 ? divide(row.amount, row.segmentWorkers) : null,
      allocated: preview[index] ?? null
    })),
    needs: [
      ...!rosterReady ? [{ key: "people", missing: sourceAvailable ? "\u767B\u8BB0\u4EBA\u5458\u662F\u5426\u8986\u76D6\u5B9E\u9645\u73ED\u7EC4" : "\u5B9E\u9645\u53C2\u4E0E\u4EBA\u5458\u540D\u5355", owner: "\u751F\u4EA7\u4E3B\u7BA1 / \u8003\u52E4\u8D1F\u8D23\u4EBA", action: sourceAvailable ? "\u6838\u5BF9\u662F\u5426\u7531\u73ED\u7EC4\u957F\u4EE3\u62A5\uFF1B\u8865\u9F50\u5B9E\u9645\u53C2\u4E0E\u540D\u5355\uFF0C\u540C\u4E00\u4EBA\u8DE8\u5DE5\u5E8F\u53BB\u91CD" : "\u8865\u9F50\u6240\u9009\u5DE5\u5355\u7684\u5B9E\u9645\u53C2\u4E0E\u4EBA\u5458\uFF0C\u5E76\u6309\u4EBA\u5458\u53BB\u91CD", benefit: "\u8BA1\u7B97\u771F\u5B9E\u4EBA\u5747\u4EBA\u5DE5\u8D39\uFF0C\u907F\u514D\u628A\u73ED\u7EC4\u5DE5\u8D44\u9664\u4EE5\u4EE3\u62A5\u4EBA\u6570" }] : [],
      ...personHours === null ? [{ key: "hours", missing: "\u5404\u5DE5\u5E8F\u7684\u6709\u6548\u4EBA\u65F6", owner: "\u8F66\u95F4\u4E3B\u7BA1", action: sourceAvailable ? "\u786E\u8BA4\u6BCF\u6BB5\u5B9E\u9645\u4EBA\u6570\u548C\u771F\u6B63\u4F5C\u4E1A\u65F6\u95F4\uFF0C\u6838\u5B9E\u4F11\u606F\u3001\u505C\u673A\u662F\u5426\u6263\u9664\uFF1B\u96F6\u65F6\u957F\u82E5\u5C5E\u4E8E\u4E0D\u8BA1\u65F6\u5DE5\u5E8F\u53EF\u4FDD\u7559\u4E3A\u96F6\uFF0C\u91CD\u53E0\u65F6\u6BB5\u9700\u786E\u8BA4\u662F\u5426\u4EE3\u62A5" : "\u8865\u9F50\u6BCF\u6BB5\u7684\u8D77\u6B62\u65F6\u95F4\u548C\u4EBA\u6570\uFF1B\u4EC5\u6709\u62A5\u5DE5\u65F6\u957F\u4E0D\u591F", benefit: "\u8BA1\u7B97\u6BCF\u4EBA\u65F6\u8D39\u7528\uFF0C\u5E76\u6309\u4EBA\u65F6\u6D4B\u7B97\u5DE5\u5E8F\u5206\u644A" }] : [],
      ...rows.some((row) => row.amount === 0) ? [{ key: "zero", missing: "\u96F6\u5DE5\u8D44\u5DE5\u5E8F\u7684\u8BA1\u85AA\u65B9\u5F0F", owner: "\u751F\u4EA7 / \u4EBA\u4E8B", action: "\u786E\u8BA4\u8FD9\u4E9B\u5DE5\u5E8F\u662F\u5426\u5408\u5E76\u8BA1\u85AA\u3001\u5DE5\u8D44\u53E6\u8BB0\u6216\u5C1A\u672A\u8865\u5F55", benefit: "\u907F\u514D\u628A\u672A\u8BB0\u5DE5\u8D44\u7684\u5DE5\u5E8F\u5F53\u6210\u4E0D\u82B1\u94B1" }] : [],
      ...!reconciled ? [{ key: "cost", missing: "\u5DE5\u5E8F\u4EBA\u5DE5\u660E\u7EC6\u4E0E\u5DE5\u5355\u4EBA\u5DE5\u5408\u8BA1\u7684\u6838\u5BF9\u7ED3\u679C", owner: "\u751F\u4EA7 / \u8D22\u52A1", action: "\u68C0\u67E5\u6F0F\u8BB0\u3001\u91CD\u590D\u5DE5\u5E8F\u6216\u91D1\u989D\u7F3A\u5931", benefit: "\u8BA9\u5DE5\u5E8F\u4EBA\u5DE5\u76F8\u52A0\u4E0E\u672C\u5355\u4EBA\u5DE5\u8D39\u4E00\u81F4" }] : []
    ]
  };
}
function mesLaborRows(rows) {
  return rows.map((row, index) => ({
    id: `mes:${row.processOrder ?? "unknown"}:${index}`,
    process: row.processName || `\u5DE5\u5E8F${row.processOrder ?? index + 1}`,
    amount: finite(row.amount) ? row.amount : null,
    personHours: finite(row.laborHours) ? row.laborHours : null,
    reportedHours: finite(row.durationMinutes) ? row.durationMinutes / 60 : null,
    segmentWorkers: row.startTime && row.endTime && finite(row.workerCount) ? row.workerCount : null
  }));
}
