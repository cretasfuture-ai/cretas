import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const EDITOR = readFileSync(
  resolve(__dirname, '../ProductProcessWorkflowEditor.vue'), 'utf8');
const RAW_CATALOG = readFileSync(resolve(__dirname, '../rawMaterialCatalog.ts'), 'utf8');

/**
 * 🔴 2026-09-01 本机实测出来的缺陷：**一张配了副产的工艺图存不了也发不了。**
 *
 * 链路：
 *   副产 Cell 的 skuId 来自**物料档案的副产池**（`raw_material_types` 里 isByproduct=true）
 *   → 而 `unitContext()` 只喂了 `skuOptions`（product_types）+ `rawMaterialOptions`
 *   → 后者被 `isRawMaterialOption` 按 `category === '原料'` 过滤过，副产按定义不是「原料」
 *   → 副产 SKU 在单位上下文里查不到
 *   → `reconcileWorkflowUnits` 报 `SKU_UNIT_UNKNOWN`
 *   → **保存和发布一起被挡住**。
 *
 * 症状最坏的一点是提示指向一个不存在的问题：
 * 「SKU RM-xxx 缺少规范主单位，请先维护 SKU 单位后再发布」——
 * 而那个物料**一直有 unit='kg'**，用户照着提示去物料档案只会发现单位是好的。
 *
 * 修前/修后都在本机量过：修前保存弹「Workflow 单位契约未通过」、lockVersion 不动；
 * 修后弹「当前 Workflow 配置已保存」、lockVersion 3 → 5。
 */
describe('副产 SKU 必须进单位上下文，否则整张图存不了', () => {
  it('unitContext 喂了副产物料池', () => {
    const body = EDITOR.slice(
      EDITOR.indexOf('function unitContext()'),
      EDITOR.indexOf('function unitContext()') + 1400,
    );
    expect(body).toContain('byproductMaterialOptions.value');
    // 阴性对照：另外两个池子仍然在（这条修复是**加**一个来源，不是换掉别的）
    expect(body).toContain('skuOptions.value');
    expect(body).toContain('rawMaterialOptions.value');
  });

  it('前提仍然成立：rawMaterialOptions 确实按「原料」过滤，所以副产进不来', () => {
    // 这条是上面那条修复的**理由**。哪天 isRawMaterialOption 放宽了，这里会红 ——
    // 那时应该重新判断 unitContext 还要不要单独喂副产池，而不是默默留着。
    expect(RAW_CATALOG).toMatch(/isRawMaterialOption[\s\S]{0,160}bigCategoryOf\(option\.category\) === '原料'/);
    expect(EDITOR).toContain('rawMaterialOptions.value = rawResponse.data.filter(isRawMaterialOption)');
  });

  it('🔴 副产池是懒加载的，所以「接上没有」必须在两条路上各查一次', () => {
    // 形态 B：机制在、没接上。`ensureByproductMaterialOptions` 原本只在
    // 「新建副产 Cell」和「就地标记副产」两个动作里调 —— 打开一张**已有**副产的图
    // 从来不拉它，池子是空的，上面那行 `byproductMaterialOptions.value` 就等于喂了个空数组。
    const persistence = EDITOR.slice(
      EDITOR.indexOf('async function reconcileForPersistence'),
      EDITOR.indexOf('async function reconcileForPersistence') + 1200,
    );
    // 保存路径必须 **await** —— 对账就在下一行，fire-and-forget 等不到它回来
    expect(persistence).toMatch(/await ensureByproductMaterialOptions\(\)/);

    const hydrateTail = EDITOR.slice(
      EDITOR.indexOf('function hydrate('),
      EDITOR.indexOf('function canvasHasByproduct'),
    );
    // 渲染路径不必 await（下拉晚一点填上没关系），但必须**发起**
    expect(hydrateTail).toMatch(/ensureByproductMaterialOptions\(\)/);
  });

  it('判据挂在「画布上有没有副产」上，不是无条件每次都拉', () => {
    expect(EDITOR).toMatch(/function canvasHasByproduct\(\): boolean \{[\s\S]{0,200}isByproduct === true/);
    // 阴性对照：没有副产时不该白拉一次档案
    expect(EDITOR).toMatch(/if \(canvasHasByproduct\(\)\) await ensureByproductMaterialOptions\(\)/);
  });
});
