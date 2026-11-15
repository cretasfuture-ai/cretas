// 第八批 R38：「今天」「待办」两页的真实数据（只读）。
// 来源都是各线现有接口 / 数据文件，不新增接口、不写任何东西：
//   A 待称重任务  = 黑湖快照里「执行中」的工序任务，且 Cretas 还没有称重记录（/__unified/a/api/weighins，口径同 D 首页岗位待办）
//   C 待建批次 / 临期与过期批次 = /api/inventory-source 的入库单 + /__lsm/supply/batches（口径同 D 首页岗位待办）
//   F 等我确认  = 数字班组收件箱里待确认且我有办理权限的事项（crew-store）
//   D 对账差异  = A 的 /__unified/a/api/weighin-reconcile、C 的 /__lsm/supply/batch-reconcile（和 D 对账汇总页同一来源）
//   E 规则提醒 / 任务 = /__unified/e/api/rules、/__unified/e/api/state
// 「今天」= 快照日（R28）：checkedAt 换算北京时间的日期；味美工作区没有这些数据，一律写明，不借六膳门的数。
import { reactive } from 'vue'
import { S } from '../frame/store'
import { reconcile } from '../frame/ai-protocol'
import { C, loadAll, src, mayConfirm, syncContext } from '../crew/crew-store'
import { crewById } from '../crew/crew-config'
import { buildReports } from '../crew/crew-reports'
import { kit, type KitOrderStatus } from '../production/kitting'
import { assess, type Risk } from '../production/delivery-risk'
import { bjDay } from '../production/time'
import { num } from '../production/ydwm-a'

export interface TItem { label: string; sub?: string; to: string }
export type TState = 'idle' | 'loading' | 'ok' | 'na' | 'error'
export interface TSrc { id: string; title: string; hint: string; state: TState; count: number; items: TItem[]; note: string; link: string; linkLabel: string; line: string; asOf?: string; total?: number }
type Row = Record<string, unknown>

const DEF: Record<string, Pick<TSrc, 'title' | 'hint' | 'link' | 'linkLabel' | 'line'>> = {
  weigh: { title: '待称重任务', hint: '黑湖「执行中」且 Cretas 还没有称重的工序任务', link: '/production/weigh', linkLabel: '去称重报工', line: 'A' },
  batch: { title: '待建批次的入库单', hint: '近 7 天黑湖采购入库、成品入库里还没关联 Cretas 批次的单', link: '/lsm/inbound-batches', linkLabel: '去入库批次', line: 'C' },
  expiry: { title: '临期 / 过期批次', hint: 'Cretas 批次 7 天内到期，含已过期', link: '/lsm/inbound-batches', linkLabel: '去入库批次', line: 'C' },
  confirm: { title: '等我确认', hint: '数字员工要改数据的事，先出预览，等有权限的人确认', link: 'F:inbox', linkLabel: '逐条确认', line: 'F' },
  reconW: { title: '称重报工 ↔ 黑湖报工', hint: '两边对不上的称重记录', link: '/lsm/reconcile', linkLabel: '去对账汇总', line: 'D' },
  reconB: { title: '入库批次 ↔ 黑湖入库单', hint: '两边对不上的入库批次', link: '/lsm/reconcile', linkLabel: '去对账汇总', line: 'D' },
  rules: { title: '规则提醒', hint: '协同任务规则触发、还没处理的提醒（首检未做、任务超期、检验不合格未建任务）', link: '/collab/tasks', linkLabel: '去协同任务', line: 'E' },
  risk: { title: '交付风险（高 / 中）', hint: '订单交期 × 计划进度 × 齐套，代码判定；六膳门的黑湖没有订单到工单的关联，计划进度写「不能判断」', link: '/production/delivery-risks', linkLabel: '去交付风险', line: 'A' },
  oaPending: { title: '待我处理的审批', hint: '黑湖报工审批待审批 + 未完成的协同任务；原型不识别登录人，不按人过滤', link: '/workflow/pending', linkLabel: '去审批中心', line: 'D' },
  oaMine: { title: '我发起的', hint: 'Cretas 协同任务（黑湖快照没有发起人字段）', link: '/workflow/my-created', linkLabel: '去我发起的', line: 'D' },
  oaActed: { title: '已处理', hint: '已完成 / 已取消的协同任务 + 黑湖报工审批已批准、未通过', link: '/workflow/acted', linkLabel: '去已处理', line: 'D' },
  oaCopied: { title: '抄送我的', hint: '黑湖快照没有抄送字段', link: '/workflow/copied', linkLabel: '去抄送我的', line: 'D' },
  tasks: { title: '协同任务（未完成）', hint: '待处理、进行中的协同任务', link: '/collab/tasks', linkLabel: '打开协同任务', line: 'E' }
}

