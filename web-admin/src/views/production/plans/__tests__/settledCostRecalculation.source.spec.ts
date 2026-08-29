import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(process.cwd(), 'src/views/production/plans/list.vue'), 'utf8');
const api = readFileSync(resolve(process.cwd(), 'src/api/productionPlan.ts'), 'utf8');

describe('已结单计划成本重算入口', () => {
  it('只在已入库、可看价格且有写权限时开放', () => {
    expect(source).toContain('canWrite.value && canViewPrice.value');
    expect(source).toContain("['POSTED', 'POSTED_WITH_TOLERANCE'].includes");
    expect(source).toContain('v-if="canRecalculateSettledCost(row)"');
  });

  it('请求体不接受客户端成本数字', () => {
    expect(api).toMatch(/post<ProductionCostRecalculationResult>\(\s*`\/\$\{factoryId\}\/production-plans\/\$\{planId\}\/recalculate-settled-costs`,\s*\)/);
    expect(source).toContain('不会使用手填成品成本');
  });
});
