import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { FACT_STATUS_LABEL, describeFactSource } from '@/api/factoryConfirmedFacts';

const read = (rel: string) => readFileSync(resolve(process.cwd(), rel), 'utf8');

describe('已确认资料: 来源选择与版本历史页', () => {
  it('来源说明读得懂, 不丢来源', () => {
    expect(describeFactSource('USER_ANSWER', 'conversation:abcdef1234567')).toContain('abcdef12');
    expect(describeFactSource('OTHER', 'x')).toBe('OTHER：x');
    expect(Object.keys(FACT_STATUS_LABEL).sort()).toEqual(['CONFIRMED', 'CONFLICT_PENDING', 'PENDING_CONFIRM', 'REJECTED', 'SUPERSEDED'])
    expect(FACT_STATUS_LABEL.PENDING_CONFIRM).toBe('待确认');
  });

  it('页面逐条给两个选项, 并真的调用接口', () => {
    const page = read('src/views/factory-readiness/facts.vue');
    expect(page).toContain("choose(item.field, 'KEEP_CONFIRMED')");
    expect(page).toContain("choose(item.field, 'USE_NEW')");
    expect(page).toContain('resolveFactConflict(factoryId.value, field, choice)');
    expect(read('src/api/factoryConfirmedFacts.ts')).toContain('/factory-build/facts');
  });

  it('路由已注册且就绪页有入口', () => {
    expect(read('src/router/index.ts')).toContain("path: 'factory-readiness/facts'");
    expect(read('src/views/factory-readiness/index.vue')).toContain('/factory-readiness/facts');
  });
});
