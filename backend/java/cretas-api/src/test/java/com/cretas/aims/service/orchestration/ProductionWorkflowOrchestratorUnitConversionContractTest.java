package com.cretas.aims.service.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3（2026-05-10 客户对接会议）单位换算 —— BOM 层用 g，仓库/调拨层用 kg，系统自动换算。
 *
 * <h2>🔴 这个文件为什么被重写（2026-08-27）</h2>
 *
 * <p>原来的 8 条用例反射调用 {@code private convertUnit(...)}，并用<b>全 null 依赖</b>
 * 构造 orchestrator，注释写着「只调 convertUnit 不会触碰它们」。
 * 那个假设<b>已经不成立</b>：换算逻辑后来被收敛进了 {@code UnitConversionService}，
 * {@code convertUnit} 现在只剩一行委派，于是 8 条全部 NPE
 * （{@code Cannot invoke "UnitConversionService.convert(...)"}），
 * 长期躺在夜间棘轮的存量红名单里。</p>
 *
 * <p><b>收敛本身是对的</b> —— 本仓在单位口径上栽过（后端把「件/个/只」拆成三个单位而
 * 前端仍折成一个，用户无法满足）。换算只该有一份。</p>
 *
 * <h2>为什么不是「把桩补上」</h2>
 *
 * <p>⛔ 因为那样会写出一条<b>恒真式</b>：orchestrator 这一层拿不到真实换算表，
 * 只能 mock {@code UnitContractService}。而把它打桩成「返回 1000 倍」再断言 1000 倍，
 * 是拿自己的桩验证自己 —— 变异改产品代码它<b>不会红</b>。</p>
 *
 * <p>换算数学已经由 {@code service/unit/UnitContractServiceTest} 守着，
 * 并且是<b>绿的</b>（{@code kg.baseCode == "g"}、{@code factorToBase == 1000}、
 * {@code convert(kg → g)}、以及不支持的组合返回失败态）。
 * 在这里再写一份就是「同一个东西有两份，它一定会漂」。</p>
 *
 * <h2>⇒ 所以这里改成守<b>性质</b>：orchestrator 不许自己长出换算数学</h2>
 *
 * <p>这条判据抓的正是「收敛完成之后又被人加回去」那个动作 ——
 * 那是让两份重新分叉的唯一入口，也是本文件唯一还有价值的断言。</p>
 *
 * <p>⚠️ 代理性写明：这是<b>源码级</b>判据，它判不出运行时行为。
 * 换个写法（先把因子赋给变量、或从配置读）本闸看不出来。
 * 它的作用是<b>让「把换算搬回 orchestrator」这个动作必须显式地绕过一道写着理由的闸</b>，
 * 而不是物理上不可能。</p>
 */
@DisplayName("D3: 单位换算只有一份 —— orchestrator 只许委派, 不许自己算")
class ProductionWorkflowOrchestratorUnitConversionContractTest {

    private static final Path ORCHESTRATOR = Paths.get(
            "src/main/java/com/cretas/aims/service/orchestration/ProductionWorkflowOrchestrator.java");

    private static String source() throws IOException {
        return Files.readString(ORCHESTRATOR, StandardCharsets.UTF_8);
    }

    /** 去掉注释，免得闸把自己的说明文字当成被测对象（本仓多次栽在这上面）。 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("阳性对照：文件读得到且确实有 convertUnit（仪器活着）")
    void scannerActuallyReadsTheOrchestrator() throws IOException {
        String src = source();
        // 若哪天文件被改名/拆分，这两条会红，提醒来改本闸 ——
        // 否则 stripComments 之后扫到空字符串，下面两条会静默恒绿。
        assertThat(src).as("读不到 ProductionWorkflowOrchestrator.java").isNotEmpty();
        assertThat(src).as("convertUnit 不在了 —— 本闸失去被测对象，请来改它").contains("convertUnit");
    }

    @Test
    @DisplayName("convertUnit 必须委派给统一的 UnitConversionService")
    void convertUnitDelegatesToTheSharedService() throws IOException {
        String src = stripComments(source());
        assertThat(src)
                .as("convertUnit 不再委派给 UnitConversionService —— 换算又变成两份了。"
                        + "单位口径本仓栽过（后端拆三个单位而前端折成一个，用户无法满足），只该有一份。")
                .contains("unitConversionService.convert(");
    }

    @Test
    @DisplayName("orchestrator 里不许出现换算因子字面量")
    void orchestratorCarriesNoConversionFactorOfItsOwn() throws IOException {
        String src = stripComments(source());
        // 1000 是 g↔kg，也是这类漂移最常见的复发形状。
        assertThat(src)
                .as("orchestrator 里出现了换算因子字面量 —— 换算数学正在被搬回来。"
                        + "请改用 unitConversionService；换算表由 UnitContractService 唯一定义。")
                .doesNotContain("BigDecimal(\"1000\")")
                .doesNotContain("valueOf(1000)")
                .doesNotContain("divide(THOUSAND")
                .doesNotContain("multiply(THOUSAND");
    }
}
