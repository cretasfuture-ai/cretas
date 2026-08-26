/**
 * ExcelUpload 预览表的价格列权限过滤 (P0, 2026-08-25)。
 *
 * 缺陷: `visiblePreviewHeaders` 的判据写的是
 *     `getColumnTypeBadge(header).label !== '楼'`
 * 而 badge 的 label 是 `'¥'`。那个 '楼' 是 '¥' 被 GBK 往返吃出来的
 * (UTF-8 的 ¥ = C2 A5, 正好是 GBK 的「楼」)。`'楼' !== '¥'` 恒为真 ⇒ 整个
 * `||` 恒为真 ⇒ 过滤器一列都没滤掉过。
 *
 * ⛔ 这里**不**断言「源码里不许出现 '楼'」—— 那守不住下一次编码事故, 也守不住
 * 有人把 badge 文案从 '¥' 改成 'CNY'。断言守的是**行为**:
 *   无价格权限 ⇒ 金额列不出现在预览表里, 且它的样本值不出现在字段确认表里。
 *
 * 走的是产品真实入口: 挂载真组件 → 通过 el-upload 的 on-change 触发 handleUpload
 * → 真的 runUploadAndAnalyze → 真的 buildFieldTypeConfirmations / detectFieldType
 * → 真的渲染。桩只挡住 API 与 element-plus 的 DOM。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';

// ── 解析结果: revenue 列是货币格式 → detectFieldType 判成「金额」 ──────
// 表头与样本值刻意全 ASCII: 断言本身不该因为编码往返而变色。
const HEADERS = ['store', 'revenue', 'qty'];
const PREVIEW_ROWS = [
  { store: 'Jingan', revenue: '$1,200.00', qty: '12' },
  { store: 'Xuhui', revenue: '$3,400.00', qty: '34' },
  { store: 'Pudong', revenue: '$5,600.00', qty: '56' },
];
const PRICE_HEADER = 'revenue';
const A_PRICE_SAMPLE = '$1,200.00';

const uploadAndAnalyzeMock = vi.fn(async () => ({
  success: true,
  parseResult: {
    row_count: PREVIEW_ROWS.length,
    headers: HEADERS,
    preview_data: PREVIEW_ROWS,
    field_mappings: null,
    table_type: 'sales',
  },
  analysis: null,
  chartRecommendations: [],
  uploadId: 'u-1',
  requiresConfirmation: false,
}));

vi.mock('@/api/smartbi', () => ({
  uploadAndAnalyze: (...a: unknown[]) => uploadAndAnalyzeMock(...(a as [])),
  uploadFileAsync: vi.fn(),
  pollUploadStatus: vi.fn(),
  confirmUploadAndPersist: vi.fn(),
  detectTableRegions: vi.fn(async () => ({ success: false, totalRegions: 0, regions: [] })),
}));

let canViewPriceFlag = false;
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({
    get canViewPrice() {
      return canViewPriceFlag;
    },
    canWrite: () => true,
  }),
}));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F_TEST', user: { factoryId: 'F_TEST' } }),
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/utils/echarts', () => ({
  default: {
    init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(), on: vi.fn() }),
    graphic: { LinearGradient: class {} },
  },
}));
vi.mock('@/components/smartbi', () => ({
  KPICard: { template: '<div class="kpi-card-stub" />' },
  AIInsightPanel: { template: '<div class="ai-insight-stub" />' },
}));

import ExcelUpload from '../ExcelUpload.vue';

// ── element-plus 桩 ────────────────────────────────────────────────
// el-table 把 :data 交给子 column, el-table-column 渲染 header slot +
// 逐行的 default slot —— 与真组件同形, 所以断言看的是真的渲染结果。
const TableStub = {
  name: 'ElTableStub',
  props: ['data'],
  provide(this: { data: unknown[] }) {
    return { tableRows: () => this.data || [] };
  },
  template: '<div class="el-table-stub"><slot /></div>',
};

const TableColumnStub = {
  name: 'ElTableColumnStub',
  props: ['prop', 'label'],
  inject: { tableRows: { default: () => () => [] as unknown[] } },
  template: `
    <div class="col-stub" :data-prop="prop" :data-label="label">
      <div class="col-header"><slot name="header" /></div>
      <div class="col-cell" v-for="(row, i) in tableRows()" :key="i">
        <slot v-bind="{ row: row, $index: i }" />
      </div>
    </div>`,
};

const SelectStub = {
  name: 'ElSelectStub',
  props: ['modelValue'],
  emits: ['update:modelValue', 'change'],
  template: '<div class="el-select-stub" />',
};

const UploadStub = {
  name: 'ElUploadStub',
  props: ['onChange'],
  template: '<div class="el-upload-stub"><slot /></div>',
};

const passthrough = (cls: string) => ({ template: `<div class="${cls}"><slot /></div>` });

const stubs = {
  'el-upload': UploadStub,
  'el-table': TableStub,
  'el-table-column': TableColumnStub,
  'el-select': SelectStub,
  'el-option': { props: ['label', 'value'], template: '<div class="el-option-stub" />' },
  'el-card': passthrough('el-card'),
  'el-steps': passthrough('el-steps'),
  'el-step': passthrough('el-step'),
  'el-icon': passthrough('el-icon'),
  'el-tag': passthrough('el-tag'),
  'el-button': { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
  'el-progress': passthrough('el-progress'),
  'el-breadcrumb': passthrough('el-breadcrumb'),
  'el-breadcrumb-item': passthrough('el-breadcrumb-item'),
  'el-dialog': passthrough('el-dialog'),
  'el-radio-group': passthrough('el-radio-group'),
  'el-radio': passthrough('el-radio'),
  'el-result': passthrough('el-result'),
};

async function mountAndParse(canViewPrice: boolean): Promise<VueWrapper> {
  canViewPriceFlag = canViewPrice;
  const wrapper = mount(ExcelUpload, {
    global: { stubs, directives: { loading: () => undefined } },
  });

  // 产品真实入口: el-upload 的 on-change 就是 handleUpload。
  const onChange = wrapper.findComponent(UploadStub).props('onChange') as
    (f: unknown) => Promise<unknown>;
  expect(typeof onChange).toBe('function');

  await onChange({
    name: 'sales.csv',
    raw: new File(['store,revenue,qty\n'], 'sales.csv', { type: 'text/csv' }),
  });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

/** 预览表里真正渲染出来的列 (按 el-table-column 的 prop 取, 只认解析出的表头)。 */
function previewColumns(wrapper: VueWrapper): string[] {
  return wrapper
    .findAllComponents(TableColumnStub)
    .map((c) => c.props('prop') as string)
    .filter((p) => HEADERS.includes(p));
}

