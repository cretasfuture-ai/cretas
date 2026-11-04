/**
 * 缺口 03 回料比例上限 (按产品): 空 = 不设上限 = 不拦; 后端约定 0 = 清除, 合法域 (0, 100]。
 * 与 ProductTypeServiceImpl.normalizeMaxReworkRatio 同口径, 后端是最终裁决。
 */

/** 表单值 → 请求值。空值发 0: 编辑时把已设的上限清掉必须显式发, 后端 null = 不改。 */
export function reworkRatioRequestValue(v: number | null | undefined): number {
  return v == null || !Number.isFinite(v) ? 0 : v;
}

/** 填了但不在 (0, 100] 内时返回原因; 空值合法 (不设上限)。 */
export function reworkRatioBlockReason(v: number | null | undefined): string | null {
  if (v == null) return null;
  if (!Number.isFinite(v) || v <= 0 || v > 100) {
    return '回料比例上限要在 0 到 100 之间；不想限制就留空';
  }
  return null;
}