export const T = reactive({
  ws: 'lsm' as 'lsm' | 'ydwm', day: '', checkedAt: '', loaded: false, busy: false,
  s: {} as Record<string, TSrc>,
  chips: { 汇报: 0, 预警: 0, 对账: 0, 缺口: 0, 疑点: 0 } as Record<string, number>,
  doneToday: 0
})
const mk = (id: string, patch: Partial<TSrc> = {}): TSrc => ({ id, ...DEF[id], state: 'idle', count: 0, items: [], note: '', ...patch })
for (const id of Object.keys(DEF)) T.s[id] = mk(id)

const bj = (t: number) => new Date(t + 8 * 3600e3).toISOString().slice(0, 10)
const days = (a: string, b: string) => Math.round((Date.parse(a + 'T00:00:00Z') - Date.parse(b + 'T00:00:00Z')) / 864e5)
const fmt = (iso?: string | null) => { const t = Date.parse(String(iso || '')); return Number.isFinite(t) ? new Date(t + 8 * 3600e3).toISOString().slice(0, 16).replace('T', ' ') : '' }
const hdr = (): Record<string, string> => C.session ? { 'x-acceptance-session': C.session } : {}
const withS = (u: string) => C.session ? u + (u.includes('?') ? '&' : '?') + 'acceptanceSession=' + encodeURIComponent(C.session) : u
async function get(url: string): Promise<{ status: number; body: { success?: boolean; message?: string; data?: any } }> { // eslint-disable-line @typescript-eslint/no-explicit-any
  const r = await fetch(withS(url), { headers: hdr() }); const body = await r.json().catch(() => ({})); return { status: r.status, body }
}
const errMsg = (e: unknown) => e instanceof Error ? e.message : '读取失败'
function set(id: string, patch: Partial<TSrc>) { T.s[id] = { ...T.s[id], ...patch } }

// 味美工作区：六膳门的数据不能借给味美
const NO_YDWM = '味美工作区没有这类数据（只有本机练习数据），这里不显示；六膳门工作区里有。'

async function loadWeigh() {
  set('weigh', { state: 'loading' })
  try {
    const { status, body } = await get('/__unified/a/api/weighins?ws=lsm')
    if (status >= 400 || !body.success) throw Error(body.message || '称重记录读取失败')
    const d = body.data || {}
    const weighed = new Set<string>([...(d.entries || []).filter((e: Row) => !e.revoked && !e.revokedAt).map((e: Row) => String(e.taskId)), ...Object.keys(d.tasks || {})])
    const res = (S.source?.resources || {}) as Record<string, Row[]>
    const proj = new Map((res.project || []).map(p => [String(p.id), p]))
    const list = (res.task || []).filter(t => String(t.processStatus) === '10' && !weighed.has(String(t.id)))
    set('weigh', {
      state: 'ok', count: list.length, asOf: S.source?.checkedAt as string,
      note: '黑湖采集核验 ' + fmt(S.source?.checkedAt as string) + '（北京时间）；Cretas 已称重的任务不再算待办',
      items: list.slice(0, 8).map(t => ({ label: String(proj.get(String(t.projectId))?.projectCode || t.projectId) + ' · ' + String(t.processName || ''), sub: String(proj.get(String(t.projectId))?.productName || t.productCode || ''), to: '/production/weigh?task=' + t.id }))
    })
  } catch (e) { set('weigh', { state: 'error', note: '称重记录读取失败（' + errMsg(e) + '），不显示数量，也不推算。' }) }
}

