/** Read issuance time only to delay sending; the server still validates every JWT claim. */
export function tokenActivationDelayMs(token: string, now = Date.now()): number {
  const payload: unknown = JSON.parse(atob((token.split('.')[1] || '').replace(/-/g, '+').replace(/_/g, '/')));
  const issuedAt = payload && typeof payload === 'object' && 'iat' in payload ? payload.iat : undefined;
  if (typeof issuedAt !== 'number' || !Number.isSafeInteger(issuedAt) || issuedAt < 0) throw new Error('测试登录会话缺少有效签发时间');
  const wait = issuedAt * 1000 - now;
  if (wait > 5000) throw new Error('测试服务时钟偏差超过5秒，请核对服务时间');
  return wait > 0 ? Math.min(5000, Math.ceil(wait) + 25) : 0;
}
