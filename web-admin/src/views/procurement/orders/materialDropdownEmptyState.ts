/**
 * 新建采购订单弹窗里「该供应商的原料」下拉的空态判定。
 *
 * <h2>🔴 为什么重做（2026-09-01，owner 在 LIUSHANMEN 生产上撞到）</h2>
 * 上一版按「有没有输入搜索词」二分，而用户能采取的行动取决于
 * **这家供应商到底配没配原料**。两条轴不重合，于是有一个象限给出**确信而错误**的建议：
 *
 * | 供应商配了原料? | 有搜索词? | 旧文案 | 对不对 |
 * |---|---|---|---|
 * | 有 | 有 | 「试试原料名关键字或拼音首字母」 | ✅ |
 * | **没有** | **有** | **同上** | 🔴 **错** —— 那里根本没东西可搜 |
 *
 * ⚠️ **叫人去做一件不可能成功的事，比什么都不说更糟** —— 它把人支走做无用功，
 * 而且看起来很有把握。与今晚那批「凭空的 0」同族：界面**确信地**陈述一件假的事。
 *
 * ⇒ 判定顺序固定为 **先 `relationCount === 0`，再看 query**。
 *
 * <h2>⛔ supplier-has-none 刻意做短</h2>
 * `orders/list.vue:1516` 已有一个**全宽 el-alert**，条件同样是 `relations.length === 0`，
 * 就在原料明细正上方、不截断、不用打开下拉就看得见。
 * 下拉里再写一遍全文 = 同一句话两份，一定会漂（形态 D）。
 * ⚠️ 下一个人看到这里短、上面长，可能会「顺手补全」—— ⛔ 别补，那会把重复造回来。
 *
 * <h2>为什么抽成纯函数</h2>
 * 判定读 ref 时没法逐象限测，而**最该被钉住的正是那个错误象限** ——
 * 它是这次真实的用户伤害，也是最容易在将来重构里被丢掉的一条。
 */

export type MaterialDropdownEmptyState =
  /** 还没选供应商 —— 下一步就在旁边，不需要指路 */
  | 'no-supplier'
  /** 选了供应商，但它名下一条原料都没配 */
  | 'supplier-has-none'
  /** 供应商有原料，只是当前搜索词没匹配上 */
  | 'no-match';

export interface MaterialDropdownEmptyInput {
  /** 是否已选供应商 */
  hasSupplier: boolean;
  /** 该供应商名下**全部**可供原料条数（⛔ 不是过滤后的条数） */
  relationCount: number;
  /** 当前搜索词（未 trim 也可） */
  query: string;
}

export interface MaterialDropdownEmptyPresentation {
  state: MaterialDropdownEmptyState;
  /** 主文案 */
  text: string;
  /** 是否给「去配置供应原料」的动作 */
  showConfigureAction: boolean;
  /**
   * 是否建议用户换个搜索词。
   * 🔴 **只有在真的有东西可搜时才为 true** —— 这是本模块存在的主要理由。
   */
  suggestSearchTips: boolean;
}

export function resolveMaterialDropdownEmpty(
  input: MaterialDropdownEmptyInput,
): MaterialDropdownEmptyPresentation {
  if (!input.hasSupplier) {
    return {
      state: 'no-supplier',
      text: '请先选择供应商',
      // 供应商下拉就在同一行左边，用户一眼看得到 ⇒ 不需要额外指路。
      showConfigureAction: false,
      suggestSearchTips: false,
    };
  }

  // ⚠️ 顺序要紧：这一条必须在 query 判断**之前**。
  // 供应商零原料时用户随手一打字，旧版会落进 no-match 并叫他「试试拼音」——
  // 那是一条不可能成功的指令。
  if (input.relationCount === 0) {
    return {
      state: 'supplier-has-none',
      // ⛔ 刻意短：完整说明在上方那个全宽 alert 里，这里只做指认 + 动作。
      text: '该供应商还没有配可采购的原料',
      showConfigureAction: true,
      suggestSearchTips: false,
    };
  }

  return {
    state: 'no-match',
    // ⚠️ 这里**有**东西可搜，所以搜索建议是成立的；同时保留「可能压根没配」这条,
    // 因为 owner 撞到的正是这一种 —— 供应商有原料, 只是要买的那个没配。
    text: '没找到匹配的原料',
    showConfigureAction: true,
    suggestSearchTips: true,
  };
}
