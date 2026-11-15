export type PricingLine = {
  quantity?: number | string | null;
  unit?: string | null;
  quantityUnit?: string | null;
  unitPrice?: number | string | null;
  priceUnit?: string | null;
  lineAmount?: number | string | null;
  convertedPricingQuantity?: number | string | null;
};

/**
 * ⛔ **不要为了让展示变中文而往这里加条目**。
 *
 * `canonicalUnitCode` 读的是这张表, 而它不只用于展示 —— 还参与**构造 API 请求体**
 * (如单位换算接口的 fromUnit/toUnit)。2026-07-31 曾往这里加 `'斤' -> 'jin'`, 结果
 * `SeasoningBindingDialog` 发给后端的 `fromUnit` 从「斤」变成了「jin」, 被既有测试当场拦下。
 *
 * 要让某个规范码显示成中文, 加到下面的 {@link UNIT_LABELS} 即可 —— 那张表是纯展示,
 * 且中文写法本来就会原样透传 (查不到 label 就返回自身)。
 *
 * <h2>🔴 不动点判据 (2026-09-02, 缺陷 33 根治)</h2>
 *
 * <b>每一个目标码 (等号右边的值) 必须是后端 `UnitContractServiceImpl#normalize()` 的
 * 不动点</b> —— 即 `normalize(目标码).code() === 目标码`。这不是新规则, 是这张表
 * 从一开始就该满足、却一直没人校验的契约 ({@code __tests__/unitAliasFixedPoint.source.spec.ts}
 * 直接读后端源码钉住)。
 *
 * <p>旧表把计数/包装单位的目标码写成英文 (`box`/`case`/`bag`/`slice`/`pcs`), 而后端
 * #2628 起这类单位的规范码<b>就是中文字本身</b> ({@code alias("盒","盒","box")} ——
 * `box` 只是别名, `盒` 才是不动点)。于是任何直接把 `canonicalUnitCode()` 的输出当作
 * 请求体字段值发出去的调用点 (未经 {@link storageUnitCode} 翻译), 发的都是后端的
 * <b>别名</b>而不是<b>规范码</b>。多数场景后端 `normalize()` 能认出别名, 不会立刻炸;
 * 但 `pcs` 这个别名<b>只指向「件」一个目标</b> (`alias("件","件","pcs",...)`, 后端
 * <b>没有</b> `个 -> pcs`/`只 -> pcs` 的别名), 而旧表却把 个/只/件 三个不同的后端规范码
 * <b>全部折进同一个内部键 `'pcs'`</b> —— 「个」经这张表变成 `'pcs'` 后, 唯一能被后端
 * 认回的目标就是「件」, 「个」这个身份从此丢失、拿不回来。这正是
 * `PurchaseServiceImpl#canonicalUnit('pcs')`('件') != 物料字典单位「个」的
 * `canonicalUnit('个')`('个') → 422/400 的根因: 请求体在**构造阶段**就已经把
 * 「个」写成了「件」的别名, 不是后端不认「个」。
 *
 * <p>本次改动: 让 个/只/件 各自映射到<b>自己</b> (三个不动点, 不再共享 `'pcs'`),
 * box/case/carton/slice/bag/pcs/pc/piece/pieces 映射到各自的中文规范码,
 * L/mL 的目标码改成后端实际的小写码 `l`/`ml` (后端 `key()` 会把大小写都折成小写,
 * 规范码本身是小写)。
 *
 * <p>⚠️ <b>`canonicalUnitCodeKeepingCount` 因此在功能上变成了 no-op 包装</b> ——
 * `canonicalUnitCode` 自己现在就不再折叠 个/只/件。<b>不要因此删除它</b>: 它仍是
 * `sameUnit`/多处调用点的显式依赖, 删掉是另一次改动, 不在本次范围内; 留着也是
 * 防线 (万一这张表将来又被改回折叠写法, 至少那些显式调用点还守着)。
 */
const UNIT_ALIASES: Record<string, string> = {
  kg: 'kg', '千克': 'kg', '公斤': 'kg',
  g: 'g', '克': 'g',
  box: '盒', '盒': '盒',
  case: '箱', carton: '箱', '箱': '箱',
  slice: '片', '片': '片',
  bag: '袋', '袋': '袋',
  pcs: '件', pc: '件', piece: '件', pieces: '件', '件': '件',
  '个': '个',
  '只': '只',
  l: 'l', '升': 'l',
  ml: 'ml', '毫升': 'ml',
};

