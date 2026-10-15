import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const api = vi.hoisted(() => ({
  getDraftWriteOrder: vi.fn(),
  getWrittenDrafts: vi.fn(),
  getDraftProposal: vi.fn(),
  previewDraft: vi.fn(),
  materializeDraft: vi.fn(),
  readBackDraft: vi.fn(),
}))
const messageBox = vi.hoisted(() => ({ confirm: vi.fn() }))

vi.mock('@/api/factoryBlueprintDraftApi', () => api)
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ user: { id: 7, userType: 'factory' } }) }))
vi.mock('element-plus', () => ({ ElMessageBox: messageBox }))

import FactoryBlueprintDraftItem from '../components/FactoryBlueprintDraftItem.vue'

/** 真的 Element Plus 输入框: 失焦行为(夹值)只有真组件才有 */
const ep = await vi.importActual<typeof import('element-plus')>('element-plus')

const stubs = {
  'el-alert': { props: ['title', 'description'], template: '<div class="alert">{{ title }} {{ description }}</div>' },
  'el-tag': { template: '<span class="tag"><slot /></span>' },
  'el-button': {
    props: ['disabled', 'loading'], emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
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

const field = (path: string, over: Record<string, unknown> = {}) => ({
  path, label: path, reason: '', constraint: '', inputType: 'TEXT', options: [], nullable: false,
  min: null, max: null, maxLength: null, structureKind: null, itemFields: [], minItems: null, maxItems: null,
  itemLabel: null, ...over,
})

const certificates = () => field('certificates', {
  label: '证书', inputType: 'STRUCTURE', structureKind: 'LIST', itemLabel: '证书', minItems: 0, maxItems: 2,
  itemFields: [field('name', { label: '证书名称' }), field('expiresOn', { label: '到期日', inputType: 'DATE', nullable: true })],
})

const positions = (over: Record<string, unknown> = {}) => field('positions', {
  label: '岗位', reason: '访谈没有登记岗位', constraint: '1–3 个岗位', inputType: 'STRUCTURE', structureKind: 'LIST',
  itemLabel: '岗位', minItems: 1, maxItems: 3,
  itemFields: [
    field('positionCode', { label: '岗位编号', maxLength: 20 }),
    field('minimumHeadcount', { label: '最少在岗人数', inputType: 'INTEGER', min: 1, max: 500, constraint: '1–500 人' }),
    field('department', { label: '部门', inputType: 'ENUM',
      options: [{ value: 'QUALITY', label: '质量' }, { value: 'PROCESSING', label: '加工' }] }),
    certificates(),
  ],
  ...over,
})

const decision = () => field('decision', {
  label: '判定树', inputType: 'STRUCTURE', structureKind: 'OBJECT',
  itemFields: [field('q1', { label: '有控制措施', inputType: 'BOOLEAN' }), field('q2', { label: '专为消除危害', inputType: 'BOOLEAN' })],
})

const item = (over: { positionsValue?: unknown; inputs?: Array<Record<string, unknown>> } = {}) => ({
  itemKey: 'accounts',
  label: '岗位与培训要求',
  status: 'NEEDS_INPUT',
  request: { factoryId: 'F006', blueprintId: 'bp-1', positions: over.positionsValue ?? null, decision: null },
  requiredInputs: over.inputs ?? [positions(), decision()],
  evidence: [],
})

function mountItem(it = item()) {
  return mount(FactoryBlueprintDraftItem, {
    props: {
      factoryId: 'F006', factoryName: '示范工厂', blueprintId: 'bp-1', blueprintVersion: 2,
      sourceDigest: 'abcdef0123456789', capabilityId: 'accounts', capabilityLabel: '岗位与培训', item: it,
    },
    global: { stubs, components: { ElInput: ep.ElInput } },
  })
}

const q = (w: VueWrapper, testid: string) => w.get(`[data-testid="${testid}"]`)
const rowCards = (w: VueWrapper, path: string) =>
  w.findAll('[data-testid^="draft-row-card-"]').filter((c) => new RegExp(`^draft-row-card-${path.replace(/[[\]]/g, '\\$&')}\\[\\d+\\]$`).test(c.attributes('data-testid') ?? ''))

async function chooseOption(w: VueWrapper, fieldPath: string, text: string) {
  const option = q(w, `draft-input-${fieldPath}`).findAll('option').find((o) => o.text() === text)
  if (!option) throw new Error(`${fieldPath} 没有选项 ${text}`)
  await option.setSelected()
}

async function fillPosition(w: VueWrapper, i: number, code: string, headcount: string) {
  await q(w, `draft-input-positions[${i}].positionCode`).get('input').setValue(code)
  await q(w, `draft-input-positions[${i}].minimumHeadcount`).get('input').setValue(headcount)
  await chooseOption(w, `positions[${i}].department`, '质量')
}

async function fillDecision(w: VueWrapper) {
  await q(w, 'draft-input-decision.q1').findAll('button.radio')[0].trigger('click')
  await q(w, 'draft-input-decision.q2').findAll('button.radio')[1].trigger('click')
}

beforeEach(() => {
  Object.values(api).forEach((m) => m.mockReset())
  messageBox.confirm.mockReset()
  api.previewDraft.mockResolvedValue({ data: { status: 'READY', previewDigest: 'pv-1', issues: [] } })
})

describe('DraftStructureEditor inside a draft item', () => {
  it('starts with minItems empty rows, shows the bounds, and refuses to delete below minItems with a reason', async () => {
    const w = mountItem()
    await flushPromises()
    expect(w.find('textarea').exists()).toBe(false) // JSON 编辑框已经没有了
    expect(rowCards(w, 'positions')).toHaveLength(1)
    expect(q(w, 'draft-row-card-positions[0]').text()).toContain('岗位 1')
    expect(q(w, 'draft-structure-positions').get('[data-testid="draft-structure-bound"]').text()).toBe('至少 1 行，至多 3 行')
    expect(q(w, 'draft-row-remove-positions[0]').attributes('disabled')).toBeDefined()
    expect(q(w, 'draft-structure-positions').get('[data-testid="draft-row-remove-reason"]').text()).toContain('至少要保留 1 行')
    // 嵌套的证书 minItems 0: 零行 + 空状态提示 + 添加按钮
    const certs = q(w, 'draft-structure-positions[0].certificates')
    expect(certs.get('[data-testid="draft-structure-empty"]').text()).toContain('还没有证书')
    expect(certs.get('[data-testid="draft-row-add-positions[0].certificates"]').attributes('disabled')).toBeUndefined()
  })

  it('add is disabled at maxItems with the reason; delete becomes possible above minItems', async () => {
    const w = mountItem()
    await flushPromises()
    const add = () => q(w, 'draft-row-add-positions')
    await add().trigger('click')
    await add().trigger('click')
    expect(rowCards(w, 'positions')).toHaveLength(3)
    expect(add().attributes('disabled')).toBeDefined()
    expect(q(w, 'draft-structure-positions').get('[data-testid="draft-row-add-reason"]').text()).toBe('最多 3 行，已经满了')
    await add().trigger('click')
    expect(rowCards(w, 'positions')).toHaveLength(3)
    expect(q(w, 'draft-row-remove-positions[1]').attributes('disabled')).toBeUndefined()
    await q(w, 'draft-row-remove-positions[1]').trigger('click')
    expect(rowCards(w, 'positions')).toHaveLength(2)
    expect(add().attributes('disabled')).toBeUndefined()
  })

  it('prefilled rows come from the request', async () => {
    const w = mountItem(item({ positionsValue: [
      { positionCode: 'P-QA', minimumHeadcount: null, department: 'QUALITY', certificates: [{ name: '健康证', expiresOn: null }] },
      { positionCode: 'P-OP', minimumHeadcount: 3, department: null, certificates: null },
    ] }))
    await flushPromises()
    expect(rowCards(w, 'positions')).toHaveLength(2)
    expect((q(w, 'draft-input-positions[0].positionCode').get('input').element as HTMLInputElement).value).toBe('P-QA')
    expect((q(w, 'draft-input-positions[1].minimumHeadcount').get('input').element as HTMLInputElement).value).toBe('3')
    expect((q(w, 'draft-input-positions[0].certificates[0].name').get('input').element as HTMLInputElement).value).toBe('健康证')
    expect(rowCards(w, 'positions[1].certificates')).toHaveLength(0)
  })

  it('an unfilled sub-field blocks 预览 and is named by row', async () => {
    const w = mountItem()
    await flushPromises()
    await fillPosition(w, 0, 'P-QA', '2')
    await fillDecision(w)
    expect(q(w, 'draft-preview').attributes('disabled')).toBeUndefined() // 阳性对照: 都填了可以预览
    await q(w, 'draft-row-add-positions').trigger('click')
    await q(w, 'draft-input-positions[1].positionCode').get('input').setValue('P-OP')
    expect(q(w, 'draft-preview').attributes('disabled')).toBeDefined()
    const missing = q(w, 'draft-missing').text()
    expect(missing).toContain('还差 2 项')
    expect(missing).toContain('岗位 2 · 最少在岗人数')
    expect(missing).toContain('岗位 2 · 部门')
    await q(w, 'draft-preview').trigger('click')
    expect(api.previewDraft).not.toHaveBeenCalled()
  })

  it('nested LIST inside LIST serializes exactly and a sub-field ENUM sends option.value', async () => {
    const w = mountItem()
    await flushPromises()
    await fillPosition(w, 0, 'P-QA', '2')
    await q(w, 'draft-row-add-positions[0].certificates').trigger('click')
    await q(w, 'draft-row-add-positions[0].certificates').trigger('click')
    await q(w, 'draft-input-positions[0].certificates[0].name').get('input').setValue('健康证')
    await q(w, 'draft-input-positions[0].certificates[0].expiresOn').get('input[type="checkbox"]').setValue(true)
    await q(w, 'draft-input-positions[0].certificates[1].name').get('input').setValue('HACCP')
    await q(w, 'draft-input-positions[0].certificates[1].expiresOn').get('input.date').setValue('2027-01-01')
    await fillDecision(w)
    await q(w, 'draft-preview').trigger('click')
    await flushPromises()
    expect(api.previewDraft).toHaveBeenCalledTimes(1)
    expect(api.previewDraft.mock.calls[0][3]).toEqual({
      factoryId: 'F006',
      blueprintId: 'bp-1',
      positions: [{
        positionCode: 'P-QA', minimumHeadcount: 2, department: 'QUALITY',
        certificates: [{ name: '健康证', expiresOn: null }, { name: 'HACCP', expiresOn: '2027-01-01' }],
      }],
      decision: { q1: true, q2: false },
    })
  })

  it('a 400 naming positions[1].minimumHeadcount highlights exactly that row and field', async () => {
    api.previewDraft.mockRejectedValue(Object.assign(new Error('字段 positions[1].minimumHeadcount 超出范围'),
      { code: 'BLUEPRINT_DRAFT_INPUT_INVALID', status: 400 }))
    const w = mountItem()
    await flushPromises()
    await fillPosition(w, 0, 'P-QA', '2')
    await q(w, 'draft-row-add-positions').trigger('click')
    await fillPosition(w, 1, 'P-OP', '3')
    await fillDecision(w)
    await q(w, 'draft-preview').trigger('click')
    await flushPromises()
    const target = q(w, 'draft-input-positions[1].minimumHeadcount')
    expect(target.attributes('data-error')).toBe('true')
    expect(target.get('[data-testid="draft-input-server-error"]').text()).toContain('超出范围')
    expect(q(w, 'draft-input-positions[0].minimumHeadcount').attributes('data-error')).toBeUndefined()
    expect(q(w, 'draft-input-positions').attributes('data-error')).toBeUndefined()
  })

  it('an out-of-range nested number keeps what was typed after blur (real Element Plus) and blocks 预览', async () => {
    const w = mountItem()
    await flushPromises()
    await fillPosition(w, 0, 'P-QA', '2')
    await fillDecision(w)
    expect(q(w, 'draft-preview').attributes('disabled')).toBeUndefined()
    const box = q(w, 'draft-input-positions[0].minimumHeadcount').get('input')
    await box.setValue('0')
    await box.trigger('change')
    await box.trigger('blur')
    await flushPromises()
    expect((q(w, 'draft-input-positions[0].minimumHeadcount').get('input').element as HTMLInputElement).value).toBe('0')
    expect(q(w, 'draft-input-positions[0].minimumHeadcount').get('[data-testid="draft-input-problem"]').text()).toContain('不能小于 1')
    expect(q(w, 'draft-preview').attributes('disabled')).toBeDefined()
    expect(q(w, 'draft-missing').text()).toContain('岗位 1 · 最少在岗人数')
  })

  it('an older backend STRUCTURE without itemFields is a contract error on the item, not a JSON box', async () => {
    const old = { ...positions(), structureKind: undefined, itemFields: undefined }
    const w = mountItem(item({ inputs: [old] }))
    await flushPromises()
    expect(q(w, 'draft-contract-error').text()).toContain('structureKind')
    expect(w.find('textarea').exists()).toBe(false)
    expect(w.find('[data-testid="draft-structure-positions"]').exists()).toBe(false)
    expect(q(w, 'draft-input-positions').text()).toContain('格式与页面不一致')
    expect(q(w, 'draft-preview').attributes('disabled')).toBeDefined()
  })
})

describe('VALUES (a list of plain values) inside a draft item', () => {
  const optionCodes = (path: string, over: Record<string, unknown> = {}) => field(path, {
    label: '选项编码', reason: '访谈没有列出选项', constraint: '1–3 个选项', inputType: 'STRUCTURE', structureKind: 'VALUES',
    itemLabel: '选项', minItems: 1, maxItems: 3,
    itemFields: [field('optionCodes', { label: '选项编码', maxLength: 40, constraint: '大写编码' })],
    ...over,
  })
  const formsItem = (inputs: Array<Record<string, unknown>>, request: Record<string, unknown>) => ({
    itemKey: 'forms', label: '表单', status: 'NEEDS_INPUT', request, requiredInputs: inputs, evidence: [],
  })
  const valueBox = (w: VueWrapper, path: string) => q(w, `draft-input-${path}`).get('input')

  it('top-level VALUES: one typed control per value, bounds with reasons, serialized as a plain array', async () => {
    const path = 'forms[0].fields[1].optionCodes'
    const w = mountItem(formsItem([optionCodes(path)],
      { factoryId: 'F006', blueprintId: 'bp-1', forms: [{ fields: [{ optionCodes: null }, { optionCodes: null }] }] }))
    await flushPromises()
    const structure = q(w, `draft-structure-${path}`)
    expect(structure.get('[data-testid="draft-structure-bound"]').text()).toBe('至少 1 行，至多 3 行')
    expect(structure.get('[data-testid="draft-values-rule"]').text()).toContain('大写编码')
    expect(structure.text()).toContain('选项 1')
    expect(q(w, `draft-row-remove-${path}[0]`).attributes('disabled')).toBeDefined()
    expect(structure.get('[data-testid="draft-row-remove-reason"]').text()).toContain('至少要保留 1 行')

    await valueBox(w, `${path}[0]`).setValue('HIGH')
    await q(w, `draft-row-add-${path}`).trigger('click')
    await valueBox(w, `${path}[1]`).setValue('LOW')
    await q(w, `draft-row-add-${path}`).trigger('click')
    expect(q(w, `draft-row-add-${path}`).attributes('disabled')).toBeDefined()
    expect(structure.get('[data-testid="draft-row-add-reason"]').text()).toBe('最多 3 行，已经满了')
    // 第 3 个值空着: 不能预览, 并被点名
    expect(q(w, 'draft-preview').attributes('disabled')).toBeDefined()
    expect(q(w, 'draft-missing').text()).toContain('选项 3')
    await q(w, `draft-row-remove-${path}[2]`).trigger('click')
    expect(q(w, 'draft-preview').attributes('disabled')).toBeUndefined()

    await q(w, 'draft-preview').trigger('click')
    await flushPromises()
    expect(api.previewDraft.mock.calls[0][3]).toEqual({
      factoryId: 'F006', blueprintId: 'bp-1',
      forms: [{ fields: [{ optionCodes: null }, { optionCodes: ['HIGH', 'LOW'] }] }],
    })
  })

  it('VALUES nested in a LIST row (0–50, nullable): starts empty, then serializes values inside the row object', async () => {
    const fieldsList = field('forms[0].fields', {
      label: '字段', inputType: 'STRUCTURE', structureKind: 'LIST', itemLabel: '字段', minItems: 1, maxItems: 5,
      itemFields: [field('fieldKey', { label: '字段标识' }), optionCodes('optionCodes', { minItems: 0, maxItems: 50, nullable: true })],
    })
    const w = mountItem(formsItem([fieldsList], { factoryId: 'F006', blueprintId: 'bp-1', forms: [{ fields: null }] }))
    await flushPromises()
    const nested = 'forms[0].fields[0].optionCodes'
    expect(q(w, `draft-structure-${nested}`).get('[data-testid="draft-structure-empty"]').text()).toContain('还没有选项')
    await q(w, 'draft-input-forms[0].fields[0].fieldKey').get('input').setValue('temp')
    expect(q(w, 'draft-preview').attributes('disabled')).toBeUndefined() // 0 个值也合法(minItems 0)
    await q(w, `draft-row-add-${nested}`).trigger('click')
    await q(w, `draft-row-add-${nested}`).trigger('click')
    await valueBox(w, `${nested}[0]`).setValue('HIGH')
    await valueBox(w, `${nested}[1]`).setValue('LOW')
    await q(w, 'draft-preview').trigger('click')
    await flushPromises()
    expect(api.previewDraft.mock.calls[0][3]).toEqual({
      factoryId: 'F006', blueprintId: 'bp-1',
      forms: [{ fields: [{ fieldKey: 'temp', optionCodes: ['HIGH', 'LOW'] }] }],
    })
  })
})

describe('readability polish', () => {
  const coldReason = '建厂访谈没有问温度和冷链控制'
  const coldControls = () => field('coldControls', {
    label: '冷链控制点', reason: coldReason, constraint: '没有冷链控制点就留空列表', inputType: 'STRUCTURE',
    structureKind: 'LIST', itemLabel: '控制点', minItems: 0, maxItems: null,
    itemFields: [
      field('code', { label: '编码', reason: coldReason, constraint: '2–50 位大写编码' }),
      field('controlType', { label: '类型', reason: '访谈给的类型不是冷链类型', inputType: 'ENUM',
        constraint: 'FREEZER、REFRIGERATOR、COLD_CHAIN_VEHICLE 之一（COLD_CHAIN_VEHICLE 可不填所在仓库）',
        options: [
          { value: 'COLD_CHAIN_VEHICLE', label: '冷藏车' },
          { value: 'FREEZER', label: '冷冻库' },
          { value: 'REFRIGERATOR', label: '冷藏库' },
        ] }),
      field('lowerTemp', { label: '温度下限（℃）', reason: coldReason, inputType: 'DECIMAL', min: -273.15, max: 100 }),
    ],
  })
  const warehouseItem = () => ({
    itemKey: 'warehouses', label: '仓库与冷链', status: 'NEEDS_INPUT', evidence: [],
    request: { factoryId: 'F006', blueprintId: 'bp-1', name: null, coldControls: null },
    requiredInputs: [field('name', { label: '仓库名称', reason: '访谈没有给名称' }), coldControls()],
  })
  const count = (text: string, needle: string) => text.split(needle).length - 1

  it('shows the row-bound sentence exactly once', async () => {
    const w = mountItem(warehouseItem())
    await flushPromises()
    expect(count(q(w, 'draft-input-coldControls').text(), '行数不限，可以不填')).toBe(1)
  })

  it('a sub-field repeats the reason only when it differs from the parent STRUCTURE', async () => {
    const w = mountItem(warehouseItem())
    await flushPromises()
    await q(w, 'draft-row-add-coldControls').trigger('click')
    const block = q(w, 'draft-input-coldControls').text()
    expect(count(block, coldReason)).toBe(1) // 外层顶部一次, 子字段不再重复
    expect(q(w, 'draft-input-coldControls[0].code').find('[data-testid="draft-input-reason"]').exists()).toBe(false)
    expect(q(w, 'draft-input-coldControls[0].lowerTemp').find('[data-testid="draft-input-reason"]').exists()).toBe(false)
    expect(q(w, 'draft-input-coldControls[0].controlType').get('[data-testid="draft-input-reason"]').text())
      .toBe('访谈给的类型不是冷链类型')
    // 子字段的要求照常显示
    expect(q(w, 'draft-input-coldControls[0].code').get('[data-testid="draft-input-constraint"]').text()).toContain('2–50 位大写编码')
  })

  it('enum requirement text shows option labels, not raw codes', async () => {
    const w = mountItem(warehouseItem())
    await flushPromises()
    await q(w, 'draft-row-add-coldControls').trigger('click')
    const constraint = q(w, 'draft-input-coldControls[0].controlType').get('[data-testid="draft-input-constraint"]').text()
    expect(constraint).toBe('要求：冷冻库、冷藏库、冷藏车 之一（冷藏车 可不填所在仓库）')
    expect(constraint).not.toMatch(/FREEZER|REFRIGERATOR|COLD_CHAIN_VEHICLE/)
  })

  it('an optional empty list is not counted as 需补充; the sentence matches the 可预览 state', async () => {
    const w = mountItem(warehouseItem())
    await flushPromises()
    expect(q(w, 'draft-fill-summary').text()).toBe('还需你补充 1 项') // 只差仓库名称
    await q(w, 'draft-input-name').get('input').setValue('原料库')
    expect(q(w, 'draft-preview').attributes('disabled')).toBeUndefined()
    expect(q(w, 'draft-fill-summary').text()).toBe('待填项都已填好，可以预览')
    expect(q(w, 'draft-optional-empty').text()).toBe('可选：冷链控制点（0 行）')
    expect(w.text()).not.toContain('还需你补充')
  })
})
