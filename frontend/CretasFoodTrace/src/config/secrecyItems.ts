/**
 * 工厂级保密配置 (Steve 2026-11-03) —— App 端只读。
 *
 * 配置项来自 `secrecy-items.json` (后端 Java 枚举 SecrecyItem 生成, SecrecyItemsJsonDriftContractTest 逐字节核对,
 * ⛔ 不要手改)。生效值随 `/api/mobile/{factoryId}/feature-config` 下发: moduleId = "secrecy" 那一行的
 * `config.secrecy: {CODE: bool}`。⛔ 读取一律判断 `=== true`: 本 App 缺行时默认「模块启用」, 保密配置不能沿用这个默认。
 */
import secrecyItemsFile from './secrecy-items.json';

export type SecrecyCode =
  | 'PROCESS_SECRET'
  | 'PLATFORM_RECIPE_BLOCK'
  | 'EGRESS_REDACTION'
  | 'TRAINING_FILTER'
  | 'CUSTOMER_HELD'
  | 'AI_SECRECY_AUDIT';

export interface SecrecyItemMeta {
  code: SecrecyCode;
  label: string;
  offEffect: string;
}

export const SECRECY_ITEMS: readonly SecrecyItemMeta[] = (secrecyItemsFile as { items: SecrecyItemMeta[] }).items;

export const SECRECY_MODULE_ID = 'secrecy';

interface ModuleLike {
  config?: Record<string, unknown> | null;
}

/** 某项是否开启: 只有 feature-config 里明确是 true 才算 (没加载 / 缺行 / 缺字段 / 非布尔都不算)。 */
export function isSecrecyOnIn(modules: Record<string, ModuleLike | undefined> | null | undefined,
                              code: SecrecyCode): boolean {
  if (!SECRECY_ITEMS.some((item) => item.code === code)) return false;
  const secrecy = modules?.[SECRECY_MODULE_ID]?.config?.secrecy;
  if (!secrecy || typeof secrecy !== 'object') return false;
  return (secrecy as Record<string, unknown>)[code] === true;
}
