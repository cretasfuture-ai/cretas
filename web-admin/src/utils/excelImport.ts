import * as XLSX from 'xlsx';

export interface ParsedTable {
  headers: string[];
  rows: Array<Record<string, unknown> | unknown[]>;
}

export function tableFromRows(rows: unknown[][]): ParsedTable {
  const nonEmpty = rows.filter(row => row.some(cell => String(cell ?? '').trim() !== ''));
  if (!nonEmpty.length) return { headers: [], rows: [] };
  const headers = nonEmpty[0].map(cell => String(cell ?? '').trim());
  const data = nonEmpty.slice(1).map(row => Object.fromEntries(headers.map((h, i) => [h, row[i] ?? ''])));
  return { headers, rows: data };
}

export function parseExcelArrayBuffer(buffer: ArrayBuffer): ParsedTable {
  const workbook = XLSX.read(buffer, { type: 'array', cellDates: false });
  const name = workbook.SheetNames[0];
  if (!name) return { headers: [], rows: [] };
  const sheet = workbook.Sheets[name];
  if (!sheet) return { headers: [], rows: [] };
  return tableFromRows(XLSX.utils.sheet_to_json<unknown[]>(sheet, { header: 1, raw: false, defval: '', blankrows: false }));
}
