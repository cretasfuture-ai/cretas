/**
 * AiEntryDrawer (AI 录入抽屉) 按 AI 对话标准 (§3 web-admin) 重构后的行为测试。
 * 真实挂载 AiEntryDrawer + 真实 useAiChat, 只 mock 网络 (parseFormInput) 与路由。
 *
 * jsdom 不计算 scoped CSS, 所以样式判据分两路:
 *   - DOM: 没有头像 / 时间节点、data-testid 存在、没有气泡 class;
 *   - 源文本: 读 AiEntryDrawer.vue 的 <style> 断言声明, 并配阴性对照 (扫描器自己能抓到 11px / 旧圆角)。
 *
 * 🔴 写入这一步 (H2 / H3 / H4):
 *   抽屉本身不调写接口 —— 它的「写」是点「填入表单」后 emit('fill-form', params), 由列表页弹出预填的新建对话框,
 *   用户在那里再点提交才落库。所以这里守的是: 预览行一行不少、没点之前 fill-form 0 次、点了恰好 1 次、参数逐字段与改前一致。
 *   「改前读数」是在 origin/main 的旧抽屉上跑同一份 fixture 得到的, 写成字面量 (见 BASELINE_* 常量)。
 *
 * 每个「X 没出现」的断言都配了一个「X 在另一种条件下出现」的对照 (硬约束 9)。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  PRODUCTION_PLAN_CONFIG, PRODUCT_CONFIG, PURCHASE_ORDER_CONFIG, SALES_ORDER_CONFIG,
  STOCKTAKING_CONFIG, WH_INBOUND_CONFIG, PROCESS_TASK_CONFIG, ITEM_KEY_LABELS,
  type AiEntryConfig,
} from '../types';

const h = vi.hoisted(() => ({
  routeMock: { query: {} as Record<string, string> },
  parse: undefined as unknown as ReturnType<typeof import('vitest').vi.fn>,
}));

vi.mock('@/api/formAssistant', () => ({
  parseFormInput: (...a: unknown[]) => h.parse(...a),
}));
vi.mock('@/composables/useFactoryId', async () => {
  const { ref } = await import('vue');
  return { useFactoryId: () => ref('F001') };
});
vi.mock('vue-router', () => ({
  useRoute: () => h.routeMock,
  useRouter: () => ({ push: vi.fn() }),
}));

import AiEntryDrawer from '../AiEntryDrawer.vue';

const SFC_PATH = resolve(__dirname, '..', 'AiEntryDrawer.vue');
const sfc = readFileSync(SFC_PATH, 'utf8');
const styleSrc = sfc.slice(sfc.indexOf('<style'));

const globalStubs = {
  'el-drawer': {
    props: ['modelValue'],
    template: '<div class="el-drawer-stub"><slot name="header" title-id="t1" title-class="tc" /><slot /></div>',
  },
  'el-button': {
    props: ['disabled', 'type', 'size', 'text', 'round', 'plain'],
    emits: ['click'],
    template: '<button class="el-button" :data-size="size" :disabled="disabled" @click="$emit(\'click\', $event)"><slot /></button>',
  },
  'el-input': {
    props: ['modelValue'],
    emits: ['update:modelValue', 'keydown'],
    template: '<textarea class="chat-textarea" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" @keydown="$emit(\'keydown\', $event)" />',
  },
  'el-alert': { props: ['title'], template: '<div class="el-alert">{{ title }}</div>' },
};

function ok(fieldValues: Record<string, unknown>, extra: Record<string, unknown> = {}) {
  return { success: true, data: { success: true, confidence: 0.91, fieldValues, ...extra }, message: '' };
}

let wrappers: VueWrapper[] = [];

function mountDrawer(
  config: AiEntryConfig,
  opts: { query?: Record<string, string>; confirmGuard?: (p: Record<string, unknown>) => string | null } = {},
) {
  h.routeMock.query = opts.query ?? {};
  const w = mount(AiEntryDrawer, {
    props: { modelValue: true, config, confirmGuard: opts.confirmGuard, guardActionLabel: opts.confirmGuard ? '去补客户' : undefined },
    global: { stubs: globalStubs },
    attachTo: document.body,
  });
  wrappers.push(w);
  return w;
}

async function send(w: VueWrapper, text: string) {
  await w.find('textarea.chat-textarea').setValue(text);
  await w.find('button.send-btn').trigger('click');
  await flushPromises();
}

function btnByText(w: VueWrapper, text: string) {
  const b = w.findAll('button').find((x) => x.text().trim() === text);
  if (!b) throw new Error(`找不到按钮「${text}」`);
  return b;
}

beforeEach(() => {
  h.parse = vi.fn();
  Element.prototype.scrollTo = vi.fn() as unknown as typeof Element.prototype.scrollTo;
});
afterEach(() => {
  wrappers.forEach((w) => w.unmount());
  wrappers = [];
  document.body.innerHTML = '';
});

// ===================== fixtures (改前改后同一份) =====================

const FULL_FIXTURES: Array<{ name: string; config: AiEntryConfig; values: Record<string, unknown>; baselineRows: number }> = [
  {
    name: '生产计划', config: PRODUCTION_PLAN_CONFIG, baselineRows: 8,
    values: { productTypeName: '黄金鱼片 400g 盒装', plannedQuantity: 500, quantityUnit: 'kg', plannedDate: '2026-07-29' },
  },
  {
    name: '产品', config: PRODUCT_CONFIG, baselineRows: 6,
    values: { name: '纸箱', productCategory: 'PACKAGING', unit: '个' },
  },
  {
    name: '采购单', config: PURCHASE_ORDER_CONFIG, baselineRows: 5,
    values: { supplierName: 'XX供应商', purchaseType: 'URGENT', items: [{ materialName: '大豆', quantity: 500, unit: 'kg', unitPrice: 0 }] },
  },
  {
    name: '销售单', config: SALES_ORDER_CONFIG, baselineRows: 5,
    values: { customerName: 'YY客户', items: [{ productName: '鱼片', quantity: 800, unit: 'kg', unitPrice: 12 }] },
  },
  {
    name: '盘点', config: STOCKTAKING_CONFIG, baselineRows: 3,
    values: { batchNumber: 'PB-20260516-001', adjustQuantity: -5, reason: '称重误差' },
  },
  {
    name: '入库', config: WH_INBOUND_CONFIG, baselineRows: 5,
    values: { supplierName: 'XX供应商', receiveDate: '2026-07-29', items: [{ materialName: '大豆', receivedQuantity: 500, unit: 'kg' }] },
  },
  {
    name: '生产批次', config: PROCESS_TASK_CONFIG, baselineRows: 4,
    values: { productTypeName: '黄金鱼片 400g 盒装', plannedQuantity: 300 },
  },
];

/** 改前读数: 旧抽屉上同一份 fixture、同一句输入, 点「填入表单」后 emit 的参数 (字面量)。 */
const FILL_INPUT = {
  supplierName: 'XX供应商',
  purchaseType: 'URGENT',
  items: [{ materialName: '大豆', quantity: 500, unit: 'kg', unitPrice: 0, customCode: 'X1' }],
};
/** 旧抽屉实测: 5 个键 (normalizeAiOrderDates 补 orderDate / expectedDeliveryDate 两个 undefined 键), 明细原样 (含未知键 customCode)。 */
const BASELINE_FILL_PARAMS = {
  orderDate: undefined,
  expectedDeliveryDate: undefined,
  supplierName: 'XX供应商',
  purchaseType: 'URGENT',
  items: [{ materialName: '大豆', quantity: 500, unit: 'kg', unitPrice: 0, customCode: 'X1' }],
};
const BASELINE_FILL_KEYS = ['expectedDeliveryDate', 'items', 'orderDate', 'purchaseType', 'supplierName'];