async function loadBatches() {
  set('batch', { state: 'loading' }); set('expiry', { state: 'loading' })
  let batches: Row[] | null = null, orders: Row[] | null = null
  try {
    const { status, body } = await get('/__lsm/supply/batches')
    if (status >= 400 || !body.success) throw Error(body.message || '批次读取失败')
    batches = (body.data.batches || []).filter((x: Row) => !x.revokedAt)
  } catch (e) { const n = '批次读取失败（' + errMsg(e) + '），不显示数量，也不推算。'; set('batch', { state: 'error', note: n }); set('expiry', { state: 'error', note: n }); return }
  const today = T.day
  const expiring = batches!.filter(b => b.expiryDate).map(b => ({ b, left: days(String(b.expiryDate), today) })).filter(x => x.left <= 7).sort((a, b) => a.left - b.left)
  set('expiry', {
    state: 'ok', count: expiring.length, note: '按快照日 ' + today + ' 算；到期按北京时间日历日',
    items: expiring.slice(0, 8).map(({ b, left }) => ({ label: String(b.batchNo), sub: String(b.productName || b.productCode || '') + ' · 到期 ' + String(b.expiryDate) + ' · ' + (left < 0 ? '已过期 ' + (-left) + ' 天' : left === 0 ? '今天到期' : '剩 ' + left + ' 天'), to: '/lsm/inbound-batches?batch=' + b.batchNo }))
  })
  try {
    const r = await fetch('/api/inventory-source'), b = await r.json()
    if (!r.ok || !b.success || b.data.factoryId !== 'LIUSHANMEN') throw Error(b.message || '库存来源读取失败')
    orders = b.data.orders || []
  } catch (e) { set('batch', { state: 'error', note: '入库单读取失败（' + errMsg(e) + '），不显示数量，也不推算。' }); return }
  const linked = new Set(batches!.map(x => String(x.blOrderNo || '')).filter(Boolean))
  const list = orders!.filter(o => ['采购入库', '成品入库'].includes(String(o.bizTypeDisplay)) && !linked.has(String(o.orderNo)) && o.bizAt && days(today, String(o.bizAt).slice(0, 10)) <= 7 && days(today, String(o.bizAt).slice(0, 10)) >= 0)
  set('batch', {
    state: 'ok', count: list.length, note: '近 7 天（按快照日 ' + today + '）；黑湖单据时点以各单为准',
    items: list.slice(0, 8).map(o => ({ label: String(o.orderNo), sub: String(o.bizTypeDisplay) + ' · ' + String(o.bizAt).slice(0, 16) + ' · ' + ((o.lines as Row[]) || []).length + ' 行', to: '/lsm/inbound-batches?order=' + o.orderNo }))
  })
}

function loadConfirm() {
  const st = C.state
  if (!st) { set('confirm', { state: 'error', note: '数字班组收件箱没读到，不显示数量。' }); return }
  const mine = st.inbox.filter(x => x.status === 'pending' && mayConfirm(x))
  const hidden = st.inbox.filter(x => x.status === 'pending').length - mine.length
  set('confirm', {
    state: 'ok', count: mine.length, note: '只显示我有权限办理的' + (hidden > 0 ? '；另有 ' + hidden + ' 条因没有对应模块权限不显示' : ''),
    items: mine.slice(0, 8).map(x => ({ label: x.title, sub: (crewById(x.employee)?.name || x.employee) + ' · ' + x.module, to: 'F:inbox' }))
  })
}

async function loadRecon(id: 'reconW' | 'reconB', url: string, owner: string) {
  set(id, { state: 'loading' })
  try {
    const { status, body } = await get(url)
    if (status === 404 || status === 503 || body.message === '未找到' || body.message === '未知接口') { set(id, { state: 'na', note: (id === 'reconW' ? 'A' : 'C') + ' 线的对账接口还没接上，不推算。' }); return }
    if (status >= 400 || !body.success) throw Error(body.message || '读取失败')
    const d = body.data
    if (d.available === false) { set(id, { state: 'na', note: d.reason || '当前工作区没有这项对账' }); return }
    const rows = (d.rows || []) as { key: string; label?: string; owner?: string; cretas: Row | null; blacklake: Row | null }[]
    const fields = (d.fields || []).map((f: { key: string; label: string }) => ({ key: f.key, label: f.label, digits: f.key === 'count' ? 0 : 2, cretas: (x: Row) => x[f.key], blacklake: (x: Row) => x[f.key] }))
    const res = reconcile({ cretas: rows.filter(x => x.cretas).map(x => ({ ...x.cretas, __k: x.key, __l: x.label || '' })), blacklake: rows.filter(x => x.blacklake).map(x => ({ ...x.blacklake, __k: x.key, __l: x.label || '' })), key: (x: Row) => x.__k, label: (x: Row | null) => String(x?.__l || ''), fields } as never)
    const c = res.counts as Record<string, number>
    const bad = (res.rows as { key: string; state: string; label?: string }[]).filter(x => x.state === 'diff' || x.state === 'cretasOnly')
    const own = (k: string) => rows.find(x => x.key === k)?.owner || owner
    const bo = c.blacklakeOnly || 0
    set(id, {
      state: 'ok', count: (c.diff || 0) + (c.cretasOnly || 0), total: (c.diff || 0) + (c.cretasOnly || 0) + bo, asOf: d.asOf || undefined,
      note: '有差额 ' + (c.diff || 0) + '，只在 Cretas ' + (c.cretasOnly || 0) + '，一致 ' + (c.match || 0) + (bo ? '；只在黑湖 ' + bo + ' 条（Cretas 还没录，多是待称重 / 待建批次，不算差额）' : '') + (d.asOf ? ' · 黑湖数据时点 ' + fmt(d.asOf) : ''),
      items: bad.slice(0, 8).map(x => ({ label: x.label || rows.find(y => y.key === x.key)?.label || x.key, sub: (x.state === 'diff' ? '有差额' : '只在 Cretas') + ' · 找' + own(x.key), to: '/lsm/reconcile' }))
    })
  } catch (e) { set(id, { state: 'error', note: '对账读取失败（' + errMsg(e) + '），不显示数量，也不推算。' }) }
}

