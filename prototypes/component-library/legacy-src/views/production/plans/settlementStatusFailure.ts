/**
 * 生产计划列表批量拉「结单状态」失败时，这一条到底是**合法空态**还是**真失败**。
 *
 * ## 为什么需要它（2026-08-19 客户实际反馈）
 *
 * 生产计划列表会给每个 `COMPLETED` 计划调一次
 * `GET /production-plans/{planId}/settlement`。后端对**没有 `production_settlements` 行**的
 * 计划返回 404「该生产计划尚未结单, 不能确认入库」。
 *
 * 但那不总是错误 —— **存货生产（SAFETY_STOCK）走的是「小结」**，成品在小结时就入库了，
 * `production_settlements` 里本来就没有行。
 *
 * 实际长相：客户结单成功之后，右上角一直弹红；换到别的页面回来还弹；
 * 而**筛「已结单」就不弹**。因为弹的是列表里**另一条过往计划**
 * （`PLAN-1786724332978`，存货生产），跟他刚做的操作毫无关系。
 *
 * ▎**一个合法状态被标成了错误，而且是全局红条。**
 * 本仓形态 A¹¹：算「缺了什么」之前，先问「这里的空是不是一种合法状态」。
 *
 * ## 判据
 *
 * 对**这个接口**而言 `HTTP 404` 精确等于「这个计划没有仓库确认单」。
 * ⚠️ 拦截器把后端的 errorCode 换成了 `'NOT_FOUND'`（`new ApiError(msg, 'NOT_FOUND', 404)`），
 * 所以这里认 `status`，⛔ 不认 code —— 认 code 会永远匹配不上。
 */

/** 这一条为什么没拿到状态。 */
export type SettlementFailure =
  /** 合法空态：该计划没有仓库确认单（存货生产走小结）。⛔ 不是错误。 */
  | { kind: 'absent' }
  /** 真失败：网络、权限、5xx…… 该说出来。 */
  | { kind: 'error'; message: string };

const DEFAULT_MESSAGE = '入库状态加载失败，请刷新后重试';

/**
 * @param reason `Promise.allSettled` 的 rejection（通常是 `ApiError`）
 */
/**
 * 后端如实回答「这张计划没有仓库确认单」时的载荷判据。
 *
 * <p>2026-08-19 之后读接口不再抛 404，改成 200 + `settled=false`。
 * ⚠️ 判据必须是 <b>`=== false`</b>：老后端根本不返回这个字段（`undefined`），
 * 写成 `!data.settled` 会把**每一条**都判成「没有结单」——
 * 那是本仓「兜底默认值把『我不知道』翻译成『是 0』」的同一个坑。
 */
export function isNotSettledPayload(data: unknown): boolean {
  return !!data
    && typeof data === 'object'
    && (data as { settled?: unknown }).settled === false;
}

export function classifySettlementFailure(reason: unknown): SettlementFailure {
  const err = reason as { status?: unknown; message?: unknown } | null | undefined;
  if (err && typeof err === 'object' && err.status === 404) {
    return { kind: 'absent' };
  }
  const message = err && typeof err === 'object' && typeof err.message === 'string' && err.message.trim()
    ? err.message
    : DEFAULT_MESSAGE;
  return { kind: 'error', message };
}
