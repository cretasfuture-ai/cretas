import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createHash } from 'node:crypto'

const auth = vi.hoisted(() => ({ isFactory: true, currentRole: 'factory_super_admin', factoryId: 'F006' }))
const draftApi = vi.hoisted(() => ({
  getDraftWriteOrder: vi.fn(),
  getWrittenDrafts: vi.fn(),
  getDraftProposal: vi.fn(),
  previewDraft: vi.fn(),
  materializeDraft: vi.fn(),
  readBackDraft: vi.fn(),
}))
const promoApi = vi.hoisted(() => ({
  previewPromotion: vi.fn(),
  executePromotion: vi.fn(),
  getPromotion: vi.fn(),
  listPromotions: vi.fn(),
}))
const messageBox = vi.hoisted(() => ({ confirm: vi.fn() }))

vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }))
vi.mock('@/api/factoryBlueprintDraftApi', () => draftApi)
vi.mock('@/api/factoryBlueprintPromotionApi', () => promoApi)
vi.mock('element-plus', () => ({ ElMessageBox: messageBox }))

import FactoryBlueprintDraftsPanel from '../components/FactoryBlueprintDraftsPanel.vue'

const stubs = {
  'el-input-number': { props: ['modelValue'], emits: ['update:modelValue', 'change'], template: '<input type="number" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" @change="$emit(\'change\')" />' },
  'el-alert': { props: ['title', 'description'], template: '<div class="alert">{{ title }} {{ description }}</div>' },
  'el-tag': { template: '<span class="tag"><slot /></span>' },
  'el-button': {
    props: ['disabled', 'loading'], emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
}

const blueprint = { id: 'bp-1', version: 2, capabilities: [
  { id: 'products', label: '产品' }, { id: 'materials', label: '物料' }, { id: 'accounts', label: '岗位' },
] }

const row = (capabilityId: string) => ({
  capabilityId, adapterMode: 'MODULE_DRAFT', entryOpen: true, proposalOpen: false, requiredPermission: 'p',
  writeDependencies: [], dependenciesWithoutOpenEntry: [],
})
const draft = (draftId: string, naturalKey: string, label: string) =>
  ({ draftId, naturalKey, label, status: 'DRAFT', desiredDigest: 'x', createdAt: 'now' })

const readyPreview = () => ({
  status: 'READY', action: 'CREATE', capabilityId: 'products', draftId: 'd-1', naturalKey: 'P-01', before: null,
  after: { code: 'P-01', name: '卤猪蹄', unit: 'kg', productCategory: 'FINISHED_PRODUCT', shelfNote: null },
  blockers: [], promotionPreviewDigest: 'pd-1', expiresAt: '2026-10-05T12:00:00',
})

const expectedKey = `bp:products:${createHash('sha256')
  .update('promotion:F006:bp-1:2:abcdef0123456789:products:d-1').digest('hex').slice(0, 40)}`

beforeEach(() => {
  auth.isFactory = true
  auth.currentRole = 'factory_super_admin'
  auth.factoryId = 'F006'
  for (const m of [...Object.values(draftApi), ...Object.values(promoApi)]) m.mockReset()
  messageBox.confirm.mockReset()
  messageBox.confirm.mockResolvedValue('confirm')
  draftApi.getDraftWriteOrder.mockResolvedValue({ data: { schemaVersion: 'v1',
    capabilities: [row('products'), row('materials'), row('accounts')] } })
  draftApi.getWrittenDrafts.mockResolvedValue({ data: { schemaVersion: 'v1', blueprintId: 'bp-1', blueprintVersion: 2,
    sourceDigest: 'abcdef0123456789', capabilities: [
      { capabilityId: 'products', supported: true, drafts: [draft('d-1', 'P-01', '卤猪蹄'), draft('d-2', 'P-02', '鸡腿')] },
      { capabilityId: 'materials', supported: true, drafts: [draft('m-1', 'M-01', '猪蹄原料')] },
      { capabilityId: 'accounts', supported: true, drafts: [draft('a-1', 'accounts', '岗位')] },
    ] } })
  promoApi.listPromotions.mockResolvedValue({ data: { blueprintId: 'bp-1', blueprintVersion: 2, sourceDigest: 'abcdef0123456789',
    promotions: [
      { promotionId: 'pr-2', capabilityId: 'products', draftId: 'd-2', status: 'PROMOTED', action: 'CREATE', naturalKey: 'P-02', liveTargetId: 'PT-88' },
      { promotionId: 'pr-x', capabilityId: 'products', draftId: 'd-1', status: 'CLAIMED', action: 'CREATE', naturalKey: 'P-01', liveTargetId: null },
    ] } })
  promoApi.previewPromotion.mockResolvedValue({ data: readyPreview() })
  promoApi.executePromotion.mockResolvedValue({ data: { promotionId: 'pr-1', status: 'PROMOTED', liveTargetId: 'PT-101',
    replayed: false, readback: { code: 'P-01' } } })
})

function mountPanel() {
  return mount(FactoryBlueprintDraftsPanel, {
    props: { factoryId: 'F006', factoryName: '示范工厂', blueprint },
    global: { stubs },
  })
}

const q = (w: VueWrapper, id: string) => w.get(`[data-testid="${id}"]`)

async function openPreview(w: VueWrapper) {
  await q(w, 'promo-products-d-1').get('[data-testid="promo-start"]').trigger('click')
  await flushPromises()
}

async function keyReady(w: VueWrapper) {
  await vi.waitFor(() => expect(q(w, 'promo-execute').attributes('data-key-ready')).toBe('true'))
}

describe('draft 转正 in the drafts panel', () => {
  it('reviews existing material values and confirms UPDATE without claiming a new record', async () => {
    promoApi.previewPromotion.mockResolvedValue({ data: { ...readyPreview(), action: 'UPDATE', capabilityId: 'materials', draftId: 'm-1',
      before: { id: 'RM-1', name: '旧原料', unit: '个', shelfLifeDays: 62 },
      after: { name: '新原料', unit: '个', shelfLifeDays: 365, isByproduct: false }, promotionPreviewDigest: 'update-digest' } })
    promoApi.executePromotion.mockResolvedValue({ data: { promotionId: 'u1', status: 'PROMOTED', liveTargetId: 'RM-1', replayed: false, readback: { name: '新原料' } } })
    const w = mountPanel(); await flushPromises()
    const card = q(w, 'promo-materials-m-1'); await q(card, 'promo-start').trigger('click'); await flushPromises(); await keyReady(card)
    expect(card.find('[data-testid="promo-before"]').exists()).toBe(true)
    expect(q(card, 'promo-before').text()).toContain('旧原料'); expect(q(card, 'promo-before').text()).toContain('62')
    expect(q(card, 'promo-effect').text()).toContain('更新现有正式物料'); expect(q(card, 'promo-effect').text()).toContain('不新建记录')
    expect(card.text()).toContain('新原料'); expect(card.text()).toContain('365'); expect(card.text()).toContain('个')
    expect(promoApi.executePromotion).not.toHaveBeenCalled()
    await q(card, 'promo-execute').trigger('click'); await flushPromises()
    expect(messageBox.confirm.mock.calls[0]?.[0]).toContain('核对修改前后的值')
    expect(promoApi.executePromotion).toHaveBeenCalledWith('F006', 'bp-1', 'materials', 'm-1', expect.objectContaining({ confirmedPromotionDigest: 'update-digest' }))
    expect(q(card, 'promo-receipt').text()).toContain('RM-1'); w.unmount()
  })

  it('reviews packaging gross and net price and requires a fresh preview after changing price', async () => {
    const pricing = { ...readyPreview(), action: 'UPDATE', capabilityId: 'materials', draftId: 'm-1',
      before: { id: 'RM-1', name: '包装袋', unit: '个', taxIncludedUnitPrice: 0.2, unitPrice: 0.18, taxRate: 'TAX_13' },
      after: { name: '包装袋', unit: '个', taxIncludedUnitPrice: 0.2, unitPrice: 0.18, taxRate: 'TAX_13', 'classification.packagingCategory': true },
      promotionPreviewDigest: 'old-price' }
    let finishPricePreview: (() => void) | undefined
    const pendingPricing = new Promise(resolve => {
      finishPricePreview = () => resolve({ data: { ...pricing,
        after: { ...pricing.after, taxIncludedUnitPrice: 0.26, unitPrice: 0.23 }, promotionPreviewDigest: 'new-price' } })
    })
    promoApi.previewPromotion.mockResolvedValueOnce({ data: pricing }).mockReturnValueOnce(pendingPricing)
    const w = mountPanel(); await flushPromises()
    const card = q(w, 'promo-materials-m-1'); await q(card, 'promo-start').trigger('click'); await flushPromises(); await keyReady(card)
    expect(q(card, 'promo-before').text()).toContain('0.18')
    expect(card.text()).toContain('未税参考价'); expect(card.text()).toContain('13%')
    await q(card, 'promo-material-price').setValue('0.26'); await flushPromises()
    expect(q(card, 'promo-execute').attributes('disabled')).toBeDefined()
    expect(q(card, 'promo-stale').text()).toContain('重新预览含税价和未税价')
    await q(card, 'promo-execute').trigger('click'); await flushPromises()
    expect(messageBox.confirm).not.toHaveBeenCalled(); expect(promoApi.executePromotion).not.toHaveBeenCalled()
    await q(card, 'promo-repreview').trigger('click'); await flushPromises()
    expect(q(card, 'promo-execute').attributes('disabled')).toBeDefined()
    finishPricePreview?.(); await flushPromises()
    expect(promoApi.previewPromotion).toHaveBeenLastCalledWith('F006', 'bp-1', 'materials', 'm-1', { taxIncludedUnitPrice: 0.26 })
    expect(card.text()).toContain('0.23'); expect(q(card, 'promo-execute').attributes('disabled')).toBeUndefined()
    await q(card, 'promo-execute').trigger('click'); await flushPromises()
    expect(promoApi.executePromotion).toHaveBeenCalledWith('F006', 'bp-1', 'materials', 'm-1', expect.objectContaining({ confirmedPromotionDigest: 'new-price', taxIncludedUnitPrice: 0.26 }))
    w.unmount()
  })

  it('refuses UPDATE confirmation when the reviewed live target is missing', async () => {
    promoApi.previewPromotion.mockResolvedValue({ data: { ...readyPreview(), action: 'UPDATE', before: null } })
    const w = mountPanel(); await flushPromises(); await openPreview(w); await keyReady(w)
    expect(q(w, 'promo-execute').attributes('disabled')).toBeDefined()
    await q(w, 'promo-execute').trigger('click'); await flushPromises()
    expect(messageBox.confirm).not.toHaveBeenCalled(); expect(promoApi.executePromotion).not.toHaveBeenCalled(); w.unmount()
  })

  it('exposes BOM promotion, requires fresh preview of explicit packaging roles, then carries roles into execution', async () => {
    draftApi.getDraftWriteOrder.mockResolvedValue({ data: { capabilities: [row('bom')] } })
    draftApi.getWrittenDrafts.mockResolvedValue({ data: { blueprintId: 'bp-1', blueprintVersion: 2,
      sourceDigest: 'abcdef0123456789', capabilities: [
        { capabilityId: 'bom', supported: true, drafts: [draft('bom-1', 'P-01', '卤猪蹄配方')] },
      ] } })
    const after = { outputQuantity: 1, outputUnit: 'kg', packagingRoles: [
      { workflowPortId: 'bag-port', label: '包装袋', unit: '个', role: null },
    ] }
    promoApi.previewPromotion.mockResolvedValueOnce({ data: { ...readyPreview(), capabilityId: 'bom',
      draftId: 'bom-1', status: 'BLOCKED', promotionPreviewDigest: null, after,
      blockers: [{ code: 'BOM_PACKAGING_ROLE_REQUIRED', message: '请选择包装角色', nextStep: '填写后重新预览' }],
    } }).mockResolvedValue({ data: { ...readyPreview(), capabilityId: 'bom', draftId: 'bom-1', after,
      promotionPreviewDigest: 'bom-preview' } })
    promoApi.executePromotion.mockResolvedValue({ data: { status: 'PROMOTED', capabilityId: 'bom',
      draftId: 'bom-1', liveTargetId: 'recipe-1', replayed: false, liveWriteCount: 1 } })
    const w = mount(FactoryBlueprintDraftsPanel, {
      props: { factoryId: 'F006', factoryName: '示范工厂', blueprint: { ...blueprint,
        capabilities: [{ id: 'bom', label: '配方' }] } }, global: { stubs },
    })
    await flushPromises()
    expect(q(w, 'promo-start').text()).toBe('转正为正式配方')
    await q(w, 'promo-start').trigger('click'); await flushPromises()
    expect(q(w, 'promo-execute').attributes('disabled')).toBeDefined()
    await q(w, 'promo-bom-role-bag-port').setValue('PRIMARY_CONTAINER')
    expect(q(w, 'promo-execute').attributes('disabled')).toBeDefined()
    expect(promoApi.executePromotion).not.toHaveBeenCalled()
    await q(w, 'promo-repreview').trigger('click'); await flushPromises()
    expect(promoApi.previewPromotion).toHaveBeenLastCalledWith('F006', 'bp-1', 'bom', 'bom-1',
      { packagingRoles: { 'bag-port': 'PRIMARY_CONTAINER' } })
    await keyReady(w)
    await q(w, 'promo-execute').trigger('click'); await flushPromises()
    expect(promoApi.executePromotion).toHaveBeenCalledWith('F006', 'bp-1', 'bom', 'bom-1',
      expect.objectContaining({ confirmedPromotionDigest: 'bom-preview',
        packagingRoles: { 'bag-port': 'PRIMARY_CONTAINER' } }))
    expect(q(w, 'promo-receipt').text()).toContain('recipe-1')
  })

  it('marks promoted drafts from /promotions; only PROMOTED counts; non-promotable capabilities get no entry', async () => {
    const w = mountPanel()
    await flushPromises()
    expect(promoApi.listPromotions).toHaveBeenCalledWith('F006', 'bp-1')
    const d2 = q(w, 'promo-products-d-2')
    expect(d2.get('[data-testid="promo-done"]').text()).toBe('已转正 · 正式记录 PT-88')
    expect(d2.find('[data-testid="promo-start"]').exists()).toBe(false)
    // CLAIMED 不算已转正
    expect(q(w, 'promo-products-d-1').get('[data-testid="promo-start"]').text()).toBe('转正为正式产品')
    expect(q(w, 'promo-materials-m-1').get('[data-testid="promo-start"]').text()).toBe('转正为正式物料')
    expect(w.find('[data-testid="draft-promotions-accounts"]').exists()).toBe(false)
  })

  it('preview → readable card → confirm with identity → execute with digest + fixed key only → receipt → refresh', async () => {
    const w = mountPanel()
    await flushPromises()
    await openPreview(w)
    expect(promoApi.previewPromotion).toHaveBeenCalledWith('F006', 'bp-1', 'products', 'd-1')
    const card = q(w, 'promo-preview')
    expect(q(w, 'promo-effect').text()).toBe('将在正式产品表新建 1 条')
    expect(card.text()).toContain('产品编号')
    expect(card.text()).toContain('P-01')
    expect(card.text()).toContain('成品（FINISHED_PRODUCT）')
    expect(card.text()).toContain('shelfNote') // 不认识的键原样显示, 不丢
    expect(card.text()).toContain('（空）')
    expect(q(w, 'promo-execute').text()).toBe('转正为正式产品')

    await keyReady(w)
    await q(w, 'promo-execute').trigger('click')
    await flushPromises()
    expect(messageBox.confirm).toHaveBeenCalledTimes(1)
    expect(messageBox.confirm.mock.calls[0][0])
      .toBe('转正为正式产品 — 示范工厂（F006）· 卤猪蹄：将写入正式产品表，写入后不能在这里撤销')
    expect(messageBox.confirm.mock.calls[0][2].confirmButtonText).toBe('转正为正式产品')
    expect(promoApi.executePromotion).toHaveBeenCalledTimes(1)
    const [f, b, cap, id, body] = promoApi.executePromotion.mock.calls[0]
    expect([f, b, cap, id]).toEqual(['F006', 'bp-1', 'products', 'd-1'])
    expect(body).toEqual({ confirmedPromotionDigest: 'pd-1', idempotencyKey: expectedKey })
    expect(Object.keys(body).sort()).toEqual(['confirmedPromotionDigest', 'idempotencyKey'])
    expect(body.idempotencyKey.length).toBeLessThanOrEqual(128)
    expect(q(w, 'promo-receipt').text()).toContain('PT-101')
    expect(promoApi.listPromotions).toHaveBeenCalledTimes(2)
  })

  it('a BLOCKED preview disables the action and shows each reason with its next step', async () => {
    promoApi.previewPromotion.mockResolvedValue({ data: { ...readyPreview(), status: 'BLOCKED', blockers: [
      { code: 'PROMOTION_PRICE_REQUIRED', message: '正式物料表要求单价，草稿里没有', nextStep: '先在物料草稿里补单价' },
    ] } })
    const w = mountPanel()
    await flushPromises()
    await openPreview(w)
    await keyReady(w)
    expect(q(w, 'promo-blockers').text()).toContain('正式物料表要求单价，草稿里没有')
    expect(q(w, 'promo-blockers').text()).toContain('下一步：先在物料草稿里补单价')
    expect(q(w, 'promo-execute').attributes('disabled')).toBeDefined()
    await q(w, 'promo-execute').trigger('click')
    await flushPromises()
    expect(messageBox.confirm).not.toHaveBeenCalled()
    expect(promoApi.executePromotion).not.toHaveBeenCalled()
  })

  it('a READY status with blockers is still not executable (blockers win)', async () => {
    promoApi.previewPromotion.mockResolvedValue({ data: { ...readyPreview(), blockers: [
      { code: 'PROMOTION_LIVE_CONFLICT', message: '正式产品表已有编号 P-01 的「卤猪蹄」', nextStep: '确认是否同一产品' },
    ] } })
    const w = mountPanel()
    await flushPromises()
    await openPreview(w)
    await keyReady(w)
    expect(q(w, 'promo-execute').attributes('disabled')).toBeDefined()
  })

  it('a stale preview keeps the card, explains, and offers 重新预览', async () => {
    promoApi.executePromotion.mockRejectedValue(Object.assign(
      new Error('预览之后草稿或正式数据已经变化，或预览已过期，这次确认没有写入任何数据'),
      { code: 'PROMOTION_PREVIEW_STALE', status: 409, actionHint: '重新预览，核对将写入的内容后再确认' }))
    const w = mountPanel()
    await flushPromises()
    await openPreview(w)
    await keyReady(w)
    await q(w, 'promo-execute').trigger('click')
    await flushPromises()
    expect(q(w, 'promo-stale').text()).toContain('预览之后草稿或正式数据已经变化')
    expect(q(w, 'promo-stale').text()).toContain('下一步：重新预览，核对将写入的内容后再确认')
    expect(w.find('[data-testid="promo-preview"]').exists()).toBe(true)
    expect(q(w, 'promo-execute').attributes('disabled')).toBeDefined()
    await q(w, 'promo-repreview').trigger('click')
    await flushPromises()
    expect(promoApi.previewPromotion).toHaveBeenCalledTimes(2)
    expect(w.find('[data-testid="promo-stale"]').exists()).toBe(false)
    expect(q(w, 'promo-execute').attributes('disabled')).toBeUndefined()
  })

  it('PROMOTION_ALREADY_DONE shows the backend text and refreshes /promotions', async () => {
    promoApi.executePromotion.mockRejectedValue(Object.assign(
      new Error('这份草稿已经转正过（正式数据 ID：PT-7），不能重复转正'),
      { code: 'PROMOTION_ALREADY_DONE', status: 409, actionHint: '查看这份草稿的转正记录' }))
    const w = mountPanel()
    await flushPromises()
    await openPreview(w)
    await keyReady(w)
    await q(w, 'promo-execute').trigger('click')
    await flushPromises()
    expect(q(w, 'promo-error').text()).toContain('这份草稿已经转正过（正式数据 ID：PT-7）')
    expect(q(w, 'promo-error').text()).toContain('下一步：查看这份草稿的转正记录')
    expect(promoApi.listPromotions).toHaveBeenCalledTimes(2)
  })

  it('platform admin: no promotion entries and no promotion requests', async () => {
    auth.isFactory = false
    auth.currentRole = 'super_admin'
    auth.factoryId = ''
    const w = mountPanel()
    await flushPromises()
    expect(draftApi.getDraftWriteOrder).toHaveBeenCalledTimes(1)
    expect(promoApi.listPromotions).not.toHaveBeenCalled()
    expect(promoApi.previewPromotion).not.toHaveBeenCalled()
    expect(promoApi.getPromotion).not.toHaveBeenCalled()
    expect(w.find('[data-testid^="draft-promotions-"]').exists()).toBe(false)
    expect(w.text()).not.toContain('转正为正式')
  })
})
