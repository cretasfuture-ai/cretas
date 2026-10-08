/**
 * AiOperationCard 的预览行 (AI 对话标准 H2 / H3 / V3), 真实挂载 AiOperationCard, 只 mock API。
 *
 * H3 的真值是「改前那张卡的行」(下面冻结的 PRE_CHANGE_SKIP_KEYS 规则): 业务行一行都不能少。
 * 标签 (KEY_LABELS) 与系统键 (HIDDEN_KEYS) 是 RN 那份的拷贝, 会漂, 所以读 RN 源文件逐键对比。
 * ⚠️ 与 RN previewRows 不同: RN 会藏没有 label 的英文键, web 不藏 (已报 organizer 裁定)。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const RN_FILE = resolve(__dirname, '../../../../../../../frontend/CretasFoodTrace/src/components/ai/aiWriteConfirm.ts');

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

/**
 * 改前 (origin/main a137c1c140) 的 AiOperationCard 只跳过这 8 个系统键, 其余 previewData 键全部成行。
 * 冻结在这里当 H3 的「改前」基线: 改后的业务行一行都不能比它少。
 */
const PRE_CHANGE_SKIP_KEYS = new Set(['factoryId', 'factory_id', 'userId', 'user_id', 'userRole', 'role', 'intentCode', 'userInput']);
const preChangeRowKeys = (data: Record<string, unknown>) => Object.keys(data).filter((k) => !PRE_CHANGE_SKIP_KEYS.has(k));

describe('AiOperationCard 预览行: 业务字段一行都不藏 (H3 优先于 V3)', () => {
  beforeEach(() => {
    confirmIntentActionMock.mockReset();
    messageBoxConfirmMock.mockClear();
  });

  it('flat 预览: 行数与改前相同 (10 行), 已知键显示中文, 中文键原样, 未知英文键照原样显示键名', () => {
    const before = preChangeRowKeys(FLAT_PREVIEW_DATA);
    // 阳性对照: 基线真的有行, 而且包含三类键
    expect(before.length).toBe(10);
    const w = mountCard(flatResponse());
    const keys = rowKeys(w);
    expect(keys.length).toBe(before.length);
    for (const k of before) {
      expect(keys).toContain(k in KEY_LABELS ? KEY_LABELS[k] : k);
    }
    // 未知英文键 (改前就在) 仍然在, 值也在 —— 用户要在看得见全部写入内容的前提下点确认
    for (const k of ['plannedQty', 'Batch_Weird', 'productId']) expect(keys).toContain(k);
    expect(w.text()).toContain('eb0aa47b');
    // 已知英文键显示中文, 不再露英文键名
    expect(keys).not.toContain('batchNumber');
    expect(keys).not.toContain('supplierName');
  });

  it('阴性对照: 系统键仍不显示 (改前改后都不显示)', () => {
    const text = mountCard(flatResponse()).text();
    for (const hidden of ['factoryId', 'userInput']) expect(text).not.toContain(hidden);
  });

  it('diff 预览: 行数与改前相同 (3 行), 未知英文键 Weird_key 仍在且值都在', () => {
    const before = Array.from(new Set([...Object.keys(DIFF_CURRENT), ...Object.keys(DIFF_NEXT)])).filter((k) => !PRE_CHANGE_SKIP_KEYS.has(k));
    expect(before.length).toBe(3);
    const w = mountCard(diffResponse());
    const keys = rowKeys(w);
    expect(keys.length).toBe(before.length);
    expect(keys).toEqual(expect.arrayContaining(['数量', '批次号', 'Weird_key']));
    for (const v of [DIFF_CURRENT['数量'], DIFF_NEXT['数量'], DIFF_CURRENT['Weird_key'], DIFF_NEXT['Weird_key']]) {
      expect(w.text()).toContain(String(v));
    }
  });

  it('对象值按「键：值」逐项显示, 不是 JSON; 对象里的键也一个不藏', () => {
    const text = mountCard(flatResponse()).text();
    expect(text).toContain('名称：甲供应商');
    expect(text).toContain('code：X1');
    expect(text).toContain('contactPhone：123');
    expect(text).not.toContain('{');
    expect(text).not.toContain('"');
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