/** 字段确认表里那一行的「确认类型」下拉。 */
function confirmTypeSelects(wrapper: VueWrapper) {
  return wrapper.findAllComponents(SelectStub);
}

beforeEach(() => {
  canViewPriceFlag = false;
  uploadAndAnalyzeMock.mockClear();
});

describe('ExcelUpload 预览表价格列过滤', () => {
  it('阳性对照: 有价格权限时金额列渲染出来了', async () => {
    const wrapper = await mountAndParse(true);
    const cols = previewColumns(wrapper);
    expect(cols).toContain(PRICE_HEADER);
    expect(cols).toEqual(HEADERS);
  });

  it('无价格权限时金额列不出现在预览表里', async () => {
    const wrapper = await mountAndParse(false);
    const cols = previewColumns(wrapper);
    expect(cols).not.toContain(PRICE_HEADER);
    // 非金额列不受影响 —— 否则「滤掉了」可能只是整张表没渲染
    expect(cols).toEqual(['store', 'qty']);
  });

  it('无价格权限时金额样本值不出现在字段确认表里', async () => {
    const wrapper = await mountAndParse(false);
    const html = wrapper.html();
    expect(html).not.toContain(A_PRICE_SAMPLE);
    // 阳性对照: 非金额列的样本值照常显示
    expect(html).toContain('Jingan');
  });

  it('阳性对照: 有价格权限时金额样本值照常显示', async () => {
    const wrapper = await mountAndParse(true);
    expect(wrapper.html()).toContain(A_PRICE_SAMPLE);
  });

  it('无价格权限时, 把「确认类型」改成非金额也不能把金额列变回可见', async () => {
    const wrapper = await mountAndParse(false);
    const selects = confirmTypeSelects(wrapper);
    // 字段确认表每个字段一个下拉, 顺序 = headers 顺序
    expect(selects.length).toBe(HEADERS.length);
    const priceSelect = selects[HEADERS.indexOf(PRICE_HEADER)];

    priceSelect.vm.$emit('update:modelValue', '文本');
    priceSelect.vm.$emit('change');
    await flushPromises();

    expect(previewColumns(wrapper)).not.toContain(PRICE_HEADER);
    expect(wrapper.html()).not.toContain(A_PRICE_SAMPLE);
  });

  it('变异到达性: 同一个改类型动作在有权限时确实生效 (badge 从 ¥ 变 Aa)', async () => {
    // ⛔ 没有这一条, 上一条「改了也还是隐藏」可能只是那个 emit 根本没打进去。
    const wrapper = await mountAndParse(true);
    const priceCol = wrapper
      .findAllComponents(TableColumnStub)
      .find((c) => c.props('prop') === PRICE_HEADER)!;
    expect(priceCol.find('.col-header').text()).toContain('¥');

    const selects = confirmTypeSelects(wrapper);
    const priceSelect = selects[HEADERS.indexOf(PRICE_HEADER)];
    priceSelect.vm.$emit('update:modelValue', '文本');
    priceSelect.vm.$emit('change');
    await flushPromises();

    const after = wrapper
      .findAllComponents(TableColumnStub)
      .find((c) => c.props('prop') === PRICE_HEADER)!;
    expect(after.find('.col-header').text()).toContain('Aa');
    expect(after.find('.col-header').text()).not.toContain('¥');
  });
});
