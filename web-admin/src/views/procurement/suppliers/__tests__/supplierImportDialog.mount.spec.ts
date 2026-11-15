import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { nuxtUiStubs } from './nuxtUiStubs';

/**
 * 供应商导入弹窗（迁 UModal 之后）—— 行为不变的两条主线：
 *
 * ① 缺必填列的文件：弹窗必须把「缺少必填映射：…」说出来，并且「生成预览」按钮是禁用的（不能放过去）；
 * ② 预览里有字段错误的行：错误行必须回显（分类 = 字段错误、说明列 = 后端给的错误原文），
 *    并且它不进「确认新增」的计数（只勾选 VALID 行）。
 *
 * 只走到「选文件 → 预览/校验」，⛔ 不触发 confirmSupplierImport。
 * 阳性对照：同一个弹窗，列齐全且行合法 ⇒ 缺列提示消失、「生成预览」可点、说明列显示「校验通过」。
 */
const api = vi.hoisted(() => ({
  previewSupplierImport: vi.fn(),
  confirmSupplierImport: vi.fn(),
  downloadSupplierImportErrors: vi.fn(),
  parseSupplierWorkbook: vi.fn(),
}));

vi.mock('@/api/supplierManagement', () => ({
  previewSupplierImport: api.previewSupplierImport,
  confirmSupplierImport: api.confirmSupplierImport,
  downloadSupplierImportErrors: api.downloadSupplierImportErrors,
}));
vi.mock('../supplierImport', async (orig) => ({
  ...(await orig<Record<string, unknown>>()),
  parseSupplierWorkbook: api.parseSupplierWorkbook,
}));
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
}));

import SupplierImportDialog from '../SupplierImportDialog.vue';

const stubs = {
  ...nuxtUiStubs,
  UStepper: defineComponent({ name: 'UStepper', setup: () => () => h('div', { class: 'stub-stepper' }) }),
  URadioGroup: defineComponent({ name: 'URadioGroup', setup: () => () => h('div', { class: 'stub-radio' }) }),
  UFileUpload: defineComponent({
    name: 'UFileUpload',
    props: { modelValue: { type: null, default: null } },
    emits: ['update:modelValue'],
    setup: () => () => h('div', { class: 'stub-upload' }),
  }),
};

const FULL_HEADERS = ['供应商名称', '联系人', '联系电话', '地址'];

function xlsx(name = 'suppliers.xlsx'): File {
  return new File([new Uint8Array([1])], name);
}

async function mountDialog() {
  const wrapper = mount(SupplierImportDialog, {
    props: { modelValue: true, factoryId: 'F006' },
    global: { stubs },
  });
  await flushPromises();
  return wrapper;
}

async function pickFile(wrapper: Awaited<ReturnType<typeof mountDialog>>, table: { headers: string[]; rows: unknown[] }) {
  api.parseSupplierWorkbook.mockResolvedValue(table);
  wrapper.findComponent({ name: 'UFileUpload' }).vm.$emit('update:modelValue', xlsx());
  await flushPromises();
}

function buildPreviewButton(wrapper: Awaited<ReturnType<typeof mountDialog>>) {
  return wrapper.find('[data-testid="supplier-import-build-preview"]');
}