/**
 * 规范码 → 展示文案。
 *
 * 🔴 规则 (Steve 2026-07-31 拍板):
 * - **可换算的国际单位** (kg/g/mg/L/mL/mm/cm/m/km) 保留英文码 —— 它们真的参与换算,
 *   用户也认这套写法。
 * - **计数 / 包装单位** (box/bag/pcs/case/slice/pack/bottle/can/crate/pail/roll/portion/item)
 *   **一律不得以英文码示人** —— 它们不参与任何换算, 码只是个内部标识, 对用户没有意义。
 *   `斤`/`吨` 同理: `jin`/`t` 对用户是天书。
 *
 * 缺一条就会漏出英文码 (`displayUnit` 查不到 label 时原样返回)。同目录
 * `__tests__/unitDisplayContract.spec.ts` 直接读后端权威表比对, 后端加了单位这里没加就会红。
 *
 * <h2>⚠️ 2026-09-02 (缺陷 33): 这张表现在服务<b>两个</b>互相独立的查询路径</h2>
 *
 * 1. `displayUnit()` 的正常路径 —— 键是 `canonicalUnitCode()` 的<b>输出</b>。
 *    {@link UNIT_ALIASES} 改成不动点之后, `canonicalUnitCode('box')` 已经直接返回
 *    `'盒'`, 查不到 `UNIT_LABELS['盒']`(没有这个键)时会回落到 `code` 本身 —— 恰好就是
 *    `'盒'`, 结果不变。<b>`pcs`/`box`/`case`/`bag`/`slice`/`portion`/`item`/`pack`/
 *    `bottle`/`can`/`crate`/`pail`/`roll`/`tray`/`plate`/`sheet` 这些键因此不会再被
 *    这条路径命中</b>, 但留着不是垃圾 —— 见下一条。
 * 2. `productSpecification.ts` 的 `TRANSLATED_UNIT_CODES` (由本表 `value !== key`
 *    的条目反推) —— 匹配的是<b>历史落库文本里字面出现的英文单词</b>(后端曾经把
 *    `product.getUnit()` 的码直接拼进规格串, 例如 `"10pack/箱"`), 与
 *    `canonicalUnitCode()` 现在输出什么<b>无关</b>。删掉这些键会让
 *    `displayProductSpecification` 认不出这些历史英文缩写, 所以<b>保留</b>。
 *
 * `l`/`ml` 的键从大写 `L`/`mL` 改成小写: 后端 `key()` 把所有输入都折成小写,
 * `canonicalUnitCode()` 现在对齐输出小写 `l`/`ml`(见 {@link UNIT_ALIASES} 注释),
 * 这里的键必须跟着变, 否则 `displayUnit('升')` 会查不到条目、回落成 code 本身 `l`
 * (小写字母 l), 用户会看到「1 l/瓶」而不是「1 L/瓶」。展示文案本身仍保持大写 `L`/`mL`
 * —— 国际单位大小写写法是展示层的独立选择, 不因内部键变小写而变。
 */
const UNIT_LABELS: Record<string, string> = {
  // 可换算国际单位 —— 保持英文 (键小写, 与 canonicalUnitCode 的输出对齐; 展示文案仍大写)
  mg: 'mg', g: 'g', kg: 'kg', l: 'L', ml: 'mL',
  mm: 'mm', cm: 'cm', m: 'm', km: 'km',
  // 中文计量单位 —— 英文码对用户无意义
  jin: '斤', t: '吨',
  // 计数 / 包装 —— 一律中文。这些键不再被 canonicalUnitCode() 的输出直接命中
  // (它现在已经返回中文), 保留是为了 productSpecification.ts 匹配历史英文缩写。
  // 🔴 pcs 展示成「件」而不是「只」(2026-08-01 定案, 推翻 #1672 的对齐方向):
  //   本文件的 DISTINCT_COUNT_LABELS 与后端 UnitContractServiceImpl 同源, 都是 {只, 个} ——
  //   两侧都把「件」排除在外, 即两侧都认定 **件 ≡ pcs**。把 pcs 渲染成「只」等于断言
  //   只 ≡ pcs, 与这条同源规则**直接矛盾**(`sameUnit('pcs', displayUnit('pcs'))` 曾经是 false,
  //   一个单位不等于它自己的展示标签)。Java 侧 UnitDisplayNames 也是「件」。
  //   ⚠️ 用户手填的 只/个/件 由 RAW_COUNT_LABELS 原样保留, 不受本行影响 ——
  //   本行只决定**裸规范码 pcs** 显示成什么。
  pcs: '件', portion: '份', slice: '片', item: '项',
  box: '盒', case: '箱', bag: '袋', pack: '包', bottle: '瓶',
  can: '罐', crate: '框', pail: '桶', roll: '卷',
  tray: '托盘', plate: '板', sheet: '张',
};

