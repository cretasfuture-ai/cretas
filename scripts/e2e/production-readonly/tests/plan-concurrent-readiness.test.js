'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  assertReadinessPayload,
  matchesExpectedReadiness,
  validatePlanReadinessOptions,
} = require('../scenarios/plan-concurrent-readiness-readonly');

function validPayload(overrides = {}) {
  return {
    success: true,
    data: {
      planId: 'plan-qa-1',
      planNumber: 'QA-DAG19-001',
      planStatus: 'PENDING',
      demandThroughDate: null,
      observedAt: '2026-11-07T00:00:00Z',
      cohortBasis: 'DATE_AND_OWNERSHIP_POOL',
      consideredPlanCount: 2,
      unverifiedPlanCount: 0,
      notes: [],
      material: {
        status: 'VERIFIED',
        reason: 'OK',
        lowerPercent: 60,
        upperPercent: 100,
        items: [{
          materialTypeId: 'raw-1', materialName: 'QA原料', unit: 'kg', required: 75,
          consumed: 0, ownHeldReserved: 0, otherUnoccupied: 0, stockFree: 45,
          lowerPercent: 60, upperPercent: 60,
        }],
      },
      progress: {
        status: 'UNVERIFIED', basis: 'FINISHED_GOODS_RECEIVED', plannedQuantity: 100,
        receivedQuantity: null, remainingQuantity: null, completionPercent: null,
        unit: 'kg', reason: 'MISSING_FINISHED_GOODS',
      },
      advisoryOnly: true,
      ...overrides,
    },
  };
}

test('accepts numeric readiness and preserves unknown progress as null', () => {
  const data = assertReadinessPayload(validPayload(), 'plan-qa-1');
  assert.equal(data.material.lowerPercent, 60);
  assert.equal(data.progress.completionPercent, null);
});

test('accepts full database-sized display names while preserving bounded identities', () => {
  const payload = validPayload();
  payload.data.material.items[0].materialName = '🧀'.repeat(255);
  assert.equal(assertReadinessPayload(payload, 'plan-qa-1').material.items[0].materialName.length, 510);
  payload.data.material.items[0].materialName = 'x'.repeat(513);
  assert.throws(() => assertReadinessPayload(payload, 'plan-qa-1'));
});

test('accepts explicit overproduction without clipping or treating it as a material percentage', () => {
  const payload = validPayload();
  payload.data.progress = { ...payload.data.progress, status: 'VERIFIED', receivedQuantity: 120,
    remainingQuantity: 0, completionPercent: 150 };
  assert.equal(assertReadinessPayload(payload, 'plan-qa-1').progress.completionPercent, 150);
  assert.equal(validatePlanReadinessOptions({ syntheticQa: true,
    plans: [{ planId: 'plan-qa-1', planNumber: 'QA-DAG19-001', expectedProgressPercent: 150 }] }).valid, true);
});

test('rejects an unrelated plan and an expected-value mismatch', () => {
  const option = { planId: 'plan-qa-1', planNumber: 'QA-DAG19-001', expectedLowerPercent: 60, expectedUpperPercent: 100 };
  const data = assertReadinessPayload(validPayload(), 'plan-qa-1');
  assert.equal(matchesExpectedReadiness(data, option), true);
  assert.equal(matchesExpectedReadiness({ ...data, planId: 'other-plan' }, option), false);
  assert.equal(matchesExpectedReadiness({ ...data, material: { ...data.material, lowerPercent: 61 } }, option), false);
  const unrelated = validPayload();
  unrelated.data = { ...unrelated.data, planId: 'other-plan' };
  assert.throws(() => assertReadinessPayload(unrelated, 'plan-qa-1'));
});

test('rejects malformed unknown values instead of turning them into zero', () => {
  assert.throws(() => assertReadinessPayload(validPayload({
    material: { ...validPayload().data.material, lowerPercent: '0' },
  }), 'plan-qa-1'));
  assert.throws(() => assertReadinessPayload(validPayload({
    progress: { ...validPayload().data.progress, status: 'UNVERIFIED', completionPercent: 0 },
  }), 'plan-qa-1'));
});

test('requires explicit fixed synthetic QA options and bounds the case count', () => {
  assert.equal(validatePlanReadinessOptions({ syntheticQa: false, plans: [] }).valid, false);
  assert.equal(validatePlanReadinessOptions({ syntheticQa: true, plans: [
    { planId: 'plan-qa-1', planNumber: 'QA-DAG19-001', expectedMaterialStatus: 'VERIFIED', expectedLowerPercent: 60, expectedUpperPercent: 100, expectedProgressPercent: null },
  ] }).valid, true);
  assert.equal(validatePlanReadinessOptions({ syntheticQa: true, plans: [
    { planId: 'same', planNumber: 'QA-1' }, { planId: 'same', planNumber: 'QA-2' },
  ] }).valid, false);
});
