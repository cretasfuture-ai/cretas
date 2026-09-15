import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import ElementPlus from 'element-plus';
import { ApiError } from '@/types/api';
import {
  individualDetail,
  mixedFarthestLayerTrace,
  noLabelTrace,
  noSectionsTrace,
  sixCattleTrace,
} from './fixtures';

/**
 * 溯源查询页 —— 行为验收 (真挂载、真渲染, 只桩掉 API / store / router)。
 *
 * ① 有祖先且全带个体 → 横幅「本批来自 6 头牛」
 * ② 最远层混一个不带个体的原料 → 不出横幅            ← 变异靶: 判据「全部」改「任一」
 * ③ traceCode: null → 「未打印标签」                    ← 变异靶: null 兜底改成显示批次号
 * ④ 批次不存在 (ApiError 404, 两种形状) → 「未找到批次 X」, ⛔ 不显示空表格
 * ⑤ 三个空数组 → 三张表不渲染 + 页尾一行「暂无加工/质检/出货记录」
 * 另: 来源链三态分开 / 可点的行打开个体抽屉 / `?batchNumber=` 带参直开。
 */

const routeMock: { query: Record<string, string> } = { query: {} };
const pushMock = vi.fn();
const replaceMock = vi.fn();
vi.mock('vue-router', () => ({
  useRoute: () => routeMock,
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));

vi.mock('@/api/request', () => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }));

const getFullTraceMock = vi.fn();
vi.mock('@/api/traceability', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/traceability')>()),
  getFullTrace: (...args: unknown[]) => getFullTraceMock(...args),
}));

const getDetailMock = vi.fn();
vi.mock('@/api/sourceIndividual', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/sourceIndividual')>()),
  getSourceIndividualDetail: (...args: unknown[]) => getDetailMock(...args),
}));

import TracePage from '../index.vue';

function ok<T>(data: T) {
  return Promise.resolve({ success: true, data, message: '' });
}

function mountPage() {
  return mount(TracePage, { global: { plugins: [ElementPlus] } });
}

async function search(wrapper: VueWrapper, batchNumber: string) {
  await wrapper.find('input[data-testid="batch-input"]').setValue(batchNumber);
  await wrapper.find('form.query-bar').trigger('submit');
  await flushPromises();
  await nextTick();
}

beforeEach(() => {
  routeMock.query = {};
  pushMock.mockReset();
  replaceMock.mockReset();
  getFullTraceMock.mockReset();
  getDetailMock.mockReset();
});