export function canonicalUnitCode(value: unknown): string {
  const raw = String(value ?? '').trim();
  if (!raw) return '';
  // 遗留复合写法 `pcs:只` 是 #2628 迁移前「码=pcs, 附带用户当初填的中文标签」的存量数据。
  // 🔴 2026-09-02: 这里的返回值只取 group[1] (pcs/kg/g), **不看** group[2] 的具体标签 ——
  // 意味着 canonicalUnitCode('pcs:只') 与 canonicalUnitCode('pcs:个')(若存在)会折到同一个值。
  // 这是刻意的: 本函数是**内部比较键**, 允许折叠; 具体标签的保留由 displayUnit() 自己的
  // legacyCountComposite 分支负责 (它直接返回 group[1] 本身, 不经过这张表)。
  const legacyComposite = raw.match(/^(pcs|kg|g):(只|公斤|克)$/i);
  if (legacyComposite) return UNIT_ALIASES[legacyComposite[1].toLowerCase()] || legacyComposite[1];
  return UNIT_ALIASES[raw.toLowerCase()] || UNIT_ALIASES[raw] || raw;
}

/**
 * 件/个/只 三个中文计数标签在展示时必须原样还回去, 不能互相替换
 * (见下面 {@link DISTINCT_COUNT_LABELS}: 一只鸡不是一件包材)。
 *
 * ⚠️ 2026-09-02 (缺陷 33 根治后): `canonicalUnitCode('个')`/`('只')`/`('件')`
 * 现在<b>各自返回自己</b> (不再折成共同的 `pcs`, 见 {@link UNIT_ALIASES} 注释),
 * 且这三个字都不在 {@link UNIT_LABELS} 的键里, 所以就算<b>没有</b>这道守卫,
 * `displayUnit` 走到最后一行 `UNIT_LABELS[code] || code` 也会因为查不到条目而
 * 回落成 `code` 本身、结果不变。<b>这道守卫因此从「必需」降级为「防线」</b> ——
 * 不删是因为它独立于 `UNIT_ALIASES` 的具体取值, 万一那张表将来又被改回折叠写法,
 * 至少这三个最常见的字面值还有兜底。
 *
 * 🔴 #1672 (2026-07-23) 把 `UNIT_LABELS.pcs` 从「件」改成「只」以对齐系统单位表的
 * `unitName`, 于是**用户填「件」的产品在界面上显示成「只」**。
 * `__tests__/productSpecification.spec.ts` 当天就红了, 但 vitest 当时不在任何
 * push 门禁里 (`vue-build-check` 挂 `if: inputs.full_audit`), 一直没人看见。
 */
const RAW_COUNT_LABELS = new Set(['只', '个', '件']);

export function displayUnit(value: unknown): string {
  const raw = String(value ?? '').trim();
  if (RAW_COUNT_LABELS.has(raw)) return raw;
  // 🔴 遗留复合写法 `pcs:只` **自带用户当初填的那个标签**, 与 RAW_COUNT_LABELS 同理原样保留。
  // 不保留的话, 2026-08-01 把 UNIT_LABELS.pcs 从「只」改成「件」会顺带把这些历史数据
  // 从「只」翻成「件」—— 那正是 #1672 造成、#2097 修掉的那类「用户填的标签被改写」缺陷。
  const legacyCountComposite = raw.match(/^pcs:(只|个|件)$/i);
  if (legacyCountComposite) return legacyCountComposite[1];
  const code = canonicalUnitCode(value);
  return UNIT_LABELS[code] || code;
}