async function previewFor(config: AiEntryConfig, values: Record<string, unknown>, query: Record<string, string> = {}) {
  h.parse.mockResolvedValue(ok(values));
  const w = mountDrawer(config, { query });
  await send(w, '来一单');
  return w;
}

// ===================== 1. 无头像、无时间 (L1 / L3 / L4) =====================

describe('L1 / L3 / L4 · 助手全宽无头像无气泡, 不显示每条消息时间', () => {
  it('欢迎态: 没有头像节点; 有欢迎语和示例芯片 (对照)', () => {
    const w = mountDrawer(PURCHASE_ORDER_CONFIG);
    expect(w.find('.welcome-text').text()).toContain('你好');
    expect(w.findAll('.quick-example-chip').length).toBe(PURCHASE_ORDER_CONFIG.examples.length);
    expect(w.find('.welcome-avatar').exists()).toBe(false);
    expect(w.find('.msg-avatar').exists()).toBe(false);
  });

  it('发一句后: 用户气泡 1 个、助手正文 1 个 (对照), 助手没有气泡 class / 头像 / 时间', async () => {
    const w = await previewFor(PURCHASE_ORDER_CONFIG, FULL_FIXTURES[2].values);
    const user = w.findAll('[data-testid="aie-user-bubble"]');
    const body = w.findAll('[data-testid="aie-assistant-body"]');
    expect(user.length).toBe(1);
    expect(body.length).toBe(1);
    expect(user[0].text()).toBe('来一单');
    expect(body[0].text()).toContain('已整理好');
    // 助手: 没有 .chat-bubble 包着, 没有头像
    expect(body[0].classes()).not.toContain('chat-bubble');
    expect(body[0].element.closest('.chat-bubble')).toBeNull();
    expect(w.find('.msg-avatar').exists()).toBe(false);
    // 用户: 是气泡
    expect(user[0].element.closest('.chat-message.user')).not.toBeNull();
    // L4: 消息区没有 HH:MM
    const thread = w.find('.chat-messages').text();
    expect(thread).not.toMatch(/\b\d{1,2}:\d{2}\b/);
    // 对照: 这个正则确实抓得到时间
    expect('今天 10:32 来一单').toMatch(/\b\d{1,2}:\d{2}\b/);
  });

  it('加载中: 也没有头像 (旧版 loading 行自带一个)', async () => {
    let release: (v: unknown) => void = () => undefined;
    h.parse.mockReturnValue(new Promise((r) => { release = r; }));
    const w = mountDrawer(PURCHASE_ORDER_CONFIG);
    await w.find('textarea.chat-textarea').setValue('来一单');
    await w.find('button.send-btn').trigger('click');
    await flushPromises();
    expect(w.find('[data-testid="aie-loading-skeleton"]').exists()).toBe(true); // 对照: 加载态确实渲染了
    expect(w.find('.msg-avatar').exists()).toBe(false);
    release(ok({}));
    await flushPromises();
  });

  it('源文本: 助手正文无背景 / 边框 / 阴影, 声明了 16px / 1.6; 旧的白底气泡声明不在了', () => {
    const rule = /\.assistant-body\s*\{([^}]*)\}/.exec(styleSrc)?.[1] ?? '';
    expect(rule, '找不到 .assistant-body 规则').not.toBe('');
    expect(rule).toMatch(/font-size:\s*16px/);
    expect(rule).toMatch(/line-height:\s*1\.6/);
    expect(rule).not.toMatch(/background:\s*#fff/);
    expect(rule).not.toMatch(/border:\s*1px/);
    expect(rule).not.toMatch(/box-shadow:\s*0/);
    // 阴性对照: 旧写法的特征串确实会被这个扫描抓到
    expect('border-radius: 14px 14px 14px 4px;').toMatch(/border-radius:\s*14px 14px 14px 4px/);
    expect(styleSrc).not.toMatch(/\.chat-message\.assistant \.chat-bubble/);
  });
});

