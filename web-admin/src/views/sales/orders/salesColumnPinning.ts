/** 视口 < 1200 时「下一步」列不固定，避免压住总金额/状态列；表格自身横向滚动。 */
export const SALES_PIN_MIN_WIDTH = 1200;

export function salesColumnPinning(viewportWidth: number): { left: string[]; right: string[] } {
  return { left: [], right: viewportWidth >= SALES_PIN_MIN_WIDTH ? ['actions'] : [] };
}
