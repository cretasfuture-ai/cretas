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
  buildCostOverview: () => buildCostOverview,
  buildUnitCostChart: () => buildUnitCostChart,
  createUnitCostChart: () => createUnitCostChart,
  groupEligibleCostProjects: () => groupEligibleCostProjects,
  groupEligibleProjectsByProduct: () => groupEligibleProjectsByProduct
});
module.exports = __toCommonJS(stdin_exports);
function isFinitePositive(value) {
  return typeof value === "number" && Number.isFinite(value) && value > 0;
}
function isFiniteNumber(value) {
  return typeof value === "number" && Number.isFinite(value);
}
function groupEligibleCostProjects(projects, unit) {
  const groups = /* @__PURE__ */ new Map();
  for (const project of projects) {
    if (!project.unitCostAvailable || !project.unit || unit && project.unit !== unit) continue;
    if (!isFinitePositive(project.finishedGood) || !isFiniteNumber(project.materialCost) || !isFiniteNumber(project.laborCost)) continue;
    const product = project.product.trim() || "\u672A\u547D\u540D\u4EA7\u54C1";
    const productCode = project.productCode?.trim() ? project.productCode : null;
    const identity = productCode ?? `missing-code-project:${project.projectId}`;
    const key = `${identity}\0${product}\0${project.unit}`;
    const existing = groups.get(key);
    const row = project;
    if (existing) existing.rows.push(row);
    else groups.set(key, { rows: [row], product, productCode, unit: project.unit });
  }
  return [...groups.entries()].map(([key, group]) => {
    const finishedGood = group.rows.reduce((sum, row) => sum + row.finishedGood, 0);
    const materialCost = group.rows.reduce((sum, row) => sum + row.materialCost, 0);
    const laborCost = group.rows.reduce((sum, row) => sum + row.laborCost, 0);
    const perProject = group.rows.map((row) => (row.materialCost + row.laborCost) / row.finishedGood);
    return {
      key,
      product: group.product,
      productCode: group.productCode,
      displayIdentity: group.productCode ? group.productCode === group.productCode.trim() ? group.productCode : `\u7F16\u7801\u542B\u9996\u5C3E\u7A7A\u683C ${JSON.stringify(group.productCode)}` : `\u7F16\u7801\u7F3A\u5931 \xB7 ${group.rows.map((p) => p.projectCode).join("\u3001")}`,
      unit: group.unit,
      sampleCount: group.rows.length,
      finishedGood,
      materialCost,
      laborCost,
      weightedMaterialUnitCost: materialCost / finishedGood,
      weightedLaborUnitCost: laborCost / finishedGood,
      weightedUnitCost: (materialCost + laborCost) / finishedGood,
      minimumUnitCost: Math.min(...perProject),
      maximumUnitCost: Math.max(...perProject)
    };
  }).sort((a, b) => a.product.localeCompare(b.product, "zh-CN") || a.unit.localeCompare(b.unit));
}
const groupEligibleProjectsByProduct = groupEligibleCostProjects;
function buildUnitCostChart(groups) {
  return {
    chartType: "bar",
    title: "\u4EA7\u54C1\u5355\u4F4D\u76F4\u63A5\u6210\u672C \xB7 \u540C\u5355\u4F4D\u6BD4\u8F83",
    subTitle: "\u6750\u6599\u4E0E\u4EBA\u5DE5\u5206\u522B\u5806\u53E0\uFF1B\u4E0D\u540C\u5355\u4F4D\u4E0D\u6DF7\u5408",
    xAxis: { type: "category", data: groups.map((group) => `${group.product}
${group.displayIdentity}`) },
    yAxis: [{ type: "value", name: "\u6765\u6E90\u91D1\u989D / \u6210\u54C1\u5355\u4F4D" }],
    legend: { show: true, data: ["\u6750\u6599", "\u4EBA\u5DE5"], position: "bottom" },
    tooltip: { trigger: "axis" },
    series: [
      { name: "\u6750\u6599", type: "bar", stack: "unit-cost", data: groups.map((group) => group.weightedMaterialUnitCost), yAxisIndex: 0, itemStyle: { color: "#1b65a8" } },
      { name: "\u4EBA\u5DE5", type: "bar", stack: "unit-cost", data: groups.map((group) => group.weightedLaborUnitCost), yAxisIndex: 0, itemStyle: { color: "#6ca5d7" } }
    ]
  };
}
const createUnitCostChart = buildUnitCostChart;
function buildCostOverview(input) {
  const projectCount = Math.max(0, input.production.projectCount || 0);
  const eligibleProjects = Math.max(0, Math.min(projectCount, input.cost.unitCostAvailableProjects || 0));
  const businessSignalCount = input.costAlerts?.summary?.byFamily?.business_signal || 0;
  const dataQualityAlertCount = input.costAlerts?.summary?.byFamily?.data_quality || 0;
  const readinessAlertCount = input.costAlerts?.summary?.byFamily?.readiness || 0;
  return {
    projectCount,
    eligibleProjects,
    blockedProjects: Math.max(0, projectCount - eligibleProjects),
    orderCoveragePercent: projectCount ? eligibleProjects / projectCount * 100 : null,
    linkedMaterialLines: input.cost.linkedMaterialLines,
    unlinkedMaterialLines: input.cost.unlinkedMaterialLines,
    sourceZeroCostLines: input.cost.sourceZeroCostLines,
    knownMaterialCostLines: input.cost.knownMaterialCostLines,
    businessSignalCount,
    dataQualityAlertCount,
    readinessAlertCount
  };
}