let allTasks: Row[] = []
async function loadE() {
  allTasks = []
  set('rules', { state: 'loading' }); set('tasks', { state: 'loading' })
  type Rem = { id: string; rule: string; title: string; role: string; status: string; at: string; ref?: { code?: string } }
  let reminders: Rem[] = []
  try {
    const { status, body } = await get('/__unified/e/api/rules?ws=lsm')
    if (status >= 400 || !body.success) throw Error(body.message || '读取失败')
    reminders = (body.data.reminders || []).filter((m: Rem) => m.status === 'open')
    set('rules', { state: 'ok', count: reminders.length, note: reminders.length ? '规则提醒按触发时间，不改来源数据' : '规则都在运行，没有未处理的提醒',
      items: reminders.slice(0, 8).map(m => ({ label: m.title, sub: (m.ref?.code ? m.ref.code + ' · ' : '') + (m.role || '负责岗位'), to: '/collab/tasks' })) })
  } catch (e) { set('rules', { state: 'error', note: '规则提醒读取失败（' + errMsg(e) + '），不显示数量。' }) }
  try {
    const { status, body } = await get('/__unified/e/api/state?ws=lsm')
    if (status >= 400 || !body.success) throw Error(body.message || '读取失败')
    allTasks = body.data.tasks || []
    const tasks = allTasks.filter((t: Row) => t.status === 'TODO' || t.status === 'DOING')
    set('tasks', { state: 'ok', count: tasks.length, note: '原型没有登录人与岗位的对应关系，这里列出全部未完成的任务（待处理 + 进行中）',
      items: tasks.slice(0, 8).map((t: Row) => ({ label: String(t.no) + ' · ' + String(t.title), sub: ((t.assignee as Row)?.name || (t.assignee as Row)?.role || '未指定') + ' · ' + (t.status === 'DOING' ? '进行中' : '待处理') + (t.due ? ' · 截止 ' + String(t.due).slice(0, 10) : ''), to: '/collab/tasks' })) })
  } catch (e) { set('tasks', { state: 'error', note: '协同任务读取失败（' + errMsg(e) + '），不显示数量。' }) }
}

// 「发现的问题」各来源条数：口径同 F 的 /crew/issues（汇报 / 疑点 / 缺口来自数字员工汇报，预警来自 E，对账按有差异的对账来源数）
function computeChips() {
  const out = { 汇报: 0, 预警: 0, 对账: 0, 缺口: 0, 疑点: 0 } as Record<string, number>
  const st = C.state
  const closed = (k: string) => st?.reports[k]?.status === 'dismissed'
  if (src()) {
    for (const r of buildReports(src(), C.inv).filter(x => st?.config.enabled[x.employee] !== false)) {
      if (closed(r.key)) continue
      out[r.level === '数据疑点' ? '疑点' : r.level === '说明' ? '缺口' : '汇报']++
    }
    const gaps = ((S.source as unknown as { sourceGaps?: unknown[] })?.sourceGaps) || []
    if (gaps.length && !closed('gap:source')) out.缺口++
  }
  out.预警 = T.s.rules.state === 'ok' ? T.s.rules.count : 0
  for (const id of ['reconW', 'reconB']) { const x = T.s[id]; if (x.state === 'ok' && (x.total || 0) > 0) out.对账++ }
  T.chips = out
  const t = new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10)
  T.doneToday = (st?.records || []).filter(r => r.outcome === 'done' && new Date(Date.parse(r.at) + 8 * 3600e3).toISOString().slice(0, 10) === t).length
}

