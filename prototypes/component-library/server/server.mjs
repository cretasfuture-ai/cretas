import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import diagnostics from 'node:diagnostics_channel'
import { createNativeBusiness } from './native-business.mjs'
import { fusionData } from './fusion-data.mjs'

const ROOT = path.dirname(fileURLToPath(import.meta.url)), PARENT = path.dirname(ROOT), PORT = 19050
const access = JSON.parse(fs.readFileSync(path.join(PARENT, 'private/local-access.json')))
const require = createRequire(import.meta.url), { Workspace } = require('../tools/v3/workspace.cjs')
const sources = { factory: new Workspace(PARENT, 'factory', 'test-v5-original-20261029'), platform: new Workspace(PARENT, 'platform', 'test-v5-original-20261029') }
const evidence = path.join(PARENT, 'evidence/v5'), audit = { startedAt: new Date().toISOString(), externalRequests: [], localOriginalFetches: 0, originalSnapshotReads: 0, blocked: [], productionWrites: 0 }
fs.mkdirSync(evidence, { recursive: true })
const CSP = "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; frame-src 'self'; worker-src 'self' blob:; object-src 'none'; frame-ancestors 'self'"
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.json': 'application/json; charset=utf-8', '.png': 'image/png', '.jpg': 'image/jpeg', '.webp': 'image/webp', '.woff2': 'font/woff2', '.ico': 'image/x-icon' }
function persist() { fs.writeFileSync(path.join(evidence, 'runtime.json'), JSON.stringify(audit, null, 2)) }
diagnostics.channel('undici:request:create').subscribe(({ request }) => { if (!/^http:\/\/127\.0\.0\.1:1884[12]$/.test(String(request.origin))) { audit.externalRequests.push({ origin: String(request.origin), path: request.path, method: request.method }); persist() } })
function send(res, status, body, type = 'application/json; charset=utf-8', extra = {}) { res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store', 'Content-Security-Policy': CSP, 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'same-origin', ...extra }); res.end(Buffer.isBuffer(body) ? body : type.includes('json') ? JSON.stringify(body) : String(body)) }
const error = (message, code) => ({ success: false, message, code, prototypeLocalOnly: true })
function file(res, target) { if (!fs.existsSync(target) || !fs.statSync(target).isFile()) return send(res, 404, error('本地文件未收录', 'FILE_NOT_CAPTURED')); send(res, 200, fs.readFileSync(target), types[path.extname(target)] || 'application/octet-stream') }
const dev = process.argv.includes('--dev'), vite = dev ? await (await import('vite')).createServer({ root: ROOT, configLoader: 'runner', server: { middlewareMode: true }, appType: 'custom' }) : null
async function original(req, res, url, p) {
  const cookieRole = (req.headers.cookie || '').split(';').map(s => s.trim()).find(s => s.startsWith('cretas_v5_original_role='))?.split('=')[1]
  const role = url.searchParams.get('v5role') === 'platform' ? 'platform' : url.searchParams.get('v5role') === 'factory' ? 'factory' : cookieRole === 'platform' ? 'platform' : 'factory'
  if (/^\/api\/mobile\/[^/]+\/(?:client-errors|activity-events)$/.test(p) && req.method === 'POST') return send(res, 200, { success: true, data: null, localTelemetryOnly: true })
  if (req.method !== 'GET') { audit.blocked.push({ method: req.method, path: p, reason: 'original-preview-readonly' }); persist(); return send(res, 403, error('左右对照中的原版只读；完整原版可通过独立入口操作。', 'ORIGINAL_PREVIEW_READONLY')) }
  if (p.startsWith('/api/') || p.startsWith('/smartbi-api/')) {
    if (/\/photos\/[^/]+\/image$|\/attachments\/[^/]+\/download$/.test(p)) return send(res, 404, error('此样板未开放图片或附件读取', 'NOT_IN_SAMPLE'))
    const w = sources[role], target = p + url.search
    const result = await (w.readDomain?.('GET', target, null) || w.read('GET', target, null))
    if (!result) { audit.blocked.push({ method: 'GET', path: p, reason: 'offline-not-captured' }); persist(); return send(res, 404, error('该接口尚未收录，样板未连接生产服务。', 'OFFLINE_NOT_CAPTURED')) }
    audit.originalSnapshotReads++; return send(res, result.status || 200, result.body, result.type || 'application/json; charset=utf-8')
  }
  if (p.startsWith('/__prototype/')) {
    if (!/\.(?:js|css)$/.test(p)) return send(res, 403, error('此对照窗不提供原型管理操作', 'READONLY_PREVIEW'))
  } else {
    const native = path.resolve(PARENT, 'public/native', '.' + p), base = path.join(PARENT, 'public/native')
    if (native.startsWith(base + path.sep) && fs.existsSync(native) && fs.statSync(native).isFile() && p !== '/index.html') return file(res, native)
    res.setHeader('Set-Cookie', 'cretas_v5_original_role=' + role + '; Path=/; HttpOnly; SameSite=Strict')
  }
  const upstream = await fetch('http://127.0.0.1:' + (role === 'platform' ? 18841 : 18842) + p + url.search, { headers: { cookie: 'cretas_prototype_access=' + access.ticket }, redirect: 'manual' })
  audit.localOriginalFetches++; persist(); send(res, upstream.status, Buffer.from(await upstream.arrayBuffer()), upstream.headers.get('content-type') || 'text/html; charset=utf-8')
}
const nativeBusiness = createNativeBusiness({ parent: PARENT, access, send, audit, persist })
const server = http.createServer(async (req, res) => {
  try {
    if (!['127.0.0.1:' + PORT, 'localhost:' + PORT].includes(req.headers.host)) return send(res, 403, error('请从本机样板入口打开', 'LOCAL_ACCESS_REQUIRED'))
    if (/%(?:25|2f|5c)/i.test((req.url || '').split('?')[0])) return send(res, 400, error('无效路径', 'INVALID_PATH'))
    const url = new URL(req.url, 'http://127.0.0.1:' + PORT), p = decodeURIComponent(url.pathname)
    if (p.includes('..') || p.includes('\\') || p.includes('%')) return send(res, 400, error('无效路径', 'INVALID_PATH'))

    if (req.method === 'GET' && url.searchParams.get('access') === access.ticket) { url.searchParams.delete('access'); return send(res, 200, '<!doctype html><meta charset="utf-8"><script>location.replace(' + JSON.stringify('/samples/' + url.search) + ')</script>', 'text/html; charset=utf-8', { 'Set-Cookie': 'cretas_prototype_access=' + access.ticket + '; Path=/; HttpOnly; SameSite=Strict' }) }
    if (req.headers.origin && !['http://127.0.0.1:' + PORT, 'http://localhost:' + PORT].includes(req.headers.origin) || req.headers['sec-fetch-site'] === 'cross-site') return send(res, 403, error('请从本机样板入口打开', 'LOCAL_ACCESS_REQUIRED'))
    if (p === '/__v5/health') return send(res, 200, { ready: true, version: 5, pages: 5 })
    if (!(req.headers.cookie || '').split(';').some(s => s.trim() === 'cretas_prototype_access=' + access.ticket)) return send(res, 403, error('请使用“打开五页交互样板.html”进入', 'LOCAL_ACCESS_REQUIRED'))
    if (req.method === 'HEAD') return send(res, 404, error('样板不转发HEAD请求', 'HEAD_NOT_SUPPORTED'))
    if (p === '/__v5/data' && req.method === 'GET') {
      const requestedSession=req.headers['x-v5-business-session'],session=typeof requestedSession==='string'&&/^test-v5-[a-z0-9-]{8,54}$/.test(requestedSession)?requestedSession:undefined
      const data=fusionData(PARENT,session)
      const definitions=fs.existsSync(path.join(ROOT,'private/fusion-workflows.json'))?JSON.parse(fs.readFileSync(path.join(ROOT,'private/fusion-workflows.json'))):[]
      data.fusion={definitionCount:definitions.length,defaultOwnerId:definitions.find(x=>x.definition.nodes.length>=10&&!x.id.startsWith('RMT_'))?.id||definitions[0]?.id,capturedAt:new Date(fs.statSync(path.join(ROOT,'private/fusion-workflows.json')).mtimeMs).toISOString()}
      return send(res,200,data)
    }
    if (p === '/__v5/identity' && req.method === 'GET') {
      const role=url.searchParams.get('role')==='platform'?'platform':'factory'
      const response=await fetch('http://127.0.0.1:'+(role==='platform'?18841:18842)+'/__prototype/bootstrap.js'+(role==='platform'?'?factory=none':''),{headers:{cookie:'cretas_prototype_access='+access.ticket},redirect:'manual'})
      const script=await response.text(),match=script.match(/^window\.__CRETAS_PROTOTYPE__=(.+);\r?\n/)
      if(!response.ok||!match) return send(res,502,error('本机账号初始化失败','IDENTITY_UNAVAILABLE'))
      const config=JSON.parse(match[1]); return send(res,200,config.user)
    }
    if (p === '/__v5/audit' && req.method === 'GET') return send(res, 200, audit)
    if (p === '/__v5/business-theme.css' && req.method === 'GET') return file(res, path.join(ROOT, 'business-theme.css'))
    if (await nativeBusiness(req, res, url)) return
    if (p === '/') { res.writeHead(302, { Location: '/samples/' }); return res.end() }
    if (p.startsWith('/samples/')) {
      if (req.method !== 'GET') return send(res, 405, error('此资源不提供写入', 'METHOD_NOT_ALLOWED'))
      if (p.startsWith('/samples/private/') || p.includes('/node_modules/') || /\.(?:log|cjs|mjs|json)$/.test(p)) return send(res, 403, error('私有资源不在交付页面范围', 'PRIVATE_RESOURCE'))
      if (vite) { req.url = p + url.search; return vite.middlewares(req, res, async () => { const html = await vite.transformIndexHtml('/samples/', fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8')); send(res, 200, html, 'text/html; charset=utf-8') }) }
      const target = path.resolve(ROOT, 'dist', '.' + p.slice('/samples'.length)), base = path.join(ROOT, 'dist')
      if (!target.startsWith(base + path.sep) && target !== base) return send(res, 403, error('无效资源', 'INVALID_PATH'))
      if (fs.existsSync(target) && fs.statSync(target).isFile()) return file(res, target)
      if (path.extname(p)) return send(res, 404, error('样板资源不存在', 'NOT_FOUND'))
      return file(res, path.join(ROOT, 'dist/index.html'))
    }
    await original(req, res, url, p)
  } catch (e) { if (!res.headersSent) send(res, 500, error(e.message, 'LOCAL_SAMPLE_ERROR')); else res.end() }
})
server.listen(PORT, '127.0.0.1')
fs.writeFileSync(path.join(ROOT, 'private/server-state.json'), JSON.stringify({ pid: process.pid, port: PORT, startedAt: audit.startedAt, dev }, null, 2))
const target = 'http://127.0.0.1:' + PORT + '/samples/?access=' + access.ticket
fs.writeFileSync(path.join(PARENT, '打开五页交互样板.html'), '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="referrer" content="no-referrer"><meta http-equiv="refresh" content="0;url=' + target + '"><a href="' + target + '">打开五页交互样板</a></html>')
persist(); console.log(JSON.stringify({ port: PORT, version: 5, pages: 5, dev }))
