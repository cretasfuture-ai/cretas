/**
 * 迁移 P4「发现的问题」(/issues) 的数据层：只读聚合各来源已有接口 + 本页的处置记录。
 *
 * 分类（DECISIONS R27/R33）：数字员工汇报 / 规则预警 / 对账差异 / 数据缺口。
 *   数字员工汇报  数字班组 P8 才上线 ⇒ 现在没有来源，页签写明原因、不画数量。
 *   规则预警      缺料异常、交付风险（判定可能延期）、工艺参数偏离、成本 / 损耗异常、冷链超温告警
 *                （= 原「异常中心」四个入口 + 每日差异核对建的成本 / 损耗异常）。
 *   对账差异      Cretas ↔ 外部软件（黑湖）对账，只在有外部接口授权的工厂出现（MIGRATION-PLAN 4.1）；
 *                授权判定在 P10 才有 ⇒ 本批整类不渲染（不是显示空页签）。
 *   数据缺口      交付风险里「数据不够判断」的订单、每日差异核对最近一次「没量到 / 部分 / 失败」。
 *   （数据疑点 R30 是 P5 的事：只在 CATEGORY_DEFS 留位置，本批不渲染。）
 *
 * 处置：关闭（必须选结论 + 写一句）/ 转成我的待办 / 恢复，只记在 factory_issue_dispositions，
 *       ⛔ 不改来源单据；来源仍在各自页面按原流程处理。
 *
 * G-1：每一路读取带超时（默认 10 s），超时 ⇒ 这一路「读取失败」，inflight 在 finally 释放。
 * G-2：读不到的来源不写 0、不画角标，写明原因；权限不够的来源不发请求，标「不适用」。
 * 处置记录读不到 ⇒ 不知道哪些已关闭 ⇒ 整页禁用关闭 / 转待办（不猜）。
 */
import { reactive } from 'vue';
import type { IssueDispositionView, IssueSourceType } from '@/api/issues';

export const READ_TIMEOUT_MS = 10_000;

export type IssueCategory = 'report' | 'rule' | 'reconcile' | 'gap';
export type SrcState = 'idle' | 'loading' | 'ok' | 'na' | 'error';

export interface CategoryDef {
  id: IssueCategory;
  label: string;
  hint: string;
  /** false ⇒ 整类不渲染（对账差异：没有外部接口授权的工厂不出现这一类）。 */
  rendered: boolean;
  /** 没有任何来源时写在页签里的原因。 */
  reason?: string;
}

export const CATEGORY_DEFS: readonly CategoryDef[] = [
  {
    id: 'report', label: '数字员工汇报', rendered: true,
    hint: '数字员工扫描数据后发现的问题和提醒',
    reason: '数字员工汇报依赖数字班组，数字班组在 P8 才上线，现在没有数据来源，不显示数量。',
  },
  { id: 'rule', label: '规则预警', rendered: true, hint: '系统规则触发、还没处理完的异常：缺料、交付风险、工艺参数偏离、成本 / 损耗异常、冷链超温' },
  {
    id: 'reconcile', label: '对账差异', rendered: false,
    hint: 'Cretas 与外部软件两边记录对不上的',
    reason: '只在有外部软件接口授权的工厂出现；授权判定在 P10 上线。',
  },
  { id: 'gap', label: '数据缺口', rendered: true, hint: '缺数据、算不出的地方：交付风险数据不够判断、每日差异核对没量到' },
  // P5：{ id: 'suspect', label: '数据疑点', ... } —— R30，不当异常，只列出来找谁核。
] as const;

export const RENDERED_CATEGORIES = CATEGORY_DEFS.filter((c) => c.rendered);

export interface Issue {
  /** sourceType:sourceId，处置记录按它对上。 */
  key: string;
  sourceType: IssueSourceType;
  sourceId: string;
  category: IssueCategory;
  /** 来源名（缺料异常 / 交付风险 …），显示成标签。 */
  sourceLabel: string;
  title: string;
  summary: string;
  owner?: string;
  at?: string;
  link: string;
  linkLabel: string;
}

export type SrcId = 'shortage' | 'risk' | 'paramDeviation' | 'costDeviation' | 'coldChain' | 'dailyRun';

export interface IssueSrc {
  id: SrcId;
  title: string;
  /** 这一路会产出哪些分类的问题（交付风险同时产出规则预警与数据缺口）。 */
  categories: IssueCategory[];
  state: SrcState;
  note: string;
}

const SRC_DEF: Record<SrcId, Pick<IssueSrc, 'title' | 'categories'>> = {
  shortage: { title: '缺料异常', categories: ['rule'] },
  risk: { title: '交付风险', categories: ['rule', 'gap'] },
  paramDeviation: { title: '工艺参数偏离', categories: ['rule'] },
  costDeviation: { title: '成本 / 损耗异常', categories: ['rule'] },
  coldChain: { title: '冷链超温', categories: ['rule'] },
  dailyRun: { title: '每日差异核对', categories: ['gap'] },
};
export const SRC_IDS = Object.keys(SRC_DEF) as SrcId[];

