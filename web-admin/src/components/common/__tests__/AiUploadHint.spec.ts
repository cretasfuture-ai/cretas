// web-admin/src/components/common/__tests__/AiUploadHint.spec.ts
import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import AiUploadHint from '../AiUploadHint.vue';

describe('AiUploadHint', () => {
  it('默认是图片的说法', () => {
    expect(mount(AiUploadHint).text()).toContain('图片会发给 AI 识别');
  });

  it('PDF 入口说 PDF 文件', () => {
    expect(mount(AiUploadHint, { props: { kind: 'pdf' } }).text()).toContain('PDF 文件会发给 AI 识别');
  });
});

/**
 * 上传后图片 / PDF 会交给 AI 识别的入口 (2026-10-27 盘点; 餐饮已废弃的送货单入口除外)。
 * 新增这类入口时把文件加进来; 去掉提示会在这里红。
 */
const AI_UPLOAD_ENTRIES: Record<string, string> = {
  'views/finance/invoices/list.vue': '<AiUploadHint kind="pdf" />', // 开具发票: 发票 PDF → OCR 核对金额
  'views/sales/orders/detail.vue': '<AiUploadHint kind="pdf" />', // 订单详情里开票: 同上
  'views/warehouse/storage-locations/index.vue': '<AiUploadHint', // 上传底图 → AI 识别库位
};

/**
 * 去掉模板注释 `<!-- -->` 与整行的 `//` 注释 (只留在注释里的提示不算)。
 * 不剥 `/* *\/`: 源码字符串里常有 `/*` (路径通配等), 正则剥会误伤真代码。
 */
const withoutComments = (src: string) => src
  .replace(/<!--[\s\S]*?-->/g, '').replace(/^[ \t]*\/\/[^\n]*$/gm, '');

describe('AI 识别上传入口都带提示', () => {
  for (const [file, marker] of Object.entries(AI_UPLOAD_ENTRIES)) {
    it(file, () => {
      const src = withoutComments(readFileSync(resolve(__dirname, '../../..', file), 'utf-8'));
      expect(src).toContain(marker);
      expect(src).toContain("import AiUploadHint from '@/components/common/AiUploadHint.vue'");
    });
  }
});
