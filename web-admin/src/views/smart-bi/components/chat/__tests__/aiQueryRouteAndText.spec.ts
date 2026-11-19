import { describe, it, expect } from 'vitest';
import { chooseAiQueryRoute } from '../aiQueryRoute';
import { sanitizeFactoryAiText } from '../factoryAiText';

describe('chooseAiQueryRoute (问题 51)', () => {
  it('咨询页 + 已选数据源 → 上传数据问答', () => {
    expect(chooseAiQueryRoute({ tab: 'consult', hasAnyWrite: true, hasAnalysisData: true })).toBe('upload-analysis');
    expect(chooseAiQueryRoute({ tab: 'consult', hasAnyWrite: false, hasAnalysisData: true })).toBe('upload-analysis');
  });
  it('没有数据源 → Java 意图层 (阴性对照)', () => {
    expect(chooseAiQueryRoute({ tab: 'consult', hasAnyWrite: true, hasAnalysisData: false })).toBe('java-intent');
  });
  it('操作页 (有写权限) → Java 意图层, 写操作确认链不变', () => {
    expect(chooseAiQueryRoute({ tab: 'operate', hasAnyWrite: true, hasAnalysisData: true })).toBe('java-intent');
  });
  it('只读账号即使 tab 是 operate 也按咨询处理', () => {
    expect(chooseAiQueryRoute({ tab: 'operate', hasAnyWrite: false, hasAnalysisData: true })).toBe('upload-analysis');
  });
});

describe('sanitizeFactoryAiText (对话标准 V3)', () => {
  it('E21 原文: indicator_code、indicator_codes 不露英文', () => {
    const out = sanitizeFactoryAiText('还需要以下信息：indicator_code、indicator_codes');
    expect(out).toBe('还需要以下信息：指标');
    expect(out).not.toMatch(/[A-Za-z]_[A-Za-z]/);
  });
  it('未知英文标识整段去掉, 标点收拾干净', () => {
    expect(sanitizeFactoryAiText('缺少参数：foo_bar_id，请补充。')).toBe('缺少参数：请补充。');
    expect(sanitizeFactoryAiText('状态 IN_PROGRESS 已更新')).not.toContain('IN_PROGRESS');
  });
  it('中文、数字、金额、普通英文词原样 (阴性对照)', () => {
    const s = '10月华北演示客户买得最多，¥16,927.44，占 34.8%；Top 3 SKU 见图。';
    expect(sanitizeFactoryAiText(s)).toBe(s);
  });
});
