/**
 * LSM-COSTBI 第 9 项: AI 问答的问候语随数据源更新; 工厂租户的快捷问题不出餐饮词。
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  buildWelcomeText,
  FACTORY_QUICK_QUESTIONS,
  quickQuestionsFor,
  RESTAURANT_QUICK_QUESTIONS,
} from '../aiQueryGreeting';

const RESTAURANT_WORDS = /门店|哪家店|菜品|菜|客单价|会员|套餐|营业额|外卖|堂食/;

describe('quickQuestionsFor', () => {
  it.each(['default', 'finance', 'inventory', 'review', 'member'])(
    'factory tenant gets no restaurant words for %s files', (fileDomain) => {
      const qs = quickQuestionsFor('FACTORY', fileDomain);
      expect(qs.length).toBeGreaterThan(0);
      expect(qs.filter(q => RESTAURANT_WORDS.test(q))).toEqual([]);
    });

  it('logistics / unknown domain also uses the factory set', () => {
    expect(quickQuestionsFor('LOGISTICS', 'default')).toEqual(FACTORY_QUICK_QUESTIONS.default);
    expect(quickQuestionsFor(undefined, 'default')).toEqual(FACTORY_QUICK_QUESTIONS.default);
  });

  it('restaurant tenant keeps its own set (positive control)', () => {
    expect(quickQuestionsFor('RESTAURANT', 'default')).toEqual(RESTAURANT_QUICK_QUESTIONS.default);
    expect(quickQuestionsFor('RESTAURANT', 'default').some(q => RESTAURANT_WORDS.test(q))).toBe(true);
  });
});

describe('buildWelcomeText', () => {
  it('names the current data source', () => {
    expect(buildWelcomeText('FACTORY', '数据源：B.xlsx')).toContain('当前数据源：B.xlsx');
    expect(buildWelcomeText('FACTORY', '')).toContain('暂无上传数据');
  });

  it('factory greeting talks about production / cost', () => {
    expect(buildWelcomeText('FACTORY', '')).toContain('生产、成本');
    expect(buildWelcomeText('RESTAURANT', '')).toContain('销售、财务、库存');
  });
});

describe('AIQuery.vue wiring', () => {
  const src = readFileSync(resolve(__dirname, '..', 'AIQuery.vue'), 'utf-8');

  it('rewrites the welcome message when the data source label changes', () => {
    // 之前只在 onMounted 写一次, 切数据源后问候语仍是旧文件名
    expect(src).toMatch(/watch\(dataSourceLabel,[\s\S]{0,200}id === 'welcome'[\s\S]{0,120}buildWelcomeMessage\(\)/);
  });

  it('chooses quick questions by the tenant business domain', () => {
    expect(src).toContain('quickQuestionsFor(authStore.businessDomain, domain)');
    expect(src).not.toContain('QUICK_QUESTIONS_BY_DOMAIN');
  });
});
