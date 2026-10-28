// 工艺保密访问记录: 把后端写的机读说明翻译成人话。
//
// 管理动作的说明由 ProcessSecretPolicyService 写成固定格式 (已落库的旧记录也是这个格式, 所以在这里解析,
// 不改后端):
//   ENABLE <mode> owner=<userId>
//   UPDATE <mode>-><mode> owner <userId>-><userId>
//   DISABLE <mode>
//   GRANT #<grantId> to <granteeType>:<userId>
//   REVOKE #<grantId>
// 其余说明 (访问判定: 「保密负责人」「有效授权」等) 本来就是中文, 原样显示。

export interface AuditGrantInfo {
  granteeId: number;
  canViewFull: boolean;
  canExport: boolean;
  canEdit: boolean;
  purpose?: string | null;
  expiresAt?: string | null;
}

export interface AuditLabelContext {
  userName: (id: number) => string;
  modeLabel: (mode: string) => string;
  /** 已加载的本产品授权, 用来把 #id 换成「谁、能做什么、到什么时候」。 */
  grantById: (id: number) => AuditGrantInfo | undefined;
  formatTime: (s?: string | null) => string;
}

export interface AuditDescription {
  /** 动作名, 覆盖通用的「修改保密设置」; 不是管理动作时为 null。 */
  action: string | null;
  text: string;
}

function permissionText(g: AuditGrantInfo): string {
  const parts = [g.canViewFull && '查看', g.canExport && '导出', g.canEdit && '修改'].filter(Boolean);
  return parts.length ? parts.join('、') : '无';
}

function grantDetail(g: AuditGrantInfo | undefined, ctx: AuditLabelContext): string {
  if (!g) return '';
  const bits = [`可以${permissionText(g)}`];
  if (g.purpose) bits.push(`用途：${g.purpose}`);
  if (g.expiresAt) bits.push(`到期：${ctx.formatTime(g.expiresAt)}`);
  return `（${bits.join('；')}）`;
}

export function describeAuditReason(reason: string | null | undefined, ctx: AuditLabelContext): AuditDescription {
  const text = (reason || '').trim();
  if (!text) return { action: null, text: '—' };

  let m = /^ENABLE (\S+) owner=(\d+)$/.exec(text);
  if (m) {
    return { action: '开启保密', text: `设为「${ctx.modeLabel(m[1])}」，负责人：${ctx.userName(Number(m[2]))}` };
  }

  m = /^UPDATE (\S+)->(\S+) owner (\d+|null)->(\d+)$/.exec(text);
  if (m) {
    const changes: string[] = [];
    if (m[1] !== m[2]) changes.push(`保密方式「${ctx.modeLabel(m[1])}」改为「${ctx.modeLabel(m[2])}」`);
    if (m[3] !== m[4]) {
      const before = m[3] === 'null' ? '—' : ctx.userName(Number(m[3]));
      changes.push(`负责人 ${before} 改为 ${ctx.userName(Number(m[4]))}`);
    }
    return { action: '修改保密设置', text: changes.length ? changes.join('；') : '保存设置（无变化）' };
  }

  m = /^DISABLE (\S+)$/.exec(text);
  if (m) {
    return { action: '关闭保密', text: `关闭「${ctx.modeLabel(m[1])}」，该产品的全部授权同时撤销` };
  }

  m = /^GRANT #(\d+) to (\w+):(\d+)$/.exec(text);
  if (m) {
    const who = m[2] === 'PLATFORM_ADMIN' ? '平台人员' : ctx.userName(Number(m[3]));
    return { action: '新增授权', text: `授权给 ${who}${grantDetail(ctx.grantById(Number(m[1])), ctx)}` };
  }

  m = /^REVOKE #(\d+)$/.exec(text);
  if (m) {
    const g = ctx.grantById(Number(m[1]));
    return { action: '撤销授权', text: g ? `撤销 ${ctx.userName(g.granteeId)} 的授权` : '撤销一条授权' };
  }

  return { action: null, text };
}
