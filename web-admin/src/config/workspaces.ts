import { isPathAllowedForRole } from '@/router/guards';
import type { ModuleName } from '@/store/modules/permission';

export const FACTORY_WORKSPACE_ROLES = ['factory_super_admin', 'permission_admin'] as const;
export type FactoryWorkspaceRole = (typeof FACTORY_WORKSPACE_ROLES)[number];

export type WorkspaceLink = {
  path: string;
  title: string;
  description: string;
  module: ModuleName;
};

export type FactoryWorkspace = {
  id: 'overview' | 'exceptions' | 'plans-approvals' | 'trace-review';
  path: string;
  title: string;
  question: string;
  description: string;
  links: readonly WorkspaceLink[];
};

export type WorkspaceVisibilityContext = {
  role: string;
  factoryType: string;
  factoryId?: string | null;
  isPlatform: boolean;
  canAccess: (module: ModuleName) => boolean;
  /** Resolve the existing router record; absence fails closed. */
  canVisitRoute?: (path: string, context: WorkspaceVisibilityContext, expectedModule: ModuleName) => boolean;
  /** Read the sidebar's fully filtered, identity-bound path snapshot. */
  isPathVisible?: (path: string, context: WorkspaceVisibilityContext) => boolean;
};

export const FACTORY_WORKSPACE_FACTORY_TYPES = ['FACTORY', 'CENTRAL_KITCHEN'] as const;

/** Do not inherit authStore's legacy FACTORY fallback for this new surface. */
export function resolveWorkspaceFactoryType(rawFactoryType: unknown): string {
  return typeof rawFactoryType === 'string'
    && FACTORY_WORKSPACE_FACTORY_TYPES.includes(rawFactoryType as (typeof FACTORY_WORKSPACE_FACTORY_TYPES)[number])
    ? rawFactoryType
    : '';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/** Read only the raw factory-user shape; never inherit authStore fallbacks. */
export function resolveWorkspaceFactoryTypeFromUser(user: unknown): string {
  if (!isRecord(user) || user.userType !== 'factory' || !isRecord(user.factoryUser)) return '';
  return resolveWorkspaceFactoryType(user.factoryUser.factoryType);
}

export const FACTORY_WORKSPACES: readonly FactoryWorkspace[] = [
  {
    id: 'overview',
    path: '/workspaces/overview',
    title: '经营总览',
    question: '工厂当前经营与生产状态如何？',
    description: '从现有经营报告和首页数据入口查看工厂的整体运行情况。',
    links: [
      { path: '/dashboard', title: '首页驾驶舱', description: '查看现有生产、质量、仓储和设备概览。', module: 'dashboard' },
      { path: '/analytics/ai-reports', title: 'AI经营报告', description: '打开已生成的经营分析报告。', module: 'analytics' },
    ],
  },
  {
    id: 'exceptions',
    path: '/workspaces/exceptions',
    title: '异常中心',
    question: '哪些异常需要优先处理？',
    description: '汇总缺料、交付、工艺参数和冷链异常入口，帮助按证据优先级处理。',
    links: [
      { path: '/production/shortage-cases', title: '缺料异常', description: '查看缺料处理实例及其当前处理状态。', module: 'production' },
      { path: '/production/delivery-risks', title: '交付风险', description: '查看已有交付风险及其证据链。', module: 'production' },
      { path: '/quality/param-deviation', title: '工艺参数偏离', description: '查看报工参数判定快照和偏离处理。', module: 'quality' },
      { path: '/quality/cold-chain', title: '冷链超温', description: '查看已记录的冷链超温批次。', module: 'quality' },
    ],
  },
  {
    id: 'plans-approvals',
    path: '/workspaces/plans-approvals',
    title: '方案与审批',
    question: '计划和待审批事项现在到哪一步？',
    description: '连接现有生产计划、排程和 OA 审批入口，保持原有审批权限。',
    links: [
      { path: '/production/plans', title: '生产计划', description: '查看现有生产计划及其执行进度。', module: 'production' },
      { path: '/scheduling/plans', title: '排程计划', description: '查看已存在的排程计划。', module: 'scheduling' },
      { path: '/production/approval', title: '报工审批', description: '进入现有生产报工审批工作台。', module: 'production' },
      { path: '/workflow/pending', title: '待我审批', description: '查看当前账号已有的 OA 待办。', module: 'dashboard' },
    ],
  },
  {
    id: 'trace-review',
    path: '/workspaces/trace-review',
    title: '追溯与复盘',
    question: '如何核对批次事实并复盘生产结果？',
    description: '连接批次追溯、生产报表和经营报告入口，核对事实与结果。',
    links: [
      { path: '/production/traceability', title: '批次追溯', description: '按批次查看现有溯源链路。', module: 'production' },
      { path: '/production/analysis-query', title: '生产分析', description: '查看 BOM 达成率、工序投入产出和人效分析。', module: 'production' },
      { path: '/analytics/ai-reports', title: '经营报告', description: '回看已生成的经营分析报告。', module: 'analytics' },
    ],
  },
] as const;

export function isEligibleFactoryWorkspaceContext(context: WorkspaceVisibilityContext): boolean {
  return FACTORY_WORKSPACE_ROLES.includes(context.role as FactoryWorkspaceRole)
    && FACTORY_WORKSPACE_FACTORY_TYPES.includes(context.factoryType as (typeof FACTORY_WORKSPACE_FACTORY_TYPES)[number])
    && context.isPlatform === false
    && Boolean(context.factoryId?.trim())
    && context.canAccess('dashboard');
}

export function visibleWorkspaceLinks(
  workspace: FactoryWorkspace,
  context: WorkspaceVisibilityContext,
): WorkspaceLink[] {
  if (!isEligibleFactoryWorkspaceContext(context)) return [];

  return workspace.links.filter((link) => (
    context.canAccess(link.module)
    && isPathAllowedForRole(context.role, link.path)
    && context.canVisitRoute?.(link.path, context, link.module) === true
    && context.isPathVisible?.(link.path.split('?')[0], context) === true
  ));
}

export function visibleFactoryWorkspaces(context: WorkspaceVisibilityContext): FactoryWorkspace[] {
  if (!isEligibleFactoryWorkspaceContext(context)) return [];
  // Keep an eligible workspace visible when all of its links are denied so the
  // page can explain the permission boundary instead of showing a dead link.
  return FACTORY_WORKSPACES.map((workspace) => ({
    ...workspace,
    links: visibleWorkspaceLinks(workspace, context),
  }));
}

export function workspaceByPath(path: string): FactoryWorkspace | undefined {
  return FACTORY_WORKSPACES.find((workspace) => workspace.path === path);
}