let inflight: Promise<void> | null = null
export function loadToday(): Promise<void> {
  if (inflight) return inflight
  inflight = run().finally(() => { inflight = null })
  return inflight
}
async function run() {
  syncContext()
  T.ws = C.ws
  if (C.ws === 'ydwm') {
    for (const id of Object.keys(DEF)) set(id, { state: 'na', count: 0, items: [], note: NO_YDWM })
    T.chips = { 汇报: 0, 预警: 0, 对账: 0, 缺口: 0, 疑点: 0 }; T.day = ''; T.loaded = true; return
  }
  const at = S.source?.checkedAt
  if (!at) { T.loaded = false; return }
  T.checkedAt = String(at); T.day = bj(Date.parse(String(at)))
  T.busy = true
  try {
    await loadAll()
    loadConfirm()
    await Promise.all([loadWeigh(), loadBatches(), loadRecon('reconW', '/__unified/a/api/weighin-reconcile?ws=lsm', '车间'), loadRecon('reconB', '/__lsm/supply/batch-reconcile', '仓库'), loadE(), loadRisk()])
    loadOa()
    computeChips()
    T.loaded = true
  } finally { T.busy = false }
}

// 每个岗位在「今天」里看哪几项（只放有数据来源的）；没有来源的岗位写明缺什么、找哪条线
export const ROLE_SOURCES: Record<string, string[]> = {
  boss: ['confirm', 'oaPending', 'risk', 'reconW', 'reconB', 'expiry', 'weigh'],
  plan: ['risk', 'weigh', 'confirm', 'tasks'],
  workshop: ['weigh', 'reconW', 'confirm'],
  warehouse: ['batch', 'expiry', 'reconB', 'confirm'],
  purchase: ['confirm', 'oaPending'],
  sales: ['confirm', 'risk', 'oaPending'],
  quality: ['rules', 'tasks', 'confirm'],
  finance: ['reconW', 'reconB', 'confirm', 'oaPending']
}
export const ROLE_GAPS: Record<string, string> = {
  purchase: '采购的「待到货、待收票、待核价」六膳门还没有现成口径：采购单在黑湖，需要 C 线给出哪些状态算「要我办」，接口出来后再接。',
  sales: '销售的「待回复交期、待开票、逾期应收」还没有现成口径：需要 C 线给出订单与应收的待办状态，接口出来后再接。交付风险已接。',
  plan: '排产草案、采购建议没有产能与库存线配置，暂不显示（A 线给出配置口径后再接）；交付风险已接，但黑湖没有订单到工单的关联，计划进度只能写「不能判断」。',
  finance: '成本差异、毛利红线没有按天的待办口径，需要 B 线给出后再接。'
}

