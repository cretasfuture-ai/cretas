/**
 * 确认领料 — 辅料自动计算的纯函数 (owner 2026-09-02 拍板, 详见 list.vue 里
 * prepareConfirmDialog / recalcAuxRows 的改动说明)。
 *
 * 「肯定我们原料是放在最前面去填写的…辅料基本上不太需要去填写，因为它基本上是
 * 自动计算的…我在确认原料、输入了所有原料以后，剩下的辅料自动实时计算？我修改
 * 以后它也会重新计算。」—— owner 原话，逐字对着这个屏幕说的。
 *
 * 拆成独立模块（而不是留在 <script setup> 顶层）是为了能脱离 Vue 组件挂载直接
 * 单测这几条精确数字（0.2514 / 0.419 这种），也避免依赖「未被模板引用的纯函数
 * 会不会出现在 test-utils 的 vm 代理上」这条不稳定的编译产物细节。
 *
 * 口径「单锅基准」镜像后端 FactoryMaterialRequisitionServiceImpl（同 #460 用的公式）：
 *   - massUnitToKg       ~ #massToKg
 *   - deriveAuxiliaryQty ~ #seasoningRequiredQty（dosagePerKgG × 投入kg ÷ 1000）
 * 两侧各自独立实现（前端是实时预览，后端 generateFromPlan 时才是那批单据的权威值）——
 * 这个屏幕上真正的写入权威永远是仓管最终确认/覆盖后提交的 pickedInput，不是这份计算本身。
 */

/** 只读取参与计算所需的最小字段集合, 与具体行类型解耦。 */
export interface MassRow {
  materialCategory?: string | null;
  pickedInput?: number | string | null;
  unit?: string | null;
}

/**
 * 质量单位统一换算到 kg。⛔ 计数单位(个/只/件…)返回 null, 不硬折——
 * 镜像后端 FactoryMaterialRequisitionServiceImpl#massToKg。
 */
export function massUnitToKg(qty: number, unit?: string | null): number | null {
  if (!Number.isFinite(qty) || !unit) return null;
  const u = unit.trim();
  if (/^(kg|千克|公斤)$/i.test(u)) return qty;
  if (/^(g|克)$/i.test(u)) return qty / 1000;
  if (/^(t|吨)$/i.test(u)) return qty * 1000;
  if (/^(jin|斤)$/i.test(u)) return qty / 2;
  return null;
}

/**
 * RAW 行「实际拣货」合计（kg）—— 只累加换算得出质量的行，计数单位原料不计入分母
 * （镜像后端 plannedRawInputKg 的兜底口径）。留空/非数字的行按 0 处理（不参与）。
 */
export function rawPickedTotalKg(rows: MassRow[]): number {
  return rows.reduce((sum, row) => {
    if (row.materialCategory !== 'RAW') return sum;
    if (row.pickedInput === null || row.pickedInput === undefined || row.pickedInput === '') return sum;
    const n = Number(row.pickedInput);
    if (!Number.isFinite(n)) return sum;
    const kg = massUnitToKg(n, row.unit);
    return kg === null ? sum : sum + kg;
  }, 0);
}

/**
 * 单锅基准: dosagePerKgG(g / kg 投入) × RAW 投入合计(kg) ÷ 1000, 按行单位换算。
 *
 * `totalRawKg <= 0` 或没有比例数据时返回 `null`（「按实际领用」）—— ⛔ 不返回 0：
 * 0 与 null 语义相反（#469 教训：0 = 明确「这次不领」，null = 「还不知道」）。
 * 一个由「空输入」算出来的 0 会跟那个明确的 0 撞语义。
 */
export function deriveAuxiliaryQty(
  dosagePerKgG: number | null | undefined,
  totalRawKg: number,
  unit?: string | null,
): number | null {
  const dosage = Number(dosagePerKgG);
  if (!Number.isFinite(dosage) || dosage <= 0) return null;
  if (!Number.isFinite(totalRawKg) || totalRawKg <= 0) return null;
  const grams = dosage * totalRawKg;
  const u = (unit || 'kg').trim();
  if (/^(g|克)$/i.test(u)) return Number(grams.toFixed(3));
  if (/^(kg|千克|公斤)$/i.test(u)) return Number((grams / 1000).toFixed(4));
  return null; // 非质量单位不硬折, 留给人工填 (镜像后端 seasoningRequiredQty 同一条护栏)
}

/**
 * 按数量把物料行拆成「非零」/「零」两组 —— 缺陷43: 零值行不删（0 是明确的事实,
 * 审计要留痕), 只是把它们从主视野里折叠出去。见 list.vue 的 buildItemsSummaryHtml。
 */
export function splitZeroQtyRows<T>(items: T[], qtyOf: (item: T) => number): { nonZero: T[]; zero: T[] } {
  const nonZero: T[] = [];
  const zero: T[] = [];
  for (const item of items) {
    (qtyOf(item) > 0 ? nonZero : zero).push(item);
  }
  return { nonZero, zero };
}
