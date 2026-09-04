/**
 * 多路彼此独立的异步加载 —— 逐路各自 settle，一路失败不影响其它路。
 *
 * 缺陷背景 (2026-09-05, owner 点名): `ProductionPlanManagementScreen.loadOptions()`
 * 原来用 `Promise.all([产品类型, 客户, 可用库存])` 一起等，任意一路失败(尤其是客户下拉——
 * `GET .../customers` 补了读权限门之后, hr_admin/equipment_admin/procurement_manager/
 * production_manager 等在 L1 被明确拒绝 sales+finance 模块的角色现在会拿到 403) 都会让
 * `Promise.all` 整体 reject，另外两路(产品类型/可用库存)也一并拿不到，整屏下拉选项空白——
 * "一拒全拒"。
 *
 * 抽成这个独立的小函数(不依赖任何 React / react-native-paper / expo-* 原生模块)是为了
 * 能脱离整屏渲染直接单测；`ProductionPlanManagementScreen` 那一屏经实测无法在当前 jest
 * 环境下 import 级别加载(`expo-document-picker` 在 import 阶段就抛
 * `Cannot read properties of undefined (reading 'NativeModule')`，本仓其它对这一屏的
 * 测试也一律用源码文本契约而非渲染，见 `productionPlanCustomerDisplayContract.test.ts`)，
 * 所以真正的行为断言必须落在一个不牵连这些依赖的纯函数上。
 *
 * 见 `settleAllIndependently.test.ts`：客户那一路 reject 时，产品类型与可用库存两路
 * 仍然各自拿到结果 —— 这正是本函数存在的理由。若把内部实现改回 `Promise.all`，
 * 该测试会变红(整体 reject，产品类型/可用库存两路也读不到)。
 */
export interface SettledIndependently<T extends Record<string, unknown>> {
  /** 成功的那些路的结果，键与传入的 promises 对象一致；失败的路不会出现在这里 */
  values: Partial<T>;
  /** 失败的路的 key 列表，顺序与传入对象的键顺序一致 */
  failedKeys: Array<keyof T>;
  /** 失败的路各自的原因，仅含 failedKeys 里的键，供调用方按需记日志 */
  failureReasons: Partial<Record<keyof T, unknown>>;
}

export async function settleAllIndependently<T extends Record<string, unknown>>(
  promises: { [K in keyof T]: Promise<T[K]> }
): Promise<SettledIndependently<T>> {
  const keys = Object.keys(promises) as Array<keyof T>;
  const settled = await Promise.allSettled(keys.map((k) => promises[k]));

  const values: Partial<T> = {};
  const failedKeys: Array<keyof T> = [];
  const failureReasons: Partial<Record<keyof T, unknown>> = {};

  settled.forEach((result, i) => {
    // 非空断言是安全的: keys 与 settled 由同一个 keys.map(...) 调用一一对应生成,
    // 长度恒相等, i 恒在 keys 的合法下标范围内 (noUncheckedIndexedAccess 让
    // TS 把数组下标访问的结果类型加上 undefined, 这里不是运行时会真的拿到 undefined)。
    const key = keys[i]!;
    if (result.status === 'fulfilled') {
      values[key] = result.value as T[typeof key];
    } else {
      failedKeys.push(key);
      failureReasons[key] = result.reason;
    }
  });

  return { values, failedKeys, failureReasons };
}