/**
 * 需要翻译的单位码 —— `UNIT_LABELS` 里 label 与 code 不同的那些 (`box`/`bag`/`pack`…)。
 * 用于 `displayProductSpecification` 正则匹配<b>历史落库文本里字面出现的英文单词</b>
 * (与 `canonicalUnitCode()` 现在输出什么无关, 见 `UNIT_LABELS` 顶部注释)。
 *
 * 🔴 由表推导, 不手抄。手抄过一次: `displayProductSpecification` 原先硬编码
 * `box|case|slice` 三个码, 于是 2026-08-06 客户在规格列看到 `1kg/pack 10pack/箱`
 * —— `pack` 明明在 UNIT_LABELS 里有「包」, 只是那条正则没跟上。
 * `kg`/`g`/`mg`/`mm`/`cm`/`m`/`km` 这些自映射的国际符号不在此列, 本来就不该翻。
 *
 * <h2>⚠️ 2026-09-02: `l`/`ml` 显式排除, 尽管它们在 UNIT_LABELS 里 value !== key</h2>
 *
 * `UNIT_LABELS` 里 `l: 'L'` / `ml: 'mL'` 只是**内部键小写、展示文案大写**的记录
 * (键跟着 canonicalUnitCode() 的不动点走, 见 {@link UNIT_ALIASES} 注释), 不是
 * 「需要把字面英文翻成中文」那类条目 —— 与 box/case/pack 这些"英文缩写→中文"的翻译
 * 不是同一件事。如果不排除, `displayProductSpecification` 会把规格文本里任何形似
 * "毫升/升"的拉丁字母 l/L/ml/mL 都当成待翻译的匹配对象, 而 `250ml/L` 这种<b>本来就该
 * 原样保留</b>的国际单位符号会被交给 `displayUnit()` —— 大小写被悄悄改写 (`ml`→`mL`),
 * 与"国际计量符号不翻"这条既有契约(`productSpecification.spec.ts`)冲突。
 */
const NEVER_TRANSLATE_IN_SPEC_TEXT = new Set(['l', 'ml']);
export const TRANSLATED_UNIT_CODES: readonly string[] = Object.keys(UNIT_LABELS)
  .filter((code) => UNIT_LABELS[code] !== code && !NEVER_TRANSLATE_IN_SPEC_TEXT.has(code))
  .sort((a, b) => b.length - a.length);

/**
 * #1976 例外名单 —— 与后端 `UnitContractServiceImpl.DISTINCT_COUNT_LABELS` 同源。
 * 权威表把 件/个/只 都并进 pcs, 但业务上**一只 ≠ 一件**(一只鸡不是一件包材)。
 * 「件」不在名单里: 它就是 pcs 的中文名, 件≡pcs 是对的。
 */
const DISTINCT_COUNT_LABELS = new Set(['只', '个']);

/**
 * 归一单位码, 但<b>保留</b> 只/个 —— 与后端
 * {@code UnitContractServiceImpl.crossLanguageCode} 逐条对应(它同样先查
 * DISTINCT_COUNT_LABELS 再走权威表)。
 *
 * <h2>🔴 2026-08-13 真机 E2E 抓到 (根治于 2026-09-02, 缺陷 33)</h2>
 * 销售订单选中物料 {@code YL060 温氏黄油鸡}(物料字典单位「只」)后, 订单行显示
 * 「<b>件</b>」; {@code BC001 吸塑盒}(单位「个」)也显示「件」——
 * 三个计件单位在界面上被合并成同一个字。
 *
 * <p>根因不在 {@link displayUnit}(它有 RAW_COUNT_LABELS, 传「只」会原样返回), 而在<b>更早</b>:
 * 订单行选品时就 {@code canonicalUnitCode('只') → 'pcs'} 把原标签抹掉了, 显示层再想还原也没有了。
 *
 * <p>后果: 仓管在发货单上看到「2 <b>件</b>黄油鸡」而不是「2 <b>只</b>」。
 * 数量算术不受影响(扣减用纯数字), 坏的是**人读到的东西**。
 * 这正是同文件里 #1672 / #2097 修过两次的那类「用户填的标签被改写」缺陷 ——
 * 名单早就在({@code DISTINCT_COUNT_LABELS}), 只是**只接在 sameUnit 上, 没接到落库/展示这条路**。
 *
 * <h2>⚠️ 2026-09-02 (缺陷 33 根治): 本函数现在与 {@link canonicalUnitCode} 行为等价</h2>
 * {@link UNIT_ALIASES} 的目标码改成不动点之后, `canonicalUnitCode('个')`/`('只')` 已经
 * 各自返回自己 —— 与本函数「先查 {@link DISTINCT_COUNT_LABELS} 再原样返回」的效果完全相同。
 * <b>不要因此删除本函数或它的调用点</b>(list.vue 由 #474 显式接了 7 处):
 * (1) 它是既有 API, 删除是另一次改动, 超出本次「对齐目标码」的范围;
 * (2) 万一 {@link UNIT_ALIASES} 将来又被改回折叠写法, 这些显式调用点仍然守着 #1976。
 */