// ===================== 2. 用户气泡 (L2) =====================

describe('L2 · 用户消息浅色气泡', () => {
  const rule = /\.user-bubble\s*\{([^}]*)\}/.exec(styleSrc)?.[1] ?? '';

  it('圆角四角 12、内边距 10px 12px、最宽 min(78%, 480px)、浅色底深色字', () => {
    expect(rule, '找不到 .user-bubble 规则').not.toBe('');
    expect(rule).toMatch(/border-radius:\s*12px\s*;/);
    expect(rule).toMatch(/padding:\s*10px 12px\s*;/);
    expect(rule).toMatch(/max-width:\s*min\(78%,\s*480px\)/);
    expect(rule).not.toMatch(/color:\s*(white|#fff)\b/);
    expect(rule).not.toMatch(/linear-gradient/);
  });

  it('阴性对照: 旧的 14/14/4/14 圆角与 80% 宽、10px 14px 内边距不在整份样式里', () => {
    expect(styleSrc).not.toMatch(/border-radius:\s*14px 14px 4px 14px/);
    expect(rule).not.toMatch(/max-width:\s*80%/); // 教程里的演示气泡 (.demo-bubble) 另有 80%, 只看用户气泡这条规则
    expect(rule).not.toMatch(/padding:\s*10px 14px/);
    // 扫描器本身能抓到它们
    expect('border-radius: 14px 14px 4px 14px;').toMatch(/border-radius:\s*14px 14px 4px 14px/);
  });
});

// ===================== 3. 字体与字号 (T1 / T2 / T3) =====================

describe('T1 / T2 / T3 · 字体栈与字号', () => {
  it('容器声明系统字体栈, 不写死 Noto / Playfair', () => {
    const stack = /\$ai-font-stack:\s*([^;]*);/.exec(styleSrc)?.[1] ?? '';
    expect(stack).toMatch(/-apple-system[^;]*PingFang SC[^;]*Microsoft YaHei[^;]*sans-serif/);
    // 栈真的挂在对话容器和抽屉头上 (接上没有)
    expect(/\.ai-chat-container\s*\{([^}]*)\}/.exec(styleSrc)?.[1] ?? '').toMatch(/font-family:\s*\$ai-font-stack/);
    expect(/\.drawer-header\s*\{([^}]*)\}/.exec(styleSrc)?.[1] ?? '').toMatch(/font-family:\s*\$ai-font-stack/);
    const styleNoComments = styleSrc.replace(/\/\/[^\n]*/g, '');
    expect(styleNoComments).not.toMatch(/Noto (Sans|Serif) SC|Playfair/);
  });

  it('整份 <style> 里没有 10px / 11px 字号 (T3)', () => {
    const tiny = [...styleSrc.matchAll(/font-size:\s*(\d+(?:\.\d+)?)px/g)].map((m) => Number(m[1])).filter((n) => n < 12);
    expect(tiny).toEqual([]);
    // 阴性对照: 同一个扫描器对旧值 (11px) 会报
    const oldSample = '.scope-badge { font-size: 11px; } .x { font-size: 10px; }';
    const flagged = [...oldSample.matchAll(/font-size:\s*(\d+(?:\.\d+)?)px/g)].map((m) => Number(m[1])).filter((n) => n < 12);
    expect(flagged).toEqual([11, 10]);
    // 对照: 扫描器读到了足够多的字号声明 (不是读空)
    expect([...styleSrc.matchAll(/font-size:\s*\d+px/g)].length).toBeGreaterThan(20);
  });

  it('用户气泡正文 16px / 1.6', () => {
    const rule = /\.user-bubble\s*\{([^}]*)\}/.exec(styleSrc)?.[1] ?? '';
    expect(rule).toMatch(/font-size:\s*16px/);
    expect(rule).toMatch(/line-height:\s*1\.6/);
  });
});

