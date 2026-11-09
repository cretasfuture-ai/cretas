/**
 * 成本差异 Excel 导出: 行内容由 costVarianceFormat.ts 生成 (与页面同一套文字), 这里只负责写文件。
 */
import * as XLSX from 'xlsx';
import type { SheetRow } from './costVarianceFormat';

export function downloadSheet(rows: SheetRow[], sheetName: string, fileName: string): void {
  const ws = XLSX.utils.aoa_to_sheet(rows);
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, sheetName);
  XLSX.writeFile(wb, fileName);
}