export function canonicalUnitCodeKeepingCount(value: unknown): string {
  const raw = String(value ?? '').trim();
  if (!raw) return '';
  return DISTINCT_COUNT_LABELS.has(raw) ? raw : canonicalUnitCode(raw);
}

/**
 * 有<b>普适换算因子</b>的单位 —— 与后端 {@code CanonicalUnit#factorToBase != null} 一一对应
 * ({@code systemUnits()} 里 {@code add(units, "kg", …, "1000", …)} 那个参数非 null 的那些)。
 *
 * 🔴 <b>这不是第四张单位表</b>, 是对<b>已有码</b>的一次分组。之所以能只用这一个判据,
 * 是因为后端 #2628 之后「不参与换算的单位, 码就是中文字本身」——
 * 于是「该发中文还是该发拉丁符号」完全由「有没有换算因子」决定。
 * 分组与后端是否一致由 {@code __tests__/unitStorageContract.spec.ts} <b>直接读后端源码</b>钉住:
 * 后端加一个单位而这里没跟, 那条会红。
 */
// ⚠️ 2026-09-02: 'l'/'ml' 改小写, 与 canonicalUnitCode() 现在的输出对齐
// (见 UNIT_ALIASES 注释 —— 后端规范码本身就是小写)。
const CONVERTIBLE_UNIT_CODES = new Set([
  'mg', 'g', 'kg', 'jin', 't',
  'ml', 'l',
  'mm', 'cm', 'm', 'km',
]);

/**
 * <b>发给后端的那个值</b> —— 与后端 {@code UnitContractServiceImpl#storageUnit} 落库的是同一个单位,
 * 且计数 / 包装单位一律是<b>用户看到的那个中文字</b>。
 *
 * <h2>🔴 为什么必须与 {@link canonicalUnitCode} 分开 (2026-08-18)</h2>
 *
 * {@link canonicalUnitCode} 是<b>前端内部的比较键</b> (去重 / 判同 / el-select 的 value)。
 * 2026-08-18 写下这段话时, 它把「盒」折成 {@code box}, 与后端 #2628 之后的方向相反
 * (「盒」的规范码就是「盒」, {@code box} 只是别名) —— 于是用户在下拉里选的是「盒」,
 * 请求体里发出去的是 {@code box}, 落库/回传/发货单再翻回中文, <b>中间凭空多了一趟翻译,
 * 而任何一处漏翻, 仓管就在单据上读到英文</b> (实测 {@code sales_delivery_items} 至今
 * 躺着 2 行 {@code box})。
 *
 * <p>⚠️ <b>2026-09-02 更新 (缺陷 33 根治)</b>: {@link UNIT_ALIASES} 的目标码改成
 * 后端 `normalize()` 的不动点之后, {@link canonicalUnitCode} 对计数/包装单位<b>也</b>
 * 直接返回中文规范码了 —— 两个函数现在<b>只在 斤/吨 上还有分歧</b> (见下方例外说明),
 * 其余情况 {@code storageUnitCode(x) === canonicalUnitCode(x)} 恒成立。
 * <b>本函数依然保留、依然是「发给后端」的唯一入口</b>: (1) 斤/吨 这一个真实例外还需要它
 * 分派; (2) 它是本仓单测/契约测试 (`unitStorageContract.spec.ts`) 独立钉住的公开契约,
 * 删掉是另一次改动, 不在本次「对齐目标码」的范围内; (3) 万一 `UNIT_ALIASES` 未来又漂回
 * 折叠写法, 这个「发出去之前统一走一遍」的出口仍然兜底。
 *
 * <p>⇒ 内部键继续用 {@link canonicalUnitCode}(比较 / 去重 / 包装规格匹配都不用改),
 * <b>请求体一律走这个函数</b>: 用户看到哪个字, 就发哪个字。
 *
 * <p>⛔ 不要在这里新建一张「后端存储码」表 —— 本仓已经有三张单位表在漂。
 * 本函数只做<b>分派</b>: 可换算的走 {@link canonicalUnitCode}(两侧同为拉丁符号),
 * 其余走 {@link displayUnit}(后端的码就是那个中文字)。
 *
 * <p>⚠️ 已知且<b>有意</b>的例外: 斤 / 吨。后端的码是 {@code jin}/{@code t}, 这里发中文;
 * 后端 {@code normalize} 会把它们收回 {@code jin}/{@code t}, <b>落库值不变</b>。
 * 反过来在前端把「斤」翻成 {@code jin} 正是 2026-07-31 那次事故(见 {@link UNIT_ALIASES} 注释), 别做。
 * (「斤」/「吨」不在 {@link UNIT_ALIASES} 表里, 就是为了不让它们参与折叠, 这个例外因此
 * 与不动点判据不冲突 —— 判据只约束「表里出现的目标码」, 「斤」/「吨」本就不在表里。)
 *
 * <p>⛔ 也<b>不要</b>把它和 {@link displayUnit} 合并成一个函数。{@link displayUnit} 服务于眼睛,
 * 改它的动机随时会出现(#1672 就把 {@code pcs} 的标签从「件」改成「只」又改回来);
 * 而本函数服务于落库 —— 跟着展示动会<b>静默改写已落库的数据</b>。
 */