// ===================== 4. 骨架屏 (W1) =====================

describe('W1 · 加载用骨架屏', () => {
  it('加载中 3 条 + 文字, 结束后消失 (改前是转圈三点 + 「思考中」)', async () => {
    let release: (v: unknown) => void = () => undefined;
    h.parse.mockReturnValue(new Promise((r) => { release = r; }));
    const w = mountDrawer(PURCHASE_ORDER_CONFIG);
    await w.find('textarea.chat-textarea').setValue('来一单');
    await w.find('button.send-btn').trigger('click');
    await flushPromises();
    expect(w.findAll('[data-testid="aie-skeleton-bar"]').length).toBe(3);
    expect(w.find('[data-testid="aie-loading-skeleton"]').text().length).toBeGreaterThan(0);
    expect(w.find('.dot-anim').exists()).toBe(false);
    release(ok({ supplierName: 'A' })); // 缺必填 → 追问
    await flushPromises();
    expect(w.findAll('[data-testid="aie-skeleton-bar"]').length).toBe(0);
    expect(w.find('[data-testid="aie-assistant-body"]').exists()).toBe(true); // 对照: 加载结束后是回答
  });

  it('开了「减少动态效果」时条静止 (源文本)', () => {
    expect(styleSrc).toMatch(/prefers-reduced-motion:\s*reduce[\s\S]*?\.skeleton-bar[\s\S]*?animation:\s*none/);
  });
});

// ===================== 5. 超时 / 失败 (W2 / W3 / H5) =====================

