// 保存工艺保密策略后服务端给的提示 (ProcessSecretPolicyService.PolicyView.warnings)。
//
// 提示不是拒绝: 保存已经成功 (200)。例: 产品全名是通用品类词 (产品就叫「牛腱」) 时, 训练过滤不按名字拦
// (Steve 2026-11-03 选 B), 服务端返回
//   「该名称为通用品类词，训练过滤不按名字拦截，仅按编码/工艺数值保护」
// 页面必须把它显示给保存人 (负责人), 不能只弹「已保存」。

export interface PolicySaveResult {
  warnings?: string[] | null;
}

/** 服务端返回的提示 (去掉空串); 没有提示返回空数组。 */
export function policySaveWarnings(data: PolicySaveResult | null | undefined): string[] {
  const list = data?.warnings;
  if (!Array.isArray(list)) return [];
  return list.filter((w): w is string => typeof w === 'string' && w.trim() !== '');
}

/** 显示给保存人的一段话: 「产品名」：提示1；提示2。 */
export function policySaveWarningText(productName: string, warnings: string[]): string {
  return `「${productName}」：${warnings.join('；')}`;
}
