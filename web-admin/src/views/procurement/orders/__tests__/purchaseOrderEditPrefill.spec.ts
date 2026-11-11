import { describe, expect, it } from 'vitest';

import {
  isSupplierLockedInEditor,
  matchDraftLinesToSupplierRelations,
  resolveSupplierMaterialRelations,
} from '../purchaseOrderEditPrefill';

/**
 * 客户反馈 (Google Sheet 2026-07-20, 采购订单):
 * 「"编辑"功能无法进入，点击后仅会在域名后新增字符且无响应」.
 *
 * #2004 修好了第一层 (watch route.query.edit), 但 2026-08-04 在 prod 复现仍然打不开:
 * F006 唯一那张草稿单 PO-20260717-0002 是「开始采购」自动生成的, supplierId 为 null,
 * openEditDialog 仍然无条件调 listSupplierMaterials(factoryId, '') →
 * GET /F006/suppliers//materials (注意双斜杠) → 404 → 整个函数在 dialogVisible=true
 * 之前抛出, 弹窗永不打开 —— 用户看到的症状与修复前逐字一致.
 *
 * 讽刺的是这正是表里第 22 行要求「编辑」按钮的场景: 自动生成的采购单没有供应商,
 * 需要事后补录. 为它而生的入口, 恰恰只在它身上必崩.
 *
 * 同文件的 onSupplierChange 早就写对了 (`form.value.supplierId ? await ... : []`),
 * 本模块把那个判断抽出来, 让两个调用点共用同一套规则.
 */
describe('resolveSupplierMaterialRelations (Google Sheet 2026-07-20 / prod 复现 2026-08-04)', () => {
  it('无供应商的草稿单不发 supplier-scoped 请求, 直接给空关系表', async () => {
    const calls: Array<[string, string]> = [];
    const fetchRelations = async (factoryId: string, supplierId: string) => {
      calls.push([factoryId, supplierId]);
      return [{ id: 'r1', active: true }];
    };

    const relations = await resolveSupplierMaterialRelations('F006', null, fetchRelations);

    // 关键断言: 一次请求都不能发 —— 发了就会拼出 /F006/suppliers//materials 并 404.
    expect(calls).toEqual([]);
    expect(relations).toEqual([]);
  });

  it('空串与纯空格同样按「没有供应商」处理', async () => {
    const calls: string[] = [];
    const fetchRelations = async (_f: string, supplierId: string) => {
      calls.push(supplierId);
      return [];
    };

    expect(await resolveSupplierMaterialRelations('F006', '', fetchRelations)).toEqual([]);
    expect(await resolveSupplierMaterialRelations('F006', '   ', fetchRelations)).toEqual([]);
    expect(calls).toEqual([]);
  });

  it('有供应商时照常读取, 并滤掉已停用的供货关系', async () => {
    const fetchRelations = async (factoryId: string, supplierId: string) => {
      expect(factoryId).toBe('F006');
      expect(supplierId).toBe('sup-1');
      return [
        { id: 'r1', active: true },
        { id: 'r2', active: false },
        { id: 'r3' },
      ];
    };

    const relations = await resolveSupplierMaterialRelations('F006', 'sup-1', fetchRelations);

    // active 未声明视为在用 (与 onSupplierChange 的 `active !== false` 口径一致).
    expect(relations.map((row) => row.id)).toEqual(['r1', 'r3']);
  });

  it('真实读取失败必须抛出, 不能吞成空列表', async () => {
    // 禁止降级处理: 供应商存在却读不到供货关系, 是错误, 不是「这个供应商没有物料」.
    // 吞掉会让用户在残缺的物料下拉里存出错单.
    const fetchRelations = async () => {
      throw new Error('Request failed with status code 500');
    };

    await expect(
      resolveSupplierMaterialRelations('F006', 'sup-1', fetchRelations),
    ).rejects.toThrow('Request failed with status code 500');
  });
});

/**
 * D-S411R-01 (2026-11-10): 编辑弹窗供应商下拉原来是 `:disabled="isEditing"`, 对没有供应商的
 * 自动草稿也锁 ⇒ 提交 422 PURCHASE_SUPPLIER_REQUIRED 却无处补供应商。
 * 挂载真实页面的断言见 draftSupplierBackfill.mount.spec.ts; 这里逐象限钉住判定本身。
 */
describe('isSupplierLockedInEditor (D-S411R-01)', () => {
  it('草稿且原本没有供应商: 不锁 (空串 / null / 纯空格 同样处理)', () => {
    for (const originalSupplierId of ['', null, undefined, '   ']) {
      expect(isSupplierLockedInEditor({ isEditing: true, orderStatus: 'DRAFT', originalSupplierId })).toBe(false);
    }
  });

  it('草稿且已有供应商: 不锁, 可以换 (2026-11-11 交接单第二节)', () => {
    expect(isSupplierLockedInEditor({ isEditing: true, orderStatus: 'DRAFT', originalSupplierId: 'sup-1' })).toBe(false);
  });

  it('非草稿一律锁, 即使没有供应商 (纵深防御)', () => {
    for (const orderStatus of ['SUBMITTED', 'WORKFLOW_RUNNING', 'FINANCE_APPROVED', 'APPROVED', 'COMPLETED', '', null]) {
      expect(isSupplierLockedInEditor({ isEditing: true, orderStatus, originalSupplierId: '' })).toBe(true);
    }
  });

  it('新建不锁', () => {
    expect(isSupplierLockedInEditor({ isEditing: false, orderStatus: '', originalSupplierId: '' })).toBe(false);
  });
});

describe('matchDraftLinesToSupplierRelations (D-S411R-01)', () => {
  it('按原料精确对应; 供应商不供应的原料留给用户, 不猜', () => {
    const lines = [
      { materialTypeId: 'mat-salt', quantity: 56.74 },
      { materialTypeId: 'mat-sugar', quantity: 3 },
    ];
    const relations = [
      { id: 'rel-salt', materialTypeId: 'mat-salt' },
      { id: 'rel-oil', materialTypeId: 'mat-oil' },
    ];

    const { matched, unmatched } = matchDraftLinesToSupplierRelations(lines, relations);

    expect(matched.map(({ line, relation }) => [line.materialTypeId, relation.id])).toEqual([['mat-salt', 'rel-salt']]);
    expect(unmatched.map((line) => line.materialTypeId)).toEqual(['mat-sugar']);
    // 返回的是原行对象 (调用方就地补字段), 数量不被碰。
    expect(matched[0].line).toBe(lines[0]);
    expect(lines[0].quantity).toBe(56.74);
  });

  it('同一原料有多条生效供应关系时不挑第一条, 当作未对应', () => {
    const { matched, unmatched } = matchDraftLinesToSupplierRelations(
      [{ materialTypeId: 'mat-salt' }],
      [{ id: 'rel-a', materialTypeId: 'mat-salt' }, { id: 'rel-b', materialTypeId: 'mat-salt' }],
    );
    expect(matched).toEqual([]);
    expect(unmatched).toHaveLength(1);
  });

  it('还没选供应商 (关系表为空) 时全部未对应', () => {
    const { matched, unmatched } = matchDraftLinesToSupplierRelations([{ materialTypeId: 'mat-salt' }], []);
    expect(matched).toEqual([]);
    expect(unmatched).toHaveLength(1);
  });
});