interface ApiRes { success?: boolean; message?: string; data?: unknown }
type Row = Record<string, unknown>;

export interface IssuesDeps {
  get: (url: string, config?: Record<string, unknown>) => Promise<ApiRes>;
  timeoutMs?: number;
}

export interface IssuesContext {
  factoryId: string;
  /** 任一模块有读权限即 true（与后端 @RequirePermission requireAll=false 同义）。 */
  canRead: (modules: readonly string[]) => boolean;
}

export class ReadTimeoutError extends Error {
  constructor(ms: number) { super(`超过 ${Math.round(ms / 1000)} 秒没有返回`); this.name = 'ReadTimeoutError'; }
}

const errMsg = (e: unknown) => (e instanceof Error && e.message ? e.message : '读取失败');
const asRows = (v: unknown): Row[] => (Array.isArray(v) ? (v as Row[]) : []);
const str = (v: unknown) => (v === null || v === undefined ? '' : String(v));
const minute = (v: unknown) => str(v).replace('T', ' ').slice(0, 16);
export const issueKey = (t: IssueSourceType, id: string) => `${t}:${id}`;

function blank(id: SrcId): IssueSrc {
  return { id, ...SRC_DEF[id], state: 'idle', note: '' };
}

/** 原「异常中心」只读这几种冷链告警状态之外的都算还没处理完。 */
const COLD_CHAIN_DONE = new Set(['RESOLVED', 'CLOSED']);
const RUN_GAP = new Set(['NOT_MEASURED', 'PARTIAL', 'FAILED']);
const RUN_LABEL: Record<string, string> = { NOT_MEASURED: '没量到', PARTIAL: '只量了一部分', FAILED: '扫描失败' };

