import { beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

vi.mock('../request', () => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }));

import { del, get, post, put } from '../request';
import {
  DNA_STATUS_LABELS,
  createSourceIndividual,
  deleteSourceIndividual,
  findSourceIndividualByTag,
  listSourceIndividuals,
  updateSourceIndividual,
} from '../sourceIndividual';

const mockedGet = vi.mocked(get);
const mockedPost = vi.mocked(post);
const mockedPut = vi.mocked(put);
const mockedDel = vi.mocked(del);

beforeEach(() => {
  [mockedGet, mockedPost, mockedPut, mockedDel].forEach((m) => m.mockReset());
  mockedGet.mockResolvedValue({ success: true, data: { content: [] }, message: '' });
  mockedPost.mockResolvedValue({ success: true, data: {}, message: '' });
  mockedPut.mockResolvedValue({ success: true, data: {}, message: '' });
  mockedDel.mockResolvedValue({ success: true, data: undefined, message: '' });
});

describe('source individual API client', () => {
  it('builds tenant-bound paths under /{factoryId}/source-individuals', async () => {
    await listSourceIndividuals('F006', { page: 2, size: 50, keyword: 'SI-1' });
    expect(mockedGet).toHaveBeenCalledWith('/F006/source-individuals', {
      params: { page: 2, size: 50, keyword: 'SI-1' },
    });

    await createSourceIndividual('F006', { individualCode: 'SI-1' });
    expect(mockedPost).toHaveBeenCalledWith('/F006/source-individuals', { individualCode: 'SI-1' });

    await updateSourceIndividual('F006', 'abc', { species: 'CATTLE' });
    expect(mockedPut).toHaveBeenCalledWith('/F006/source-individuals/abc', { species: 'CATTLE' });

    await deleteSourceIndividual('F006', 'abc');
    expect(mockedDel).toHaveBeenCalledWith('/F006/source-individuals/abc');
  });

  it('url-encodes the tag number — tags are free text and may contain / or #', async () => {
    await findSourceIndividualByTag('F006', 'A/B#1');
    expect(mockedGet).toHaveBeenCalledWith('/F006/source-individuals/by-tag/A%2FB%231');
  });

  it('sends keyword as undefined when blank — an empty keyword must not become a filter', async () => {
    await listSourceIndividuals('F006', { keyword: '' });
    expect(mockedGet).toHaveBeenCalledWith('/F006/source-individuals', {
      params: { page: 1, size: 20, keyword: undefined },
    });
  });

  it('DNA status labels cover exactly the four values the DB CHECK allows', () => {
    // 🔴 这四个值同时活在三处: PG 的 ck_source_individual_dna_status、Java 枚举
    //    DnaVerificationStatus、这里。形态 D(同一个东西有两份一定会漂)在这里是
    //    三份 —— 跨语言抽不成一份, 所以至少钉住「不多不少就这四个」。
    expect(Object.keys(DNA_STATUS_LABELS).sort()).toEqual(
      ['MATCHED', 'MISMATCH', 'SAMPLED', 'UNSAMPLED']
    );
  });
});

describe('source individual page reachability', () => {
  /**
   * 🔴 「做完了、路由也注册了、菜单零登记」在本仓发生过两次(PR#76 / PR#96),
   * 后果是用户只能手打 URL。本仓已有一道全量棘轮
   * (`router/__tests__/routeReachability.spec.ts`), 但棘轮只禁增长 ——
   * 它不会告诉我**这一条**有没有登记。这里点名钉住自己这一条。
   */
  const SRC = resolve(__dirname, '../..');
  const ROUTE_PATH = 'source-individuals';

  it('the route is declared and the sidebar menu links to it', () => {
    const router = readFileSync(resolve(SRC, 'router/index.ts'), 'utf8');
    const menu = readFileSync(resolve(SRC, 'components/layout/menuConfig.ts'), 'utf8');

    expect(router).toContain(`path: '${ROUTE_PATH}'`);
    expect(router).toContain("component: () => import('@/views/source-individuals/index.vue')");
    // 阳性对照: 确认我读的确实是那两个文件而不是空串 —— 否则上面的 toContain
    // 在「文件读错了」时会以完全相同的方式红, 而在 `not.toContain` 那一侧会恒绿。
    expect(router.length).toBeGreaterThan(1000);
    expect(menu.length).toBeGreaterThan(1000);

    expect(menu).toContain("path: '/warehouse/source-individuals'");
  });
});