describe('W2 · 超时与失败: 没有保存任何数据 + 重试', () => {
  it('请求超时 → 人话文案含「没有保存任何数据」, 不露英文原文; 有「重试」', async () => {
    h.parse.mockRejectedValue(Object.assign(new Error('timeout of 120000ms exceeded'), { code: 'ECONNABORTED' }));
    const w = mountDrawer(PURCHASE_ORDER_CONFIG);
    await send(w, '来一单');
    const text = w.find('.chat-messages').text();
    expect(text).toContain('没有保存任何数据');
    expect(text).toContain('超时');
    expect(text).not.toMatch(/timeout|ECONNABORTED|120000/i);
    expect(w.find('[data-testid="aie-retry"]').exists()).toBe(true);
    expect(w.emitted('fill-form')).toBeUndefined();
  });

  it('后端 success=false → 原样显示后端文案 + 「没有保存任何数据」 + 重试', async () => {
    h.parse.mockResolvedValue({ success: true, data: { success: false, message: 'AI服务未配置，请手动填写表单', fieldValues: {} }, message: '' });
    const w = mountDrawer(PURCHASE_ORDER_CONFIG);
    await send(w, '来一单');
    const text = w.find('.chat-messages').text();
    expect(text).toContain('AI服务未配置，请手动填写表单');
    expect(text).toContain('没有保存任何数据');
    expect(w.find('[data-testid="aie-retry"]').exists()).toBe(true);
  });

  it('请求抛 Error(英文 / HTTP 码) → 不露原文; 抛中文业务原因 → 保留中文原因', async () => {
    h.parse.mockRejectedValue(new Error('Request failed with status code 500'));
    const w1 = mountDrawer(PURCHASE_ORDER_CONFIG);
    await send(w1, '来一单');
    expect(w1.find('.chat-messages').text()).not.toMatch(/Request failed|500/);
    expect(w1.find('.chat-messages').text()).toContain('没有保存任何数据');

    h.parse.mockRejectedValue(new Error('权限不足'));
    const w2 = mountDrawer(PURCHASE_ORDER_CONFIG);
    await send(w2, '来一单');
    expect(w2.find('.chat-messages').text()).toContain('权限不足');
    expect(w2.find('.chat-messages').text()).toContain('没有保存任何数据');
  });

  it('对照: 成功的回答下面没有「没有保存任何数据」、没有重试', async () => {
    const w = await previewFor(PURCHASE_ORDER_CONFIG, FULL_FIXTURES[2].values);
    expect(w.find('.chat-messages').text()).not.toContain('没有保存任何数据');
    expect(w.find('[data-testid="aie-retry"]').exists()).toBe(false);
  });

  it('点「重试」: 用同一句话再解析 1 次, 不重复出用户消息, 成功后失败提示消失', async () => {
    h.parse.mockRejectedValueOnce(new Error('timeout of 120000ms exceeded'));
    h.parse.mockResolvedValueOnce(ok(FULL_FIXTURES[2].values));
    const w = mountDrawer(PURCHASE_ORDER_CONFIG);
    await send(w, '来一单');
    expect(h.parse).toHaveBeenCalledTimes(1);
    await w.find('[data-testid="aie-retry"]').trigger('click');
    await flushPromises();
    expect(h.parse).toHaveBeenCalledTimes(2);
    const inputs = h.parse.mock.calls.map((c) => (c[1] as { userInput: string }).userInput);
    expect(inputs[1]).toBe(inputs[0]);              // 同一句话, 不折进上一次失败的历史
    expect(w.findAll('[data-testid="aie-user-bubble"]').length).toBe(1);
    expect(w.find('[data-testid="aie-retry"]').exists()).toBe(false);
    expect(w.findAll('.preview-row').length).toBe(PURCHASE_ORDER_CONFIG.fields.length); // 对照: 重试成功出了预览
    expect(w.emitted('fill-form')).toBeUndefined(); // 重试不写入
  });
});

// ===================== 6. 遥测 (E1 / E2) =====================

describe('E1 / E2 · 遥测默认不显示, ?aiDebug=1 才显示', () => {
  it('默认 (vitest 下 import.meta.env.DEV 恒为 true): 没有遥测行, 文本不含置信度 / 耗时 / token', async () => {
    expect(import.meta.env.DEV).toBe(true);
    const w = await previewFor(PURCHASE_ORDER_CONFIG, FULL_FIXTURES[2].values);
    expect(w.find('[data-testid="aie-telemetry"]').exists()).toBe(false);
    const text = w.text();
    expect(text).not.toMatch(/置信度|\b\d+ms\b|token|快速响应|PURCHASE_ORDER/i);
  });

  it('?aiDebug=1: 遥测行出现, 带置信度和实体类型; 同一份回答、同一个组件', async () => {
    const w = await previewFor(PURCHASE_ORDER_CONFIG, FULL_FIXTURES[2].values, { aiDebug: '1' });
    const row = w.find('[data-testid="aie-telemetry"]');
    expect(row.exists()).toBe(true);
    expect(row.text()).toContain('置信度');
    expect(row.text()).toContain('91%');
    expect(row.text()).toContain('PURCHASE_ORDER');
  });

  it('?aiDebug=0 / 其它值 → 不显示', async () => {
    const w = await previewFor(PURCHASE_ORDER_CONFIG, FULL_FIXTURES[2].values, { aiDebug: '0' });
    expect(w.find('[data-testid="aie-telemetry"]').exists()).toBe(false);
  });
});

