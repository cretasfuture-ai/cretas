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
describe('单位上下文不许用「按业务分类过滤过」的列表', () => {
  it('unitContext 喂的是**未过滤**的物料档案', () => {
    const body = EDITOR.slice(
      EDITOR.indexOf('function unitContext()'),
      EDITOR.indexOf('function unitContext()') + 2200,
    );
    expect(body).toContain('materialArchiveForUnits.value');
    expect(body).toContain('skuOptions.value');
    // 🔴 阴性对照：⛔ 不许再拿过滤过的列表当单位来源。
    // 第一版只补了「副产池」，换一个 category='调料' 的物料当副产用就又中招 ——
    // 加一类漏一类，根因是「按业务分类过滤过的列表不该当作单位来源」。
    expect(body).not.toContain('rawMaterialOptions.value');
    expect(body).not.toContain('byproductMaterialOptions.value');
  });

  it('那份未过滤的档案确实来自原始响应，没有再过一次业务筛子', () => {
    expect(EDITOR).toMatch(
      /materialArchiveForUnits\.value = rawResponse\.data\.map\(/);
    // 只丢掉「没有 id 或没有单位」的行 —— 那是数据不可用，不是业务分类
    expect(EDITOR).toMatch(/\.filter\(\(row\) => row\.id && row\.unit\)/);
    // 切换产品/工厂时要一起清，否则会串味
    expect(EDITOR).toMatch(/materialArchiveForUnits\.value = \[\];/);
  });

  it('前提仍然成立：rawMaterialOptions 确实按「原料」过滤，所以副产进不来', () => {
    // 这条是上面那条修复的**理由**。哪天 isRawMaterialOption 放宽了，这里会红 ——
    // 那时应该重新判断 unitContext 还要不要单独喂副产池，而不是默默留着。
    expect(RAW_CATALOG).toMatch(/isRawMaterialOption[\s\S]{0,160}bigCategoryOf\(option\.category\) === '原料'/);
    expect(EDITOR).toContain('rawMaterialOptions.value = rawResponse.data.filter(isRawMaterialOption)');
  });

  it('🔴 单位来源不许挂在懒加载的池子上', () => {
    // 形态 B：机制在、没接上。`ensureByproductMaterialOptions` 只在「新建副产 Cell」和
    // 「就地标记副产」两个动作里调 —— 打开一张**已有**副产的图从来不拉它。
    // 第一版把单位来源接在它上面，于是不得不在保存路径上补一次 await。
    // 换成随目录一起加载的 materialArchiveForUnits 之后，这个时序依赖**整个消失**。
    const persistence = EDITOR.slice(
      EDITOR.indexOf('async function reconcileForPersistence'),
      EDITOR.indexOf('async function reconcileForPersistence') + 1400,
    );
    expect(persistence).not.toMatch(/await ensureByproductMaterialOptions\(\)/);

    // 但副产**下拉**仍然需要那个池子，渲染时照旧发起（不 await）
    const hydrateTail = EDITOR.slice(
      EDITOR.indexOf('function hydrate('),
      EDITOR.indexOf('function canvasHasByproduct'),
    );
    expect(hydrateTail).toMatch(/ensureByproductMaterialOptions\(\)/);
  });

  it('🔴 阴性对照：回料**不会**中同一个招 —— 它的 SKU 池本来就在单位上下文里', () => {
    // 「一个原因解释两个症状时，逐个验证机制对每个症状都成立」。
    // 副产中招是因为它按**硬外键**从物料档案选(bom_recipe_items.material_type_id →
    // raw_material_types)，那个池子原本没进 unitContext。
    // 回料不一样：它从 WorkflowSkuPicker 的 semi/finished 选，两者都是 skuOptions 的子集，
    // 而 skuOptions 一直在 unitContext 里 ⇒ 结构上不可能查不到主单位。
    const EDITOR_SRC = EDITOR;
    expect(EDITOR_SRC).toMatch(/const semiFinishedSkuOptions = computed\(\(\) => skuOptions\.value\.filter/);
    expect(EDITOR_SRC).toMatch(/const finishedGoodSkuOptions = computed\(\(\) => skuOptions\.value\.filter/);
    // 抽屉里回料 SKU 用的就是这两个池子
    const DRAWER = readFileSync(resolve(__dirname, '../WorkflowReworkConfigDrawer.vue'), 'utf8');
    expect(DRAWER).toContain(':semi-options="semiOptions"');
    expect(DRAWER).toContain(':finished-options="finishedOptions"');
    // ⇒ 所以「拉副产池」这件事只需要按副产判条件, 不必也为回料拉一次
    expect(EDITOR_SRC).not.toMatch(/canvasHasRework/);
  });

  it('副产下拉的拉取判据挂在「画布上有没有副产」上，不是无条件每次都拉', () => {
    expect(EDITOR).toMatch(/function canvasHasByproduct\(\): boolean \{[\s\S]{0,200}isByproduct === true/);
    expect(EDITOR).toMatch(/if \(canvasHasByproduct\(\)\) void ensureByproductMaterialOptions\(\)/);
  });
});
