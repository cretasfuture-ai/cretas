import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '../detail.vue'), 'utf8');

function functionBody(signature: string, nextSignature: string): string {
  const start = source.indexOf(signature);
  const end = source.indexOf(nextSignature, start + signature.length);
  expect(start, `missing ${signature}`).toBeGreaterThan(-1);
  expect(end, `missing ${nextSignature}`).toBeGreaterThan(start);
  return source.slice(start, end);
}

describe('sales delivery receipt-proof flow', () => {
  it('opens a real receipt-proof form instead of directly confirming delivery', () => {
    expect(source).toContain('填写凭证并签收');
    expect(source).toContain('客户签收 — ${signatureTarget?.deliveryNumber');
    expect(source).toContain('填写客户现场实际签收人姓名');
    expect(source).toContain('entity-type="SHIPMENT"');
    expect(source).toContain('file-category="SIGNATURE"');
    expect(source).toContain('至少上传 1 张真实签收凭证');
  });

  it('requires signer, a completed photo upload, and no failed upload before submit', () => {
    const readiness = functionBody(
      'const signatureSubmissionReady = computed',
      'function existingSignaturePhotoUrls',
    );
    expect(readiness).toContain('signatureForm.value.signedByName.trim()');
    expect(readiness).toContain('signaturePhotoUrls.value.length > 0');
    expect(readiness).toContain('signatureQueue.value.pending === 0');
    expect(readiness).toContain('signatureQueue.value.failed === 0');
    expect(source).toContain(':disabled="!signatureSubmissionReady"');
  });

  it('persists receipt proof before the status transition and keeps a recoverable partial-failure message', () => {
    const submit = functionBody(
      'async function submitDeliveredWithSignature()',
      '// 批次分配 (P0-13)',
    );
    const signatureCall = submit.indexOf('/signature`');
    const deliveredCall = submit.indexOf('/delivered`');
    expect(signatureCall).toBeGreaterThan(-1);
    expect(deliveredCall).toBeGreaterThan(signatureCall);
    expect(submit).toContain('签收凭证已保存，但签收状态未更新');
    expect(submit).toContain('duration: 0');
    expect(submit).toContain('showClose: true');
  });
});
