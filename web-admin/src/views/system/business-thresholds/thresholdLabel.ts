/**
 * 业务阈值配置页的显示名。
 *
 * 后端 factory_thresholds.description 是中文说明, 常见形如「库龄新鲜段上限」或
 * 「客供料收货能不能超收: 0 = ... 」。显示名取第一个冒号/括号之前的短语,
 * 没有说明时才回退到键名; 键名本身只放进 tooltip, 不再当主标题外露给用户。
 */
export function thresholdDisplayName(
  row: { thresholdKey: string; description?: string | null },
): string {
  const desc = (row.description ?? '').trim();
  if (!desc) return row.thresholdKey;
  const head = desc.split(/[:：(（。]/)[0].trim();
  return head || row.thresholdKey;
}
