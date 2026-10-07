/**
 * H3 的同一份预览数据: 改前 / 改后确认卡都用它数行。
 * 覆盖: 中文键、已知英文键 (有 label)、未知英文键 (无 label)、系统键、对象值、null 值。
 */
export const FLAT_PREVIEW_DATA: Record<string, unknown> = {
  今天的打卡: '未打卡',
  剩余数量: 12,
  batchNumber: 'B-001',
  quantity: 200,
  notes: null,
  unit: 'kg',
  supplierName: { 名称: '甲供应商', code: 'X1', contactPhone: '123' },
  // 未知英文键 (没有 label): web 照原样显示键名, 一行都不藏 (H3 优先于 V3)
  plannedQty: 5,
  Batch_Weird: 'x',
  productId: 'eb0aa47b-1111-2222-3333-444455556666',
  // 系统键
  factoryId: 'F001',
  userInput: '原话',
};

export const DIFF_CURRENT: Record<string, unknown> = { 数量: '320kg', batchNumber: '#10805', Weird_key: 'a' };
export const DIFF_NEXT: Record<string, unknown> = { 数量: '520kg', batchNumber: '#10806', Weird_key: 'b' };

export function flatResponse(intentName: string | null = '原料入库') {
  return {
    status: 'PREVIEW',
    intentRecognized: true,
    intentCode: 'MATERIAL_INBOUND',
    ...(intentName === null ? {} : { intentName }),
    message: '{"x":1}',
    formattedText: '',
    confirmableAction: {
      confirmToken: 'tok-h3',
      commandDigest: 'digest-h3',
      expiresAt: '2026-07-24T00:05:00Z',
      expiresInSeconds: 300,
      previewData: FLAT_PREVIEW_DATA,
    },
  };
}

export function diffResponse() {
  return {
    status: 'PREVIEW',
    intentRecognized: true,
    intentCode: 'MATERIAL_BATCH_UPDATE',
    intentName: '修改批次数量',
    message: '{}',
    formattedText: '',
    confirmableAction: {
      confirmToken: 'tok-h3d',
      commandDigest: 'd'.repeat(64),
      expiresAt: '2026-07-24T00:05:00Z',
      expiresInSeconds: 300,
      currentValues: DIFF_CURRENT,
      newValues: DIFF_NEXT,
      impactSummary: '此操作将修改现有数据，确认后立即生效。',
      actionType: 'UPDATE',
      riskLevel: 'MEDIUM',
    },
  };
}
