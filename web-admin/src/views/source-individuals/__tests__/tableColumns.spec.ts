import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';
import type { SourceIndividual } from '@/api/sourceIndividual';

/**
 * 源头个体档案列表 —— 2026-09-15 投屏截图取证 (impeccable critique B 侧):
 *   1440 视口出横向滚动条, 「DNA 比对」排最右被固定的「操作」列盖得只剩一个字。
 * 这里钉住两件事:
 *   ① 「DNA 比对」在第 3 列 (档案编号 / 标识牌编号 之后), 且是 round chip
 *   ② 空字段整格留空, ⛔ 不渲染 '-' —— 阳性对照: 同一张表里有值的格确实渲染出了值
 * 列宽本身 (1366 不出横向滚动条) jsdom 量不到, 只能靠 index.vue 里那段预算注释 + 真机看。
 */

const routeMock: { query: Record<string, string> } = { query: {} };
vi.mock('vue-router', () => ({
  useRoute: () => routeMock,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true }),
}));

const listMock = vi.fn();
vi.mock('@/api/sourceIndividual', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/sourceIndividual')>()),
  listSourceIndividuals: (...args: unknown[]) => listMock(...args),
}));
vi.mock('@/api/request', () => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }));

import SourceIndividualsPage from '../index.vue';

/** 一头登记齐全的 + 一头只有编号的 (SL-CATTLE-007 那种)。 */
function rows(): SourceIndividual[] {
  return [
    {
      id: 'si-1',
      factoryId: 'F006',
      individualCode: 'SL-BULL-001',
      tagNumber: '12345671',
      species: 'CATTLE',
      breed: '西门塔尔',
      sex: '公',
      birthDate: '2024-03-01',
      originFarm: '胜龙一号场',
      dnaVerificationStatus: 'MATCHED',
      dnaVerificationStatusDisplayName: '已比中',
    },
    {
      id: 'si-7',
      factoryId: 'F006',
      individualCode: 'SL-CATTLE-007',
      tagNumber: null,
      species: null,
      breed: null,
      sex: null,
      birthDate: null,
      originFarm: null,
      dnaVerificationStatus: null,
      dnaVerificationStatusDisplayName: null,
    },
  ];
}

async function mountWith(content: SourceIndividual[]) {
  listMock.mockResolvedValue({
    success: true,
    data: { content, page: 1, size: 20, totalElements: content.length, totalPages: 1 },
    message: '',
  });
  const wrapper = mount(SourceIndividualsPage, { global: { plugins: [ElementPlus] } });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  routeMock.query = {};
  listMock.mockReset();
});

describe('源头个体档案 — 列顺序', () => {
  it('「DNA 比对」在第 3 列 (紧跟 档案编号 / 标识牌编号), 不在最右', async () => {
    const wrapper = await mountWith(rows());
    const headers = wrapper.findAll('thead th').map((th) => th.text()).filter((t) => t !== '');
    expect(headers.slice(0, 3)).toEqual(['档案编号', '标识牌编号', 'DNA 比对']);
    // 阳性对照: 表头真的渲染全了, 「第 3 列」不是「只有 3 列」
    expect(headers).toEqual(['档案编号', '标识牌编号', 'DNA 比对', '物种', '品种', '性别', '出生日期', '来源养殖场', '操作']);
    expect(headers.filter((h) => h.startsWith('DNA'))).toHaveLength(1);
  });

  it('DNA 状态是 round chip', async () => {
    const wrapper = await mountWith(rows());
    const chips = wrapper.findAll('[data-testid="dna-chip"]');
    expect(chips).toHaveLength(1); // 只有登记了状态的那一头有 chip
    expect(chips[0].text()).toBe('已比中');
    // el-tag 根是 <Transition>, VTU 把它 stub 成 <transition-stub>, data-testid 落在 stub 上;
    // class 在里面那个 .el-tag 上 —— 实测 (probe) 得来的, 不是猜的。
    const tag = chips[0].find('.el-tag');
    expect(tag.exists()).toBe(true);
    expect(tag.classes()).toContain('is-round');
    expect(tag.classes()).toContain('el-tag--success');
  });
});

describe('源头个体档案 — 空字段不渲染 -', () => {
  it('SL-CATTLE-007 那种只有编号的行: 其它格整格留空, 一个 - 都没有', async () => {
    const wrapper = await mountWith(rows());
    const bodyRows = wrapper.findAll('tbody tr.el-table__row');
    expect(bodyRows).toHaveLength(2);

    const sparse = bodyRows.find((r) => r.text().includes('SL-CATTLE-007'))!;
    expect(sparse).toBeTruthy();
    // 按格比, 不按整行 text —— 编号 SL-CATTLE-007 自己就带连字符, 整行 contains('-') 是错的尺子
    const sparseCells = sparse.findAll('td').map((td) => td.text());
    expect(sparseCells).not.toContain('-');
    // 除编号与操作列, 其余格都是空串
    expect(sparseCells.filter((t) => t !== '')).toEqual(['SL-CATTLE-007', '编辑删除']);

    // 阳性对照: 有值的那一行, 每一格都真的渲染出了值 —— 「空」不是「整张表都没渲染」
    const full = bodyRows.find((r) => r.text().includes('SL-BULL-001'))!;
    const fullCells = full.findAll('td').map((td) => td.text());
    expect(fullCells).toEqual([
      'SL-BULL-001', '12345671', '已比中', 'CATTLE', '西门塔尔', '公', '2024-03-01', '胜龙一号场', '编辑删除',
    ]);
  });
});
