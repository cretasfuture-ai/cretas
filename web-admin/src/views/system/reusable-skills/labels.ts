/** 可复用 Skill 页的中文说法：界面上不出现工具名、类型、状态等后端代码。 */

/** 经工具网关治理的工具（只有它们的运行能保存为可复用 Skill）。 */
const TOOL_LABELS: Record<string, string> = {
  material_expired_query: '已过期原料查询',
  material_stock_summary: '原料库存汇总',
  material_batch_query: '原料批次查询',
  alert_rule_toggle: '告警规则启用/停用',
  smartbi_factory_analysis: '工厂数据分析',
  product_create: '新建产品'
};

export function toolLabel(tool: string): string {
  return TOOL_LABELS[tool] ?? '其他工具';
}

export function toolsText(tools: string[]): string {
  return tools.length ? tools.map(toolLabel).join('、') : '无';
}

const PARAM_TYPE_LABELS: Record<string, string> = {
  STRING: '文字',
  INTEGER: '整数',
  NUMBER: '数字',
  BOOLEAN: '是/否',
  DATE: '日期',
  ENUM: '可选项'
};

export function paramTypeLabel(type: string): string {
  return PARAM_TYPE_LABELS[type] ?? '其他';
}

const RUN_STATUS: Record<string, { label: string; type: 'success' | 'warning' | 'danger' }> = {
  SUCCEEDED: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  DENIED: { label: '被拒绝', type: 'danger' },
  OUTCOME_UNKNOWN: { label: '结果未知', type: 'warning' }
};

/** 不认识的状态一律按「结果未知」显示，从不显示原始代码，也不说成成功。 */
export function runStatus(status: string | null | undefined) {
  return (status && RUN_STATUS[status]) || { label: '结果未知', type: 'warning' as const };
}
