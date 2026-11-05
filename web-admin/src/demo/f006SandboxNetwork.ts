import { currentTokenOwnsSandbox, SANDBOX_STORAGE_KEY, sandboxRequestUrl, sandboxSession } from './f006Sandbox'

let installed = false
/** Also cover streaming fetch and API clients which do not use the shared Axios instance. */
export function installSandboxNetworkBoundary(): void {
  if (installed || typeof window === 'undefined') return
  installed = true
  const originalFetch = window.fetch?.bind(window)
  if (originalFetch) window.fetch = (input, init) => {
    if (!sandboxSession.value) return originalFetch(input, init)
    try {
      const raw = input instanceof Request ? input.url : String(input)
      const rewritten = sandboxRequestUrl(raw, init?.method || (input instanceof Request ? input.method : 'GET'))
      return originalFetch(input instanceof Request ? new Request(rewritten, input) : rewritten, init)
    } catch (error) { return Promise.reject(error) }
  }
  const originalOpen = XMLHttpRequest.prototype.open
  XMLHttpRequest.prototype.open = function (method: string, url: string | URL, asynchronous?: boolean, username?: string | null, password?: string | null) {
    return originalOpen.call(this, method, sandboxRequestUrl(String(url), method), asynchronous !== false, username, password)
  }
  window.addEventListener('storage', event => {
    if (event.key !== SANDBOX_STORAGE_KEY) return
    if (currentTokenOwnsSandbox() === false) return
    // Existing pages can hold production entity IDs. A full reload clears those instances.
    window.location.replace('/demo-flow')
  })
}
