'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { resolveCases, selectEmployee } = require('../scenarios/platform-factory-access-readonly');
test('ordinary and protected controls require explicit, exact employee eligibility', () => {
  const directory = [{ id: 1, username: 'staff_a', active: true, protectedRole: false }, { id: 2, username: 'owner_a', active: true, protectedRole: true }];
  assert.equal(selectEmployee(directory, { username: 'staff_a', protectedRole: false }).id, 1);
  assert.equal(selectEmployee(directory, { username: 'owner_a', protectedRole: true }).id, 2);
  assert.throws(() => selectEmployee(directory, { username: 'staff_a', protectedRole: true }));
  assert.throws(() => selectEmployee(directory, { username: 'missing', protectedRole: false }));
  assert.throws(() => selectEmployee([...directory, directory[0]], { username: 'staff_a', protectedRole: false }));
  assert.throws(() => selectEmployee([{ ...directory[0], active: false }], { username: 'staff_a', protectedRole: false }));
});
test('missing target or protection expectation cannot silently skip an acceptance case', () => {
  const ordinary = { factoryId: 'F1', username: 'staff_a', protectedRole: false };
  const protectedCase = { factoryId: 'F2', username: 'owner_a', protectedRole: true };
  assert.deepEqual(resolveCases({ factoryAccessCases: [ordinary, protectedCase] }), [ordinary, protectedCase]);
  assert.throws(() => resolveCases({ factoryAccessFactoryId: 'F1' }));
  assert.throws(() => resolveCases({ factoryAccessCases: [] }));
  assert.throws(() => resolveCases({ factoryAccessCases: [protectedCase] }));
  assert.throws(() => resolveCases({ factoryAccessCases: [ordinary, { ...protectedCase, factoryId: 'F1' }] }));
  assert.throws(() => resolveCases({ factoryAccessCases: [ordinary, { ...protectedCase, protectedRole: false }] }));
  assert.throws(() => resolveCases({ factoryAccessCases: [{ ...ordinary, username: '' }, protectedCase] }));
  assert.throws(() => resolveCases({ factoryAccessCases: [{ factoryId: 'F1' }] }));
  assert.throws(() => resolveCases({ factoryAccessCases: [{ factoryId: '../F1', protectedRole: false }] }));
});
