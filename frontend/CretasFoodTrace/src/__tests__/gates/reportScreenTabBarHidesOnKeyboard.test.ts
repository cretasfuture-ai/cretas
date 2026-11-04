/**
 * 报工页挂在这两个底部 Tab 下。小米真机实测 (2026-11-04): 键盘弹起时 Tab 栏被顶到键盘上方,
 * 挡住工艺参数输入框下面的「偏高 · 标准 …」提示。质检员导航早就开了这个选项, 这两个漏了。
 */
import fs from 'fs';
import path from 'path';

const read = (file: string) =>
  fs.readFileSync(path.resolve(__dirname, '../../navigation', file), 'utf8');

describe.each(['OperatorNavigator.tsx', 'WorkshopSupervisorTabNavigator.tsx'])('%s', (file) => {
  it('挂着报工页的 Tab 栏在键盘弹起时隐藏', () => {
    expect(read(file)).toContain('tabBarHideOnKeyboard: true');
  });
});
