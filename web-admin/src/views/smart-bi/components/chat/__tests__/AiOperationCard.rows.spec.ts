/**
 * AiOperationCard 的预览行 (AI 对话标准 H2 / H3 / V3), 真实挂载 AiOperationCard, 只 mock API。
 *
 * H3 的真值不是手写的数字, 而是 RN 的 `previewRows` (同一份 fixture 直接喂给它数行) ——
 * web 这份规则是 RN 的拷贝, 会漂, 所以再单独读 RN 源文件逐键对比 KEY_LABELS / HIDDEN_KEYS。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import ts from 'typescript';

/**
 * 直接 import RN 那份 .ts 会让 vite 去读 frontend/CretasFoodTrace/tsconfig.json (extends expo, worktree 里没有 node_modules)。
 * 所以读源文本、用 typescript 转成 CommonJS 再执行 —— 拿到的仍是 RN 真实的 previewRows, 不是我手抄的一份。
 */
const RN_FILE = resolve(__dirname, '../../../../../../../frontend/CretasFoodTrace/src/components/ai/aiWriteConfirm.ts');
function loadRnPreviewRows(): (action: unknown) => Array<{ key: string; label: string; value: string }> {
  const out = ts.transpileModule(readFileSync(RN_FILE, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText;
  const mod: { exports: Record<string, unknown> } = { exports: {} };
  new Function('exports', 'module', 'require', out)(mod.exports, mod, () => {
    throw new Error('RN aiWriteConfirm 不应有运行时 import');
  });
  return mod.exports.previewRows as ReturnType<typeof loadRnPreviewRows>;
}
const rnPreviewRows = loadRnPreviewRows();

const confirmIntentActionMock = vi.fn();
const messageBoxConfirmMock = vi.fn(async () => 'confirm');
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>();
  return {
    ...actual,
    ElMessageBox: { ...actual.ElMessageBox, confirm: (...args: unknown[]) => messageBoxConfirmMock(...args) },
  };
});
vi.mock('@/api/smartbi/intent-chat', () => ({
  confirmIntentAction: (...args: unknown[]) => confirmIntentActionMock(...args),
  executeIntent: vi.fn(),
  fetchCachedXlsx: vi.fn(),
  submitIntentFeedback: vi.fn(async () => true),
}));

import AiOperationCard from '../AiOperationCard.vue';
import { HIDDEN_KEYS, KEY_LABELS } from '../aiWriteConfirmRows';
import {
  FLAT_PREVIEW_DATA, DIFF_CURRENT, DIFF_NEXT, flatResponse, diffResponse,
} from './aiWriteConfirmFixture';

