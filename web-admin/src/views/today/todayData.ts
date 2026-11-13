/**
 * P3「今天」「待办」两页的数据层（只读、前端聚合，不加后端接口）。
 *
 * 来源（全部是正式端已有接口，原型里读演示文件的项按此替换）：
 *   待我审批      GET /{f}/workflow/instances/pending            + 报废审批 GET /{f}/disposal-records?status=PENDING（仅有资格者）
 *   我发起的      GET /{f}/workflow/instances/my-created
 *   已处理/抄送   GET /{f}/workflow/instances/acted · /copied
 *   临期/过期批次 GET /{f}/material-batches/expiring?days=7 · /expired
 *   交付风险      GET /{f}/delivery-risks?openOnly=true
 *   待收货        GET /{f}/warehouse/receiving/tasks
 *   待审批无订单入库 GET /{f}/task-badges/unordered-inbound
 *   待处理销售订单   GET /{f}/task-badges/sales-orders
 *
 * G-1：每一路读取带 AbortController 超时（默认 10 s）。超时 ⇒ 这一路 state='error'、写「读取失败」，
 *      并且 inflight 在 finally 里释放，下一次 load() 一定会发新请求（不会被挂起的请求拖住）。
 *      超时同时用 Promise.race 兜底：即使下游不理 signal（挂起的假请求 / 被包装掉 signal），也会在时限到时放手。
 * G-2：state!=='ok' 的来源没有数字，页面不画角标，页签里写 note（原因）。
 *
 * 判据：⛔ 读不到不写 0，也不拿别的数顶；权限不够的来源【不发请求】，标 'na' 并写明原因。
 */
import { reactive } from 'vue';

export const READ_TIMEOUT_MS = 10_000;

export type SrcState = 'idle' | 'loading' | 'ok' | 'na' | 'error';
export interface TItem { label: string; sub?: string; to?: string }
export interface TSrc {
  id: SrcId;
  title: string;
  hint: string;
  state: SrcState;
  count: number;
  items: TItem[];
  /** state='ok' 时是口径说明；其它状态是不显示数字的原因（G-2）。 */
  note: string;
  link: string;
  linkLabel: string;
}

export type SrcId =
  | 'approve' | 'mine' | 'acted' | 'copied'
  | 'expiry' | 'risk' | 'receiving' | 'unordered' | 'salesOrders' | 'assigned';

interface ApiRes { success?: boolean; message?: string; data?: unknown; totalElements?: number; total?: number }
type Row = Record<string, unknown>;

export interface TodayDeps {
  get: (url: string, config?: Record<string, unknown>) => Promise<ApiRes>;
  timeoutMs?: number;
}

export interface TodayContext {
  factoryId: string;
  userId: string;
  /** 任一模块有读权限即 true（与后端 @RequirePermission requireAll=false 同义）。 */
  canRead: (modules: readonly string[]) => boolean;
  /** 报废审批资格：必须来自 disposalApproval store（后端 approval-permission），⛔ 不按岗位名。 */
  disposalCanApprove: () => Promise<boolean>;
}

const DEF: Record<SrcId, Pick<TSrc, 'title' | 'hint' | 'link' | 'linkLabel'>> = {
  approve: { title: '待我审批', hint: '审批中心里轮到我的审批实例，加上我有资格办的报废审批', link: '/workflow/pending', linkLabel: '去待我审批' },
  mine: { title: '我发起的', hint: '我发起、还在流转的审批', link: '/workflow/my-created', linkLabel: '去我发起的' },
  acted: { title: '已处理', hint: '我已经处理过的审批', link: '/workflow/acted', linkLabel: '去已处理' },
  copied: { title: '抄送我的', hint: '抄送给我的审批', link: '/workflow/copied', linkLabel: '去抄送我的' },
  expiry: { title: '临期 / 过期批次', hint: '原料批次 7 天内到期，含已过期', link: '/warehouse/materials', linkLabel: '去原料批次' },
  risk: { title: '交付风险', hint: '本厂未结束的交付风险处理实例里，系统判定「可能延期」的订单', link: '/production/delivery-risks', linkLabel: '去交付风险' },
  receiving: { title: '待收货任务', hint: '采购到货后仓库还没收完的收货任务', link: '/warehouse/materials?view=receiving', linkLabel: '去入库任务' },
  unordered: { title: '待审批的无订单入库申请', hint: '没有采购单就到货、等审批的入库申请', link: '/warehouse/unordered-inbound-applications', linkLabel: '去无订单入库' },
  salesOrders: { title: '待处理销售订单', hint: '按我的数据范围，需要有人动手的销售订单', link: '/sales/orders', linkLabel: '去销售订单' },
  assigned: { title: '指派给我', hint: '交付风险处理实例里负责人是我的', link: '/production/delivery-risks', linkLabel: '去交付风险' },
};
export const SRC_IDS = Object.keys(DEF) as SrcId[];