// ===================== 7. 🔴 写入一步 (H2 / H3 / H4) =====================

describe('🔴 写入一步 · 预览一行不少、不点不写、点了恰好 1 次、参数不变', () => {
  for (const fx of FULL_FIXTURES) {
    it(`${fx.name}: 预览行数 = 改前读数 ${fx.baselineRows} = 配置字段数; 每个字段的中文名都在`, async () => {
      const w = await previewFor(fx.config, fx.values);
      const rows = w.findAll('.preview-row');
      expect(rows.length).toBe(fx.baselineRows);
      expect(rows.length).toBe(fx.config.fields.length);
      const labels = rows.map((r) => r.find('.preview-label').text().replace('*', '').trim());
      expect(labels).toEqual(fx.config.fields.map((f) => f.label));
      // 对照: 有值的行不是「暂无」
      const filled = rows.filter((r) => !r.classes().includes('is-empty'));
      expect(filled.length).toBeGreaterThan(0);
      for (const r of filled) expect(r.find('.preview-value').text()).not.toBe('暂无');
    });
  }

  it('没点「填入表单」: fill-form 0 次、抽屉没关; 点了恰好 1 次且参数与改前字面量逐字段相等', async () => {
    const w = await previewFor(PURCHASE_ORDER_CONFIG, FILL_INPUT);
    expect(w.findAll('.preview-row').length).toBe(5); // 预览已出 (对照: 「没发生」不是因为根本没到预览)
    expect(w.emitted('fill-form')).toBeUndefined();
    expect(w.emitted('update:modelValue')).toBeUndefined();

    await btnByText(w, '填入表单').trigger('click');
    expect(w.emitted('fill-form')?.length).toBe(1);
    const params = w.emitted('fill-form')![0][0] as Record<string, unknown>;
    expect(params).toStrictEqual(BASELINE_FILL_PARAMS);
    expect(Object.keys(params).sort()).toEqual(BASELINE_FILL_KEYS);
    expect(w.emitted('update:modelValue')?.[0]).toEqual([false]);
  });

  it('「继续修改」不触发 fill-form; 收起预览后已收集字段还在', async () => {
    const w = await previewFor(PURCHASE_ORDER_CONFIG, FULL_FIXTURES[2].values);
    await btnByText(w, '继续修改').trigger('click');
    expect(w.emitted('fill-form')).toBeUndefined();
    expect(w.find('.preview-card').exists()).toBe(false);
  });

  it('confirmGuard 拦住时: 按钮禁用、点了 0 次 fill-form; 对照: 无 guard 时 1 次', async () => {
    h.parse.mockResolvedValue(ok(FULL_FIXTURES[2].values));
    const guarded = mountDrawer(PURCHASE_ORDER_CONFIG, { confirmGuard: () => '客户未建档' });
    await send(guarded, '来一单');
    const btn = btnByText(guarded, '填入表单');
    expect(btn.attributes('disabled')).toBeDefined();
    await btn.trigger('click');
    expect(guarded.emitted('fill-form')).toBeUndefined();
    expect(guarded.text()).toContain('客户未建档');

    const open = await previewFor(PURCHASE_ORDER_CONFIG, FULL_FIXTURES[2].values);
    await btnByText(open, '填入表单').trigger('click');
    expect(open.emitted('fill-form')?.length).toBe(1);
  });

  it('🔴 没有中文 label 的英文键照原样显示键名, 不藏; 有 label 的显示中文', async () => {
    const w = await previewFor(PURCHASE_ORDER_CONFIG, {
      supplierName: 'XX供应商',
      items: [{ materialName: '大豆', quantity: 500, unit: 'kg', unitPrice: 0, customCode: 'X1' }],
    });
    const items = w.findAll('.preview-row').find((r) => r.find('.preview-label').text().includes('采购明细'))!;
    const text = items.find('.preview-value').text();
    expect(text).toContain('物料名称：大豆');
    expect(text).toContain('数量：500');
    expect(text).toContain('单位：kg');
    expect(text).toContain('customCode：X1');          // 未知英文键: 原样, 不藏
    expect(text).not.toMatch(/materialName|quantity|unitPrice/); // 已知键不再露英文
    // 改前行为原样保留: 单价 0 不进文本 (旧过滤 v !== 0)
    expect(text).not.toContain('单价');
  });

  it('入库明细: receivedQuantity 有中文名', async () => {
    const w = await previewFor(WH_INBOUND_CONFIG, FULL_FIXTURES[5].values);
    const text = w.findAll('.preview-row').find((r) => r.find('.preview-label').text().includes('入库明细'))!.find('.preview-value').text();
    expect(text).toContain('物料名称：大豆');
    expect(text).toContain('收货数量：500');
  });
});

