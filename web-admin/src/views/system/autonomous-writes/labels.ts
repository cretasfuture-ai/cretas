/** 定时任务写入页的中文说法：界面上不出现后端的枚举码。 */

/** 运行策略里开放给定时任务的写工具（与后端 runtime-tool-policies.yaml 的 SCHEDULER 写工具一致）。 */
export const SCHEDULABLE_WRITE_TOOLS: Record<string, string> = {
  alert_rule_toggle: '告警规则启用/停用'
};

export function toolLabel(toolName: string): string {
  return SCHEDULABLE_WRITE_TOOLS[toolName] ?? '其他工具';
}

const GRANT_STATES: Record<string, { label: string; type: 'success' | 'info' | 'warning' | 'danger' }> = {
  ACTIVE: { label: '生效中', type: 'success' },
  REVOKED: { label: '已撤销', type: 'info' },
  EXPIRED: { label: '已过期', type: 'info' },
  EXHAUSTED: { label: '次数已用完', type: 'warning' }
};

export function grantState(state: string) {
  return GRANT_STATES[state] ?? { label: '状态未知', type: 'danger' as const };
}

interface RunOutcome { label: string; reason: string; type: 'success' | 'info' | 'warning' | 'danger' }

const RUN_OUTCOMES: Record<string, RunOutcome> = {
  SUCCESS: { label: '已执行', reason: '按委托授权执行成功', type: 'success' },
  WRITES_SUSPENDED: { label: '未执行', reason: '定时任务写入总开关没有打开或已关闭', type: 'warning' },
  GRANT_DENIED: {
    label: '未执行',
    reason: '没有可用的委托授权（未签发、已撤销、已过期、次数用完，或任务配置在签发后改过）',
    type: 'danger'
  },
  WRITE_DENIED: { label: '未执行', reason: '不符合自动写入规则（例如参数里没有写明启用还是停用）', type: 'danger' },
  GATEWAY_DENIED: { label: '未执行', reason: '执行人当前没有这项操作的权限，或账号已停用', type: 'danger' },
  RUN_AS_MISSING: { label: '未执行', reason: '这个定时任务没有执行人', type: 'danger' },
  CONFIG_INVALID: { label: '未执行', reason: '定时任务的配置无效', type: 'danger' },
  TOOL_NOT_FOUND: { label: '未执行', reason: '定时任务使用的工具不存在', type: 'danger' },
  FAILED: { label: '执行失败', reason: '工具执行失败，业务数据没有改动', type: 'danger' },
  TIMEOUT: { label: '结果未知', reason: '执行超时，请到业务页面核对实际结果', type: 'warning' },
  OUTCOME_UNKNOWN: { label: '结果未知', reason: '无法确认是否已写入，请到业务页面核对后裁定，系统不会自动重试', type: 'warning' }
};

export function runOutcome(rawStatus: string | null | undefined): RunOutcome {
  return (rawStatus && RUN_OUTCOMES[rawStatus]) || {
    label: '结果未知', reason: '系统未能识别这次运行的结果，请联系管理员核对', type: 'warning'
  };
}

/** 固定参数的中文展示：布尔的 enabled 显示为「设为启用 / 设为停用」。 */
export function paramsText(params: Record<string, unknown> | null | undefined): string {
  if (!params || Object.keys(params).length === 0) return '无';
  return Object.entries(params).map(([key, value]) => {
    if (key === 'enabled' && typeof value === 'boolean') return value ? '设为启用' : '设为停用';
    if (key === 'ruleId') return `规则 ${String(value)}`;
    return `${key}：${typeof value === 'object' ? JSON.stringify(value) : String(value)}`;
  }).join('，');
}