// D 的个人 OA（口径同 /workflow/* 四页）：黑湖报工审批 approvalStatus 0 待审批 / 10 已批准 / 20 未通过 + E 协同任务
function loadOa() {
  const outs = (((S.source?.resources || {}) as Record<string, Row[]>).output || [])
  const lake = (s: string[]) => outs.filter(r => s.includes(String(r.approvalStatus)))
  const open = (x: Row) => !['DONE', 'CANCELLED'].includes(String(x.status))
  const ST: Record<string, string> = { TODO: '未开始', DOING: '进行中', DONE: '已完成', CANCELLED: '已取消' }
  const tItem = (x: Row, to: string): TItem => ({ label: String(x.no) + ' · ' + String(x.title), sub: '协同任务 · ' + String((x.assignee as Row)?.role || '未指定') + ' · ' + (ST[String(x.status)] || String(x.status)) + (x.due ? ' · 截止 ' + String(x.due) : ''), to })
  const lakeItem = (r: Row, to: string): TItem => ({ label: '报工 ' + String(r.id), sub: '黑湖报工审批 · ' + String(r.productName || '') + ' · ' + ({ '0': '待审批', '10': '已批准', '20': '未通过' } as Record<string, string>)[String(r.approvalStatus)], to })
  if (T.s.tasks.state !== 'ok') {
    for (const id of ['oaPending', 'oaMine', 'oaActed']) set(id, { state: 'error', note: '协同任务没读到，审批类不显示数量，也不推算。' })
  } else {
    const pend = lake(['0']), done = lake(['10', '20'])
    const op = allTasks.filter(open), cl = allTasks.filter(x => !open(x))
    set('oaPending', { state: 'ok', count: op.length + pend.length, note: '黑湖报工审批 ' + pend.length + ' + 协同任务 ' + op.length + '；黑湖没有审批人字段，办理在黑湖', items: [...op.map(x => tItem(x, '/workflow/pending')), ...pend.map(r => lakeItem(r, '/workflow/pending'))].slice(0, 8) })
    set('oaMine', { state: 'ok', count: allTasks.length, note: '黑湖快照没有发起人字段，只有 Cretas 协同任务', items: allTasks.slice(0, 8).map(x => tItem(x, '/workflow/my-created')) })
    set('oaActed', { state: 'ok', count: cl.length + done.length, note: '协同任务 ' + cl.length + ' + 黑湖报工审批 ' + done.length, items: [...cl.map(x => tItem(x, '/workflow/acted')), ...done.map(r => lakeItem(r, '/workflow/acted'))].slice(0, 8) })
  }
  set('oaCopied', { state: 'ok', count: 0, note: '黑湖快照没有抄送字段，Cretas 协同任务也没有抄送人；缺：抄送关系的来源，找 D 线定' })
}

// A 的交付风险（口径同 /production/delivery-risks 六膳门侧：黑湖销售订单 + 订单行库存，进度不能判断）
async function loadRisk() {
  set('risk', { state: 'loading' })
  try {
    const { status, body } = await get('/__lsm/supply/extras')
    if (status >= 400 || !body.success) throw Error(body.message || '黑湖销售订单读取失败')
    const today = T.day, orders = (body.data.resources?.sale_order || []) as Row[]
    const WORST: KitOrderStatus[] = ['缺料', '部分', '不能判断', '料齐待产', '齐套']
    const out: { no: string; customer: string; due: string; risk: Risk; reasons: string[]; left: number | null }[] = []
    for (const o of orders) {
      if (!['进行中', '待审批'].includes(String(o.statusDesc))) continue
      const kits: (KitOrderStatus | null)[] = []
      for (const l of ((o.saleManageOrderDetailRowApiVOList as Row[]) || [])) {
        const qty = num(l.qty), shipped = num(l.productShipmentQty) ?? 0
        const openQty = qty === null ? null : num(l.productUnClearQty) ?? Math.max(qty - shipped, 0), stock = num(l.productStockQty)
        const k = openQty === null || openQty <= 0 ? null : kit({ line: { productId: String(l.productId), name: String(l.productName ?? ''), qty: openQty, unit: String(l.productUnitName ?? ''), makeOrBuy: 'make' }, bomOf: () => null, stockOf: () => ({ onHand: stock, allocated: 0, unit: null }), releasedOf: () => null } as never)
        kits.push(k ? k.status : null)
      }
      const v = kits.filter((x): x is KitOrderStatus => !!x), ks = v.length ? WORST.find(w => v.includes(w))! : null
      const due = bjDay(o.arrivalPlanTime)
      const a = assess({ due, today, kit: ks, schedule: null })
      out.push({ no: String(o.orderNo), customer: String(o.customerName ?? ''), due, risk: a.risk, reasons: a.reasons, left: a.daysLeft })
    }
    const hot = out.filter(x => x.risk === '高' || x.risk === '中').sort((a, b) => (a.risk === b.risk ? (a.left ?? 9e9) - (b.left ?? 9e9) : a.risk === '高' ? -1 : 1))
    const hi = hot.filter(x => x.risk === '高').length
    set('risk', { state: 'ok', count: hot.length, note: '高 ' + hi + '、中 ' + (hot.length - hi) + '（共 ' + out.length + ' 张未结订单，按快照日 ' + today + '）；计划进度黑湖对不上，写「不能判断」',
      items: hot.slice(0, 8).map(x => ({ label: x.no + ' · ' + x.customer, sub: x.risk + ' · 交期 ' + (x.due || '未填') + ' · ' + (x.reasons[0] || ''), to: '/production/delivery-risks' })) })
  } catch (e) { set('risk', { state: 'error', note: '交付风险读取失败（' + errMsg(e) + '），不显示数量，也不推算。' }) }
}