export function storageUnitCode(value: unknown): string {
  const raw = String(value ?? '').trim();
  if (!raw) return '';
  const code = canonicalUnitCodeKeepingCount(raw);
  return CONVERTIBLE_UNIT_CODES.has(code) ? code : displayUnit(raw);
}

/**
 * 判两个单位<b>是不是同一个</b> —— 中英写法互认, 但不合并 只/个/件。
 *
 * 🔴 为什么不能直接 `a === b`: 库里同一个单位有中英两种写法 (后端保存写规范码, 人工录入常是中文),
 * 字面比较会把「袋」和 `bag` 判成两种单位。2026-07-31 客户就是这么被拦住的; 前端还另有几处
 * 因此显示「跨单位不可比, 需配产品标准克重」——**而那个提示会把人引去做无用功**, 因为两个
 * 单位本来就是同一个, 配了标准克重也没用。
 *
 * 与后端 `UnitContractServiceImpl.crossLanguageCode` 逐条对应, 改一边要改另一边。
 */
export function sameUnit(left: unknown, right: unknown): boolean {
  const norm = canonicalUnitCodeKeepingCount;  // 与落库/展示同一份归一, 不再各写一份
  const l = norm(left);
  const r = norm(right);
  return l !== '' && l === r;
}

export function formatPriceUnit(value: unknown): string {
  const code = canonicalUnitCode(value);
  return code ? `元/${displayUnit(code)}` : '计价单位未配置';
}

/**
 * 单价**显示**格式 —— 有几位小数就显示几位, 没有小数就不显示小数点。
 *
 * owner 2026-08-20: 详情页把 ¥23 显示成 `23.0000 元/kg`, 「四个零没有信息量」。
 *   23      → "23"
 *   23.5    → "23.5"
 *   23.4567 → "23.4567"
 *
 * ⚠️ 只改显示。存储与提交值不经过这里 —— 提交走 `purchaseOrderPricingPayload`,
 *    本函数只被 template 插值调用, 返回的是 string, 从类型上就回不到载荷里。
 *
 * ⚠️ 为什么先 `toFixed(4)` 再剥尾零, 而不是直接 `String(n)`:
 *    后端 `purchase_order_items.unit_price` 是 `precision=15, scale=4` 的 BigDecimal,
 *    4 位就是它的全部有效精度; 而 `String(n)` 会把 0.1+0.2 那类浮点尾巴
 *    (0.30000000000000004) 原样吐给用户。
 *
 * ⛔ 不要改用 utils/format-number 的 `formatNumber` —— 它到 1 万就折成「X.XX万」,
 *    单价折成「2.30万」是错的, 且它按固定位数四舍五入会吃掉第 3、4 位小数。
 */
