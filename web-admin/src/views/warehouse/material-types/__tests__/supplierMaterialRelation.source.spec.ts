import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const materialSource = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/material-types/list.vue'),
  'utf8',
);
const supplierSource = readFileSync(
  resolve(process.cwd(), 'src/views/procurement/suppliers/SupplierDetailDrawer.vue'),
  'utf8',
);
const apiSource = readFileSync(
  resolve(process.cwd(), 'src/api/supplierManagement.ts'),
  'utf8',
);

describe('supplier material bidirectional relation contract', () => {
  it('uses the canonical material-to-suppliers relationship endpoint', () => {
    expect(apiSource).toContain('`/${factoryId}/materials/${materialTypeId}/suppliers`');
    expect(materialSource).toContain('listMaterialSuppliers(factoryId.value, material.id)');
    expect(materialSource).not.toContain('/suppliers/by-material');
    expect(materialSource).toContain('关联供应商');
    expect(materialSource).toContain('createSupplierMaterial(factoryId.value, relationForm.supplierId, payload)');
    expect(materialSource).toContain('updateSupplierMaterial(factoryId.value, relationForm.supplierId, relationEditing.value.id, payload)');
    expect(materialSource).toContain('deleteSupplierMaterial(factoryId.value, supplierId, row.id, row.version)');
  });

  it('prevents duplicate supplier choices and keeps preferred/active on the relation', () => {
    expect(materialSource).toContain('const linked = new Set(suppliersForMaterial.value.map');
    expect(materialSource).toContain('!linked.has(String(supplier.id))');
    expect(materialSource).toContain('preferred: relationForm.preferred');
    expect(materialSource).toContain('active: relationForm.active');
    expect(materialSource).toContain('已关联的供应商不会再次出现');
  });

  it('uses the shared purchase quantity unit catalog and makes price units explicit', () => {
    // ⚠️ usage-scope / displayUnit 两条保持原样 —— 它们没过期, 仍然在守「价格字段的计量单位必须明示」。
    for (const source of [materialSource, supplierSource]) {
      expect(source).toContain('usage-scope="PURCHASE_QUANTITY"');
      expect(source).toContain('displayUnit(relationForm.purchaseUnit)');
    }

    /*
     * 🔴 2026-09-02: 原本这里对**两个文件**都断言 `未配置时采购单`。
     *
     * 它在 SupplierDetailDrawer 上**已经空转** —— 那句承诺早在缺陷 17 就被删了,
     * 字面串只活在一句**解释「它已被删掉」的注释**里, 断言因此一直是绿的。
     * 本仓记过这个形态: 源码闸把自己写的注释当成了被测对象。
     *
     * 而 owner 2026-09-02 的 A 裁定(价格一律必填, 存量也要补)让这句承诺在供应关系上
     * **必须**消失: 新增/编辑路径上「未配置」这个状态已经不存在了。
     *
     * ⇒ 按形态 C‴ 处理: 把守的东西从【字面】抬到【性质】, 并配阴性对照。
     *   ⛔ 不把那句话加回去(那是把 owner 刚推翻的承诺重新写进产品),
     *   ⛔ 也不整行删掉(会连它仍然该守的东西一起丢)。
     */

    // 物料档案侧: 参考价仍然**可以留空**, 所以「未配置时会明确提示」在那里仍是活的承诺。
    expect(materialSource).toContain('未配置时采购单');

    // 供应关系侧: 现在守的是「价格必填 + 拦下来时给的是具体提示」这个性质。
    expect(supplierSource).toContain('priceProblem(');
    expect(supplierSource).toContain('含税默认采购价：');

    // 🔴 阴性对照: 被推翻的那句承诺不许回来。
    //    「留空也没关系 / 不会按 0 元处理」这类措辞若重新出现, 说明有人在 A 裁定之后
    //    又把「价格可以不填」写回了界面。
    expect(supplierSource).not.toContain('不会静默按 0 元处理');
    expect(supplierSource).not.toContain('不动这一栏就不会被拦');

    expect(supplierSource).not.toContain('usage-scope="PURCHASE"');
    expect(supplierSource).not.toContain('<el-input v-model="relationForm.purchaseUnit"');
  });

  it('resolves the material dimension before defaulting or exposing purchase units', () => {
    for (const source of [materialSource, supplierSource]) {
      expect(source).toContain("listSystemUnits(");
      expect(source).toContain("'PURCHASE_QUANTITY'");
      expect(source).toContain('resolvePurchaseUnits(');
      expect(source).toContain('purchaseUnitSelectionReason(');
      expect(source).toContain(':options=');
      expect(source).toContain('<el-alert');
    }
    expect(supplierSource).not.toContain("relationForm.purchaseUnit = material?.unit || ''");
    expect(materialSource).not.toContain("purchaseUnit: suppliersDialogMaterial.value?.unit || ''");
  });

  it('keeps material reference prices optional and bound to the inventory base unit', () => {
    expect(materialSource).toContain('选填；未知价格请留空');
    expect(materialSource).toContain('displayUnit(form.unit)');
    expect(materialSource).toContain('采购参考价如填写，必须大于 0；未知价格请留空');
    expect(materialSource).not.toContain('delete materialPayload.taxIncludedUnitPrice;\n      delete materialPayload.associatedCustomerId');
  });
});
