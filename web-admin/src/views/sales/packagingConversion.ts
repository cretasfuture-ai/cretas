/**
 * O-309-1: 成品批规格和发货行不一致时的换算确认 (销售订单分配对话框、仓库出货确认共用)。
 * 确认只跳过规格闸, 可售 / 库存 / 单位换算的校验都还在后端。
 */
import { ElMessageBox } from 'element-plus';

export const PACKAGING_MISMATCH_CODE = 'BATCH_PACKAGING_SPEC_MISMATCH';

export function isPackagingMismatchError(error: unknown): boolean {
  return Boolean(error) && typeof error === 'object'
    && (error as { code?: unknown }).code === PACKAGING_MISMATCH_CODE;
}

export interface MismatchRow {
  batchNumber: string;
  allocatedQty: number;
  unit: string;
  packagingMismatch?: boolean;
  batchPackagingLabel?: string;
  linePackagingLabel?: string;
}

/** 这一行里要确认的批次, 每条都带品名、批号、两边规格和本次数量 (防呆 Rule 2)。 */
export function mismatchConfirmLines(productName: string, rows: MismatchRow[]): string[] {
  return rows
    .filter((row) => row.packagingMismatch && Number(row.allocatedQty) > 0)
    .map((row) => `${productName} · 批次 ${row.batchNumber}：批次规格 ${row.batchPackagingLabel}，`
      + `本行规格 ${row.linePackagingLabel}，本次发 ${row.allocatedQty}${row.unit}`);
}

/** 问一次: true = 操作人核对过换算。 */
export async function confirmPackagingConversion(lines: string[]): Promise<boolean> {
  try {
    await ElMessageBox.confirm(
      `${lines.join('\n')}\n\n数量按本行规格出库。确认换算无误？`,
      '规格不一致，请确认换算',
      { type: 'warning', confirmButtonText: '确认换算', cancelButtonText: '换批次', customStyle: { whiteSpace: 'pre-line' } },
    );
    return true;
  } catch {
    return false;
  }
}