export function formatUnitPrice(value: unknown): string {
  if (value == null || value === '') return '-';
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return '-';
  // toFixed(4) 必然带小数点, 所以 `\.?0+$` 只会咬掉小数部分的尾零,
  // 不会把 "230.0000" 的整数位 0 一起咬掉 (那个 0 后面跟着 '.', 匹配不上 $)。
  return numeric.toFixed(4).replace(/\.?0+$/, '');
}

export function mergeCanonicalUnitOptions(...sources: Array<unknown | unknown[]>): string[] {
  const units = new Set<string>();
  sources.flatMap((source) => Array.isArray(source) ? source : [source]).forEach((source) => {
    const unit = canonicalUnitCode(source);
    if (unit) units.add(unit);
  });
  return Array.from(units);
}

export type PurchaseSuggestionPricing = {
  unit?: string | null;
  quantityUnit?: string | null;
  priceUnit?: string | null;
  referencePriceUnit?: string | null;
};

/**
 * 后端采购建议的权威字段是 referencePriceUnit。priceUnit 仅作为版本兼容别名，
 * 最后才回退到数量单位以兼容尚未升级的旧响应。
 */
export function resolvePurchaseSuggestionUnits(item: PurchaseSuggestionPricing) {
  const quantityUnit = canonicalUnitCode(item.quantityUnit || item.unit);
  const priceUnit = canonicalUnitCode(item.referencePriceUnit || item.priceUnit || item.unit);
  return { quantityUnit, priceUnit };
}

/**
 * 采购请求体的单位出口 —— 与销售侧 {@link storageUnitCode} 同一条规则:
 * <b>用户看到哪个字, 就发哪个字</b>。
 *
 * 🔴 2026-08-19: `9fcde2af43` 把销售侧改成走 {@link storageUnitCode}
 * (「前端不再把用户选的『盒』翻成 box 发给后端」), 但**采购侧还留在内部比较键上** ——
 * 同一个缺陷改了一半就是现场制造一份漂移: 销售单发「盒」、采购单发 `box`,
 * 两条路落进同一个库。`sales_delivery_items` 里那 2 行 `box` 就是这么来的。
 *
 * <p>⚠️ 只在<b>出口</b>翻译。{@link resolvePurchaseSuggestionUnits} 仍然返回内部比较键 ——
 * el-select 的 value、包装规格匹配 (`canonicalUnitCode(spec.packageUnit) === …`) 全靠它,
 * 把内部键也换成中文会让下拉选不中。
 */
export function purchaseOrderPricingPayload<T extends PurchaseSuggestionPricing>(item: T) {
  const { quantityUnit, priceUnit } = resolvePurchaseSuggestionUnits(item);
  return {
    ...item,
    unit: storageUnitCode(quantityUnit),
    quantityUnit: storageUnitCode(quantityUnit),
    priceUnit: storageUnitCode(priceUnit),
  };
}

/** 后端 `POST /{factoryId}/units/convert` 的结果里, 本地决策真正需要的那三格。 */
export type UnitConversionAnswer = {
  status?: string | null;
  quantity?: number | string | null;
  message?: string | null;
} | null;

// ⚠️ 成功分支带 `message?: undefined` 不是冗余 —— 本项目 tsconfig.app.json 是
//    `strict: false` + `strictNullChecks` 关闭 + `noImplicitAny: true`。在这个组合下
//    只在失败分支声明 `message` 会让 `if (!outcome.ok) { outcome.message }` 报
//    TS2339「Property 'message' does not exist on type UnitSwitchOutcome」。
//    加上可选的 undefined 后属性在联合上存在, 而 false 分支里它仍然收窄成 string。
//    ⛔ 别把它删掉「化简」—— `vue-tsc -b` 会红, 而 `vue-tsc --noEmit` 看不出来(见下)。
export type UnitSwitchOutcome =
  | { ok: true; quantity: number; unit: string; message?: undefined }
  | { ok: false; unit: string; message: string };

