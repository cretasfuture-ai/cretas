package com.cretas.aims.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸：<b>原料盘点</b>与<b>半成品盘点</b>在「没配审批流」时必须是同一套规则。
 *
 * <h4>为什么需要</h4>
 *
 * <p>两者是同一件事的两个变体：数实物 → 改账面 → 过账。而且它们用的是
 * <b>同一个 decision_type</b>（{@code INVENTORY_ADJUSTMENT}），也就是说
 * <b>一条审批流会同时接到这两种单子</b>。</p>
 *
 * <p>🔴 2026-08-27 实测: 它们此前<b>不一致</b> ——</p>
 *
 * <ul>
 *   <li>原料盘点：有差异 → 409 {@code STOCKTAKE_APPROVAL_WORKFLOW_REQUIRED}</li>
 *   <li>半成品盘点：<b>无条件 APPROVED</b>，连有没有差异都不看</li>
 * </ul>
 *
 * <p>而半成品的 {@code apply()} 不只调库存，还<b>过财务凭证</b>
 * （盘盈进收入侧 / 盘亏进损耗侧）—— 要有区别，也该是它<b>更</b>严，不是更松。</p>
 *
 * <p>生产实测：F006 的 8 张半成品盘点全部在那条审批流发布（2026-08-25 23:15）之前过账，
 * {@code workflow_instance_id} 全为 null，其中 7 张 {@code approved_by == submitted_by}。</p>
 *
 * <h4>为什么是闸而不是抽成一份</h4>
 *
 * <p>{@code FactoryStocktakeItem} 与 {@code SemiFinishedStocktakeItem} 是两个没有共同父类的
 * 实体，判据抽不成一份。⇒ 按本仓的规矩：<b>抽不动就要有一道闸钉住两份一致</b>，
 * 而不是靠人记得同步改。</p>
 *
 * <h4>⚠️ 这道闸的代理判据</h4>
 *
 * <p>它判的是<b>源码里出现了同一个错误码</b>，不是运行时真的抛了。
 * 「真的抛了」由两边各自的行为测试证。⛔ 不要把这道闸当成行为验证 ——
 * 它守的是「有人删掉其中一边时当场变红」。</p>
 */
@DisplayName("两种盘点在没配审批流时必须同样 fail-closed")
class BothStocktakeTypesFailClosedContractTest {

    private static final Path RAW = Paths.get(
            "src/main/java/com/cretas/aims/service/factory/impl/FactoryStocktakeServiceImpl.java");
    private static final Path SEMI = Paths.get(
            "src/main/java/com/cretas/aims/service/factory/impl/SemiFinishedStocktakeServiceImpl.java");

    /** 没配审批流又有差异时, 两边都必须抛这个码。 */
    private static final String FAIL_CLOSED_CODE = "STOCKTAKE_APPROVAL_WORKFLOW_REQUIRED";

    /** 两边都必须把可判的量发给条件节点 —— 同一条审批流会接到两种单子。 */
    private static final List<String> SHARED_CONTEXT_KEYS = List.of(
            "surplusCount", "shortageCount", "differenceCount",
            "hasDifference", "surplusQuantityByUnit", "shortageQuantityByUnit");

    /**
     * 逐行剔注释。
     *
     * <p>🔴 不剔就是假闸：本次改动在<b>两个</b>文件的注释里都逐字写着
     * {@code STOCKTAKE_APPROVAL_WORKFLOW_REQUIRED}（半成品那边的注释正是在解释
     * 「原料那边抛的是这个码」）。</p>
     */
    private static String code(Path p) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : Files.readString(p, StandardCharsets.UTF_8).split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                continue;
            }
            int slash = line.indexOf("//");
            out.append(slash >= 0 ? line.substring(0, slash) : line).append('\n');
        }
        return out.toString();
    }

    @Test
    @DisplayName("阳性对照: 两个文件都读得到, 而且不是空的")
    void bothFilesAreReadable() throws IOException {
        assertThat(code(RAW)).as("原料盘点源码读不到 —— 路径漂了, 后面的绿不作数").isNotEmpty();
        assertThat(code(SEMI)).as("半成品盘点源码读不到 —— 同上").isNotEmpty();
        // 两边都必须真的调过审批引擎, 否则「都有那个码」可能只是巧合
        assertThat(code(RAW)).contains("startWorkflowIfConfigured");
        assertThat(code(SEMI)).contains("startWorkflowIfConfigured");
    }

    @Test
    @DisplayName("🔴 两边都必须在「没配审批流 + 有差异」时 fail-closed")
    void bothRefuseToAutoApproveWhenThereIsADifference() throws IOException {
        assertThat(code(RAW))
                .as("原料盘点丢了 fail-closed —— 没配审批流时盘出差异会自己批自己")
                .contains(FAIL_CLOSED_CODE);
        assertThat(code(SEMI))
                .as("""
                        半成品盘点丢了 fail-closed。它的 apply() 不只调库存, 还过财务凭证
                        (盘盈进收入侧 / 盘亏进损耗侧) —— 没配审批流时直接过账 = 无人审批就动账。
                        与原料盘点用的是同一个 decision_type, 两套规则必然漂。""")
                .contains(FAIL_CLOSED_CODE);
    }

    @Test
    @DisplayName("🔴 两边发给条件节点的键必须一致 —— 同一条审批流会接到两种单子")
    void bothEmitTheSameConditionKeys() throws IOException {
        String raw = code(RAW);
        String semi = code(SEMI);
        for (String key : SHARED_CONTEXT_KEYS) {
            assertThat(raw)
                    .as("原料盘点没发 %s —— 条件节点判不到", key)
                    .contains('"' + key + '"');
            assertThat(semi)
                    .as("""
                            半成品盘点没发 %s。两者共用同一个 decision_type(INVENTORY_ADJUSTMENT),
                            一条审批流会同时接到这两种单子 —— 键名不一致会让同一个条件表达式
                            对其中一种静默失效(SpEL 引用不存在的变量会求值失败并当 false)。""", key)
                    .contains('"' + key + '"');
        }
    }

    @Test
    @DisplayName("零差异仍然放行 —— ⛔ 没有差异就没有东西要批, 拦住是刁难")
    void zeroDifferenceIsStillAllowedThrough() throws IOException {
        // 两边都必须是「有差异才拦」而不是「没配就拦」。
        assertThat(code(RAW))
                .as("原料盘点变成了无条件拦截 —— 零差异单也过不去")
                .contains("if (hasDifference)");
        assertThat(code(SEMI))
                .as("半成品盘点变成了无条件拦截 —— 零差异单也过不去")
                .contains("if (hasDifference)");
    }
}
