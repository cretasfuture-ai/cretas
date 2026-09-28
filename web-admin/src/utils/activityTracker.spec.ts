import { describe, expect, it } from 'vitest';
import { resolveControlAction } from './activityTracker';

describe('activity control naming', () => {
  it('records stable business verbs and explicit IDs without copying arbitrary text', () => {
    const button = document.createElement('button');
    button.textContent = '提交申请';
    expect(resolveControlAction(button)).toBe('control.submit-request');
    expect(resolveControlAction(button, 'disposal.submit')).toBe('disposal.submit');

    button.textContent = '删除 张三的批次';
    expect(resolveControlAction(button)).toBe('control.button');
    expect(resolveControlAction(button, 'bad label')).toBe('control.button');
  });
});