/**
 * 换采购/数量单位时「换算多少、换不了怎么办」的**唯一**前端决策点。
 *
 * 🔴 它**不做任何算术** —— 数值一律来自后端 `UnitContractService.convert`
 * (那边会做 g↔kg 这类科学换算, 也会查 material_packaging_specs)。
 * 本函数只负责三件事: 同单位直接放行 / 后端成功就采用它的数 / 其余一律 fail closed 回滚。
 *
 * ⛔ 不许在调用方再写一份因子表。仓里为此栽过一次:
 * `StartPurchaseDialog.quantityUnitToLevel1Factor` (组件已于 2026-08-19 删除)
 * 只认包装层级, g→kg 返回 null,
 * 于是弹「该物料未配置这两个单位之间的包装换算」把用户挡死 ——
 * 而后端**本来就能**做 g→kg。那正是 2026-07-20 测试人员报的
 * 「最基础的 g 与 kg 都无法自动换算」。
 *
 * @param convert 注入的后端调用 (便于测试; 生产传 `@/api/unitContract` 的 convertUnit)
 */
export async function resolveUnitSwitch(
  params: { quantity: unknown; fromUnit: unknown; toUnit: unknown },
  convert: (quantity: number, fromUnit: string, toUnit: string) => Promise<UnitConversionAnswer>,
): Promise<UnitSwitchOutcome> {
  const fromUnit = canonicalUnitCode(params.fromUnit);
  const toUnit = canonicalUnitCode(params.toUnit);
  const quantity = Number(params.quantity);

  if (!toUnit) {
    return { ok: false, unit: fromUnit, message: '请选择单位' };
  }
  if (!fromUnit || fromUnit === toUnit) {
    // 同一个单位不需要换算, 也不该为此打一次网络。
    return { ok: true, quantity: Number.isFinite(quantity) ? quantity : 0, unit: toUnit };
  }

  let answer: UnitConversionAnswer = null;
  try {
    answer = await convert(Number.isFinite(quantity) ? quantity : 0, fromUnit, toUnit);
  } catch {
    answer = null;
  }

  const converted = Number(answer?.quantity);
  const accepted = !!answer
    && (answer.status === 'CONVERTED' || answer.status === 'IDENTITY')
    && answer.quantity !== null
    && answer.quantity !== undefined
    && Number.isFinite(converted);

  if (!accepted) {
    return {
      ok: false,
      unit: fromUnit,   // fail closed: 绝不留下一个「单位换了、数字没换」的行
      message: answer?.message
        || `该物料未配置 ${displayUnit(fromUnit)} → ${displayUnit(toUnit)} 的换算关系，不能直接切换单位`,
    };
  }

  return { ok: true, quantity: Math.round(converted * 10000) / 10000, unit: toUnit };
}

function finiteNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

export type PricingAmountPreview = {
  amount: number | null;
  source: 'backend-line-amount' | 'backend-converted-quantity' | 'same-unit' | 'pending';
  message: string;
};

/**
 * 金额只接受三种权威口径：后端 lineAmount、后端换算后的计价数量、或数量/计价单位完全相同。
 * 单位不同且没有后端换算结果时必须 fail closed，绝不猜 conversionFactor。
 */
export function pricingAmountPreview(line: PricingLine): PricingAmountPreview {
  const backendAmount = finiteNumber(line.lineAmount);
  if (backendAmount !== null) {
    return { amount: backendAmount, source: 'backend-line-amount', message: '' };
  }

  const unitPrice = finiteNumber(line.unitPrice);
  const convertedQuantity = finiteNumber(line.convertedPricingQuantity);
  if (unitPrice !== null && convertedQuantity !== null) {
    return {
      amount: convertedQuantity * unitPrice,
      source: 'backend-converted-quantity',
      message: '',
    };
  }

  const quantity = finiteNumber(line.quantity);
  const quantityUnit = canonicalUnitCode(line.quantityUnit || line.unit);
  const priceUnit = canonicalUnitCode(line.priceUnit);
  if (quantity !== null && unitPrice !== null && quantityUnit && quantityUnit === priceUnit) {
    return { amount: quantity * unitPrice, source: 'same-unit', message: '' };
  }

  return {
    amount: null,
    source: 'pending',
    message: '保存后由系统换算',
  };
}
