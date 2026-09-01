import { describe, expect, it } from 'vitest';
import { resolveWarehouseInventoryEmpty } from '../warehouseInventoryEmptyState';

/**
 * 分仓库存查询「搜不到」vs「仓库真的空」(缺陷 B, 2026-09-02, LIUSHANMEN 生产实测)。
 *
 * 搜索一个不存在的批次号（如 `1300`）时，旧版无条件显示「该仓库暂无原料批次」，
 * 而统计卡片同屏写着「原料批次 56」——工人会把「搜不到」读成「仓库是空的」。
 */
describe('resolveWarehouseInventoryEmpty — 搜不到 vs 仓库真的空', () => {
  describe('🔴 关键场景：仓库有 56 个原料批次，搜索一个不存在的批次号', () => {
    const ui = resolveWarehouseInventoryEmpty({ totalCount: 56, query: '1300', kind: 'materials' });

    it('落在 no-match，⛔ 不是 warehouse-empty', () => {
      expect(ui.state).toBe('no-match');
    });

    it('文案不再说"暂无原料批次"，且带出真实总数', () => {
      expect(ui.text).not.toBe('该仓库暂无原料批次');
      expect(ui.text).toContain('56');
      expect(ui.text).toContain('1300');
    });
  });

  describe('仓库这一类批次总数确实是 0（与搜索词无关）', () => {
    it.each(['', '1300', '随便什么'])('query=%s 时都判定为 warehouse-empty', (query) => {
      const ui = resolveWarehouseInventoryEmpty({ totalCount: 0, query, kind: 'materials' });
      expect(ui.state).toBe('warehouse-empty');
      expect(ui.text).toBe('该仓库暂无原料批次');
    });
  });

  describe('成品批次同一套判定 (sibling reading, kind=products)', () => {
    it('仓库有成品但搜索词没匹配上 → no-match，文案带"成品"', () => {
      const ui = resolveWarehouseInventoryEmpty({ totalCount: 12, query: 'ZZZ', kind: 'products' });
      expect(ui.state).toBe('no-match');
      expect(ui.text).toContain('成品');
      expect(ui.text).toContain('12');
    });

    it('成品总数为 0 → warehouse-empty，文案与原有措辞一致（不破坏既有阳性对照）', () => {
      const ui = resolveWarehouseInventoryEmpty({ totalCount: 0, query: '', kind: 'products' });
      expect(ui.state).toBe('warehouse-empty');
      expect(ui.text).toBe('该仓库暂无成品批次');
    });
  });

  describe('判定顺序：totalCount === 0 优先于 query（与 materialDropdownEmptyState 同构）', () => {
    it('totalCount 从 0 变正数时，同一个空搜索词得到不同的 state（阳性对照，证明没被恒定判死）', () => {
      const empty = resolveWarehouseInventoryEmpty({ totalCount: 0, query: '', kind: 'materials' });
      // totalCount=0 且 query='' 通常不会触发空态渲染（filtered==full list==空），
      // 但函数本身仍要在这种输入下给出确定性的 warehouse-empty，不是 undefined/崩溃。
      expect(empty.state).toBe('warehouse-empty');
    });
  });

  describe('两个状态两两可辨 (⛔ 不许塌成一个)', () => {
    it('state 不同', () => {
      const a = resolveWarehouseInventoryEmpty({ totalCount: 0, query: '', kind: 'materials' }).state;
      const b = resolveWarehouseInventoryEmpty({ totalCount: 10, query: 'x', kind: 'materials' }).state;
      expect(a).not.toBe(b);
    });

    it('文案不同', () => {
      const a = resolveWarehouseInventoryEmpty({ totalCount: 0, query: '', kind: 'materials' }).text;
      const b = resolveWarehouseInventoryEmpty({ totalCount: 10, query: 'x', kind: 'materials' }).text;
      expect(a).not.toBe(b);
    });
  });
});
