/**
 * 解除批次的来源个体挂载 (#1844 终审 L-d)。
 *
 * 后端: POST /api/mobile/{factoryId}/material-batches/{batchId}/source-individual/unlink  body { reason }
 * —— 原因必填 (≤255), 后端同一事务写操作日志 (原个体 id + 原因)。
 * ⚠️ 编辑批次 (PUT) 里 sourceIndividualId 传 null = 不改、传空白 = 400; 解除只能走这条入口,
 *    ⛔ 不要试图在编辑提交体里塞空串来"清掉"个体。
 */

/** 与后端 UnlinkSourceIndividualRequest.reason 的 @Size(max=255) 一致 —— 超长后端会 400。 */
export const UNLINK_REASON_MAX_LENGTH = 255;

/** ElMessageBox.prompt 的 inputValidator: 通过返回 true, 否则返回给用户看的那句话。 */
export function validateUnlinkReason(raw: string | null | undefined): true | string {
  const reason = (raw ?? '').trim();
  if (!reason) return '请填写解除原因';
  if (reason.length > UNLINK_REASON_MAX_LENGTH) {
    return `原因最多 ${UNLINK_REASON_MAX_LENGTH} 字（当前 ${reason.length} 字）`;
  }
  return true;
}

/** axios baseURL 已是 /api/mobile, 路径从 /{factoryId} 开始。 */
export function unlinkSourceIndividualPath(factoryId: string, batchId: string): string {
  return `/${factoryId}/material-batches/${encodeURIComponent(batchId)}/source-individual/unlink`;
}
