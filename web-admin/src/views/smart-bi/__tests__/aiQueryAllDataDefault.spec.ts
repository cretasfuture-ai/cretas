/**
 * 味美 11-19 (B2/B3): 问数据 (AI) 的数据源下拉。
 *
 * 修复前: 下拉里有保存失败 (FAILED) 的上传; 默认选最新一份上传 (实测落到错表)。
 * 修复后: 只列 COMPLETED; 工厂默认「全部数据」(0, 请求里不带 uploadId, 后端按问题选表);
 * 餐饮保持 #8 规则。
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  ALL_DATA_SOURCE_ID,
  onlyCompletedUploads,
  pickDefaultDataSource,
  pickInitialDataSourceId,
} from '../composables/useDataSourceSelect';
import type { UploadHistoryItem } from '@/api/smartbi';

function up(id: number, fileName: string, status: string, createdAt: string, rowCount = 10): UploadHistoryItem {
  return { id, fileName, sheetName: fileName, tableType: 'GENERAL', rowCount, columnCount: 5, status, createdAt };
}

// 味美当天的真实形态: 最新一份是保存失败的醒发表 (当时最新), 其次才是正常表
const weimei = [
  up(5331, '味美10月-04-醒发蒸制.xlsx', 'FAILED', '2026-11-19T02:10:00'),
  up(5335, '味美10月-08b-计划品项达成率_按周.xlsx', 'COMPLETED', '2026-11-19T02:07:12', 5),
  up(5320, '味美10月-02-机台开机停机.xlsx', 'COMPLETED', '2026-11-19T01:29:10', 177),
];

describe('问数据数据源下拉 (味美 B2/B3)', () => {
  it('drops FAILED uploads from the dropdown', () => {
    const ids = onlyCompletedUploads(weimei).map(d => d.id);
    expect(ids).toEqual([5335, 5320]);
    expect(ids).not.toContain(5331);
  });

  it('factory tenant defaults to 全部数据, not the newest upload', () => {
    const list = onlyCompletedUploads(weimei);
    expect(pickInitialDataSourceId(list, false)).toBe(ALL_DATA_SOURCE_ID);
    // 阴性对照: 旧默认规则会选最新一份上传 —— 修复前的行为
    expect(pickDefaultDataSource(list)?.id).toBe(5335);
    // 不过滤状态时, 旧规则直接选中保存失败的那份 (实测第 1、2 条的根因)
    expect(pickDefaultDataSource(weimei)?.id).toBe(5331);
  });

  it('restaurant tenant keeps the #8 default-file rule', () => {
    const list = onlyCompletedUploads(weimei);
    expect(pickInitialDataSourceId(list, true)).toBe(5335);
  });

  it('全部数据 sentinel is falsy so requests carry no uploadId', () => {
    expect(ALL_DATA_SOURCE_ID || undefined).toBeUndefined();
  });

  it('AIQuery wires the 全部数据 option, status filter, upload scope and data_source event', () => {
    const src = readFileSync(resolve(__dirname, '../AIQuery.vue'), 'utf-8');
    expect(src).toContain('data-testid="ds-option-all"');
    expect(src).toContain('deduplicateUploads(onlyCompletedUploads(res.data))');
    expect(src).toContain('pickInitialDataSourceId(deduped, isRestaurantTenant.value)');
    // 「全部数据」仍走上传数据问答, 不被 Java 意图层吃掉
    expect(src).toMatch(/const hasAnalysisData = hasUploadScope\.value/);
    expect(src).toContain('onDataSource(info');
    const api = readFileSync(resolve(__dirname, '../../../api/smartbi/analysis.ts'), 'utf-8');
    expect(api).toContain("case 'data_source':");
  });
});
