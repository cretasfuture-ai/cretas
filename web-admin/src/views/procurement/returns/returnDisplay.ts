/** 退货列表名称列: 只显示后端解析出的名称/单号, 空值返回 '' (调用方显示「—」), 绝不回退成 UUID。 */
export function displayName(v: string | null | undefined): string {
  return (v ?? '').trim();
}
