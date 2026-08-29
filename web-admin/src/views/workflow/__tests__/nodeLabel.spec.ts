import { describe, expect, it } from 'vitest';
import { approverHint, currentNodeText } from '../nodeLabel';

describe('OA 当前节点展示', () => {
  it('纯英文配置节点改用授权角色中文名', () => {
    const row = {
      currentNodeLabel: 'LIUSHANMEN admin approval',
      approverRoles: ['factory_super_admin'],
    };

    expect(currentNodeText(row)).toBe('工厂总管理员');
    expect(approverHint(row)).toBe('');
  });

  it('已有中文业务节点名保持原样并补充不同的授权角色', () => {
    const row = {
      currentNodeLabel: '财务审批',
      approverRoles: ['finance_manager'],
    };

    expect(currentNodeText(row)).toBe('财务审批');
    expect(approverHint(row)).toBe('财务主管');
  });

  it('没有节点名和授权角色时明确显示占位符', () => {
    expect(currentNodeText({})).toBe('—');
  });
});
