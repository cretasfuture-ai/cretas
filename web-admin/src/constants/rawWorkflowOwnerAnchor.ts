/**
 * 「多产出计划」的内部锚点 —— 它**不是**一个产品，不许当产品名渲染给用户。
 *
 * <h2>🔴 2026-08-28 逐屏走查抓到</h2>
 *
 * f006（真实客户）→「生产管理 / 物料需求单」，「生产计划」列有一行显示成：
 *
 * ```
 * PLAN-1787108510261-C307CE6E · __RAW_WORKFLOW_OWNER__RMT_b25ab8b6-a16d-4549-92c5-eca790cc901b
 * ```
 *
 * <h2>它是什么</h2>
 *
 * 后端 `ProductProcessWorkflowServiceImpl` 为「以原料为中心、一次产出多个成品」的
 * 工序图创建一个 **inactive 的内部 ProductType 锚点**（`category = RAW_MATERIAL`，
 * `id` 直接复用原料的 id），因为运行时和历史外键都以 `product_type_id` 当通用 owner key：
 *
 * ```java
 * anchor.setId(rawOwner.getId());
 * anchor.setName("__RAW_WORKFLOW_OWNER__" + rawOwner.getId());
 * anchor.setCategory(ProductCategory.RAW_MATERIAL);
 * ```
 *
 * 那段代码自己的注释写着「**前端产品目录会过滤 RAW_MATERIAL，不再把原料伪装成 SKU**」——
 * 产品目录确实过滤了，**而物料需求单这个消费端没有**。典型的「机制在、这一处没接上」。
 *
 * <h2>⚠️ 这是跨语言的第二份，抽不成一份</h2>
 *
 * 权威定义在 Java（上面那行 `anchor.setName`），本文件是前端侧的第二份。
 * 跨语言抽不成一份 ⇒ 按「同一个东西有两份就会漂」的纪律，配一道闸钉住两份一致：
 * `src/__tests__/gates/rawWorkflowOwnerAnchorMatchesBackend.source.spec.ts`
 * 直接读 Java 源码比对这个字面量。
 *
 * ⛔ 改这个常量必须同时改 Java 那一处，否则闸会红。
 */
export const RAW_WORKFLOW_OWNER_ANCHOR_PREFIX = '__RAW_WORKFLOW_OWNER__';

/**
 * 这个名字是不是那个内部锚点。
 *
 * ⚠️ 判据用**名字前缀**是个代理判据 —— 真正的判据是
 * `category === RAW_MATERIAL && isActive === false`，但多数列表接口的 DTO
 * 只带下来一个 `productName`，拿不到那两个字段。⇒ 消费端能拿到 category 时
 * **优先用 category 判**，拿不到才退回本函数。
 */
export function isRawWorkflowOwnerAnchorName(name: unknown): boolean {
  return typeof name === 'string' && name.startsWith(RAW_WORKFLOW_OWNER_ANCHOR_PREFIX);
}

/**
 * 锚点该显示成什么。
 *
 * ⛔ 不要显示成空串或 `-` —— 那会让「这个计划有多个产出」看起来像「数据缺失」。
 * 锚点的存在**本身**就是信息：这条计划没有单一成品。
 */
export const RAW_WORKFLOW_OWNER_ANCHOR_LABEL = '多产出计划';
