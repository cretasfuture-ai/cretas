import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { canonicalDigest, PackageValidationError, validatePackage } from './validate-package.mjs';

const here = new URL('.', import.meta.url);
const fixture = async (name) => JSON.parse(await readFile(new URL(`./fixtures/${name}.json`, here), 'utf8'));
const rejectsWith = async (name, text) => {
  await assert.rejects(async () => validatePackage(await fixture(name)), (error) => {
    assert.ok(error instanceof PackageValidationError);
    assert.match(error.message, new RegExp(text));
    return true;
  });
};

test('valid QA fixture validates structurally and remains explicitly test-only', async () => {
  const result = validatePackage(await fixture('valid-qa'));
  assert.equal(result.testOnly, true);
  assert.equal(result.status, 'CONFIRMED');
  assert.match(result.canonicalDigest, /^[0-9a-f]{64}$/);
  assert.equal(result.payload.overwrite, false);
  assert.equal(result.payload.suggestedData.provisionDefaultUsers, false);
});

test('UNCONFIRMED package is refused by default and emit-preview is explicit', async () => {
  await rejectsWith('missing-facts', 'UNCONFIRMED');
  const result = validatePackage(await fixture('valid-qa'), { emitPreview: true });
  assert.equal(result.status, 'OFFLINE_PREVIEW_UNVERIFIED');
  assert.ok(result.unresolved.some((item) => item.includes('route topology')));
  assert.equal(result.canonicalDigest, canonicalDigest(result));
});

test('canonical digest sorts object keys but preserves array order', async () => {
  const left = { b: 2, a: { y: true, x: 1 }, list: [{ z: 3, a: 1 }] };
  const right = { list: [{ a: 1, z: 3 }], a: { x: 1, y: true }, b: 2 };
  assert.equal(canonicalDigest(left), canonicalDigest(right));
  const reordered = { ...right, list: [{ a: 1, z: 3 }, { a: 2 }] };
  assert.notEqual(canonicalDigest(right), canonicalDigest(reordered));
});

test('duplicate codes and orphan route references are rejected', async () => {
  await rejectsWith('duplicate-code', 'duplicate code');
  await rejectsWith('orphan-reference', 'orphan');
});

test('unknown module and unsafe defaults are rejected without claiming readiness', async () => {
  await rejectsWith('unknown-module', 'unknown offline module id');
  await rejectsWith('unsafe-defaults', 'must be explicitly false');
});

test('custom field bounds and sensitive values are rejected', async () => {
  await rejectsWith('invalid-minmax', 'min must be <= max');
  await rejectsWith('nonnumeric-bounds', 'must be numeric');
  await rejectsWith('secrets', 'sensitive/business source field is forbidden');
});

test('malformed and unconfirmed evidence references are rejected', async () => {
  await rejectsWith('malformed-evidence', 'requires status CONFIRMED and non-empty sourceRef');
});

test('source business IDs are rejected while route node IDs remain valid', async () => {
  await rejectsWith('source-ids', 'source business identifier is forbidden');
  const result = validatePackage(await fixture('valid-qa'));
  assert.equal(result.status, 'CONFIRMED');
});

test('missing payload is reported as a structured package validation error', async () => {
  await rejectsWith('missing-payload', 'payload must be an object');
});

test('legacy module flags are boolean but remain explicitly unverified', async () => {
  const result = validatePackage(await fixture('valid-qa'));
  assert.ok(result.unresolved.some((item) => item.includes('legacy flag semantics')));
  const invalidLegacy = await fixture('valid-qa');
  invalidLegacy.payload.suggestedData.modules.enableProduction = 'true';
  await rejectsWithObject(invalidLegacy, 'legacy module flag must be boolean');
  const withoutEvidence = await fixture('valid-qa');
  delete withoutEvidence.evidenceByJsonPointer['/payload'];
  delete withoutEvidence.evidenceByJsonPointer['/payload/suggestedData'];
  let thrown;
  try {
    validatePackage(withoutEvidence, { emitPreview: true });
  } catch (error) {
    thrown = error;
  }
  assert.ok(thrown instanceof PackageValidationError);
  assert.ok(thrown.errors.includes('/payload/suggestedData/modules/enableProduction: confirmed fact has no source reference'));
});

const rejectsWithObject = async (document, text) => {
  let thrown;
  try {
    validatePackage(document);
  } catch (error) {
    thrown = error;
  }
  assert.ok(thrown instanceof PackageValidationError);
  assert.ok(thrown.message.includes(text));
};

test('nested canonicalDigest is content while only the root fingerprint is excluded', async () => {
  assert.notEqual(canonicalDigest({ nested: { canonicalDigest: 'a' } }), canonicalDigest({ nested: { canonicalDigest: 'b' } }));
});

test('validator has no network client or production write path', async () => {
  const source = await readFile(fileURLToPath(new URL('./validate-package.mjs', import.meta.url)), 'utf8');
  assert.doesNotMatch(source, /\b(fetch|axios|http\.request|https?:\/\/)/);
  assert.doesNotMatch(source, /\b(?:POST|PUT|DELETE)\b/);
});