describe('供应商导入弹窗: 缺必填列 / 错误行回显', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
  });

  it('① 缺「联系电话」「地址」列: 提示缺哪些必填映射, 生成预览被禁用, 预览接口没被调用', async () => {
    const wrapper = await mountDialog();
    await pickFile(wrapper, {
      headers: ['供应商名称', '联系人'],
      rows: [{ 供应商名称: '甲', 联系人: '张三' }],
    });

    const missing = wrapper.find('[data-testid="supplier-import-missing"]');
    expect(missing.exists(), '缺列却没提示 —— 用户会以为文件没问题').toBe(true);
    expect(missing.text()).toContain('联系电话');
    expect(missing.text()).toContain('地址');
    const button = buildPreviewButton(wrapper);
    expect(button.exists(), '选了文件后应出现「生成预览」').toBe(true);
    expect(button.attributes('disabled')).toBeDefined();
    expect(wrapper.find('[data-testid="supplier-import-local-check"]').text()).toContain('字段错误 1');
    expect(api.previewSupplierImport).not.toHaveBeenCalled();
    expect(api.confirmSupplierImport, '⛔ 本组测试绝不允许走到确认写入').not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('阳性对照: 列齐全 ⇒ 缺列提示消失, 生成预览可点', async () => {
    const wrapper = await mountDialog();
    await pickFile(wrapper, {
      headers: FULL_HEADERS,
      rows: [{ 供应商名称: '甲', 联系人: '张三', 联系电话: '13800138000', 地址: '上海市浦东新区' }],
    });

    expect(wrapper.find('[data-testid="supplier-import-missing"]').exists()).toBe(false);
    expect(buildPreviewButton(wrapper).attributes('disabled')).toBeUndefined();
    wrapper.unmount();
  });

  it('② 预览返回字段错误行: 错误原文回显在说明列, 分类是「字段错误」, 且不计入确认新增', async () => {
    api.previewSupplierImport.mockResolvedValue({
      fileDigest: 'digest-1',
      mode: 'STANDARD',
      mappings: [],
      counts: { TOTAL: 2, VALID: 1, ERROR: 1, DUPLICATE: 0, IGNORED: 0 },
      rows: [
        { rowNumber: 2, classification: 'VALID', errors: {}, data: { name: '甲', contactPerson: '张三', phone: '13800138000', address: '上海市浦东新区' } },
        { rowNumber: 3, classification: 'ERROR', errors: { phone: '联系电话格式不正确', contactPerson: '联系人不能为空' }, data: { name: '乙', contactPerson: '', phone: '123', address: '北京市朝阳区' } },
      ],
    });
    const wrapper = await mountDialog();
    await pickFile(wrapper, {
      headers: FULL_HEADERS,
      rows: [
        { 供应商名称: '甲', 联系人: '张三', 联系电话: '13800138000', 地址: '上海市浦东新区' },
        { 供应商名称: '乙', 联系人: '', 联系电话: '123', 地址: '北京市朝阳区' },
      ],
    });
    await buildPreviewButton(wrapper).trigger('click');
    await flushPromises();

    expect(api.previewSupplierImport).toHaveBeenCalledTimes(1);
    const notes = wrapper.findAll('[data-testid="supplier-import-row-note"]').map((n) => n.text());
    expect(notes, '预览表没渲染出来 —— 后面的断言无从谈起').toHaveLength(2);
    expect(notes[0]).toBe('校验通过');
    expect(notes[1], '错误行必须回显后端给的错误原文').toContain('联系电话格式不正确');
    expect(notes[1]).toContain('联系人不能为空');
    expect(wrapper.find('[data-testid="supplier-import-preview"]').text()).toContain('字段错误');
    expect(wrapper.find('[data-testid="supplier-import-counts"]').text()).toContain('字段错误 1');
    // 只有 VALID 行被勾选、计入确认新增; 错误行不能被带进去
    const confirm = wrapper.find('[data-testid="supplier-import-confirm"]');
    expect(confirm.text()).toContain('确认新增 1 条');
    expect(api.confirmSupplierImport, '⛔ 本组测试绝不允许走到确认写入').not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('③ 全是错误行: 确认新增是 0 条且被禁用', async () => {
    api.previewSupplierImport.mockResolvedValue({
      fileDigest: 'digest-2', mode: 'STANDARD', mappings: [],
      counts: { TOTAL: 1, VALID: 0, ERROR: 1, DUPLICATE: 0, IGNORED: 0 },
      rows: [{ rowNumber: 2, classification: 'ERROR', errors: { phone: '联系电话格式不正确' }, data: { name: '乙' } }],
    });
    const wrapper = await mountDialog();
    await pickFile(wrapper, {
      headers: FULL_HEADERS,
      rows: [{ 供应商名称: '乙', 联系人: '李四', 联系电话: '123', 地址: '北京市朝阳区' }],
    });
    await buildPreviewButton(wrapper).trigger('click');
    await flushPromises();

    const confirm = wrapper.find('[data-testid="supplier-import-confirm"]');
    expect(confirm.text()).toContain('确认新增 0 条');
    expect(confirm.attributes('disabled')).toBeDefined();
    expect(wrapper.text()).toContain('下载错误报告');
    wrapper.unmount();
  });
});