export function createIssuesLoader(deps: IssuesDeps) {
  const timeoutMs = deps.timeoutMs ?? READ_TIMEOUT_MS;
  const S = reactive({
    factoryId: '',
    busy: false,
    loadedAt: 0,
    src: Object.fromEntries(SRC_IDS.map((id) => [id, blank(id)])) as Record<SrcId, IssueSrc>,
    issues: [] as Issue[],
    dispositions: {} as Record<string, IssueDispositionView>,
    dispositionState: 'idle' as SrcState,
    dispositionNote: '',
  });
  let inflight: { key: string; promise: Promise<void> } | null = null;

  const setSrc = (id: SrcId, patch: Partial<IssueSrc>) => { S.src[id] = { ...S.src[id], ...patch }; };

  async function read(url: string, params?: Record<string, unknown>): Promise<ApiRes> {
    const ac = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const timeout = new Promise<never>((_, reject) => {
      timer = setTimeout(() => { ac.abort(); reject(new ReadTimeoutError(timeoutMs)); }, timeoutMs);
    });
    try {
      const res = await Promise.race([deps.get(url, { params, signal: ac.signal, _silent: true }), timeout]);
      if (!res || res.success === false) throw new Error(res?.message || '接口返回失败');
      return res;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  const fail = (id: SrcId, e: unknown) => setSrc(id, {
    state: 'error', note: `读取失败：${errMsg(e)}。这一路不显示、也不算进数量；刷新会重新读取。`,
  });
  const na = (id: SrcId, why: string) => setSrc(id, { state: 'na', note: why });

  type Job = (ctx: IssuesContext) => Promise<Issue[]>;

  const shortage: Job = async (ctx) => {
    const rows = asRows((await read(`/${ctx.factoryId}/shortage-cases`, { openOnly: true, size: 100 })).data);
    return rows.map((r) => ({
      key: issueKey('SHORTAGE_CASE', str(r.id)), sourceType: 'SHORTAGE_CASE', sourceId: str(r.id), category: 'rule',
      sourceLabel: '缺料异常',
      title: `缺料 · 订单 ${str(r.salesOrderNumber) || str(r.sourceId)}`,
      summary: `${str(r.stageLabel)}${Number(r.gapLineCount) > 0 ? ` · ${Number(r.gapLineCount)} 种物料有缺口` : ''}`,
      owner: str(r.ownerRole) || undefined, at: minute(r.lastRefreshedAt),
      link: '/production/shortage-cases', linkLabel: '去缺料异常',
    }));
  };

  const risk: Job = async (ctx) => {
    const rows = asRows((await read(`/${ctx.factoryId}/delivery-risks`, { openOnly: true, size: 100 })).data);
    return rows.filter((r) => r.riskLevel === 'RISK' || r.riskLevel === 'UNKNOWN').map((r) => {
      const unknown = r.riskLevel === 'UNKNOWN';
      const order = `${str(r.orderNumber) || str(r.salesOrderId)}${r.customerName ? ' · ' + str(r.customerName) : ''}`;
      return {
        key: issueKey('DELIVERY_RISK', str(r.id)), sourceType: 'DELIVERY_RISK', sourceId: str(r.id),
        category: unknown ? 'gap' : 'rule',
        sourceLabel: '交付风险',
        title: unknown ? `交付数据不够判断 · ${order}` : `可能延期 · ${order}`,
        summary: `${str(r.stageLabel)}${r.dueDate ? ' · 交期 ' + str(r.dueDate).slice(0, 10) : ''}${unknown ? ' · 不算正常，也不算风险' : ''}`,
        owner: str(r.ownerName) || str(r.ownerRole) || undefined, at: minute(r.assessedAt),
        link: '/production/delivery-risks', linkLabel: '去交付风险',
      } satisfies Issue;
    });
  };

  const paramDeviation: Job = async (ctx) => {
    const rows = asRows((await read(`/${ctx.factoryId}/quality/process-param-deviation/cases`, { openOnly: true, size: 100 })).data);
    return rows.map((r) => ({
      key: issueKey('PROCESS_PARAM_DEVIATION', str(r.id)), sourceType: 'PROCESS_PARAM_DEVIATION', sourceId: str(r.id),
      category: 'rule', sourceLabel: '工艺参数偏离',
      title: `工艺参数偏离 · ${[r.processName, r.paramLabel].filter(Boolean).map(str).join(' · ') || '参数'}`,
      summary: [r.batchNumber ? '批次 ' + str(r.batchNumber) : '', str(r.stageLabel)].filter(Boolean).join(' · '),
      owner: str(r.ownerName) || str(r.ownerRole) || undefined, at: minute(r.observedAt || r.lastRefreshedAt),
      link: '/quality/param-deviation', linkLabel: '去工艺参数偏离',
    }));
  };

  const costDeviation: Job = async (ctx) => {
    const rows = asRows((await read(`/${ctx.factoryId}/cost-deviation-cases`, { openOnly: true, size: 100 })).data);
    return rows.map((r) => ({
      key: issueKey('COST_DEVIATION', str(r.id)), sourceType: 'COST_DEVIATION', sourceId: str(r.id),
      category: 'rule', sourceLabel: str(r.sourceLabel) || '成本 / 损耗异常',
      title: `${str(r.sourceLabel) || '成本 / 损耗异常'} · 计划 ${str(r.planNumber) || str(r.planId)}`,
      summary: [str(r.productName),
        r.metricPct != null && r.thresholdPct != null ? `偏离 ${str(r.metricPct)}%（阈值 ${str(r.thresholdPct)}%）` : '',
        str(r.stageLabel)].filter(Boolean).join(' · '),
      owner: str(r.ownerName) || str(r.ownerRole) || undefined, at: minute(r.lastRefreshedAt),
      link: '/finance/cost-deviation-cases', linkLabel: '去成本 / 损耗异常',
    }));
  };

  const coldChain: Job = async (ctx) => {
    const rows = asRows((await read(`/${ctx.factoryId}/cold-chain/alerts`, { days: 30 })).data);
    return rows.filter((r) => !COLD_CHAIN_DONE.has(str(r.status))).map((r) => ({
      key: issueKey('COLD_CHAIN_ALERT', str(r.id)), sourceType: 'COLD_CHAIN_ALERT', sourceId: str(r.id),
      category: 'rule', sourceLabel: '冷链超温',
      title: `冷链超温 · ${str(r.equipmentName) || str(r.equipmentCode) || '设备 ' + str(r.equipmentId)}`,
      summary: `${str(r.minTemp)}～${str(r.maxTemp)} ℃，持续 ${str(r.durationMinutes)} 分钟${Number(r.heldBatchCount) > 0 ? ` · 已转质检 ${Number(r.heldBatchCount)} 批` : ''}`,
      at: minute(r.alertTime),
      link: '/quality/cold-chain', linkLabel: '去冷链超温',
    }));
  };

  const dailyRun: Job = async (ctx) => {
    const rows = asRows((await read(`/${ctx.factoryId}/cost-deviation-cases/daily-runs`)).data);
    const latest = rows[0];
    if (!latest || !RUN_GAP.has(str(latest.status))) return [];
    return [{
      key: issueKey('DAILY_RECONCILE_RUN', str(latest.id)), sourceType: 'DAILY_RECONCILE_RUN', sourceId: str(latest.id),
      category: 'gap', sourceLabel: '每日差异核对',
      title: `每日差异核对${RUN_LABEL[str(latest.status)] ?? str(latest.status)}`,
      summary: str(latest.notMeasuredReason) || '没有写原因',
      at: minute(latest.startedAt),
      link: '/finance/cost-deviation-cases', linkLabel: '去成本 / 损耗异常',
    }];
  };

  const JOBS: Record<SrcId, { modules: string[]; job: Job }> = {
    shortage: { modules: ['procurement', 'production', 'warehouse', 'quality', 'sales'], job: shortage },
    risk: { modules: ['production', 'sales', 'procurement'], job: risk },
    paramDeviation: { modules: ['quality', 'production'], job: paramDeviation },
    costDeviation: { modules: ['finance', 'production'], job: costDeviation },
    coldChain: { modules: ['quality', 'warehouse'], job: coldChain },
    dailyRun: { modules: ['finance', 'production'], job: dailyRun },
  };

  async function runSource(ctx: IssuesContext, id: SrcId): Promise<Issue[]> {
    const { modules, job } = JOBS[id];
    if (!ctx.canRead(modules)) {
      na(id, `当前账号没有读取${SRC_DEF[id].title}所需的模块权限，不读取（不是「没有问题」）。`);
      return [];
    }
    setSrc(id, { state: 'loading', note: '' });
    try {
      const out = await job(ctx);
      setSrc(id, { state: 'ok', note: `${out.length} 条` });
      return out;
    } catch (e) {
      fail(id, e);
      return [];
    }
  }

  async function loadDispositions(ctx: IssuesContext) {
    S.dispositionState = 'loading';
    try {
      const rows = asRows((await read(`/${ctx.factoryId}/issues/dispositions`)).data) as unknown as IssueDispositionView[];
      S.dispositions = Object.fromEntries(rows.map((d) => [issueKey(d.sourceType, d.sourceId), d]));
      S.dispositionState = 'ok';
      S.dispositionNote = '';
    } catch (e) {
      S.dispositions = {};
      S.dispositionState = 'error';
      S.dispositionNote = `处置记录读取失败：${errMsg(e)}。不知道哪些问题已关闭，先不允许关闭 / 转待办，刷新后再试。`;
    }
  }

  function load(ctx: IssuesContext): Promise<void> {
    if (!ctx.factoryId) return Promise.resolve();
    if (inflight && inflight.key === ctx.factoryId) return inflight.promise;
    if (S.factoryId !== ctx.factoryId) {
      for (const id of SRC_IDS) S.src[id] = blank(id);
      S.issues = [];
      S.dispositions = {};
      S.dispositionState = 'idle';
    }
    S.factoryId = ctx.factoryId;
    S.busy = true;
    const promise = (async () => {
      try {
        const [lists] = await Promise.all([
          Promise.all(SRC_IDS.map((id) => runSource(ctx, id))),
          loadDispositions(ctx),
        ]);
        S.issues = lists.flat();
        S.loadedAt = Date.now();
      } finally {
        if (inflight && inflight.promise === promise) inflight = null;
        S.busy = false;
      }
    })();
    inflight = { key: ctx.factoryId, promise };
    return promise;
  }

  /** 写入成功后就地更新这一条的处置（不必整页重读）。 */
  function applyDisposition(d: IssueDispositionView) {
    const k = issueKey(d.sourceType, d.sourceId);
    if (d.status === 'OPEN') {
      const { [k]: _gone, ...rest } = S.dispositions;
      S.dispositions = rest;
    } else {
      S.dispositions = { ...S.dispositions, [k]: d };
    }
  }

  return { S, load, applyDisposition };
}

export type IssuesLoader = ReturnType<typeof createIssuesLoader>;

/** 一条问题在本页的状态。 */
export function issueStatus(i: Issue, dispositions: Record<string, IssueDispositionView>): 'open' | 'converted' | 'closed' {
  const d = dispositions[i.key];
  if (d?.status === 'CLOSED') return 'closed';
  if (d?.status === 'CONVERTED') return 'converted';
  return 'open';
}

/**
 * 页签角标（G-2）：这一类的来源全部读到才给数（未关闭的条数）；有一路失败 / 还在读 ⇒ null；
 * 这一类没有来源（数字员工汇报）或全部不适用 ⇒ null；处置记录没读到（不知道哪些已关闭）⇒ null。
 */
export function categoryBadge(
  cat: IssueCategory,
  src: Record<SrcId, IssueSrc>,
  issues: Issue[],
  dispositions: Record<string, IssueDispositionView>,
  dispositionsOk: boolean,
): number | null {
  if (!dispositionsOk) return null;
  const parts = SRC_IDS.map((id) => src[id]).filter((s) => s.categories.includes(cat));
  if (!parts.length) return null;
  if (parts.some((p) => p.state === 'error' || p.state === 'loading' || p.state === 'idle')) return null;
  if (!parts.some((p) => p.state === 'ok')) return null;
  return issues.filter((i) => i.category === cat && issueStatus(i, dispositions) !== 'closed').length;
}
