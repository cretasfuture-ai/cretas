package com.cretas.aims.entity.material;

import com.cretas.aims.exception.BusinessException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 物料<b>大类</b> —— 恰好三种: 原料 / 辅料 / 包材。
 *
 * <p><b>为什么加这一层</b>: {@code raw_material_types.category} 一列里同时塞了两种东西 ——
 * 「角色」(原料/辅料/包材/主材/调味料) 和「品类」(蔬菜/肉类/水产/米面/干货)。
 * 生产上这一列有 <b>12 个平级取值</b> (2026-08-28 实测 {@code cretas_prod_db}, 506 行):
 * <pre>
 *   辅料 149 / 原料 135 / 包材 87 / 调味料 68 / 主材 29 / 调料 12
 *   添加剂 9 / 蔬菜 7 / 肉类 4 / 水产 2 / 米面 2 / 干货 2
 * </pre>
 * 于是「这个物料能不能当辅料绑到工序上」这种判断只能靠一张会漏的字面值白名单 ——
 * 原来的白名单是 {@code {AUXILIARY, SEASONING, 辅料, 调料, 调味料}}, 「添加剂」不在里面,
 * 六膳门 9 个添加剂类物料 (保水剂 / 复合磷酸盐 / 小苏打 / 无磷保水剂 …) 全部绑不上,
 * 而保水剂和复合磷酸盐是肉制品核心辅料, 按 g/kg 投入计量, 与调料同一口径。
 *
 * <p><b>做法是分两层, ⛔ 不是把 12 个值压成 3 个</b>:
 * <ul>
 *   <li>{@code category} 原地降级成<b>小类</b> —— 12 个值一个不动。
 *       「添加剂」仍然是添加剂 (GB 2760 要按类别单独管限量), 「蔬菜」仍然是蔬菜。</li>
 *   <li>{@code main_category} 是<b>大类</b>, 只有三个值, 数据库层面有 CHECK 约束钉死,
 *       长不出第四个。业务判断查大类, ⛔ 不查小类。</li>
 * </ul>
 * ⇒ 以后在「辅料」下面加任何新小类, 判定代码一个字都不用改 —— 这正是这次改动的目的。
 *
 * <p><b>⚠️ 这张表有两份</b> (Java 这一份 + 迁移 {@code V20261101_04} 里的 SQL CASE),
 * 因为回填必须在 SQL 里做而运行时写入必须在 Java 里做, 抽不成一份。
 * 两份由 {@code MaterialMainCategoryTest#migrationBackfillAgreesWithJavaMapping}
 * 钉住必须一致 —— 那道闸直接读迁移文件解析出 CASE 分支再逐条比对, ⛔ 不是各自断言各自对。
 */
public final class MaterialMainCategory {

    /** 大类: 原料 (主料 / 各种食材品类)。 */
    public static final String RAW = "原料";
    /** 大类: 辅料 (调料 / 调味料 / 添加剂 —— 一切非主料非包材的配方成分)。 */
    public static final String AUXILIARY = "辅料";
    /** 大类: 包材。 */
    public static final String PACKAGING = "包材";

    /** 允许的三个大类值。DB 侧同名 CHECK 约束与之对应。 */
    public static final Set<String> ALLOWED = Set.of(RAW, AUXILIARY, PACKAGING);

    /**
     * 小类 → 大类。键用<b>原样字面</b>(不折大小写)登记中文, ASCII 别名另行大写比较。
     *
     * <p>覆盖面 = 生产实测的 12 个值 <b>+</b> 本仓其它地方已经在处理的历史自由文本别名
     * (见 {@code RawMaterialTypeServiceImpl#materialFamily} / {@code #NUMERIC_PREFIX_MAP} /
     * {@code web-admin/src/utils/materialCategory.ts})。
     * ⛔ 别名不是为了"尽量兜住", 是因为这些值<b>今天就会被写进来</b>:
     * {@code DEFAULT_MATERIAL_TYPES} 建厂种子直接写 海水鱼/淡水鱼/虾类/贝类。
     */
    private static final Map<String, String> BY_CATEGORY = new LinkedHashMap<>();

    static {
        // ── 原料 (生产实测的 7 个 + 历史自由文本别名) ──────────────────────────
        // 判断而非事实: 蔬菜/肉类/水产/米面/干货 是【品类】, 它在配方里的角色理论上要看配方
        // (洋葱在汉堡饼里是辅料)。但这一列是物料档案上的静态属性, 拿不到配方; 且生产上
        // 这 13 行全部落在 MOCK_REST 演示租户、BOM 主料与调料两侧使用次数都是 0
        // (2026-08-28 交叉统计), 所以归原料是零消费方的选择。要改从这里改。
        put(RAW, "原料", "主材", "蔬菜", "肉类", "水产", "米面", "干货");
        put(RAW, "禽类", "海鲜", "水产类", "海水鱼", "淡水鱼", "虾类", "贝类", "水果", "粉类", "油类");
        put(RAW, "RAW", "RAW_MATERIAL");

        // ── 辅料 (生产实测的 4 个 + 别名) ─────────────────────────────────────
        // 「添加剂」归辅料是本次改动的要害: 生产上 9 个添加剂物料里已有 3 个在当调料用。
        put(AUXILIARY, "辅料", "调味料", "调料", "添加剂");
        put(AUXILIARY, "辅材", "调味品");
        put(AUXILIARY, "AUXILIARY", "SEASONING");

        // ── 包材 ────────────────────────────────────────────────────────────
        // 「包辅材」是判断: 字面上跨了包材和辅材两头, 本仓既有代码 (ProductFamilyResolver)
        // 把它与包材并列排除在主料信号之外, 这里随之归包材。生产上 0 行, 要改从这里改。
        put(PACKAGING, "包材", "包辅材");
        put(PACKAGING, "PACKAGING", "PACKAGE");
    }

    private static void put(String mainCategory, String... categories) {
        for (String category : categories) {
            BY_CATEGORY.put(category, mainCategory);
        }
    }

    private MaterialMainCategory() {
    }

    /**
     * 小类 → 大类; 认不出来返回 {@code null}。
     *
     * <p>🔴 认不出来<b>不兜底</b>成「原料」—— 那会把「我不认识这个值」翻译成一个真实读数,
     * 而下游 (辅料绑定 / BOM 页签 / 料号前缀) 会照着这个假读数往下走。
     * 调用方要么用 {@link #require(String)} 让它当场失败, 要么显式处理 null。
     *
     * @param category {@code raw_material_types.category}; null / 空白 → null
     */
    public static String of(String category) {
        if (category == null) {
            return null;
        }
        String trimmed = category.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String direct = BY_CATEGORY.get(trimmed);
        if (direct != null) {
            return direct;
        }
        // ASCII 别名大小写不敏感 (PACKAGING / packaging 两种写法生产上都出现过)。
        return BY_CATEGORY.get(trimmed.toUpperCase(Locale.ROOT));
    }

    /**
     * 同 {@link #of(String)}, 但认不出来直接 400 —— 写入路径用这个。
     *
     * <p>{@code main_category} 是 NOT NULL 列, 认不出来时唯一诚实的行为是拒绝写入并告诉用户
     * 这个类别没登记, ⛔ 不是挑一个大类塞进去。
     */
    public static String require(String category) {
        String mainCategory = of(category);
        if (mainCategory == null) {
            throw new BusinessException(400,
                    "物料基本类型「" + (category == null ? "" : category.trim()) + "」尚未归入大类")
                    .withHint("大类只有 原料 / 辅料 / 包材 三种; 新增小类需先登记它属于哪个大类")
                    .withHintTarget("category")
                    .withSeverity("BLOCKING");
        }
        return mainCategory;
    }

    /** 已登记的小类字面值 (给闸和测试遍历用; 顺序 = 登记顺序)。 */
    public static Map<String, String> mappingView() {
        return Map.copyOf(BY_CATEGORY);
    }
}
