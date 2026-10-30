import { beforeEach, describe, expect, it } from 'vitest';
import {
  factoryViewTokenForRequest,
  isFactoryViewReadonlyPost,
  isFactoryViewRequest,
} from '../platformFactoryContext';

describe('platform factory view request boundary', () => {
  beforeEach(() => sessionStorage.clear());

  it('uses the view token for business requests but not management APIs', () => {
    sessionStorage.setItem('cretas_platform_factory_view', JSON.stringify({
      factoryId: 'F006', factoryName: '六膳门', accessToken: 'short-lived',
      expiresIn: 300, expiresAt: Date.now() + 300_000, readOnly: true,
    }));
    expect(factoryViewTokenForRequest('/api/mobile/F006/dashboard')).toBe('short-lived');
    expect(factoryViewTokenForRequest('/api/platform/factories')).toBeNull();
    expect(isFactoryViewRequest('/api/mobile/F006/dashboard')).toBe(true);
    expect(isFactoryViewRequest('/api/platform/factories')).toBe(false);
  });

  it('fails closed after expiry and only recognizes exact read-only POST suffixes', () => {
    sessionStorage.setItem('cretas_platform_factory_view', JSON.stringify({
      factoryId: 'F006', accessToken: 'expired', expiresIn: 1,
      expiresAt: Date.now() - 1, readOnly: true,
    }));
    expect(() => factoryViewTokenForRequest('/api/mobile/F006/dashboard')).toThrow('凭证已过期');
    expect(isFactoryViewReadonlyPost('/api/mobile/F006/list-summary/inventory')).toBe(false);
    expect(isFactoryViewReadonlyPost('/api/mobile/F006/inventory')).toBe(false);
    expect(isFactoryViewReadonlyPost('/api/mobile/F006/ai-intents/execute', {
      intentCode: 'PURCHASER_WEEKLY_PLAN', userInput: '下周采购什么?',
    })).toBe(false);
    expect(isFactoryViewReadonlyPost('/api/mobile/F006/ai-intents/execute', {
      intentCode: 'ARBITRARY_WRITE', userInput: '删除库存',
    })).toBe(false);
  });
});

