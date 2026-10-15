import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const auth = vi.hoisted(() => ({ isFactory: true, isPlatform: false, currentRole: 'factory_super_admin', factoryId: 'F006',
  user: { id: 7, userType: 'factory' } as { id: number; userType: string } | null }))
const api = vi.hoisted(() => ({
  getDraftWriteOrder: vi.fn(),
  getWrittenDrafts: vi.fn(),
  getDraftProposal: vi.fn(),
  previewDraft: vi.fn(),
  materializeDraft: vi.fn(),
  readBackDraft: vi.fn(),
}))
const messageBox = vi.hoisted(() => ({ confirm: vi.fn() }))
const promoApi = vi.hoisted(() => ({
  previewPromotion: vi.fn(), executePromotion: vi.fn(), getPromotion: vi.fn(),
  listPromotions: vi.fn(async () => ({ data: { promotions: [] } })),
}))

vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }))
vi.mock('@/api/factoryBlueprintDraftApi', () => api)
vi.mock('@/api/factoryBlueprintPromotionApi', () => promoApi)
vi.mock('element-plus', () => ({ ElMessageBox: messageBox }))

import FactoryBlueprintDraftsPanel from '../components/FactoryBlueprintDraftsPanel.vue'

/** 真的 Element Plus 输入组件: 失焦夹值这种行为只有真组件才有, 桩测不出来 */
const ep = await vi.importActual<typeof import('element-plus')>('element-plus')

