'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { isPlatformSession } = require('../core/ui-login');
test('platform acceptance requires typed login plus matching platform storage, never a factory role alias', () => {
  const login = { actorType: 'PLATFORM_ADMIN', role: 'super_admin', factoryId: null };
  const stored = { userType: 'platform', platformRole: 'super_admin', factoryId: null };
  assert.equal(isPlatformSession(login, stored), true);
  assert.equal(isPlatformSession({ ...login, actorType: 'FACTORY_USER' }, stored), false);
  assert.equal(isPlatformSession({ ...login, factoryId: 'F1' }, stored), false);
  assert.equal(isPlatformSession(login, { ...stored, factoryId: 'F1' }), false);
  assert.equal(isPlatformSession(login, { ...stored, platformRole: 'auditor' }), false);
  assert.equal(isPlatformSession(login, { ...stored, userType: 'factory' }), false);
});
