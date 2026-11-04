/**
 * 报工页 SOP 入口的纯函数 (docs/specs/sop-attachments.md)。
 */

/**
 * 真实工序 id 才去拉 SOP。哨兵任务 (两点报工的 `__MATERIAL_INPUT__` / `__FINAL_OUTPUT__`) 没有真实工序，
 * 去请求只会得到一个假的「加载失败」。哨兵 id 一律是 `__XXX__` 形状，这里按形状判，不再复制常量清单
 * (两份清单必然漂；屏幕里的常量与后端 WorkProcessTaskService.SENTINEL_* 仍是它们自己的事)。
 */
export function isRealWorkProcessId(id: string | null | undefined): id is string {
  if (typeof id !== 'string') return false;
  const trimmed = id.trim();
  return trimmed.length > 0 && !trimmed.startsWith('__');
}

/** 给人看的失败原因：优先后端 message，其次异常信息，最后兜底；永远不返回空串。 */
export function describeSopError(e: unknown): string {
  const err = e as { response?: { data?: { message?: unknown } }; message?: unknown } | null | undefined;
  const backend = err?.response?.data?.message;
  if (typeof backend === 'string' && backend.trim()) return backend.trim();
  const own = err?.message;
  if (typeof own === 'string' && own.trim()) return own.trim();
  return '网络异常';
}

export function formatSopSize(bytes: number | null | undefined): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export function sopKindLabel(file: { fileType?: string | null; fileName?: string | null }): string {
  const type = (file.fileType ?? '').toLowerCase();
  if (type === 'application/pdf' || /\.pdf$/i.test(file.fileName ?? '')) return 'PDF';
  return '图片';
}
