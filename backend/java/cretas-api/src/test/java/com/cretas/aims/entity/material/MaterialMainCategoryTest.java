package com.cretas.aims.entity.material;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物料大类 (原料 / 辅料 / 包材) 的映射, 以及<b>它与迁移里那份 SQL CASE 一致</b>。
 *
 * <p>⚠️ 形态 D (同一个东西有两份, 它一定会漂): 这张映射表在 Java 和
 * {@code V20261101_04__raw_material_types_main_category.sql} 各有一份, 抽不成一份
 * (回填必须在 SQL 里做, 运行时写入必须在 Java 里做)。
 * 所以这里的闸钉的是<b>两份一致</b>, ⛔ 不是「各自都对」—— 两条各自正确的断言同时绿,
 * 并不排除两份内容不同。
 */
class MaterialMainCategoryTest {

    /** 生产实测 (2026-08-28, cretas_prod_db, raw_material_types 506 行) 的 12 个 category 取值及行数。 */
    private static final Map<String, Integer> PRODUCTION_CATEGORIES = new LinkedHashMap<>();

    static {
        PRODUCTION_CATEGORIES.put("辅料", 149);
        PRODUCTION_CATEGORIES.put("原料", 135);
        PRODUCTION_CATEGORIES.put("包材", 87);
        PRODUCTION_CATEGORIES.put("调味料", 68);
        PRODUCTION_CATEGORIES.put("主材", 29);
        PRODUCTION_CATEGORIES.put("调料", 12);
        PRODUCTION_CATEGORIES.put("添加剂", 9);
        PRODUCTION_CATEGORIES.put("蔬菜", 7);
        PRODUCTION_CATEGORIES.put("肉类", 4);
        PRODUCTION_CATEGORIES.put("水产", 2);
        PRODUCTION_CATEGORIES.put("米面", 2);
        PRODUCTION_CATEGORIES.put("干货", 2);
    }

    @Test
    @DisplayName("生产实测的 12 个 category 全部归得到大类, 且三类计数 = 181 / 238 / 87 (合计 506)")
    void everyProductionCategoryMapsAndTheCountsAddUp() {
        Map<String, Integer> perMain = new TreeMap<>();
        for (Map.Entry<String, Integer> e : PRODUCTION_CATEGORIES.entrySet()) {
            String main = MaterialMainCategory.of(e.getKey());
            assertNotNull(main, "生产上存在但没登记大类的 category: " + e.getKey());
            assertTrue(MaterialMainCategory.ALLOWED.contains(main),
                    "大类只能是三种之一, 实际: " + main);
            perMain.merge(main, e.getValue(), Integer::sum);
        }
        assertEquals(12, PRODUCTION_CATEGORIES.size(), "生产口径是 12 个 category, 遍历数对不上");
        assertEquals(181, perMain.get(MaterialMainCategory.RAW), "原料行数");
        assertEquals(238, perMain.get(MaterialMainCategory.AUXILIARY), "辅料行数");
        assertEquals(87, perMain.get(MaterialMainCategory.PACKAGING), "包材行数");
        assertEquals(506, perMain.values().stream().mapToInt(Integer::intValue).sum(), "总行数守恒");
    }

    @Test
    @DisplayName("要害: 添加剂 / 调味料 / 调料 全部归辅料 (这正是原白名单漏掉的那 9 个物料)")
    void additivesAndSeasoningsAreAuxiliary() {
        for (String category : new String[]{"添加剂", "调味料", "调料", "辅料", "辅材", "调味品"}) {
            assertEquals(MaterialMainCategory.AUXILIARY, MaterialMainCategory.of(category), category);
        }
    }

    @Test
    @DisplayName("阴性对照: 包材归包材, 主材/蔬菜/肉类归原料 —— 不是「什么都归辅料」")
    void nonAuxiliaryCategoriesDoNotLeakIntoAuxiliary() {
        assertEquals(MaterialMainCategory.PACKAGING, MaterialMainCategory.of("包材"));
        for (String category : new String[]{"原料", "主材", "蔬菜", "肉类", "水产", "米面", "干货"}) {
            assertEquals(MaterialMainCategory.RAW, MaterialMainCategory.of(category), category);
        }
    }

