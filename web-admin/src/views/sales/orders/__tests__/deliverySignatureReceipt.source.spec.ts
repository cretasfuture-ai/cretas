import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, it } from 'vitest';

it('签收后真实页面仍能只读查看该子单的已保存凭证', () => {
  const source = readFileSync(resolve(import.meta.dirname, '../detail.vue'), 'utf8');
  expect(source.includes('查看签收凭证')).toBe(true);
  expect(source.includes('@click="signatureReceiptTarget = row"')).toBe(true);
  expect(source.includes('<DeliverySignatureReceipt')).toBe(true);
  expect(source.includes(':photo-urls="existingSignaturePhotoUrls(signatureReceiptTarget.signaturePhotoUrls)"')).toBe(true);
  expect(source.includes('客户签收：')).toBe(true);
});
