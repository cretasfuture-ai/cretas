import fs from 'fs';
import path from 'path';

const srcRoot = path.resolve(__dirname, '../../../');
const read = (relativePath: string) => fs.readFileSync(path.join(srcRoot, relativePath), 'utf8');

describe('标签拍检工作流文案合同', () => {
  const createSource = read('screens/quality-inspector/QILabelQcCreateScreen.tsx');
  const submittedSource = read('screens/quality-inspector/QILabelQcSubmittedScreen.tsx');

  it('新建页准确说明疑点或无法判断才进入人工审核', () => {
    expect(createSource).toContain('AI 只做疑似初筛');
    expect(createSource).toContain('发现疑点或照片无法判断时进入人工审核');
    expect(createSource).toContain('AI 自动完成不等同于人工训练真值');
    expect(createSource).not.toContain('所有照片都会进入人工审核');
  });

  it('提交状态页保留 AUTO_PASSED 与人工审核的事实边界', () => {
    expect(submittedSource).toContain('AUTO_PASSED');
    expect(submittedSource).toContain('NEEDS_REVIEW');
    expect(submittedSource).toContain('AI 初筛没有发现需要人工处理的疑点');
    expect(submittedSource).toContain('最终结果由质量审核员确认');
    expect(submittedSource).toContain('AI 自动完成不等同于人工训练真值');
  });
});