    @Test
    @DisplayName("认不出来返回 null, ⛔ 不兜底成原料; require 则当场 400")
    void unknownCategoryIsNotSilentlyCoercedIntoAMainCategory() {
        assertNull(MaterialMainCategory.of("这不是一个已登记的类别"));
        assertNull(MaterialMainCategory.of(null));
        assertNull(MaterialMainCategory.of("   "));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> MaterialMainCategory.require("这不是一个已登记的类别"));
        assertTrue(ex.getMessage().contains("尚未归入大类"), ex.getMessage());
    }

    @Test
    @DisplayName("ASCII 别名大小写不敏感 (packaging / PACKAGING 生产上两种写法都出现过)")
    void asciiAliasesAreCaseInsensitive() {
        assertEquals(MaterialMainCategory.PACKAGING, MaterialMainCategory.of("packaging"));
        assertEquals(MaterialMainCategory.PACKAGING, MaterialMainCategory.of("PACKAGING"));
        assertEquals(MaterialMainCategory.AUXILIARY, MaterialMainCategory.of("auxiliary"));
        assertEquals(MaterialMainCategory.RAW, MaterialMainCategory.of("raw_material"));
    }

    @Test
    @DisplayName("setCategory 顺带推出 mainCategory —— 两列不一致在写入路径上没有发生的余地")
    void settingTheSubCategoryDerivesTheMainCategory() {
        RawMaterialType material = new RawMaterialType();
        material.setCategory("添加剂");
        assertEquals("添加剂", material.getCategory(), "小类必须原样保留, ⛔ 不许被大类覆盖");
        assertEquals(MaterialMainCategory.AUXILIARY, material.getMainCategory());

        material.setCategory("包材");
        assertEquals(MaterialMainCategory.PACKAGING, material.getMainCategory(), "改小类要跟着改大类");

        material.setCategory("没登记的类别");
        assertNull(material.getMainCategory(), "认不出来留 null 让 NOT NULL 列拒绝, ⛔ 不兜底");
    }

    // ==================== 两份一致 (形态 D) ====================

    private static final Path MIGRATION = Paths.get(
            "src/main/resources/db/flyway/V20261101_04__raw_material_types_main_category.sql");

    /** 解析迁移里的 {@code WHEN '<category>' THEN '<main>'} 分支。 */
    private static Map<String, String> parseMigrationMapping() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("WHEN\\s+'([^']+)'\\s+THEN\\s+'([^']+)'").matcher(sql);
        Map<String, String> mapping = new LinkedHashMap<>();
        while (m.find()) {
            mapping.put(m.group(1), m.group(2));
        }
        return mapping;
    }

    @Test
    @DisplayName("迁移的 SQL CASE 与 Java 映射表逐条一致 (两份, 钉一致而不是各自都对)")
    void migrationBackfillAgreesWithJavaMapping() throws IOException {
        Map<String, String> fromSql = parseMigrationMapping();

        // 🔴 阳性对照: 先证明解析器真的读到了东西 —— 一个读出 0 条的解析器会让下面的比对恒绿。
        assertTrue(fromSql.size() >= 25,
                "迁移里只解析出 " + fromSql.size() + " 个 CASE 分支, 解析器可能坏了, "
                        + "下面的一致性比对无论红不红都没有意义");

        Map<String, String> fromJava = MaterialMainCategory.mappingView();
        assertEquals(new TreeMap<>(fromJava).toString(), new TreeMap<>(fromSql).toString(),
                "Java 映射表与迁移 SQL 的 CASE 分支不一致 —— 两份漂了。"
                        + "回填和运行时写入会给同一个 category 算出不同的大类。");
    }

    @Test
    @DisplayName("迁移不许把认不出来的 category 兜底成某个大类, 必须让迁移失败")
    void migrationRefusesToCoerceUnknownCategories() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        assertTrue(sql.contains("ELSE NULL"), "CASE 的兜底必须是 NULL");
        assertTrue(sql.contains("RAISE EXCEPTION"), "回填未覆盖时必须让迁移失败而不是继续");
        assertTrue(sql.contains("SET NOT NULL"), "main_category 必须是 NOT NULL");
        assertTrue(sql.contains("chk_raw_material_main_category"),
                "大类只有三种要在 DB 层面钉死 —— 少了 CHECK 就长得出第四个");
        // 阴性对照: 任何形式的「认不出来就当原料」都不许出现。
        assertTrue(!sql.contains("COALESCE(main_category"),
                "⛔ 不许 COALESCE 兜底 —— 那会把「我不认识这个值」翻译成一个真实读数");
    }
}
