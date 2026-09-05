package com.cretas.aims.service.production;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 缺料预警的出成率解析器<b>不许</b>再读 {@code bom_yield_suggestions} 里 APPLIED 的建议。
 * (任务 RECON-LEGAL-VALUE-SENTINEL-20260906)
 *
 * <h2>为什么这条只能写成源码闸</h2>
 *
 * <p>被删掉的是一个<b>协作者</b>: {@code ProductionPlanServiceImpl} 上原来有
 * {@code @Autowired(required = false) BomYieldSuggestionRepository}, 解析器在读表头之前
 * 先查一次「最近一条 APPLIED 建议」并直接返回它的 {@code suggestedYieldRate}
 * (只判 {@code isPositive})。字段既然拆掉了, <b>行为测试就没有东西可以桩</b> ——
 * 没有协作者可以注入, 也就没有「注入了却没被读」这个可观测状态。
 *
 * <p>⚠️ 所以这是一道**代理判据**, 并且明说它看不见什么: 它扫的是源码文本(剥掉注释/字符串外的
 * 标识符), 抓不住有人用反射或换个 Repository 名字把同一件事做回来。它拦的是「顺手把那段
 * 加回来」这一种最可能的退化。
 *
 * <h2>为什么必须拦</h2>
 *
 * <p>#594 之后 {@code BomYieldSuggestionServiceImpl} 只生成 <b>PENDING</b> 建议, 喂
 * 「标准出成率」页面给人看; 它<b>不再</b>自动 apply。而 prod 上还躺着 12 条 APPLIED
 * (2026-09-06 直查, 全部 {@code applied_by='SYSTEM'}, 最后 6 条都是 {@code 100.00}) ——
 * 那是被 #594 删掉的自动 apply 循环留下的残渣。让它回流进解析器, 等于把
 * 「自学习把自己学没了」那条路重新接上一次。
 *
 * <p>⇒ 出成率对备料折算的<b>唯一</b>入口是 {@code bom_recipes.overall_yield_rate}。
 *
 * <h2>普查(硬约束 8)</h2>
 *
 * <p>{@code findFirstByFactoryIdAndProductTypeIdAndStatusAndDeletedAtIsNullOrderByAppliedAtDescGeneratedAtDesc}
 * 在 {@code src/main} 里<b>只有这一个</b>调用点(2026-09-06 全仓 grep 确认), 删掉之后是 0 个。
 * Repository 上那个方法<b>故意保留</b>(供出成率页面/审计追溯), 它本身不是缺陷载体 ——
 * 这条登记见本类 {@link #repositoryMethodItselfIsDeliberatelyKept()}。
 */
class YieldResolverHasNoAppliedSuggestionBackdoorContractTest {

    private static final Path SERVICE = Path.of(
            "src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java");
    private static final Path REPOSITORY = Path.of(
            "src/main/java/com/cretas/aims/repository/bom/BomYieldSuggestionRepository.java");

    private String source(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /**
     * 剥掉注释再扫 —— ⛔ 不要在原文上 grep 标识符。
     *
     * <p>本仓反复踩过「闸把自己所解释的那段注释当成被测对象」(本文件顶上就写着
     * {@code BomYieldSuggestionRepository} 这个词好几次)。禁词类判据必须落在会编译的代码上。
     */
    private String codeOnly(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ")
                  .replaceAll("(?m)^\\s*//.*$", " ");
    }

    @Test
    @DisplayName("阳性对照: 两个源文件都读得到, 且确实是那两个类")
    void positiveControlFilesAreReadable() throws IOException {
        assertThat(SERVICE).as("路径变了, 本闸要跟着改 —— 读不到文件时下面全是恒真式").exists();
        assertThat(REPOSITORY).exists();
        String code = codeOnly(source(SERVICE));
        assertThat(code.length()).as("剥注释剥过头 ⇒ 下面的 not.contains 无意义").isGreaterThan(50_000);
        assertThat(code).as("剥注释把被测方法也剥掉了").contains("resolveEffectiveProductYieldRate");
        assertThat(code).contains("PlanInputShareResolver");
    }

    @Test
    @DisplayName("🔴 ProductionPlanServiceImpl 的代码里不再出现 BomYieldSuggestion —— APPLIED 建议不许回流")
    void serviceCodeHasNoSuggestionRepositoryReference() throws IOException {
        String code = codeOnly(source(SERVICE));
        assertThat(code)
                .as("APPLIED 建议又被读进备料折算了 —— prod 上那 12 条全是 applied_by=SYSTEM 的残渣, "
                        + "最后 6 条都是 100.00; 让它回流等于把 #594 删掉的自动 apply 换个位置接回来")
                .doesNotContain("BomYieldSuggestion");
        assertThat(code)
                .as("解析器又去查 APPLIED 建议了")
                .doesNotContain("Status.APPLIED");
    }

    @Test
    @DisplayName("🔴 表头 overall_yield_rate 仍然是解析器唯一读的东西(阴性对照: 别把闸做成空气)")
    void serviceStillReadsHeadLevelYield() throws IOException {
        String code = codeOnly(source(SERVICE));
        // 上一条只说「不许读建议」。如果有人把整个解析器删了, 上一条照样绿 ——
        // 这一条钉住「它还在读表头」, 否则那条 doesNotContain 就是恒真式。
        assertThat(code).contains("getOverallYieldRate");
        assertThat(code).contains("findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus");
    }

    @Test
    @DisplayName("登记(硬约束 8): Repository 上那个 APPLIED 查询方法**故意保留**, 不是漏删")
    void repositoryMethodItselfIsDeliberatelyKept() throws IOException {
        String repo = source(REPOSITORY);
        assertThat(repo)
                .as("这个查询方法被顺手删了 —— 它是「标准出成率」页面/审计追溯的读口, "
                        + "本次只是把**生产计划这一侧**的调用点摘掉(src/main 里从 1 个变 0 个), "
                        + "⛔ 不是要把这个能力删掉")
                .contains("findFirstByFactoryIdAndProductTypeIdAndStatusAndDeletedAtIsNullOrderByAppliedAtDescGeneratedAtDesc");
    }
}