describe('形态 D · ITEM_KEY_LABELS 与后端 prompt 里的明细键对账', () => {
  const dir = resolve(__dirname, '..', '..', '..', '..', '..', 'backend', 'java', 'cretas-api', 'src', 'main', 'resources', 'ai', 'form-prompts', 'factory');
  const entities = ['PURCHASE_ORDER', 'SALES_ORDER', 'WH_INBOUND'];

  /** 取 `- items:` 之后、下一个顶层 `- key:` 之前的 `  - key:` 缩进行 */
  function itemKeys(md: string): string[] {
    const lines = md.split(/\r?\n/);
    const start = lines.findIndex((l) => /^- items:/.test(l));
    if (start < 0) return [];
    const keys: string[] = [];
    for (const l of lines.slice(start + 1)) {
      if (/^- \w+:/.test(l) || /^\S/.test(l)) break;
      const m = /^\s+- (\w+):/.exec(l);
      if (m) keys.push(m[1]);
    }
    return keys;
  }

  it('三份带明细的 prompt 里每个明细键都有中文名 (读到了键, 不是读空)', () => {
    const all: string[] = [];
    for (const e of entities) {
      const keys = itemKeys(readFileSync(resolve(dir, `${e}.md`), 'utf8'));
      expect(keys.length, `${e}.md 里没读到明细键`).toBeGreaterThanOrEqual(3);
      all.push(...keys);
    }
    const missing = [...new Set(all)].filter((k) => !Object.prototype.hasOwnProperty.call(ITEM_KEY_LABELS, k));
    expect(missing).toEqual([]);
    expect(new Set(all)).toEqual(new Set(['materialName', 'productName', 'quantity', 'receivedQuantity', 'unit', 'unitPrice']));
  });

  it('阴性对照: 对账函数遇到没登记的键会报 (假想 md)', () => {
    const keys = itemKeys('- items: 明细\n  - mysteryField: 神秘\n');
    expect(keys).toEqual(['mysteryField']);
    expect(Object.prototype.hasOwnProperty.call(ITEM_KEY_LABELS, 'mysteryField')).toBe(false);
  });
});

// ===================== 8. 空值 / UUID (V1 / V2 / V3) =====================

describe('V1 / V2 / V3 · 值与文字', () => {
  it('没填的字段写「暂无」, 不写「未填写」「—」「-」; 有值的字段原样', async () => {
    const w = await previewFor(PURCHASE_ORDER_CONFIG, FULL_FIXTURES[2].values);
    const empties = w.findAll('.preview-row.is-empty');
    expect(empties.length).toBe(2); // expectedDeliveryDate / remark
    for (const r of empties) {
      expect(r.find('.preview-value').text()).toBe('暂无');
      expect(r.text()).not.toMatch(/—|未填写/);
      expect(r.find('.status-dash').exists()).toBe(false);
    }
    const supplier = w.findAll('.preview-row').find((r) => r.find('.preview-label').text().includes('供应商'))!;
    expect(supplier.find('.preview-value').text()).toBe('XX供应商'); // 对照
  });

  it('产品大类 / 采购类型走已有中文映射, 渲染文本里没有英文状态码、没有 UUID', async () => {
    const w1 = await previewFor(PRODUCT_CONFIG, FULL_FIXTURES[1].values);
    expect(w1.find('.preview-card').text()).toContain('包辅材');
    expect(w1.find('.preview-card').text()).not.toMatch(/\b[A-Z][A-Z_]{3,}\b/);
    const w2 = await previewFor(PURCHASE_ORDER_CONFIG, FULL_FIXTURES[2].values);
    expect(w2.find('.preview-card').text()).toContain('紧急采购');
    expect(w2.find('.preview-card').text()).not.toMatch(/\b[A-Z][A-Z_]{3,}\b/);
    expect(w2.find('.preview-card').text()).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-/i);
    // 对照: 检测正则抓得到
    expect('URGENT eb0aa47b-1234-5678').toMatch(/\b[A-Z][A-Z_]{3,}\b/);
    expect('eb0aa47b-1234-5678').toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-/i);
  });
});

