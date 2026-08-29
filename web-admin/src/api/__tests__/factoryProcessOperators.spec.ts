import { describe, expect, it } from 'vitest';
import { mergeProcessOperatorUsers } from '../factory';

describe('工序指派候选人角色合并', () => {
  it('同时保留操作员和报工员', () => {
    const result = mergeProcessOperatorUsers([
      [{ id: 1649, username: 'lsm011', roleCode: 'operator', isActive: true }],
      [{ id: 1654, username: 'baogong002', roleCode: 'yield_operator', isActive: true }],
    ]);

    expect(result.map((user) => user.id)).toEqual([1649, 1654]);
  });

  it('失效、其他角色和重复账号不进入候选', () => {
    const result = mergeProcessOperatorUsers([
      [
        { id: 1649, username: 'lsm011', roleCode: 'operator', isActive: true },
        { id: 1700, username: 'disabled', roleCode: 'operator', isActive: false },
      ],
      [
        { id: 1649, username: 'lsm011', roleCode: 'yield_operator', isActive: true },
        { id: 1701, username: 'manager', roleCode: 'factory_admin', isActive: true },
      ],
    ]);

    expect(result).toEqual([
      { id: 1649, username: 'lsm011', roleCode: 'yield_operator', isActive: true },
    ]);
  });
});