function blank(id: SrcId): TSrc {
  return { id, ...DEF[id], state: 'idle', count: 0, items: [], note: '' };
}

export class ReadTimeoutError extends Error {
  constructor(ms: number) { super(`超过 ${Math.round(ms / 1000)} 秒没有返回`); this.name = 'ReadTimeoutError'; }
}

const errMsg = (e: unknown) => (e instanceof Error && e.message ? e.message : '读取失败');
const asRows = (v: unknown): Row[] => (Array.isArray(v) ? (v as Row[]) : []);

export function createTodayLoader(deps: TodayDeps) {
  const timeoutMs = deps.timeoutMs ?? READ_TIMEOUT_MS;
  const T = reactive({
    factoryId: '',
    busy: false,
    loadedAt: 0,
    s: Object.fromEntries(SRC_IDS.map((id) => [id, blank(id)])) as Record<SrcId, TSrc>,
  });
  let inflight: { key: string; promise: Promise<void> } | null = null;
  let token = 0;

  function set(id: SrcId, patch: Partial<TSrc>) { T.s[id] = { ...T.s[id], ...patch }; }

  /** 单路读取：AbortController + Promise.race 超时；success=false 当错误抛出。 */
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
  /** 同上，但给 Promise 型来源（报废审批资格）限时。 */
  async function limited<R>(p: Promise<R>): Promise<R> {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const timeout = new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new ReadTimeoutError(timeoutMs)), timeoutMs); });
    try { return await Promise.race([p, timeout]); } finally { if (timer) clearTimeout(timer); }
  }

  const fail = (id: SrcId, e: unknown) => set(id, {
    state: 'error', count: 0, items: [],
    note: `读取失败：${errMsg(e)}。不显示数量，也不推算；下次进入会重新读取。`,
  });
  const na = (id: SrcId, reason: string) => set(id, { state: 'na', count: 0, items: [], note: reason });

  const pageTotal = (res: ApiRes): number => {
    const d = (res.data ?? {}) as Row;
    const n = Number(d.total ?? d.totalElements ?? res.totalElements ?? res.total);
    if (Number.isFinite(n)) return n;
    return pageRows(res).length;
  };
  const pageRows = (res: ApiRes): Row[] => {
    const d = res.data;
    if (Array.isArray(d)) return d as Row[];
    const o = (d ?? {}) as Row;
    return asRows(o.items ?? o.content ?? o.records);
  };

  const wfItem = (r: Row, to: string): TItem => ({
    label: String(r.businessSummary || r.businessCode || r.businessEntityId || r.instanceId || '审批'),
    sub: [r.moduleLabel || r.moduleCode, r.currentNodeLabel, r.initiatedByUsername].filter(Boolean).map(String).join(' · '),
    to,
  });

  async function loadOa(ctx: TodayContext, id: 'approve' | 'mine' | 'acted' | 'copied', path: string, to: string) {
    set(id, { state: 'loading' });
    const f = ctx.factoryId;
    try {
      const res = await read(`/${f}/workflow/instances/${path}`, { page: 1, size: 5 });
      let count = pageTotal(res);
      let items = pageRows(res).map((r) => wfItem(r, to));
      let note = '审批实例总数，口径同审批中心';
      if (id === 'approve') {
        // 报废审批：资格只看 disposalApproval store（后端 approval-permission），不按岗位名
        const eligible = await limited(ctx.disposalCanApprove());
        if (eligible) {
          const d = await read(`/${f}/disposal-records`, { status: 'PENDING', page: 0, size: 5 });
          const dRows = pageRows(d);
          const dTotal = Number(d.totalElements ?? (d.data as Row | undefined)?.totalElements ?? dRows.length);
          const n = Number.isFinite(dTotal) ? dTotal : dRows.length;
          count += n;
          items = [...items, ...dRows.map((r): TItem => ({
            label: `报废 ${String(r.recordNumber ?? r.id ?? '')}`.trim(),
            sub: '报废审批 · 待审批',
            to: '/finance/disposal-approvals',
          }))].slice(0, 8);
          note = `审批实例 ${count - n} + 报废审批 ${n}（本人有报废审批资格）`;
        } else {
          note = `审批实例 ${count}；报废审批：本人没有审批资格，不计入`;
        }
      }
      set(id, { state: 'ok', count, items, note });
    } catch (e) { fail(id, e); }
  }

  async function loadExpiry(ctx: TodayContext) {
    if (!ctx.canRead(['warehouse', 'inventory'])) {
      return na('expiry', '当前账号没有仓储 / 库存的读权限，不读取批次（不是「没有临期批次」）。');
    }
    set('expiry', { state: 'loading' });
    const f = ctx.factoryId;
    try {
      const [soon, gone] = await Promise.all([
        read(`/${f}/material-batches/expiring`, { days: 7 }),
        read(`/${f}/material-batches/expired`),
      ]);
      const expired = asRows(gone.data);
      const expiredIds = new Set(expired.map((b) => String(b.id ?? b.batchNumber)));
      const expiring = asRows(soon.data).filter((b) => !expiredIds.has(String(b.id ?? b.batchNumber)));
      const toItem = (b: Row, tag: string): TItem => ({
        label: String(b.batchNumber ?? b.id ?? '批次'),
        sub: [b.materialName ?? b.materialTypeName, `${tag}${b.expiryDate ? ' · 到期 ' + String(b.expiryDate).slice(0, 10) : ''}`].filter(Boolean).map(String).join(' · '),
        to: '/warehouse/materials',
      });
      set('expiry', {
        state: 'ok', count: expiring.length + expired.length,
        note: `7 天内到期 ${expiring.length}，已过期 ${expired.length}`,
        items: [...expired.map((b) => toItem(b, '已过期')), ...expiring.map((b) => toItem(b, '临期'))].slice(0, 8),
      });
    } catch (e) { fail('expiry', e); }
  }

  const riskReadable = (ctx: TodayContext) => ctx.canRead(['production', 'sales', 'procurement']);
  let riskRows: Row[] | null = null;
  async function loadRisk(ctx: TodayContext) {
    riskRows = null;
    if (!riskReadable(ctx)) {
      const why = '当前账号没有生产 / 销售 / 采购的读权限，不读取交付风险（不是「没有风险」）。';
      na('risk', why); na('assigned', why); return;
    }
    set('risk', { state: 'loading' }); set('assigned', { state: 'loading' });
    try {
      const res = await read(`/${ctx.factoryId}/delivery-risks`, { openOnly: true, size: 100 });
      const rows = asRows(res.data);
      riskRows = rows;
      const risky = rows.filter((r) => r.riskLevel === 'RISK');
      const unknown = rows.filter((r) => r.riskLevel === 'UNKNOWN').length;
      const toItem = (r: Row): TItem => ({
        label: `${String(r.orderNumber ?? r.salesOrderId ?? '订单')} · ${String(r.customerName ?? '')}`.replace(/ · $/, ''),
        sub: `${String(r.stageLabel ?? '')}${r.dueDate ? ' · 交期 ' + String(r.dueDate).slice(0, 10) : ''}`,
        to: '/production/delivery-risks',
      });
      set('risk', {
        state: 'ok', count: risky.length, items: risky.slice(0, 8).map(toItem),
        note: `判定可能延期 ${risky.length}；另有 ${unknown} 张数据不够判断（不算正常，也不算风险）；共 ${rows.length} 个未结束实例`,
      });
      const mine = rows.filter((r) => r.ownerUserId != null && String(r.ownerUserId) === ctx.userId);
      set('assigned', {
        state: 'ok', count: mine.length, items: mine.slice(0, 10).map(toItem),
        note: '只统计交付风险处理实例里负责人是我的；缺料异常等其它处理实例没有「指派给我」的统一接口，不在这里',
      });
    } catch (e) { fail('risk', e); fail('assigned', e); }
  }

  async function loadReceiving(ctx: TodayContext) {
    if (!ctx.canRead(['warehouse', 'inventory'])) {
      return na('receiving', '当前账号没有仓储 / 库存的读权限，不读取收货任务。');
    }
    set('receiving', { state: 'loading' });
    try {
      const res = await read(`/${ctx.factoryId}/warehouse/receiving/tasks`);
      const rows = asRows(res.data);
      set('receiving', {
        state: 'ok', count: rows.length, note: '按收货任务条数',
        items: rows.slice(0, 8).map((r): TItem => ({
          label: String(r.orderNumber ?? r.purchaseOrderNumber ?? r.id ?? '收货任务'),
          sub: String(r.supplierName ?? r.customerName ?? ''),
          to: '/warehouse/materials?view=receiving',
        })),
      });
    } catch (e) { fail('receiving', e); }
  }

  async function loadBadge(ctx: TodayContext, id: 'unordered' | 'salesOrders', path: string, modules: readonly string[], label: string) {
    if (!ctx.canRead(modules)) return na(id, `当前账号没有${label}所需模块的读权限，不读取。`);
    set(id, { state: 'loading' });
    try {
      const res = await read(`/${ctx.factoryId}/task-badges/${path}`);
      const d = (res.data ?? {}) as Row;
      if (d.state !== 'KNOWN' || typeof d.count !== 'number' || !Number.isFinite(d.count)) {
        throw new Error(String(d.reason || '服务端说这个数暂时算不出'));
      }
      set(id, { state: 'ok', count: d.count, items: [], note: '服务端按我的数据范围计数' });
    } catch (e) { fail(id, e); }
  }

  /** 读取全部来源。同一 (工厂, 用户) 并发调用复用同一次读取；任何情况下 finally 都释放 inflight。 */
  function load(ctx: TodayContext): Promise<void> {
    const key = `${ctx.factoryId}:${ctx.userId}`;
    if (inflight && inflight.key === key) return inflight.promise;
    if (!ctx.factoryId) { return Promise.resolve(); }
    if (T.factoryId !== ctx.factoryId) {
      for (const id of SRC_IDS) T.s[id] = blank(id);
    }
    T.factoryId = ctx.factoryId;
    const mine = ++token;
    T.busy = true;
    const promise = (async () => {
      try {
        await Promise.all([
          loadOa(ctx, 'approve', 'pending', '/workflow/pending'),
          loadOa(ctx, 'mine', 'my-created', '/workflow/my-created'),
          loadOa(ctx, 'acted', 'acted', '/workflow/acted'),
          loadOa(ctx, 'copied', 'copied', '/workflow/copied'),
          loadExpiry(ctx),
          loadRisk(ctx),
          loadReceiving(ctx),
          loadBadge(ctx, 'unordered', 'unordered-inbound', ['warehouse'], '无订单入库'),
          loadBadge(ctx, 'salesOrders', 'sales-orders', ['sales'], '销售订单'),
        ]);
        T.loadedAt = Date.now();
      } finally {
        if (inflight && inflight.promise === promise) inflight = null;
        if (mine === token) T.busy = false;
      }
    })();
    inflight = { key, promise };
    return promise;
  }

  function reset() {
    inflight = null;
    token++;
    T.factoryId = '';
    T.busy = false;
    for (const id of SRC_IDS) T.s[id] = blank(id);
  }

  return { T, load, reset, readTimeoutMs: timeoutMs };
}

export type TodayLoader = ReturnType<typeof createTodayLoader>;