const stubs = {
  'el-alert': { props: ['title', 'description'], template: '<div class="alert">{{ title }} {{ description }}</div>' },
  'el-tag': { template: '<span class="tag"><slot /></span>' },
  'el-button': {
    props: ['disabled', 'loading'], emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
  'el-input': {
    props: ['modelValue'], emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  'el-input-number': {
    props: ['modelValue'], emits: ['update:modelValue'],
    template: '<input type="number" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />',
  },
  'el-select': {
    props: ['modelValue'], emits: ['update:modelValue'],
    template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option value=""></option><slot /></select>',
  },
  'el-option': { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
  'el-radio-group': {
    props: ['modelValue'], emits: ['update:modelValue'],
    provide() {
      return { radioGroup: { set: (v: unknown) => (this as unknown as { $emit: (e: string, v: unknown) => void }).$emit('update:modelValue', v) } }
    },
    template: '<div class="radio-group"><slot /></div>',
  },
  'el-radio-button': {
    props: ['value'], inject: ['radioGroup'],
    template: '<button class="radio" @click="radioGroup.set(value)"><slot /></button>',
  },
  'el-date-picker': {
    props: ['modelValue', 'valueFormat'], emits: ['update:modelValue'],
    template: '<input class="date" :data-format="valueFormat" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  'el-checkbox': {
    props: ['modelValue'], emits: ['update:modelValue'],
    template: '<label><input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" /><slot /></label>',
  },
}

const blueprint = {
  id: 'bp-1',
  version: 2,
  capabilities: [
    { id: 'accounts', label: '岗位与培训' },
    { id: 'workflow', label: '工艺路线' },
    { id: 'products', label: '产品' },
    { id: 'forms', label: '表单' },
  ],
}

const row = (capabilityId: string, over: Record<string, unknown> = {}) => ({
  capabilityId, adapterMode: 'MODULE_DRAFT', entryOpen: true, proposalOpen: true, requiredPermission: 'p',
  writeDependencies: [], dependenciesWithoutOpenEntry: [], ...over,
})

const accountsRequest = () => ({
  factoryId: 'F006',
  blueprintId: 'bp-1',
  blueprintVersion: 2,
  sourceDigest: 'abcdef0123456789',
  positions: [{ title: '质检员', department: null, minimumHeadcount: null, healthCertificateRequired: null, remark: null, startDate: null }],
})

const typed = (over: Record<string, unknown>) => ({ options: [], nullable: false, min: null, max: null, maxLength: null, ...over })

const accountsInputs = () => [
  typed({ path: 'positions[0].minimumHeadcount', label: '最少几人', reason: '访谈没有问人数', constraint: '1–500 人',
    inputType: 'INTEGER', min: 1, max: 500 }),
  typed({ path: 'positions[0].healthCertificateRequired', label: '是否需要健康证', reason: '工厂自己决定', constraint: '是 / 否',
    inputType: 'BOOLEAN' }),
  typed({ path: 'positions[0].department', label: '属于哪个部门', reason: '角色部门对不上', constraint: '质量、加工 之一',
    inputType: 'ENUM', options: [{ value: 'QUALITY', label: '质量' }, { value: 'PROCESSING', label: '加工' }] }),
  typed({ path: 'positions[0].remark', label: '备注', reason: '访谈没有问', constraint: '不超过 20 字，可以留空',
    inputType: 'TEXT', nullable: true, maxLength: 20 }),
  typed({ path: 'positions[0].startDate', label: '启用日期', reason: '访谈没有问', constraint: '日期',
    inputType: 'DATE' }),
]

const accountsProposal = (inputs: Array<Record<string, unknown>> = accountsInputs()) => ({
  status: 'NEEDS_INPUT',
  blueprintVersion: 2,
  sourceDigest: 'abcdef0123456789',
  blockers: [],
  items: [{
    itemKey: 'accounts',
    label: '岗位与培训要求',
    status: 'NEEDS_INPUT',
    request: accountsRequest(),
    requiredInputs: inputs,
    evidence: [
      { path: 'sourcePayload.suggestedData.provisionDefaultUsers', value: 'true' },
      { path: 'sourcePayload.alerts', value: '未带入：冷库温度告警' },
    ],
  }],
})

function setupApis() {
  api.getDraftWriteOrder.mockResolvedValue({ data: { schemaVersion: 'v1', capabilities: [
    row('factory', { adapterMode: 'READ_ONLY', entryOpen: false, proposalOpen: false }),
    row('accounts'),
    row('bom', { dependenciesWithoutOpenEntry: ['workflow'] }),
    row('haccp', { entryOpen: false, proposalOpen: false }),
    row('forms', { proposalOpen: false }),
    row('products'),
  ] } })
  api.getWrittenDrafts.mockResolvedValue({ data: { schemaVersion: 'v1', blueprintId: 'bp-1', blueprintVersion: 2,
    sourceDigest: 'abcdef0123456789', capabilities: [
      { capabilityId: 'accounts', supported: false, drafts: [] },
      { capabilityId: 'products', supported: true, drafts: [
        { draftId: 'pd-9', naturalKey: 'P-01', label: '卤猪蹄', status: 'DRAFT', desiredDigest: 'x', createdAt: 'now' },
      ] },
    ] } })
  api.getDraftProposal.mockImplementation(async (_f: string, _b: string, capabilityId: string) => {
    if (capabilityId === 'accounts') return { data: accountsProposal() }
    if (capabilityId === 'products') {
      return { data: { status: 'BLOCKED', sourceDigest: null, items: [],
        blockers: [{ code: 'BLUEPRINT_CAPABILITY_NOT_READY', message: '蓝图里这项能力还没有就绪', nextStep: '回到建厂访谈补充产品' }] } }
    }
    throw new Error(`unexpected proposal ${capabilityId}`)
  })
  api.previewDraft.mockResolvedValue({ data: { status: 'READY', previewDigest: 'pv-1', issues: [], expectedBusinessWriteCount: 1 } })
  api.materializeDraft.mockResolvedValue({ data: { draftId: 'd-1', replayed: false, status: 'DRAFT' } })
  const unsupported = Object.assign(new Error('请求的接口不存在'), {
    code: 'NOT_FOUND', status: 404,
    backendErrorCode: 'BLUEPRINT_DRAFT_READBACK_UNSUPPORTED', backendMessage: '这类建厂草稿暂不支持回读',
  })
  api.readBackDraft.mockRejectedValue(unsupported)
  messageBox.confirm.mockResolvedValue('confirm')
}

function mountPanel() {
  return mount(FactoryBlueprintDraftsPanel, {
    props: { factoryId: 'F006', factoryName: '示范工厂', blueprint },
    global: { stubs },
  })
}

const rowOf = (w: VueWrapper, id: string) => w.get(`[data-testid="draft-row-${id}"]`)

async function fillAccounts(w: VueWrapper) {
  await w.get('[data-testid="draft-input-positions[0].minimumHeadcount"] input').setValue('2')
  const radios = w.get('[data-testid="draft-input-positions[0].healthCertificateRequired"]').findAll('button.radio')
  await radios[1].trigger('click') // 否 —— false 也是一个回答
  // 按用户看到的文字「质量」选, 不按值选 —— 这样「下拉把 label 当值发出去」才会被抓到
  const option = w.get('[data-testid="draft-input-positions[0].department"]').findAll('option').find((o) => o.text() === '质量')
  if (!option) throw new Error('部门下拉里没有「质量」')
  await option.setSelected()
  await w.get('[data-testid="draft-input-positions[0].remark"] input[type="checkbox"]').setValue(true) // 明确留空
  await w.get('[data-testid="draft-input-positions[0].startDate"] input.date').setValue('2026-11-01')
}

async function fillAccountsExceptDepartment(w: VueWrapper) {
  await w.get('[data-testid="draft-input-positions[0].minimumHeadcount"] input').setValue('2')
  await w.get('[data-testid="draft-input-positions[0].healthCertificateRequired"]').findAll('button.radio')[1].trigger('click')
  await w.get('[data-testid="draft-input-positions[0].remark"] input[type="checkbox"]').setValue(true)
  await w.get('[data-testid="draft-input-positions[0].startDate"] input.date').setValue('2026-11-01')
}

const filledRequest = () => {
  const r = accountsRequest()
  r.positions[0] = { ...r.positions[0], minimumHeadcount: 2, healthCertificateRequired: false, department: 'QUALITY',
    remark: null, startDate: '2026-11-01' } as never
  return r
}

/** 独立对照: 用 node:crypto 按约定格式算出期望的幂等键, 不调被测代码 */
const expectedKey = `bd:accounts:${createHash('sha256')
  .update('draft:FACTORY_USER:7:F006:bp-1:2:abcdef0123456789:accounts:accounts:preview:pv-1').digest('hex').slice(0, 40)}`

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

beforeEach(() => {
  auth.isFactory = true
  auth.isPlatform = false
  auth.currentRole = 'factory_super_admin'
  auth.factoryId = 'F006'
  auth.user = { id: 7, userType: 'factory' }
  Object.values(api).forEach((m) => m.mockReset())
  messageBox.confirm.mockReset()
  Object.values(promoApi).forEach((m) => m.mockClear())
  setupApis()
})

describe('FactoryBlueprintDraftsPanel', () => {
  it('uses distinct real materialize keys for same-number factory and platform actors', async () => {
    const keys: string[] = []
    for (const userType of ['factory', 'platform']) {
      auth.user = { id: 7, userType }
      auth.isFactory = userType === 'factory'; auth.isPlatform = userType === 'platform'
      auth.currentRole = userType === 'factory' ? 'factory_super_admin' : 'super_admin'
      auth.factoryId = userType === 'factory' ? 'F006' : ''
      const w = mountPanel(); await flushPromises()
      await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
      await fillAccounts(w)
      await w.get('[data-testid="draft-preview"]').trigger('click'); await flushPromises()
      await vi.waitFor(() => expect(w.get('[data-testid="draft-confirm"]').attributes('disabled')).toBeUndefined())
      await w.get('[data-testid="draft-confirm"]').trigger('click'); await flushPromises()
      keys.push(api.materializeDraft.mock.calls.at(-1)![3].idempotencyKey)
      w.unmount()
    }
    expect(api.materializeDraft).toHaveBeenCalledTimes(2)
    expect(keys[0]).not.toBe(keys[1])
  })

  it('cannot confirm a READY preview when authenticated identity is missing', async () => {
    auth.user = null
    const w = mountPanel(); await flushPromises()
    await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
    await fillAccounts(w)
    await w.get('[data-testid="draft-preview"]').trigger('click'); await flushPromises()
    expect(w.get('[data-testid="draft-confirm"]').attributes('disabled')).toBeDefined()
    expect(w.text()).toContain('身份')
    expect(api.materializeDraft).not.toHaveBeenCalled()
    w.unmount()
  })
  it('(a) a role string without a platform namespace cannot confirm or read protected drafts', async () => {
    auth.isFactory = false
    auth.currentRole = 'super_admin'
    auth.factoryId = ''
    const w = mountPanel()
    await flushPromises()
    expect(api.getDraftWriteOrder).toHaveBeenCalledTimes(1)
    expect(api.getWrittenDrafts).not.toHaveBeenCalled()
    expect(api.getDraftProposal).not.toHaveBeenCalled()
    expect(api.readBackDraft).not.toHaveBeenCalled()
    expect(promoApi.listPromotions).not.toHaveBeenCalled()
    expect(w.get('[data-testid="drafts-factory-only"]').text()).toContain('当前账号没有建厂确认权限')
    expect(rowOf(w, 'accounts').text()).toContain('待本厂确认')
    expect(w.find('[data-testid="draft-row-toggle-accounts"]').exists()).toBe(false)
  })

  it('actual platform namespace opens proposal, written readback and promotion records', async () => {
    auth.isFactory = false; auth.isPlatform = true; auth.currentRole = 'super_admin'; auth.factoryId = ''
    const w = mountPanel(); await flushPromises()
    expect(api.getWrittenDrafts).toHaveBeenCalledWith('F006', 'bp-1')
    expect(api.getDraftProposal).toHaveBeenCalled()
    expect(promoApi.listPromotions).toHaveBeenCalledWith('F006', 'bp-1')
    expect(w.find('[data-testid="drafts-factory-only"]').exists()).toBe(false)
    w.unmount()
  })

  it('(a2) a factory super admin of another factory is treated like the platform admin', async () => {
    auth.factoryId = 'F001'
    mountPanel()
    await flushPromises()
    expect(api.getWrittenDrafts).not.toHaveBeenCalled()
    expect(api.getDraftProposal).not.toHaveBeenCalled()
  })

  it('shows every row state from the write order, proposals and written list', async () => {
    const w = mountPanel()
    await flushPromises()
    expect(api.getDraftProposal.mock.calls.map((c) => c[2]).sort()).toEqual(['accounts', 'products'])
    expect(rowOf(w, 'factory').text()).toContain('只读能力')
    expect(rowOf(w, 'accounts').text()).toContain('待填 5 项')
    expect(rowOf(w, 'bom').text()).toContain('依赖「工艺路线」入口未开放')
    expect(rowOf(w, 'haccp').text()).toContain('入口未开放')
    expect(rowOf(w, 'forms').text()).toContain('无预填')
    expect(rowOf(w, 'forms').text()).toContain('需手工填写，暂未提供')
    expect(rowOf(w, 'products').text()).toContain('阻塞')
    expect(rowOf(w, 'products').text()).toContain('蓝图里这项能力还没有就绪（下一步：回到建厂访谈补充产品）')
    expect(rowOf(w, 'products').text()).toContain('已写入 1 份')
    expect(w.get('[data-testid="drafts-lineage-warning"]').text()).toContain('修改并重新保存蓝图后，已写入的草稿将不再属于当前版本')
  })

  it('(c) supported=false shows 暂无法列出已写入 and never 未写入', async () => {
    const w = mountPanel()
    await flushPromises()
    const accounts = rowOf(w, 'accounts')
    expect(accounts.get('[data-testid="draft-row-written"]').text()).toBe('暂无法列出已写入')
    expect(w.text()).not.toContain('未写入')
  })

  it('(b) factory super admin: proposal → fill → preview → confirm → materialize with digest + fixed key, then read back', async () => {
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
    expect(w.get('[data-testid="draft-evidence"]').text()).toContain('provisionDefaultUsers')
    expect(w.get('[data-testid="draft-not-carried"]').text()).toContain('未带入：冷库温度告警')

    const previewBtn = () => w.get('[data-testid="draft-preview"]')
    expect(previewBtn().attributes('disabled')).toBeDefined()

    await fillAccounts(w)
    expect(rowOf(w, 'accounts').get('[data-testid="draft-row-state"]').text()).toBe('可预览')
    expect(previewBtn().attributes('disabled')).toBeUndefined()
    await previewBtn().trigger('click')
    await flushPromises()

    const expectedRequest = filledRequest()
    expect(api.previewDraft).toHaveBeenCalledWith('F006', 'bp-1', 'accounts', expectedRequest)

    const confirmBtn = () => w.get('[data-testid="draft-confirm"]')
    // 幂等键是异步 SHA-256, 算好之前「确认写入」保持禁用
    await vi.waitFor(() => expect(confirmBtn().attributes('disabled')).toBeUndefined())
    await confirmBtn().trigger('click')
    await flushPromises()

    expect(messageBox.confirm).toHaveBeenCalledTimes(1)
    expect(messageBox.confirm.mock.calls[0][0]).toBe('确认写入草稿 — 示范工厂（F006）· 岗位与培训·岗位与培训要求')
    expect(api.materializeDraft).toHaveBeenCalledTimes(1)
    const [f, b, cap, body] = api.materializeDraft.mock.calls[0]
    expect([f, b, cap]).toEqual(['F006', 'bp-1', 'accounts'])
    expect(body).toEqual({ ...expectedRequest, confirmedPreviewDigest: 'pv-1',
      idempotencyKey: expectedKey })
    expect(body.idempotencyKey.length).toBeLessThanOrEqual(128)
    expect(Object.keys(body).sort()).toEqual([...Object.keys(expectedRequest), 'confirmedPreviewDigest', 'idempotencyKey'].sort())

    expect(w.get('[data-testid="draft-receipt"]').text()).toContain('d-1')
    expect(api.readBackDraft).toHaveBeenCalledWith('F006', 'bp-1', 'accounts', 'd-1')
    expect(w.get('[data-testid="draft-readback"]').text()).toContain('这类建厂草稿暂不支持回读')
    expect(w.find('[data-testid="draft-materialize-error"]').exists()).toBe(false)
    expect(api.getWrittenDrafts).toHaveBeenCalledTimes(2)

    // 重试复用同一个幂等键
    await confirmBtn().trigger('click')
    await flushPromises()
    expect(api.materializeDraft).toHaveBeenCalledTimes(2)
    expect(api.materializeDraft.mock.calls[1][3].idempotencyKey).toBe(body.idempotencyKey)
  })

  it('(c) preview stays disabled until every required input is answered', async () => {
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
    await w.get('[data-testid="draft-input-positions[0].minimumHeadcount"] input').setValue('2')
    await w.get('[data-testid="draft-input-positions[0].department"] select').setValue('QUALITY')
    await w.get('[data-testid="draft-input-positions[0].startDate"] input.date').setValue('2026-11-01')
    await w.get('[data-testid="draft-input-positions[0].remark"] input[type="checkbox"]').setValue(true)
    // 只差「是否需要健康证」: 是/否两个按钮都没点, 不能默认成「否」
    expect(rowOf(w, 'accounts').get('[data-testid="draft-row-state"]').text()).toBe('待填 1 项')
    expect(w.get('[data-testid="draft-preview"]').attributes('disabled')).toBeDefined()
    await w.get('[data-testid="draft-preview"]').trigger('click')
    expect(api.previewDraft).not.toHaveBeenCalled()
  })

  it('does not materialize when input changes while confirmation is open', async () => {
    const confirmation = deferred<string>()
    messageBox.confirm.mockReturnValueOnce(confirmation.promise)
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
    await fillAccounts(w)
    await w.get('[data-testid="draft-preview"]').trigger('click')
    await flushPromises()
    await vi.waitFor(() => expect(w.get('[data-testid="draft-confirm"]').attributes('data-key-ready')).toBe('true'))
    await w.get('[data-testid="draft-confirm"]').trigger('click')
    await vi.waitFor(() => expect(messageBox.confirm).toHaveBeenCalledTimes(1))

    await w.get('[data-testid="draft-input-positions[0].minimumHeadcount"] input').setValue('3')
    confirmation.resolve('confirm')
    await flushPromises()
    expect(api.materializeDraft).not.toHaveBeenCalled()
    expect(w.get('[data-testid="draft-materialize-error"]').text()).toContain('重新预览')
    w.unmount()
  })

  it('does not materialize when the typed actor changes while confirmation is open', async () => {
    for (const [index, replacement] of [{ userType: 'platform', id: 7 }, { userType: 'factory', id: 8 }].entries()) {
      auth.user = { id: 7, userType: 'factory' }
      const confirmation = deferred<string>()
      messageBox.confirm.mockReturnValueOnce(confirmation.promise)
      const w = mountPanel()
      await flushPromises()
      await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
      await fillAccounts(w)
      await w.get('[data-testid="draft-preview"]').trigger('click')
      await flushPromises()
      await vi.waitFor(() => expect(w.get('[data-testid="draft-confirm"]').attributes('data-key-ready')).toBe('true'))
      await w.get('[data-testid="draft-confirm"]').trigger('click')
      await vi.waitFor(() => expect(messageBox.confirm).toHaveBeenCalledTimes(index + 1))

      auth.user = replacement
      confirmation.resolve('confirm')
      await flushPromises()
      expect(api.materializeDraft).not.toHaveBeenCalled()
      expect(w.get('[data-testid="draft-materialize-error"]').text()).toContain('登录身份发生变化')
      w.unmount()
    }
  })

  it('does not materialize when a new preview replaces the one being confirmed', async () => {
    const confirmation = deferred<string>()
    messageBox.confirm.mockReturnValueOnce(confirmation.promise)
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
    await fillAccounts(w)
    await w.get('[data-testid="draft-preview"]').trigger('click')
    await flushPromises()
    await vi.waitFor(() => expect(w.get('[data-testid="draft-confirm"]').attributes('data-key-ready')).toBe('true'))
    await w.get('[data-testid="draft-confirm"]').trigger('click')
    await vi.waitFor(() => expect(messageBox.confirm).toHaveBeenCalledTimes(1))

    api.previewDraft.mockResolvedValueOnce({ data: { status: 'READY', previewDigest: 'pv-2', issues: [], expectedBusinessWriteCount: 1 } })
    await w.get('[data-testid="draft-input-positions[0].minimumHeadcount"] input').setValue('3')
    await w.get('[data-testid="draft-preview"]').trigger('click')
    await flushPromises()
    confirmation.resolve('confirm')
    await flushPromises()
    expect(api.materializeDraft).not.toHaveBeenCalled()
    expect(w.get('[data-testid="draft-materialize-error"]').text()).toContain('重新预览')
    w.unmount()
  })

  it('rebinds the materialize key to a new READY preview digest after the request changes', async () => {
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
    await fillAccounts(w)
    await w.get('[data-testid="draft-preview"]').trigger('click')
    await flushPromises()
    await vi.waitFor(() => expect(w.get('[data-testid="draft-confirm"]').attributes('data-key-ready')).toBe('true'))
    await w.get('[data-testid="draft-confirm"]').trigger('click')
    await flushPromises()
    const firstBody = api.materializeDraft.mock.calls[0][3]
    expect(firstBody.confirmedPreviewDigest).toBe('pv-1')

    api.previewDraft.mockResolvedValueOnce({ data: { status: 'READY', previewDigest: 'pv-2', issues: [], expectedBusinessWriteCount: 1 } })
    await w.get('[data-testid="draft-input-positions[0].minimumHeadcount"] input').setValue('3')
    expect(w.find('[data-testid="draft-confirm"]').exists()).toBe(false)
    await w.get('[data-testid="draft-preview"]').trigger('click')
    await flushPromises()
    await vi.waitFor(() => expect(w.get('[data-testid="draft-confirm"]').attributes('data-key-ready')).toBe('true'))
    await w.get('[data-testid="draft-confirm"]').trigger('click')
    await flushPromises()

    const secondBody = api.materializeDraft.mock.calls[1][3]
    expect(secondBody.confirmedPreviewDigest).toBe('pv-2')
    expect(secondBody.idempotencyKey).not.toBe(firstBody.idempotencyKey)
    w.unmount()
  })

  it('does not let an older async preview overwrite the current input state', async () => {
    const oldPreview = deferred<{ data: { status: string; previewDigest: string; issues: [] } }>()
    const currentPreview = deferred<{ data: { status: string; previewDigest: string; issues: [] } }>()
    api.previewDraft.mockImplementationOnce(() => oldPreview.promise).mockImplementationOnce(() => currentPreview.promise)
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
    await fillAccounts(w)
    await w.get('[data-testid="draft-preview"]').trigger('click')
    await w.get('[data-testid="draft-input-positions[0].minimumHeadcount"] input').setValue('3')

    await w.get('[data-testid="draft-preview"]').trigger('click')
    currentPreview.resolve({ data: { status: 'READY', previewDigest: 'pv-current', issues: [] } })
    await flushPromises()
    expect(w.get('[data-testid="draft-preview-result"]').text()).toContain('pv-current')

    oldPreview.resolve({ data: { status: 'READY', previewDigest: 'pv-old', issues: [] } })
    await flushPromises()
    expect(w.get('[data-testid="draft-preview-result"]').text()).not.toContain('pv-old')
    w.unmount()
  })

  it('does not let an older async digest overwrite the current materialize key', async () => {
    const oldDigest = deferred<ArrayBuffer>()
    const currentDigest = deferred<ArrayBuffer>()
    const digest = vi.fn()
      .mockImplementationOnce(() => oldDigest.promise)
      .mockImplementationOnce(() => currentDigest.promise)
    const originalCrypto = globalThis.crypto
    vi.stubGlobal('crypto', { subtle: { digest } })
    try {
      const w = mountPanel()
      await flushPromises()
      await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
      await fillAccounts(w)
      await w.get('[data-testid="draft-preview"]').trigger('click')
      await flushPromises()

      // 旧摘要仍在计算时，输入改变并开始第二次预览/摘要计算。
      await w.get('[data-testid="draft-input-positions[0].minimumHeadcount"] input').setValue('3')
      await w.get('[data-testid="draft-preview"]').trigger('click')
      await flushPromises()

      currentDigest.resolve(new Uint8Array(32).fill(0x22).buffer)
      await flushPromises()
      await vi.waitFor(() => expect(w.get('[data-testid="draft-confirm"]').attributes('data-key-ready')).toBe('true'))
      await w.get('[data-testid="draft-confirm"]').trigger('click')
      await flushPromises()
      const currentKey = api.materializeDraft.mock.calls.at(-1)![3].idempotencyKey

      oldDigest.resolve(new Uint8Array(32).fill(0x11).buffer)
      await flushPromises()
      await w.get('[data-testid="draft-confirm"]').trigger('click')
      await flushPromises()
      expect(api.materializeDraft.mock.calls.at(-1)![3].idempotencyKey).toBe(currentKey)
      w.unmount()
    } finally {
      vi.stubGlobal('crypto', originalCrypto)
    }
  })

  it('(c) an out-of-range INTEGER is not an answer and the bound is shown before submitting', async () => {
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
    await fillAccounts(w)
    const headcount = w.get('[data-testid="draft-input-positions[0].minimumHeadcount"]')
    expect(headcount.text()).toContain('范围：1–500（整数）')
    await headcount.get('input').setValue('501')
    expect(headcount.get('[data-testid="draft-input-problem"]').text()).toContain('不能大于 500')
    expect(w.get('[data-testid="draft-preview"]').attributes('disabled')).toBeDefined()
  })

  it('(c) a number below min is kept exactly as typed after blur (real Element Plus), with the reason and 预览 disabled', async () => {
    const inputs = accountsInputs()
    inputs[0] = { ...inputs[0], label: '“工厂总监”最少需要几个人在岗', min: 2,
      constraint: '2–500 人（特权岗位要求职责分离，不能少于 2 人）' }
    api.getDraftProposal.mockImplementation(async (_f: string, _b: string, capabilityId: string) =>
      capabilityId === 'accounts' ? { data: accountsProposal(inputs) }
        : { data: { status: 'BLOCKED', sourceDigest: null, items: [], blockers: [{ code: 'X', message: 'm', nextStep: 'n' }] } })
    const { 'el-input': _i, 'el-input-number': _n, ...otherStubs } = stubs
    const w = mount(FactoryBlueprintDraftsPanel, {
      props: { factoryId: 'F006', factoryName: '示范工厂', blueprint },
      global: { stubs: otherStubs, components: { ElInput: ep.ElInput, ElInputNumber: ep.ElInputNumber } },
    })
    await flushPromises()
    await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
    await fillAccounts(w)
    expect(w.get('[data-testid="draft-preview"]').attributes('disabled')).toBeUndefined() // 阳性对照: 填 2 时可预览

    const field = () => w.get('[data-testid="draft-input-positions[0].minimumHeadcount"]')
    const box = field().get('input')
    await box.setValue('1')
    await box.trigger('change')
    await box.trigger('blur')
    await flushPromises()

    expect((field().get('input').element as HTMLInputElement).value).toBe('1')
    const reason = field().get('[data-testid="draft-input-problem"]').text()
    expect(reason).toContain('不能小于 2')
    expect(reason).toContain('特权岗位要求职责分离')
    expect(w.get('[data-testid="draft-preview"]').attributes('disabled')).toBeDefined()
    expect(rowOf(w, 'accounts').get('[data-testid="draft-row-state"]').text()).toBe('待填 1 项')
  })

  it('nullable=false offers no 明确留空; the DATE picker sends ISO yyyy-MM-dd', async () => {
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
    expect(w.get('[data-testid="draft-input-positions[0].remark"]').text()).toContain('明确留空')
    expect(w.get('[data-testid="draft-input-positions[0].department"]').text()).not.toContain('明确留空')
    expect(w.get('[data-testid="draft-input-positions[0].startDate"] input.date').attributes('data-format')).toBe('YYYY-MM-DD')
  })

  it('a requiredInput without inputType is a contract error on that item, not a text box', async () => {
    const inputs = accountsInputs()
    delete (inputs[2] as Record<string, unknown>).inputType
    api.getDraftProposal.mockImplementation(async (_f: string, _b: string, capabilityId: string) =>
      capabilityId === 'accounts' ? { data: accountsProposal(inputs) }
        : { data: { status: 'BLOCKED', sourceDigest: null, items: [], blockers: [{ code: 'X', message: 'm', nextStep: 'n' }] } })
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
    expect(w.get('[data-testid="draft-contract-error"]').text()).toContain('positions[0].department')
    expect(w.get('[data-testid="draft-contract-error"]').text()).toContain('缺少 inputType')
    const dept = w.get('[data-testid="draft-input-positions[0].department"]')
    expect(dept.find('select').exists()).toBe(false)
    expect(dept.find('input').exists()).toBe(false)
    await fillAccountsExceptDepartment(w)
    expect(w.get('[data-testid="draft-preview"]').attributes('disabled')).toBeDefined()
  })

  it('a field-bearing 400 highlights the input it names', async () => {
    api.previewDraft.mockRejectedValue(Object.assign(new Error('字段 positions[0].department 的值不在可选范围内'),
      { code: 'BLUEPRINT_DRAFT_INPUT_INVALID', status: 400 }))
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
    await fillAccounts(w)
    await w.get('[data-testid="draft-preview"]').trigger('click')
    await flushPromises()
    const dept = w.get('[data-testid="draft-input-positions[0].department"]')
    expect(dept.attributes('data-error')).toBe('true')
    expect(dept.get('[data-testid="draft-input-server-error"]').text()).toContain('不在可选范围内')
    expect(w.get('[data-testid="draft-input-positions[0].minimumHeadcount"]').attributes('data-error')).toBeUndefined()
  })

  it('(c) a non-READY preview without issues still cannot be confirmed', async () => {
    api.previewDraft.mockResolvedValue({ data: { status: 'BLOCKED', previewDigest: 'pv-3', issues: [] } })
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
    await fillAccounts(w)
    await w.get('[data-testid="draft-preview"]').trigger('click')
    await flushPromises()
    // 非 READY 预览不能生成绑定键；禁用必须由预览状态本身守住
    expect(w.get('[data-testid="draft-confirm"]').attributes('data-key-ready')).toBe('false')
    expect(w.get('[data-testid="draft-confirm"]').attributes('disabled')).toBeDefined()
  })

  it('(c) preview issues disable confirm and show the backend recovery', async () => {
    // status 故意给 READY: 这条只守「issues 非空就不能确认」, 不让 status 那道闸替它挡
    api.previewDraft.mockResolvedValue({ data: { status: 'READY', previewDigest: 'pv-2', issues: [
      { code: 'HEADCOUNT', target: 'positions[0].minimumHeadcount', message: '特权岗位不能少于 2 人', recovery: '把人数改成 2 或以上' },
    ] } })
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
    await fillAccounts(w)
    await w.get('[data-testid="draft-preview"]').trigger('click')
    await flushPromises()
    expect(w.get('[data-testid="draft-issues"]').text()).toContain('特权岗位不能少于 2 人')
    expect(w.get('[data-testid="draft-issues"]').text()).toContain('把人数改成 2 或以上')
    // 有 issues 的 READY 预览同样不能写入；当前摘要键只在真正可写的 READY 预览生成
    expect(w.get('[data-testid="draft-confirm"]').attributes('data-key-ready')).toBe('false')
    expect(w.get('[data-testid="draft-confirm"]').attributes('disabled')).toBeDefined()
    await w.get('[data-testid="draft-confirm"]').trigger('click')
    await flushPromises()
    expect(messageBox.confirm).not.toHaveBeenCalled()
    expect(api.materializeDraft).not.toHaveBeenCalled()
  })

  it('changing an input after preview discards the old preview', async () => {
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
    await fillAccounts(w)
    await w.get('[data-testid="draft-preview"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="draft-confirm"]').exists()).toBe(true)
    await w.get('[data-testid="draft-input-positions[0].minimumHeadcount"] input').setValue('3')
    expect(w.find('[data-testid="draft-confirm"]').exists()).toBe(false)
  })

  it('is mounted on the onboarding page only for a VALIDATED blueprint, between the blockers and the action bar', () => {
    const page = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '..', 'index.vue'), 'utf8')
    expect(page).toContain("import FactoryBlueprintDraftsPanel from './components/FactoryBlueprintDraftsPanel.vue'")
    const tagAt = page.indexOf('<FactoryBlueprintDraftsPanel')
    const blockersAt = page.indexOf('id="validation-blockers-title"')
    expect(blockersAt).toBeGreaterThan(0)
    expect(tagAt).toBeGreaterThan(blockersAt)
    // 页面里不止一个 .acts; 要的是服务端阻断项之后的那一个(step 1 的按钮区)
    expect(tagAt).toBeLessThan(page.indexOf('<div class="acts">', blockersAt))
    const tag = page.slice(tagAt, page.indexOf('/>', tagAt))
    expect(tag).toContain(`v-if="canonicalBlueprint?.lifecycleState === 'VALIDATED'"`)
    expect(tag).toContain(':blueprint="canonicalBlueprint"')
    expect(tag).toContain(':factory-id="factoryId"')
  })

  it('shows the backend message when preview is refused', async () => {
    api.previewDraft.mockRejectedValue(Object.assign(new Error('还有必填项没填：positions[0].minimumHeadcount，请补齐后再预览'),
      { code: 'BLUEPRINT_DRAFT_REQUIRED_INPUT_MISSING', status: 400 }))
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="draft-row-toggle-accounts"]').trigger('click')
    await fillAccounts(w)
    await w.get('[data-testid="draft-preview"]').trigger('click')
    await flushPromises()
    expect(w.get('[data-testid="draft-preview-error"]').text()).toContain('还有必填项没填：positions[0].minimumHeadcount')
  })
})
