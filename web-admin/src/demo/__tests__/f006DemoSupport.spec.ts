import { afterEach, expect, it, vi } from 'vitest'
import { attachDemoReceipt, type DemoTransport } from '../f006DemoSupport'
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
it('reuses the registered receipt illustration after a lost mutation response', async () => {
  const canvas = { width: 0, height: 0, getContext: () => ({ fillRect() {}, fillText() {} }), toBlob: (callback: (blob: Blob) => void) => callback(new Blob(['png'])) }
  vi.spyOn(document, 'createElement').mockReturnValue(canvas as unknown as HTMLCanvasElement)
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }))
  let registered = false
  const saved = { id: 'a1', fileName: 'demo-receipt.png' }
  const t = {
    get: vi.fn(async () => registered ? [saved] : []),
    post: vi.fn(async (path: string) => {
      if (path.endsWith('upload-url')) return { uploadUrl: 'https://upload.example.invalid', fileUrl: '/file.png' }
      registered = true
      throw new Error('response lost after commit')
    }),
  } as DemoTransport
  await expect(attachDemoReceipt('receipt1', 'PO1', 'demo', t)).rejects.toThrow(/response lost/)
  expect(await attachDemoReceipt('receipt1', 'PO1', 'demo', t)).toEqual({ attachmentId: 'a1', fileName: saved.fileName })
  expect(t.post).toHaveBeenCalledTimes(2)
})
