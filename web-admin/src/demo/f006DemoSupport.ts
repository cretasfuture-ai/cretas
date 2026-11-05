import request from '@/api/request'
import { requireSandboxReady, sandboxSession } from './f006Sandbox'

export type DemoRecord = Record<string, unknown>
export interface DemoTransport {
  get<T = unknown>(path: string): Promise<T>
  post<T = unknown>(path: string, body?: unknown, config?: Record<string, unknown>): Promise<T>
  put?<T = unknown>(path: string, body?: unknown, config?: Record<string, unknown>): Promise<T>
}
export function record(value: unknown): DemoRecord {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('业务接口未返回有效对象')
  return value as DemoRecord
}
export function unwrap(value: unknown): unknown {
  const r = record(value)
  if (r.success === false) throw new Error(String(r.message || '业务操作失败'))
  return 'data' in r ? r.data : r
}
export function rows(value: unknown): DemoRecord[] {
  const list = Array.isArray(value) ? value : record(value).content
  if (!Array.isArray(list)) throw new Error('业务接口未返回有效列表')
  return list.map(record)
}
export const demoTransport: DemoTransport = {
  get: async <T>(path: string) => unwrap(await request.get(path)) as T,
  post: async <T>(path: string, body?: unknown, config?: Record<string, unknown>) => unwrap(await request.post(path, body, config)) as T,
  put: async <T>(path: string, body?: unknown, config?: Record<string, unknown>) => unwrap(await request.put(path, body, config)) as T,
}
export function scoped(r: DemoRecord): DemoRecord {
  if (r.factoryId !== 'F006') throw new Error('返回记录不属于 F006，演示已停止')
  return r
}
export function identity(r: DemoRecord): { id: string; factoryId: string; role: string; username: string } {
  const u = r.factoryUser ? record(r.factoryUser) : r
  return { id: String(u.id ?? u.userId ?? ''), factoryId: String(u.factoryId ?? ''), role: String(u.roleCode ?? u.role ?? ''), username: String(u.username ?? '') }
}
export async function assertLiveAdmin(transport: DemoTransport = demoTransport) {
  const user = identity(record(await transport.get('/auth/me')))
  if (user.factoryId !== 'F006' || user.role !== 'factory_super_admin' || !user.id) throw new Error('当前实时会话必须是 F006 工厂管理员')
  return user
}
export async function approveWorkflow(runKey: string, moduleCode: string, entityId: string, transport: DemoTransport = demoTransport) {
  await assertLiveAdmin(transport)
  for (let i = 0; i < 8; i++) {
    const list = await transport.get(`/F006/workflow/instances/pending?moduleCode=${encodeURIComponent(moduleCode)}`)
    const outer = Array.isArray(list) ? list : record(list)
    const candidates = Array.isArray(outer) ? rows(outer) : rows(outer.content || outer.items || outer.records || [])
    const pending = candidates.find(item => String(item.businessEntityId) === entityId)
    if (!pending) return
    if (pending.factoryId && pending.factoryId !== 'F006') throw new Error('审批任务工厂不一致')
    const actionPath = moduleCode === 'PURCHASE_ORDER' ? 'demo-actions' : 'actions'
    await transport.post(`/F006/workflow/instances/${encodeURIComponent(String(pending.instanceId))}/${actionPath}`, {
      action: 'APPROVE', notes: `${runKey} 演示审批`, expectedNodeId: pending.currentNodeId,
      idempotencyKey: `${runKey.slice(-24)}:approve:${pending.instanceId}:${pending.currentNodeId}`,
    })
  }
  throw new Error('审批节点未在预期次数内完成，请打开 OA 查看本单')
}

/** A real, decodable illustration; never presented as an actual supplier invoice. */
export async function attachDemoReceipt(receiptId: string, poNumber: string, runKey: string, transport: DemoTransport = demoTransport) {
  const name = `${runKey}-receipt.png`
  const listPath = `/F006/attachments?entityType=PURCHASE_RECEIPT&entityId=${encodeURIComponent(receiptId)}`
  const existing = rows(await transport.get(listPath)).find(item => item.fileName === name)
  if (existing) return { attachmentId: existing.id, fileName: name }
  const canvas = document.createElement('canvas'); canvas.width = 1200; canvas.height = 760
  const ctx = canvas.getContext('2d'); if (!ctx) throw new Error('浏览器不能生成演示收货凭证')
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, 1200, 760); ctx.fillStyle = '#163657'; ctx.font = 'bold 44px sans-serif'
  ctx.fillText('DEMO · 采购送货凭证（演示示意）', 60, 100); ctx.font = '30px sans-serif'
  ctx.fillText(`采购单：${poNumber}`, 60, 210); ctx.fillText('原料：演示·鲜牛腱；包材：演示·250g食品盒', 60, 285)
  ctx.fillText('用途：F006 进销存与生产联动演示', 60, 360); ctx.fillText('此图不是实际发票或真实供应商送货凭证', 60, 450)
  ctx.font = '22px sans-serif'; ctx.fillText(runKey, 60, 590)
  const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob(v => v ? resolve(v) : reject(new Error('演示凭证生成失败')), 'image/png'))
  const signed = record(await transport.post('/F006/attachments/upload-url', { fileName: name, fileType: 'image/png' }))
  if (sandboxSession.value) {
    requireSandboxReady()
    const uploadPath = String(signed.uploadUrl)
    if (!/^\/F006\/demo-sandbox-local-files\/[A-Za-z0-9_-]+$/.test(uploadPath)) throw new Error('演示凭证必须写入当前沙盒文件目录')
    if (!transport.put) throw new Error('当前演示连接不支持凭证上传')
    await transport.put(uploadPath, blob, { headers: { 'Content-Type': 'image/png' } })
  } else {
    const upload = await fetch(String(signed.uploadUrl), { method: 'PUT', credentials: 'omit', headers: { 'Content-Type': 'image/png' }, body: blob })
    if (!upload.ok) throw new Error('演示收货凭证上传失败')
  }
  const attachment = record(await transport.post('/F006/attachments', { entityType: 'PURCHASE_RECEIPT', entityId: receiptId, fileName: name, fileUrl: signed.fileUrl, fileSize: blob.size, fileType: 'image/png', uploadSource: 'WEB' }))
  const saved = rows(await transport.get(listPath)).find(item => item.id === attachment.id && item.fileName === name)
  if (!saved) throw new Error('演示收货凭证尚未回读成功，请重试核对')
  return { attachmentId: saved.id, fileName: name }
}