// ===================== 9. 尺寸 (S2 / S3 / S4) =====================

describe('S2 / S3 / S4 · 按钮芯片 ≥ 32、输入框圆角 8 单行 40-44', () => {
  it('源文本: 输入框外框圆角 8、发送钮 ≥ 32、示例芯片 / 教程按钮 min-height ≥ 32', () => {
    const wrapper = /\.input-wrapper\s*\{([^}]*)\}/.exec(styleSrc)?.[1] ?? '';
    expect(wrapper).toMatch(/border-radius:\s*8px/);
    const send = /\.send-btn\s*\{([^}]*)\}/.exec(styleSrc)?.[1] ?? '';
    expect(Number(/width:\s*(\d+)px/.exec(send)?.[1])).toBeGreaterThanOrEqual(32);
    expect(Number(/height:\s*(\d+)px/.exec(send)?.[1])).toBeGreaterThanOrEqual(32);
    const chip = /\.quick-example-chip\s*\{([^}]*)\}/.exec(styleSrc)?.[1] ?? '';
    expect(Number(/min-height:\s*(\d+)px/.exec(chip)?.[1])).toBeGreaterThanOrEqual(32);
    const tut = /\.tutorial-btn\s*\{([^}]*)\}/.exec(styleSrc)?.[1] ?? '';
    expect(Number(/min-height:\s*(\d+)px/.exec(tut)?.[1])).toBeGreaterThanOrEqual(32);
    // 阴性对照: 旧的 12px 圆角不在输入框上
    expect(wrapper).not.toMatch(/border-radius:\s*12px/);
  });

  it('el-button 在对话区用默认尺寸 (不是 small)', async () => {
    const w = await previewFor(PURCHASE_ORDER_CONFIG, FULL_FIXTURES[2].values);
    for (const t of ['继续修改', '填入表单']) {
      expect(btnByText(w, t).attributes('data-size')).not.toBe('small');
    }
  });
});

// ===================== 10. 条件滚动 (N1) =====================

describe('N1 · 只有用户本来在底部附近才自动滚到底', () => {
  function geometry(w: VueWrapper, scrollTop: number) {
    const el = w.find('.chat-messages').element as HTMLElement;
    Object.defineProperty(el, 'scrollHeight', { configurable: true, value: 2000 });
    Object.defineProperty(el, 'clientHeight', { configurable: true, value: 500 });
    Object.defineProperty(el, 'scrollTop', { configurable: true, writable: true, value: scrollTop });
    const spy = vi.fn();
    el.scrollTo = spy as unknown as typeof el.scrollTo;
    return spy;
  }

  async function replyWhileAt(scrollTop: number) {
    // 用户发出那一下会强制滚一次; 这里只量「回答到了之后」是否又滚
    let release: (v: unknown) => void = () => undefined;
    h.parse.mockReturnValue(new Promise((r) => { release = r; }));
    const w = mountDrawer(PURCHASE_ORDER_CONFIG);
    await w.find('textarea.chat-textarea').setValue('来一单');
    await w.find('button.send-btn').trigger('click');
    await flushPromises();
    const spy = geometry(w, scrollTop);
    release(ok(FULL_FIXTURES[2].values));
    await flushPromises();
    return spy;
  }

  it('在底部 (差 20px) → 回答到了会滚', async () => {
    expect((await replyWhileAt(1480)).mock.calls.length).toBeGreaterThan(0);
  });

  it('往上翻了 (差 1000px) → 回答到了不滚', async () => {
    expect((await replyWhileAt(500)).mock.calls.length).toBe(0);
  });
});
