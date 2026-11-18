import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { formatReportTime } from '../reportMarkdown';

const read = (rel: string) => readFileSync(resolve(__dirname, rel), 'utf-8');

describe('PROTO-DEMO-LAST 追加：纯前端文案', () => {
  it('AI 报告列表说明列复用 formatReportTime 去掉纳秒 ISO', () => {
    const src = read('../index.vue');
    const line = src.split('\n').find((l) => l.includes('const summaryPreview')) ?? '';
    expect(line).toContain('stripReportTimes(');
    expect(src).toContain('formatReportTime(text)');
    expect(formatReportTime('取数时间 2026-11-18T00:49:36.412155525+08:00')).toBe('取数时间 2026-11-18 00:49');
  });
  it('能力闸占位不露字段名，驾驶舱 KPI 用中性「销售网点数」', () => {
    const gate = read('../../../../components/CapabilityGate.vue');
    expect(gate).not.toMatch(/:description="`/);
    expect(gate).not.toContain('${missing');
    expect(gate).toContain('当前工厂暂未提供');
    const dash = read('../../../smart-bi/Dashboard.vue');
    expect(dash).toContain("'门店数' : '销售网点数'");
    expect(dash).not.toContain("'门店数' : '网点数'");
  });
});
