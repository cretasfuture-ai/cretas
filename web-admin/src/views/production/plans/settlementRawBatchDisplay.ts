/**
 * 「核对结单」原料领用展示文案 —— 缺陷 48。
 *
 * ⛔ 不许把内部主键 (UUID materialBatchId) 直接展示给仓库用户 ——
 *   与 #484 发货行内部行ID泄露（`发货行 255（产品：熟肉饼）可用库存不足`
 *   把数据库自增主键拼进用户可见文案）同一族: 内部标识符对仓管没有任何
 *   可操作性，只会让人反复去核对一个自己看不懂的编号。
 *
 * 「逐道报工汇总」摘要行原写法 `line.batchNumber || line.materialBatchId`：
 * `batchNumber` 缺失时直接把 UUID 兜底显示出来。后端在两条已知路径
 * (deriveRawConsumptionsFromProcessSheetRows / 报工引用 derive) 都会回填
 * `batchNumber`，但手工添加的行在它真正写回批次号之前，本地状态只有
 * `materialBatchId`。展示层必须诚实：批次号不在就说「待加载」，不能拿
 * UUID 顶替。
 */
export interface RawConsumptionDisplayLine {
    batchNumber?: string | null;
    materialName?: string | null;
    materialBatchId?: string | null;
}

/** 批次号缺失时的占位文案 —— 唯一允许出现在展示层的兜底。 */
export const RAW_BATCH_NUMBER_PENDING_LABEL = '（批次号待加载）';

/**
 * 结单摘要行的展示文案：`批次号 · 物料名`；批次号不在时给出诚实占位，
 * ⛔ 绝不回退到 `materialBatchId`（内部 UUID）。
 */
export function rawConsumptionDisplayLabel(line: RawConsumptionDisplayLine): string {
    if (line.batchNumber) {
        return line.materialName ? `${line.batchNumber} · ${line.materialName}` : line.batchNumber;
    }
    return RAW_BATCH_NUMBER_PENDING_LABEL;
}

/** 用户在结单弹框手工选批次时，能提供 batchNumber/物料名的最小批次形状。 */
export interface PickedMaterialBatchLike {
    batchNumber?: string | null;
    materialName?: string | null;
    materialTypeName?: string | null;
}

/**
 * 用户在「原料/辅料实际领用」下拉里选中一个批次时，立即把 batchNumber
 * 与物料名带到这一行上 —— 不等到结单真正提交、下一次 prefill 回填才有。
 * 批次不存在（尚未加载完 / 被清空）时保持原样，不覆盖成 null。
 */
export function applyPickedMaterialBatchToLine<L extends RawConsumptionDisplayLine>(
    line: L,
    batch: PickedMaterialBatchLike | null | undefined,
): void {
    if (!batch) return;
    line.batchNumber = batch.batchNumber || null;
    line.materialName = batch.materialName || batch.materialTypeName || null;
}
