package com.cretas.aims.ai.tool.impl.foodsafety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.cretas.aims.entity.bom.BomRecipeItem;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 营养标签必须用<b>折算后</b>的用量（含出成率），不许读那个同名的持久化字段。
 *
 * <h2>为什么这两个东西会被搞混</h2>
 *
 * <pre>
 *   calculateActualQuantity()   @Transient, standardQuantity / (yieldRate/100)   ← 算值, 权威
 *   getActualQuantity()         Lombok 给持久化列生成的 getter                    ← 存值, 缓存
 * </pre>
 *
 * <p>计算方法当初<b>改名</b>时，旧名字仍然作为字段 getter 存在。于是改名
 * <b>没有破坏编译，只是把调用点的含义从「算值」悄悄换成了「存值」</b> ——
 * 这是最难发现的一类改动：编译过、测试绿、IDE 不报，只有语义变了。</p>
 *
 * <h2>2026-08-27 生产实测</h2>
 *
 * <p>{@code bom_recipe_items} <b>73 行里 55 行 {@code actual_quantity} 是 NULL</b>（75%），
 * 所以这里几乎总是落到 {@code standardQuantity} 兜底 —— 而那是<b>未折算</b>的量。</p>
 *
 * <p>⚠️ 今天两者等价：目前<b>没有任何物料设了出成率</b>（{@code yield_rate} 全为 null 或 100），
 * 而 {@code calculateActualQuantity()} 在那种情况下也返回 {@code standardQuantity}。</p>
 *
 * <p>⛔ 但<b>一旦有人设了出成率</b>（例如 58%），营养标签就会按未折算的量去算 ——
 * <b>原料低估最多 40%</b>，而标签是<b>对外的合规产物</b>。</p>
 *
 * <h2>判据</h2>
 *
 * <p>两条，一条行为一条源码：</p>
 * <ol>
 *   <li><b>行为</b>：出成率 58% 时，{@code calculateActualQuantity()} 必须给出折算后的量，
 *       且它<b>不等于</b>字段 getter（此处为 null）—— 证明这两个东西真的不同，
 *       本闸不是在守一个恒等式。</li>
 *   <li><b>源码</b>：该工具里不许再出现 {@code item.getActualQuantity()}。
 *       ⚠️ 这是代理判据，判不出运行时行为；换个写法（先赋给局部变量）看不出来。
 *       它的作用是<b>让「改回字段 getter」必须显式绕过一道写着理由的闸</b>。</li>
 * </ol>
 */
@DisplayName("营养标签: 用折算后的用量, 不用同名的持久化字段")
class NutritionUsesComputedYieldQuantityContractTest {

    private static final Path TOOL = Paths.get(
            "src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/NutritionLabelGenerateTool.java");

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("行为: 出成率 58% 时算值折算, 而字段 getter 是空的（两者确实不同）")
    void computedQuantityAppliesYieldWhileTheFieldStaysEmpty() {
        BomRecipeItem item = new BomRecipeItem();
        item.setStandardQuantity(new BigDecimal("200"));
        item.setYieldRate(new BigDecimal("58"));

        // 200 / 0.58 = 344.827586...
        assertThat(item.calculateActualQuantity())
                .as("算值必须按出成率折算")
                .isEqualByComparingTo(new BigDecimal("344.827586"));

        // 🔴 这一条是本闸不是恒等式的证据: 新建实体的持久化字段是空的,
        //    而生产库里 73 行有 55 行就是这个状态。
        assertThat(item.getActualQuantity())
                .as("字段 getter 与算值是两个东西 —— 若它也有值, 本闸的前提要重新检查")
                .isNull();
    }

    /**
     * ⚠️ 阳性对照要钉<b>变异之后仍然成立</b>的东西。
     *
     * <p>第一版这里断言的是「文件里有 {@code calculateActualQuantity()}」——
     * 那恰好就是下面那条要守的东西。做变异（把调用点改回字段 getter）时，
     * <b>它和真正的发现一起红了</b>，于是「仪器坏了」与「发现本身」分不开。</p>
     *
     * <p>⇒ 改成钉这个工具<b>是什么</b>（读营养素库、按每 100g 缩放），
     * 那在变异前后都成立；只有真正的那条断言才该随变异变色。</p>
     */
    @Test
    @DisplayName("阳性对照: 文件读得到且确实是那个按 BOM 算营养的工具（仪器活着）")
    void scannerActuallyReadsTheTool() throws IOException {
        String src = stripComments(Files.readString(TOOL, StandardCharsets.UTF_8));
        assertThat(src).as("NutritionLabelGenerateTool 读不到或已改名").isNotEmpty();
        assertThat(src)
                .as("这个工具不再读营养素库 —— 本闸失去被测对象, 请来改它")
                .contains("IngredientNutritionFact");
        assertThat(src)
                .as("这里不再按每 100g 缩放 —— 计算口径变了, 本闸的前提要重新检查")
                .contains("BigDecimal(\"100\")");
    }

    @Test
    @DisplayName("源码: 不许再读那个同名的持久化字段 getter")
    void toolMustNotReadThePersistedFieldGetter() throws IOException {
        assertThat(stripComments(Files.readString(TOOL, StandardCharsets.UTF_8)))
                .as("营养标签又去读 item.getActualQuantity() 了 —— 那是持久化字段(生产库 75% 为 NULL), "
                        + "不是含出成率的算值。一旦有物料设了出成率, 标签会低估原料最多 40%, "
                        + "而标签是对外的合规产物。")
                .doesNotContain("item.getActualQuantity()");
    }
}
