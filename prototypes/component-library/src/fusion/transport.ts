import { state } from '../state'
let installed = false, lastAction = 0
export function localBusinessUrl(input: string) {
  const u = new URL(input, location.href)
  if (u.pathname.startsWith('/__v5/')) return u.href
  if (/^\/(?:api\/|smartbi-api\/|__prototype\/)/.test(u.pathname)) return location.origin + '/__v5/business/' + state.role + u.pathname + u.search
  return input
}
export function installFusionTransport() {
  if (installed) return; installed = true
  for (const event of ['pointerdown', 'keydown', 'change', 'drop']) document.addEventListener(event, () => lastAction = Date.now(), true)
  const rawFetch = window.fetch.bind(window)
  window.fetch = (input, init) => { const headers = new Headers(init?.headers || (input instanceof Request ? input.headers : undefined)); if (Date.now() - lastAction < 15000) headers.set('X-Prototype-User-Action','local-edit'); return input instanceof Request ? rawFetch(new Request(localBusinessUrl(input.url), input), { ...init, headers }) : rawFetch(localBusinessUrl(String(input)), { ...init, headers }) }
  const open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send
  XMLHttpRequest.prototype.open = function(method: string, url: string | URL, ...args: unknown[]) { return Reflect.apply(open, this, [method, localBusinessUrl(String(url)), ...args]) }
  XMLHttpRequest.prototype.send = function(body) { if (Date.now() - lastAction < 15000) this.setRequestHeader('X-Prototype-User-Action','local-edit'); return send.call(this, body) }
}
