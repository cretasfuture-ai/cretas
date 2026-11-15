// G 骨架的路由与跳转：自带 hash 解析（嵌在统一框架里或单独预览都能用），不改 frame/store。
import { reactive } from 'vue'
import { F_ROUTES } from './pages'

type UI = { dialog(o: { title: string; content: string | HTMLElement; actions?: { label: string; variant?: string; onClick: (h: { close(): Promise<boolean> }) => void }[] }): unknown; toast(o: { message: string; tone?: string }): unknown }
const ui = (): UI | undefined => (window as unknown as { CretasUI?: UI }).CretasUI

export const G = reactive({ path: '/g/today', query: new URLSearchParams(), known: new Set<string>(), knownReady: false, hosts: {} as Record<string, string> })

export function syncG() {
  const raw = location.hash.replace(/^#/, '') || '/g/today', i = raw.indexOf('?')
  G.path = i < 0 ? raw : raw.slice(0, i); G.query = new URLSearchParams(i < 0 ? '' : raw.slice(i + 1))
}
export const ws = () => G.query.get('ws') === 'ydwm' ? 'ydwm' : 'lsm'
export const embedded = () => window.parent !== window && location.pathname.startsWith('/__unified/lsm/')

function session(): string {
  try { const top = new URL(window.top!.location.href); return top.searchParams.get('acceptanceSession') || G.query.get('acceptanceSession') || '' } catch { return G.query.get('acceptanceSession') || '' }
}

// 原型里已登记的路由（六膳门菜单 + 味美 menu-map + frame 路由），用来判断「能跳」还是「只说明」
export async function loadKnown() {
  if (G.knownReady) return
  const add = (r?: string) => { if (r) G.known.add(r.split('?')[0]) }
  try {
    const m = await (await fetch('/__unified/menu.json')).json()
    for (const g of m.groups || []) for (const e of g.entries || []) for (const x of e.members || []) add(x.route)
    for (const f of m.frameRoutes || []) { add(f.path); if (f.host) G.hosts[f.path] = f.host }
  } catch { /* 预览环境读不到时，按「只说明」处理 */ }
  try { const y = await (await fetch('/system-kit/menu-map.json')).json(); for (const p of y.pages || []) add(p.path); for (const e of y.extraEntries || []) add(e.route) } catch { /* 同上 */ }
  G.knownReady = true
}

export function explain(title: string, body: string, go?: { label: string; run: () => void }) {
  const C = ui()
  const html = '<p style="margin:0 0 8px;line-height:1.7">' + body.replace(/[<&]/g, c => c === '<' ? '&lt;' : '&amp;') + '</p><p style="margin:0;color:var(--cs-muted);font-size:12px">这是骨架页：只搭了按钮和页面关系，不做计算、不写数据。</p>'
  if (!C) { window.alert(title + '\n\n' + body); return }
  C.dialog({ title: '骨架：' + title, content: html, actions: [...(go ? [{ label: go.label, variant: 'primary', onClick: (h: { close(): Promise<boolean> }) => { h.close(); go.run() } }] : []), { label: '知道了', variant: 'secondary', onClick: (h: { close(): Promise<boolean> }) => { h.close() } }] })
}

// 味美工作区：登记了 host 的框架路由（/g/*、/crew/*、E 的页）走顶层「host?uframe=路由&参数」，刷新不丢
function ydwmTop(to: string): string {
  const [p, q] = to.split('?'), host = G.hosts[p]
  if (!host) return to
  const sp = new URLSearchParams(q || ''); sp.delete('ws'); sp.delete('acceptanceSession')
  const rest = sp.toString(); return host + '?uframe=' + encodeURIComponent(p) + (rest ? '&' + rest : '')
}

function goG(to: string) {
  if (ws() === 'ydwm' && embedded() && G.hosts[to.split('?')[0]]) { goProto(to); return }
  const [p, q] = to.split('?'), sp = new URLSearchParams(q || '')
  if (ws() === 'ydwm') sp.set('ws', 'ydwm')
  const s = sp.toString(); location.hash = p + (s ? '?' + s : '')
}

function goProto(to: string, replace = false) {
  const sess = session(), withSess = (u: string) => sess ? u + (u.includes('?') ? '&' : '?') + 'acceptanceSession=' + encodeURIComponent(sess) : u
  if (!embedded()) { // 单独预览：在 19063 新开
    const base = 'http://127.0.0.1:19063'
    window.open(ws() === 'ydwm' ? base + withSess(to) : base + withSess('/dashboard') + '#lsm:' + to, '_blank'); return
  }
  if (ws() === 'ydwm') { const u = withSess(ydwmTop(to)); if (replace) window.top!.location.replace(u); else window.top!.location.href = u; return }
  if (replace) location.replace(location.pathname + location.search + '#' + to); else location.hash = to
}

// 统一跳转：'/g/*' 骨架页；'F:key' F 线路由；其他 = 原型已有页面
export function go(to: string, label = '') {
  if (to.startsWith('F:')) {
    const [key, extra] = to.slice(2).split('?'), f0 = F_ROUTES[key]
    const f = f0 && extra ? { ...f0, route: f0.route + (f0.route.includes('?') ? '&' : '?') + extra } : f0
    if (!f) { explain(label || '数字班组', '这里将进入数字班组的页面。'); return }
    if (G.known.has(f.route.split('?')[0])) { goProto(f.route); return }
    if (key === 'issues') { goG('/g/issues' + (extra ? '?' + extra : '')); return }
    if (key === 'settings') { explain('下一批由数字班组做', '「设置 › 数字班组配置」是单独一页（启用哪几位、能力和权限的对应），下一批由数字班组做，做好后这个按钮直接打开。现在启用哪几位仍在班组首页底部。', { label: '去班组首页', run: () => goProto('/crew') }); return }
    explain(f.label, '这里将进入数字班组的「' + f.label + '」，这一页还没有上线。'); return
  }
  if (to.startsWith('/g/')) { goG(to); return }
  const path = to.split('?')[0]
  if (G.knownReady && !G.known.has(path)) {
    explain(label || to, '这里将跳到原型页面 ' + to + '。当前工作区菜单里没有登记这个路由（可能只在另一个工作区有），点「仍然打开」试试。', { label: '仍然打开', run: () => goProto(to) }); return
  }
  goProto(to)
}

export function act(a: { label: string; to?: string; say?: string; write?: boolean }, _ctx: { page: string; crew: string; line: string }) {
  if (a.to) { go(a.to, a.label); return }
  const base = a.say || ('这里将「' + a.label + '」。')
  const write = a.write ? ' 这是写操作：先出预览（改什么、影响谁），人确认后才执行；办完记进工作记录。' : ''
  explain(a.label, base + write)
}

// R33：/g/issues 不再和 F 的「发现的问题」并存；F 的页面登记后直接替换过去（不留历史，返回键不会来回跳）
export function issuesTarget(): string | null {
  const r = F_ROUTES.issues?.route
  return r && G.known.has(r.split('?')[0]) ? r : null
}
export function redirectIssues(): boolean {
  const r = issuesTarget(); if (!r) return false
  if (!/^#\/g\/issues(\?|$)/.test(location.hash)) return false // 只在地址真的停在 /g/issues 时跳
  // 参数原样带过去，G 的来源 id 换成 F 的 source 值；数据疑点页签 → source=疑点
  const SRC: Record<string, string> = { crew: '汇报', rule: '预警', reconcile: '对账', data: '缺口' }
  const sp = new URLSearchParams(G.query); sp.delete('ws'); sp.delete('acceptanceSession') // 会话由 goProto 统一补一次
  const src = sp.get('src'); sp.delete('src')
  if (src && SRC[src]) sp.set('source', SRC[src])
  if (sp.get('tab') === 'suspect') { sp.set('source', '疑点'); sp.delete('tab') }
  const q = sp.toString(); goProto(r.split('?')[0] + (q ? '?' + q : ''), true); return true
}

// 第八批：D 登记了新路由后，对应的 G 骨架页直接转过去（replace，返回键不会回到骨架）。目标路由没登记时不转，仍显示骨架。
const QUALITY_TABS: Record<string, string> = { release: '/quality/inspections', ccp: '/quality/ccp', coldchain: '/quality/cold-chain', disposition: '/quality/disposals', recall: '/quality/recalls', returns: '/quality/returns' }
export function newRouteTarget(): string | null {
  const tab = G.query.get('tab') || ''
  let r: string | null = null
  if (G.path === '/g/delivery' && (!tab || tab === 'risk')) r = '/production/delivery-risks'
  else if (G.path === '/g/engineering') r = tab === 'price' ? '/production/bom/price-adjustments' : tab && tab !== 'ecn' ? null : '/production/bom/ecns'
  else if (G.path === '/g/quality-loop') r = QUALITY_TABS[tab || 'release'] || null
  else if (G.path === '/g/reports' && (!tab || tab === 'reports')) r = '/analytics/ai-reports'
  return r && G.known.has(r) ? r : null
}
export function redirectNew(): boolean {
  const r = newRouteTarget(); if (!r) return false
  const h = location.hash; if (h !== '#' + G.path && !h.startsWith('#' + G.path + '?')) return false // 只在地址真的停在这个骨架页时跳
  goProto(r, true); return true
}
