import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { formatReportTime, renderReportMarkdown } from '../reportMarkdown';
import { costMoney } from '@/views/production-analytics/costMoney';
import { salesColumnPinning } from '@/views/sales/orders/salesColumnPinning';

const MD = '# 经营分析报告\n\n## 销售\n\n- 取数时间：2026-11-17T20:00:45.954240724+08:00\n- <script>alert(1)</script>毛利';

describe('AI 报告详情正文渲染', () => {
  it('markdown 转成 HTML，不露出源码标记，且被净化', () => {
    const html = renderReportMarkdown(MD);
    expect(html).toContain('<h1>');
    expect(html).toContain('<li>');
    expect(html).not.toContain('## ');
    expect(html).not.toContain('# 经营');
    expect(html).not.toContain('<script');
  });
  it('取数时间显示为 YYYY-MM-DD HH:mm', () => {
    expect(formatReportTime('2026-11-17T20:00:45.954240724+08:00')).toBe('2026-11-17 20:00');
    expect(renderReportMarkdown(MD)).toContain('2026-11-17 20:00');
    expect(renderReportMarkdown(MD)).not.toMatch(/\dT\d/);
  });
  it('详情走 v-html 渲染而不是 <pre>{{ body }}', () => {
    const src = readFileSync(resolve(process.cwd(), 'src/views/analytics/ai-reports/index.vue'), 'utf8');
    expect(src).toContain('v-html="bodyHtml"');
    expect(src).not.toContain('<pre class="report-body">');
  });
});

describe('成本汇总金额与总成本列', () => {
  it('千分位 + 2 位小数', () => {
    expect(costMoney(1234567.5)).toBe('¥1,234,567.50');
    expect(costMoney(12)).toBe('¥12.00');
    expect(costMoney(null)).toBe('—');
  });
  it('总成本列固定在右侧始终可见', () => {
    const src = readFileSync(resolve(process.cwd(), 'src/views/production-analytics/M67CostSummary.vue'), 'utf8');
    expect(src).toMatch(/label="总成本"[^>]*fixed="right"/);
  });
});

describe('销售订单窄屏固定列', () => {
  it('<1200 不固定下一步列，>=1200 固定', () => {
    expect(salesColumnPinning(768).right).toEqual([]);
    expect(salesColumnPinning(1199).right).toEqual([]);
    expect(salesColumnPinning(1200).right).toEqual(['actions']);
  });
  it('列表使用按宽度计算的 columnPinning', () => {
    const src = readFileSync(resolve(process.cwd(), 'src/views/sales/orders/list.vue'), 'utf8');
    expect(src).toContain('salesColumnPinning(viewportWidth.value)');
  });
});
