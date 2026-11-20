/**
 * 原料入库：数量 → 总重量(kg) 的自动填充。
 *
 * ## 为什么单独抽出来（2026-08-27）
 *
 * `list.vue` 里原来是这一行，**不看单位**：
 *
 *     // totalWeight = quantity (unit is typically kg; use quantity directly as weight)
 *     formData.totalWeight = Number((qty).toFixed(3));
 *
 * 而 `totalWeight` 的后端契约写死是公斤 —— `CreateMaterialBatchRequest`：
 * `@Schema(description = "入库总重量(kg)")` + `@NotNull` + `@DecimalMin("0.001")`。
 * 单位则跟着所选原料走（`formData.quantityUnit = String(mat.unit)`）。
 *
 * ⇒ 选一个单位是「箱」的原料、录 3，总重量被自动填成 **3 kg**。
 *   单位是「g」时更糟：录 500 g 被填成 **500 kg**，1000 倍。
 *
 * **生产实测（2026-08-27，`cretas_prod_db` 只读）**：484 个原料类型里
 * **102 个不是 kg** —— 个 54 / 盒 9 / 片 8 / 箱 6 / 卷 6 / 张 4 / 只 3 / m 3 /
 * L 3 / 包 2 / 件 1，外加 **g 3 个**（那 3 个是 1000× 误差）。
 *
 * ## 判据
 *
 * **只在单位是质量单位时自动填，且做真实换算；其余一律返回 null 让用户自己录。**
 *
 * ⛔ 不给非质量单位「兜底填一个数」—— 那是把「我不知道这批多重」写成一个具体重量，
 *    与本仓 `MaterialBatch.getTotalWeight()` 在 `weightPerUnit == null` 时
 *    `return BigDecimal.ZERO` 是同一个毛病（把「不知道」翻译成一个数）。
 *
 * ⚠️ 换算表只收**无歧义的质量单位**。⛔ 不猜「一箱多少公斤」——
 *    那个数据整条采购收货链上根本没有（`PurchaseServiceImpl` 建批次时手上只有
 *    数量 / 单位 / 包装换算系数，没有重量）。
 */

/** 单位（原样，可能带空格/大小写差异）→ 换算到 kg 的乘数。 */
const MASS_UNIT_TO_KG: Record<string, number> = {
  kg: 1,
  KG: 1,
  Kg: 1,
  公斤: 1,
  千克: 1,
  g: 0.001,
  G: 0.001,
  克: 0.001,
  t: 1000,
  T: 1000,
  吨: 1000,
};

/** 这个单位是不是「无歧义的质量单位」。 */
export function isMassUnit(unit: string | null | undefined): boolean {
  if (unit == null) return false;
  return Object.prototype.hasOwnProperty.call(MASS_UNIT_TO_KG, String(unit).trim());
}

/**
 * 由数量与单位算出应自动填入的总重量(kg)。
 *
 * @returns 质量单位 → 换算后的 kg 数（保留 3 位小数，与后端 `numeric(10,3)` 对齐）；
 *          **非质量单位 / 单位缺失 / 数量非正 → `null`**，表示「算不出来，请用户自己录」。
 *          ⛔ 调用方拿到 null 时不许回退成 `qty`。
 */
export function autoTotalWeightKg(
  qty: number | null | undefined,
  unit: string | null | undefined,
): number | null {
  if (qty == null || !Number.isFinite(Number(qty)) || Number(qty) <= 0) return null;
  if (unit == null) return null;
  const factor = MASS_UNIT_TO_KG[String(unit).trim()];
  if (factor === undefined) return null;
  return Number((Number(qty) * factor).toFixed(3));
}

/** 没自动填时给用户的解释。⛔ 不要静默留空 —— 那个字段是必填的。 */
export function autoWeightSkippedHint(unit: string | null | undefined): string {
  const u = unit == null || String(unit).trim() === '' ? '该单位' : String(unit).trim();
  return `本批数量的单位是「${u}」，不是重量单位，无法自动换算成公斤，请手动填写总重量(kg)`;
}
