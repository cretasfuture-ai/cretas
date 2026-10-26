/**
 * Shared sparkline SVG path generator for KPI mini-charts.
 * Used by Dashboard, FinancialDashboardPBI, and other SmartBI views.
 */

/** 序列值: null / undefined 表示该点没有数据 (如没上传的月份)。 */
export type SparklineValue = number | null | undefined;

const isValue = (v: SparklineValue): v is number => typeof v === 'number' && Number.isFinite(v);

/**
 * Generate an SVG path string for a sparkline from a numeric data array.
 * Missing values leave a gap in the line (never drawn as 0).
 * Returns an empty string if fewer than 2 real data points are provided.
 */
export function sparklinePath(
  data: SparklineValue[],
  width = 60,
  height = 22,
  pad = 2,
): string {
  if (!data || data.filter(isValue).length < 2) return '';
  const values = data.filter(isValue);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  const parts: string[] = [];
  let penDown = false;
  data.forEach((v, i) => {
    if (!isValue(v)) {
      penDown = false;
      return;
    }
    const x = pad + (i / (data.length - 1)) * (width - pad * 2);
    const y = height - pad - ((v - min) / range) * (height - pad * 2);
    parts.push(`${penDown ? 'L' : 'M'} ${x.toFixed(1)},${y.toFixed(1)}`);
    penDown = true;
  });
  return parts.join(' ');
}

/**
 * Generate a complete inline SVG string for embedding in tooltips or table cells.
 * Returns an `<svg>` HTML string with a sparkline + optional highlight dot.
 *
 * @param data - Numeric data array
 * @param highlightIndex - Optional index to highlight with a dot (e.g., hovered point)
 * @param width - SVG width in px
 * @param height - SVG height in px
 * @param color - Stroke color
 */
export function sparklineSVG(
  rawData: SparklineValue[],
  highlightIndex = -1,
  width = 120,
  height = 30,
  color = '#2D8B57',
): string {
  // 缺失点直接去掉 (提示框里的小图不画断线), ⛔ 不当成 0
  const data = (rawData || []).filter(isValue);
  if (data.length < 2) return '';
  const pad = 3;
  const min = Math.min(...data);
  const max = Math.max(...data);
  const range = max - min || 1;

  const points = data.map((v, i) => {
    const x = pad + (i / (data.length - 1)) * (width - pad * 2);
    const y = height - pad - ((v - min) / range) * (height - pad * 2);
    return { x, y };
  });

  const path = points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ');

  // Gradient fill under the line
  const areaPath = `${path} L ${points[points.length - 1].x.toFixed(1)},${height} L ${points[0].x.toFixed(1)},${height} Z`;

  let highlightDot = '';
  if (highlightIndex >= 0 && highlightIndex < points.length) {
    const hp = points[highlightIndex];
    highlightDot = `<circle cx="${hp.x.toFixed(1)}" cy="${hp.y.toFixed(1)}" r="3" fill="${color}" stroke="#fff" stroke-width="1.5"/>`;
  }

  return `<svg width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" style="display:block;margin:4px 0 0 0">`
    + `<defs><linearGradient id="sg" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="${color}" stop-opacity="0.2"/><stop offset="100%" stop-color="${color}" stop-opacity="0.02"/></linearGradient></defs>`
    + `<path d="${areaPath}" fill="url(#sg)"/>`
    + `<path d="${path}" fill="none" stroke="${color}" stroke-width="1.5" stroke-linecap="round"/>`
    + highlightDot
    + `</svg>`;
}