describe('溯源查询页 — 「本批来自 N 头牛」横幅', () => {
  it('① 六头牛全带个体 ⇒ 横幅「本批来自 6 头牛」', async () => {
    getFullTraceMock.mockImplementation(() => ok(sixCattleTrace()));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    expect(getFullTraceMock).toHaveBeenCalledWith('F006', 'PB-2026-001');
    const banner = wrapper.find('[data-testid="source-individual-banner"]');
    expect(banner.exists()).toBe(true);
    expect(banner.text()).toBe('本批来自 6 头牛');
    // 阳性对照: 六个个体行都渲染了、且都可点 (后端给了 id)
    expect(wrapper.findAll('[data-testid="lineage-individual"]')).toHaveLength(6);
    expect(wrapper.findAll('[data-testid="lineage-chevron"]')).toHaveLength(6);
  });

  it('② 最远层混一袋盐 ⇒ 不出横幅 (count=5 仍 >0, 只有「全部」这个条件不满足)', async () => {
    getFullTraceMock.mockImplementation(() => ok(mixedFarthestLayerTrace()));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    // 阳性对照: 链本身画出来了 (两层、六行), 否则「没横幅」可能只是页面没渲染
    expect(wrapper.find('[data-testid="lineage-timeline"]').exists()).toBe(true);
    expect(wrapper.findAll('[data-testid="lineage-node"]')).toHaveLength(6);
    expect(wrapper.findAll('[data-testid="lineage-individual"]')).toHaveLength(5);
    expect(wrapper.text()).toContain('食盐');

    expect(wrapper.find('[data-testid="source-individual-banner"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('本批来自');
  });
});

describe('溯源查询页 — 溯源码卡', () => {
  it('③ traceCode: null ⇒ 「未打印标签」, ⛔ 不拿批次号顶到溯源码的位置', async () => {
    getFullTraceMock.mockImplementation(() => ok(noLabelTrace()));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    const missing = wrapper.find('[data-testid="trace-code-missing"]');
    expect(missing.exists()).toBe(true);
    expect(missing.text()).toBe('未打印标签');
    expect(wrapper.find('[data-testid="trace-code"]').exists()).toBe(false);
    // 批次号仍在卡上, 但在它自己的那一行, 不冒充溯源码
    expect(wrapper.find('[data-testid="trace-code-batch"]').text()).toContain('PB-2026-001');
  });

  it('阴性对照: 有 traceCode 时显示它, 「未打印标签」不出现', async () => {
    getFullTraceMock.mockImplementation(() => ok(sixCattleTrace()));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    expect(wrapper.find('[data-testid="trace-code"]').text()).toBe('TC-PB-2026-001');
    expect(wrapper.find('[data-testid="trace-code-missing"]').exists()).toBe(false);
  });
});

describe('溯源查询页 — 批次不存在', () => {
  it.each([
    // 后端真实形状: HTTP 200 + {success:false, code:404} → 拦截器给 ApiError(code=404, status=undefined)
    ['200+success:false', new ApiError('未找到该批次信息', 404 as unknown as string)],
    // 真 HTTP 404
    ['HTTP 404', new ApiError('请求的接口不存在', 'NOT_FOUND', 404)],
  ])('④ %s ⇒ 「未找到批次 X」, 不显示空表格', async (_shape, error) => {
    getFullTraceMock.mockImplementation(() => Promise.reject(error));
    const wrapper = mountPage();
    await search(wrapper, 'PB-NOPE');

    const notFound = wrapper.find('[data-testid="trace-not-found"]');
    expect(notFound.exists()).toBe(true);
    expect(notFound.text()).toContain('未找到批次 PB-NOPE');
    expect(wrapper.find('[data-testid="production-card"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="lineage-card"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="trace-error"]').exists()).toBe(false);
  });

  it('阴性对照: 403 不是「未找到」—— 显示错误原话, 不说批次不存在', async () => {
    getFullTraceMock.mockImplementation(() => Promise.reject(new ApiError('权限不足', 'FORBIDDEN', 403)));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    expect(wrapper.find('[data-testid="trace-not-found"]').exists()).toBe(false);
    const err = wrapper.find('[data-testid="trace-error"]');
    expect(err.exists()).toBe(true);
    expect(err.text()).toContain('权限不足');
  });
});

describe('溯源查询页 — 加工 / 质检 / 出货', () => {
  it('⑤ 三个空数组 ⇒ 三张表都不渲染, 页尾一行「暂无加工/质检/出货记录」', async () => {
    getFullTraceMock.mockImplementation(() => ok(noSectionsTrace()));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    expect(wrapper.find('[data-testid="processing-table"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="quality-table"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="shipment-table"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="empty-sections-line"]').text()).toBe('暂无加工/质检/出货记录');
  });

  it('阴性对照: 三段都有内容 ⇒ 三张表都在, 页尾那一行不出现', async () => {
    getFullTraceMock.mockImplementation(() => ok(sixCattleTrace()));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    expect(wrapper.find('[data-testid="processing-table"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="quality-table"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="shipment-table"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="empty-sections-line"]').exists()).toBe(false);
    // 表格真的渲染了行, 不只是壳
    expect(wrapper.text()).toContain('分割');
    expect(wrapper.text()).toContain('SH-2026-001');
  });

  it('只有一段空 ⇒ 页尾只列那一段', async () => {
    getFullTraceMock.mockImplementation(() => ok({ ...sixCattleTrace(), shipments: [] }));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    expect(wrapper.find('[data-testid="empty-sections-line"]').text()).toBe('暂无出货记录');
  });
});

describe('溯源查询页 — 来源链三态', () => {
  it('lineage 为 null ⇒ 「来源链暂时取不到」(本次没拿到, 不是没有上游)', async () => {
    getFullTraceMock.mockImplementation(() => ok({ ...sixCattleTrace(), lineage: null }));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    expect(wrapper.find('[data-testid="lineage-unavailable"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="lineage-empty"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="source-individual-banner"]').exists()).toBe(false);
  });

  it('ancestors 为空 ⇒ 「无上游血缘记录」', async () => {
    const t = sixCattleTrace();
    t.lineage = { ...t.lineage!, ancestors: [], nodeCount: 0, sourceIndividualCount: 0 };
    getFullTraceMock.mockImplementation(() => ok(t));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    expect(wrapper.find('[data-testid="lineage-empty"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="lineage-unavailable"]').exists()).toBe(false);
  });

  it('两层按 depth 从远到近排, 标签「上游第 2 层」在「直接上游」上面', async () => {
    getFullTraceMock.mockImplementation(() => ok(mixedFarthestLayerTrace()));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    const labels = wrapper.findAll('.el-timeline-item__timestamp').map((el) => el.text());
    expect(labels).toEqual(['上游第 2 层', '直接上游']);
  });
});

describe('溯源查询页 — 个体抽屉', () => {
  it('挂了个体且有 id 的行可点 ⇒ 抽屉调 detail 接口并显示档案', async () => {
    getFullTraceMock.mockImplementation(() => ok(sixCattleTrace()));
    getDetailMock.mockImplementation(() => ok(individualDetail()));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    const rows = wrapper.findAll('[data-testid="lineage-node"]');
    await rows[1].trigger('click');
    await flushPromises();
    await nextTick();

    expect(getDetailMock).toHaveBeenCalledWith('F006', 'si-2');
    const drawer = wrapper.find('[data-testid="drawer-title"]');
    expect(drawer.exists()).toBe(true);
    expect(drawer.text()).toBe('SL-002');
    // 三种「没有」分开说: 父本登记过但档案不可查 / 母本未登记
    expect(wrapper.find('[data-testid="drawer-sire"]').text()).toContain('档案已不可查');
    expect(wrapper.find('[data-testid="drawer-dam"]').text()).toContain('未登记');
    // 去了哪: 原料批次 → 生产批次
    expect(wrapper.find('[data-testid="drawer-batches"]').text()).toContain('MB-2026-002');
    expect(wrapper.find('[data-testid="drawer-group-production"]').text()).toContain('PB-2026-001');
  });

  it('「编辑」跳列表并带 ?keyword=<individualCode>', async () => {
    getFullTraceMock.mockImplementation(() => ok(sixCattleTrace()));
    getDetailMock.mockImplementation(() => ok(individualDetail()));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');
    await wrapper.findAll('[data-testid="lineage-node"]')[1].trigger('click');
    await flushPromises();
    await nextTick();

    await wrapper.find('[data-testid="drawer-edit"]').trigger('click');
    expect(pushMock).toHaveBeenCalledWith({
      path: '/warehouse/source-individuals',
      query: { keyword: 'SL-002' },
    });
  });

  it('抽屉读档失败 ⇒ 显示原话; 关掉再点同一头 ⇒ 重新取 (id 没变也得刷掉错误态)', async () => {
    getFullTraceMock.mockImplementation(() => ok(sixCattleTrace()));
    getDetailMock
      .mockImplementationOnce(() => Promise.reject(new ApiError('权限不足', 'FORBIDDEN', 403)))
      .mockImplementation(() => ok(individualDetail()));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    const row = wrapper.findAll('[data-testid="lineage-node"]')[1];
    await row.trigger('click');
    await flushPromises();
    await nextTick();
    expect(getDetailMock).toHaveBeenCalledTimes(1);
    expect(wrapper.find('[data-testid="drawer-error"]').text()).toContain('权限不足');
    expect(wrapper.find('[data-testid="drawer-identity"]').exists()).toBe(false);

    // 关掉 (el-drawer v-model → false), 再点同一行
    await wrapper.findComponent({ name: 'ElDrawer' }).vm.$emit('update:modelValue', false);
    await nextTick();
    await row.trigger('click');
    await flushPromises();
    await nextTick();

    expect(getDetailMock).toHaveBeenCalledTimes(2);
    expect(wrapper.find('[data-testid="drawer-error"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="drawer-title"]').text()).toBe('SL-002');
  });

  it('阴性对照: 没有个体 id 的行不可点, 点了也不调 detail', async () => {
    getFullTraceMock.mockImplementation(() => ok(mixedFarthestLayerTrace()));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    const salt = wrapper.findAll('[data-testid="lineage-node"]').find((r) => r.text().includes('食盐'))!;
    expect(salt.classes()).not.toContain('is-clickable');
    await salt.trigger('click');
    await flushPromises();
    expect(getDetailMock).not.toHaveBeenCalled();
  });

  // ── 2026-09-15 投屏截图取证的两处 (impeccable critique B 侧) ──────────────
  it('编码只在标题栏出现一次; 正文首行是耳标 + 品种·来源场', async () => {
    getFullTraceMock.mockImplementation(() => ok(sixCattleTrace()));
    getDetailMock.mockImplementation(() => ok(individualDetail()));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');
    await wrapper.findAll('[data-testid="lineage-node"]')[1].trigger('click');
    await flushPromises();
    await nextTick();

    const drawer = wrapper.find('[data-testid="individual-drawer"]');
    expect(drawer.exists()).toBe(true);
    // 阳性对照: 标题栏确实念了编码 —— 否则「只出现一次」可能是「一次都没出现」
    expect(wrapper.find('[data-testid="drawer-title"]').text()).toBe('SL-002');
    expect((drawer.text().match(/SL-002/g) ?? []).length).toBe(1);
    // 正文首行: 耳标 + 品种·来源场 (与来源链行上点进来的那一段是同一句)
    expect(wrapper.find('[data-testid="drawer-tag"]').text()).toBe('耳标 12345672');
    expect(wrapper.find('[data-testid="drawer-origin-line"]').text()).toBe('西门塔尔 · 胜龙一号场');
    expect(wrapper.find('[data-testid="drawer-identity"]').text()).not.toContain('SL-002');
  });

  it('「去了哪」原料批次与生产批次两种节点都带图标 (同一份 LineageNodeRow, 左缘对齐)', async () => {
    getFullTraceMock.mockImplementation(() => ok(sixCattleTrace()));
    getDetailMock.mockImplementation(() => ok(individualDetail()));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');
    await wrapper.findAll('[data-testid="lineage-node"]')[1].trigger('click');
    await flushPromises();
    await nextTick();

    const batches = wrapper.find('[data-testid="drawer-batches"]');
    const production = wrapper.find('[data-testid="drawer-group-production"]');
    expect(batches.findAll('.lineage-node__icon')).toHaveLength(1);
    expect(production.findAll('.lineage-node__icon')).toHaveLength(1);
    // 图标不是空壳: 两个都真渲染了 svg (nodeTypeIcon 映射到了 Element Plus 图标组件)
    expect(batches.find('.lineage-node__icon svg').exists()).toBe(true);
    expect(production.find('.lineage-node__icon svg').exists()).toBe(true);
    expect(batches.find('[data-testid="lineage-node"]').attributes('data-node-type')).toBe('MATERIAL_BATCH');
    // 原料批次行的内容一样不少: 批次号 / 物料·数量 / 入库日期
    expect(batches.text()).toContain('MB-2026-002');
    expect(batches.text()).toContain('牛胴体 · 250kg');
    expect(batches.text()).toContain('入库：2026-09-09');
    // 「投入量」是来源链上的口径, 这里不该出现
    expect(batches.text()).not.toContain('投入量');
  });

  it('原料批次没有入库日期 ⇒ 不留一个空行', async () => {
    const d = individualDetail();
    d.batches = [{ id: 'mb-2', batchNumber: 'MB-2026-002', materialName: '牛胴体', receiptDate: null }];
    getFullTraceMock.mockImplementation(() => ok(sixCattleTrace()));
    getDetailMock.mockImplementation(() => ok(d));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');
    await wrapper.findAll('[data-testid="lineage-node"]')[1].trigger('click');
    await flushPromises();
    await nextTick();

    const batches = wrapper.find('[data-testid="drawer-batches"]');
    expect(batches.text()).not.toContain('入库');
    const lines = batches.findAll('.lineage-node__line').map((l) => l.text());
    expect(lines).toEqual(['牛胴体']);
  });
});

describe('溯源查询页 — 投屏细节 (2026-09-15 截图取证)', () => {
  it('横幅前有图标, 文案本身不变', async () => {
    getFullTraceMock.mockImplementation(() => ok(sixCattleTrace()));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    const banner = wrapper.find('[data-testid="source-individual-banner"]');
    expect(banner.find('[data-testid="source-individual-banner-icon"] svg').exists()).toBe(true);
    expect(banner.text()).toBe('本批来自 6 头牛');
  });

  it('「查询时间」是 YYYY-MM-DD HH:mm, 不是浏览器语言的 toLocaleString', async () => {
    getFullTraceMock.mockImplementation(() => ok(sixCattleTrace())); // queryTime: 2026-09-15T10:00:00
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    const line = wrapper.find('[data-testid="query-time"]');
    expect(line.exists()).toBe(true);
    expect(line.text()).toBe('查询时间: 2026-09-15 10:00');
    expect(wrapper.text()).not.toMatch(/\d{1,2}\/\d{1,2}\/\d{4}/); // 9/15/2026 这种形状不许出现
  });

  it('对照: 后端没给 queryTime ⇒ 那一行整行不渲染, 也不显示 -', async () => {
    getFullTraceMock.mockImplementation(() => ok({ ...sixCattleTrace(), queryTime: undefined as unknown as string }));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    // 阳性对照: 页面确实渲染到了页尾 (生产卡在), 「没有那一行」不是「整页没渲染」
    expect(wrapper.find('[data-testid="production-card"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="query-time"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('查询时间');
    expect(wrapper.findAll('.footer-line').map((l) => l.text())).not.toContain('查询时间: -');
  });

  it('工序「时间」列同样走 dayjs 格式', async () => {
    const t = sixCattleTrace();
    t.processingStages = [
      {
        stageType: 'CUTTING',
        stageName: '分割',
        stageOrder: 1,
        operatorName: '张三',
        durationMinutes: 40,
        startTime: '2026-09-10T08:00:00',
        endTime: '2026-09-10T08:40:00',
      },
    ];
    getFullTraceMock.mockImplementation(() => ok(t));
    const wrapper = mountPage();
    await search(wrapper, 'PB-2026-001');

    expect(wrapper.find('[data-testid="processing-table"]').text()).toContain('2026-09-10 08:00 ~ 08:40');
    expect(wrapper.text()).not.toMatch(/\d{1,2}\/\d{1,2}\/\d{4}/);
  });
});

describe('溯源查询页 — 带参直开', () => {
  it('?batchNumber= 存在 ⇒ 挂载即查询, 不用再点按钮', async () => {
    routeMock.query = { batchNumber: 'PB-2026-001' };
    getFullTraceMock.mockImplementation(() => ok(sixCattleTrace()));
    const wrapper = mountPage();
    await flushPromises();
    await nextTick();

    expect(getFullTraceMock).toHaveBeenCalledWith('F006', 'PB-2026-001');
    expect(wrapper.find('[data-testid="production-card"]').exists()).toBe(true);
    expect((wrapper.find('input[data-testid="batch-input"]').element as HTMLInputElement).value).toBe('PB-2026-001');
  });

  it('阴性对照: 没带参 ⇒ 不自动查, 显示空态', async () => {
    const wrapper = mountPage();
    await flushPromises();
    expect(getFullTraceMock).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="trace-idle"]').exists()).toBe(true);
  });
});
