import { describe, expect, it } from 'vitest';
import { markdownToPlainSummary } from '../markdownPlain';

describe('markdownToPlainSummary', () => {
  it('去掉标题、列表、加粗、引用、链接符号，只留正文', () => {
    const md = '# 经营日报\n\n## 销售\n- **营收** 12 万\n- 毛利 [详情](http://x/y)\n> 注意：应收偏高';
    const out = markdownToPlainSummary(md, 200);
    expect(out).toBe('经营日报 销售 营收 12 万 毛利 详情 注意：应收偏高');
    expect(out).not.toMatch(/[#*>\[\]]/);
  });
  it('表格竖线与分隔行不外露', () => {
    const out = markdownToPlainSummary('| 指标 | 数值 |\n|---|---|\n| 营收 | 12 |', 200);
    expect(out).toBe('指标 数值 营收 12');
  });
  it('超长按字数截断并补省略号；空值返回空串', () => {
    expect(markdownToPlainSummary('字'.repeat(100), 10)).toBe('字'.repeat(10) + '…');
    expect(markdownToPlainSummary(undefined)).toBe('');
  });
});
