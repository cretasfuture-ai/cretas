/**
 * SKU 净含量提交前校验（纯函数，便于断言）。
 *
 * 后端口径：非半成品 SKU 的净含量必须大于 0（PRODUCT_NET_CONTENT_INVALID）。
 * 客户端先拦，避免用户填完整张表才在服务端报错。
 * 半成品不要求净含量。
 *
 * @returns 需要提示给用户的文案；通过则返回 null。
 */
export function netContentGateMessage(
  amount: unknown,
  isSemiFinished: boolean,
): string | null {
  if (isSemiFinished) return null;
  if (amount === null || amount === undefined || amount === '') {
    return '请填写大于 0 的净含量';
  }
  const value = Number(amount);
  if (!Number.isFinite(value) || value <= 0) {
    return '请填写大于 0 的净含量';
  }
  return null;
}