const stubs = {
  'el-button': {
    props: ['disabled', 'loading', 'size'],
    emits: ['click'],
    template: '<button class="el-button" :data-size="size" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
  'el-icon': { template: '<i><slot /></i>' },
  'el-tag': { template: '<span class="el-tag"><slot /></span>' },
  'el-alert': { props: ['title'], template: '<div class="el-alert">{{ title }}</div>' },
};

function mountCard(response: unknown) {
  return mount(AiOperationCard, {
    props: { response: response as never, factoryId: 'F001' },
    global: { stubs },
  });
}

const rowKeys = (w: ReturnType<typeof mountCard>) =>
  w.findAll('[data-testid="aiop-rows"] .op-preview-key, [data-testid="aiop-rows"] .op-diff-key').map((n) => n.text());

describe('AiOperationCard 预览行 = RN previewRows (H3)', () => {
  beforeEach(() => {
    confirmIntentActionMock.mockReset();
    messageBoxConfirmMock.mockClear();
  });

  it('flat 预览: 行数等于 RN previewRows 的行数, 中文键和已知键一个不少', () => {
    const expected = rnPreviewRows(flatResponse().confirmableAction as never);
    // 阳性对照: fixture 确实有行 (行数 0 的比较没有意义)
    expect(expected.length).toBeGreaterThan(0);

    const w = mountCard(flatResponse());
    const keys = rowKeys(w);
    expect(keys.length).toBe(expected.length);
    expect(keys).toEqual(expected.map((r) => r.label));

    // 所有中文键都在
    for (const k of Object.keys(FLAT_PREVIEW_DATA).filter((k) => /[一-鿿]/.test(k))) {
      expect(keys).toContain(k);
    }
    // 所有已知键 (有 label) 都在, 显示的是中文 label
    for (const k of Object.keys(FLAT_PREVIEW_DATA).filter((k) => k in KEY_LABELS)) {
      expect(keys).toContain(KEY_LABELS[k]);
    }
  });

  it('阴性对照: 无 label 的英文键整行不显示, 系统键不显示, 英文键名不泄漏', () => {
    const w = mountCard(flatResponse());
    const text = w.text();
    for (const hidden of ['plannedQty', 'Batch_Weird', 'productId', 'factoryId', 'userInput', 'batchNumber', 'supplierName']) {
      expect(text).not.toContain(hidden);
    }
    // 未知英文键的值也不出现 (整行不显示, 不是只换名字)
    expect(text).not.toContain('eb0aa47b');
    expect(text).not.toContain('Batch_Weird');
  });

  it('diff 预览: 行数等于 RN previewRows, 中文键在, 未知英文键不在', () => {
    const expected = rnPreviewRows(diffResponse().confirmableAction as never);
    expect(expected.length).toBeGreaterThan(0);
    const w = mountCard(diffResponse());
    const keys = rowKeys(w);
    expect(keys.length).toBe(expected.length);
    expect(keys).toEqual(expected.map((r) => r.label));
    expect(keys).toContain('数量');
    expect(keys).toContain('批次号');
    expect(w.text()).not.toContain('Weird_key');
    // 值也都在 (当前 / 修改后)
    expect(w.text()).toContain(String(DIFF_CURRENT['数量']));
    expect(w.text()).toContain(String(DIFF_NEXT['数量']));
  });

  it('对象值按「键：值」逐项显示, 不是 JSON; 对象里的未知英文键同样不显示', () => {
    const w = mountCard(flatResponse());
    const text = w.text();
    expect(text).toContain('名称：甲供应商');
    expect(text).not.toContain('{');
    expect(text).not.toContain('"');
    expect(text).not.toContain('contactPhone');
    expect(text).not.toContain('X1');
  });

  it('null 值写文字状态, 不显示「—」', () => {
    const w = mountCard(flatResponse());
    const notesRow = rowKeys(w).indexOf('备注');
    expect(notesRow).toBeGreaterThanOrEqual(0);
    const values = w.findAll('[data-testid="aiop-rows"] .op-preview-value').map((n) => n.text());
    expect(values[notesRow]).toBe('暂无');
    expect(values).not.toContain('—');
    expect(values).not.toContain('');
  });

  it('没有意图名时显示「这项操作」, ⛔ 不显示 intentCode; 有名字时仍显示名字 (阴性对照)', () => {
    const noName = mountCard(flatResponse(null));
    expect(noName.find('.op-card-header').text()).toContain('这项操作');
    expect(noName.text()).not.toContain('MATERIAL_INBOUND');

    const named = mountCard(flatResponse('原料入库'));
    expect(named.find('.op-card-header').text()).toContain('原料入库');
    expect(named.find('.op-card-header').text()).not.toContain('这项操作');

    // 过渡卡 (WRITE_CONFIRM_REQUIRED) 同样不出现 intentCode, 且不出现「「这项操作」」
    const transient = mountCard({ status: 'WRITE_CONFIRM_REQUIRED', intentCode: 'MATERIAL_INBOUND', message: 'x' });
    expect(transient.text()).toContain('这项操作需要确认');
    expect(transient.text()).not.toContain('MATERIAL_INBOUND');
    expect(transient.text()).not.toContain('「这项操作」');
  });
});

describe('web 的 KEY_LABELS / HIDDEN_KEYS 与 RN 源文件逐键相同 (形态 D: 两份会漂)', () => {
  const rnSource = readFileSync(RN_FILE, 'utf8');

  it('KEY_LABELS', () => {
    const body = rnSource.match(/const KEY_LABELS: Record<string, string> = \{([\s\S]*?)\n\};/);
    expect(body).not.toBeNull();
    const rn: Record<string, string> = {};
    for (const m of body![1].matchAll(/(\w+):\s*'([^']+)'/g)) rn[m[1]] = m[2];
    expect(Object.keys(rn).length).toBeGreaterThan(10); // 阳性对照: 真的解析出了东西
    expect(KEY_LABELS).toEqual(rn);
  });

  it('HIDDEN_KEYS', () => {
    const body = rnSource.match(/const HIDDEN_KEYS = new Set\(\[([\s\S]*?)\]\);/);
    expect(body).not.toBeNull();
    const rn = [...body![1].matchAll(/'([^']+)'/g)].map((m) => m[1]);
    expect(rn.length).toBeGreaterThan(10);
    expect([...HIDDEN_KEYS].sort()).toEqual([...rn].sort());
  });
});

describe('确认 / 取消的触发与参数不变 (H2 / H4)', () => {
  beforeEach(() => {
    confirmIntentActionMock.mockReset();
    messageBoxConfirmMock.mockClear();
  });

  it('不点确认: 写入接口 0 次; 点取消: 仍是 0 次且卡片收起', async () => {
    const w = mountCard(flatResponse());
    await flushPromises();
    expect(confirmIntentActionMock).toHaveBeenCalledTimes(0);

    const cancel = w.findAll('button.el-button').find((b) => b.text() === '取消');
    await cancel!.trigger('click');
    await flushPromises();
    expect(confirmIntentActionMock).toHaveBeenCalledTimes(0);
    expect(w.text()).toBe('已取消');
  });

  it('点确认: 恰好 1 次, 参数与改前一致 (factoryId, confirmToken, {commandDigest, expiresAt})', async () => {
    confirmIntentActionMock.mockResolvedValue({ status: 'SUCCESS', message: '已执行' });
    const w = mountCard(flatResponse());
    const confirm = w.findAll('button.el-button').find((b) => b.text().includes('确认执行'));
    // 阳性对照: 按钮真的在, 且是 32px 档 (default), 不是 24px 档 (small)
    expect(confirm).toBeDefined();
    expect(confirm!.attributes('data-size')).toBe('default');
    await confirm!.trigger('click');
    await flushPromises();

    expect(confirmIntentActionMock).toHaveBeenCalledTimes(1);
    expect(confirmIntentActionMock).toHaveBeenCalledWith('F001', 'tok-h3', {
      commandDigest: 'digest-h3',
      expiresAt: '2026-07-24T00:05:00Z',
    });
    expect(w.emitted('confirmed')).toHaveLength(1);
  });
});
