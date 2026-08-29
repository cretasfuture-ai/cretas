import fs from 'fs';
import path from 'path';

import { ROLE_METADATA } from '../../../types/auth';
import { ROLE_OPTIONS } from '../../../types/hrNavigation';

const sourceRoot = path.resolve(__dirname, '../../../');
const read = (relativePath: string) =>
  fs.readFileSync(path.resolve(sourceRoot, relativePath), 'utf8');

describe('个人中心角色文案', () => {
  it('阳性对照: 出成率录入员在角色元数据和 HR 选项中使用同一中文名', () => {
    expect(ROLE_METADATA.yield_operator?.displayName).toBe('出成率录入员');
    expect(ROLE_OPTIONS.find(({ value }) => value === 'yield_operator')?.label).toBe('出成率录入员');
  });

  it('共享个人中心从角色元数据取显示名，不泄漏内部角色码', () => {
    const source = read('screens/profile/ProfileScreen.tsx');

    expect(source).toContain("import { ROLE_METADATA } from '../../types/auth';");
    expect(source).toContain("ROLE_METADATA[role ?? '']?.displayName || '未知角色'");
    expect(source).not.toContain('return roleMap[role');
    expect(source).not.toContain("|| role || '未知角色'");
  });

  it('出成率录入员与普通操作员都不显示数据导出和开发测试入口', () => {
    const source = read('screens/profile/ProfileScreen.tsx');

    expect(source).toContain(
      "const isOperator = roleCode === 'operator' || roleCode === 'yield_operator';"
    );
    expect(source.match(/\{!isOperator && \(/g)).toHaveLength(3);
    expect(source).toContain('数据导出 — A1: hidden for operators');
    expect(source).toContain('服务器连接测试 — A1: hidden for operators');
    expect(source).toContain('意图执行测试 — A1: hidden for operators');
  });
});
