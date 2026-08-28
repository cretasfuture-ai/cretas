package com.cretas.aims.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code StateMachineService} 的每个能力都必须有 controller 暴露 ——
 * ⛔ 否则它在生产上根本到不了。
 *
 * <h4>🔴 2026-08-28 生产实测抓到</h4>
 *
 * 工作流设计器（{@code /system/workflow-designer}）的三个动作全部 404：
 *
 * <pre>
 *   保存草稿    saveStateMachine   -> POST /{f}/state-machines           缺 /rules
 *   发布        publishDraft       -> POST /{f}/state-machines/../publish 缺 /rules **且后端没有这个端点**
 *   版本历史    getVersionHistory  -> GET  /{f}/state-machines/../versions 同上
 * </pre>
 *
 * ⚠️ 两个独立的坏叠在一起：**前缀写错** 且 **端点根本不存在**。
 * 只补前缀不解决问题 —— 实测 {@code GET /rules/state-machines/PRODUCTION_WORKFLOW/versions}
 * 补上 {@code /rules} 之后**仍然 404**，而阳性对照 {@code GET /rules/state-machines} 是 200。
 *
 * <h4>这道闸守的是哪一类</h4>
 *
 * 不是「那三条路径对不对」，是「**service 层实现了能力，却没有任何 controller 调它**」——
 * 本仓反复记过的「机制在、没接上」。service 的单测能绿，而生产上没有一条路径到得了它。
 *
 * ⛔ 想让它变绿，是去加 controller 端点；**不是**往
 * {@link #DELIBERATELY_NOT_EXPOSED} 里加名字 —— 那个清单只放**内部 helper**
 * （被别的 service 调用、不面向用户的），每加一条都要能说出它为什么不该有端点。
 */
@DisplayName("状态机 — service 能力必须有 controller 暴露")
class StateMachineServiceExposureContractTest {

    private static final Path CONTROLLER = Paths.get(
            "src/main/java/com/cretas/aims/controller/RuleController.java");
    private static final Path SERVICE = Paths.get(
            "src/main/java/com/cretas/aims/service/StateMachineService.java");

    /**
     * 内部 helper —— 由别的 service 调用，不面向用户，故意不给端点。
     * ⚠️ 是**清单**不是计数：计数会掩盖「暴露一个、同时藏起另一个」。
     */
    private static final Set<String> DELIBERATELY_NOT_EXPOSED = Set.of(
            "getInitialState",          // 执行引擎内部取初态
            "getAllStates",             // 同上
            "isFinalState",             // 同上
            "executeTransitionByEvent", // 事件驱动入口, 由领域服务内部调用
            "getPublishedStateMachine"  // 设计器要的是"当前这一份"(含草稿), 走 getStateMachine
    );

    /** 剥掉注释与字符串字面量 —— 否则 javadoc 里**提到**方法名会被数成**调用**。 */
    private static String stripCommentsAndStrings(String source) {
        String s = source.replaceAll("(?s)/\\*.*?\\*/", "");
        s = s.replaceAll("//[^\n]*", "");
        return s.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static Set<String> serviceMethods() throws IOException {
        String s = stripCommentsAndStrings(read(SERVICE));
        Matcher m = Pattern.compile("(?m)^\\s{4}[\\w<>,\\.\\[\\] ]+?\\s(\\w+)\\s*\\([^;{]*\\)\\s*;").matcher(s);
        Set<String> out = new TreeSet<>();
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static Set<String> exposedMethods() throws IOException {
        String s = stripCommentsAndStrings(read(CONTROLLER));
        Matcher m = Pattern.compile("stateMachineService\\.(\\w+)\\s*\\(").matcher(s);
        Set<String> out = new TreeSet<>();
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    @DisplayName("阳性对照：这把尺子读得到两侧的方法名")
    void theRulerCanActuallyReadBothSides() throws IOException {
        // ⛔ 没有这条，「0 处未暴露」可能只是因为两个正则谁也匹配不上。
        assertThat(serviceMethods())
                .as("一个 service 方法都没解析出来 —— 正则过期了，本次读数作废")
                .hasSizeGreaterThan(8);
        assertThat(exposedMethods())
                .as("一个 controller 调用都没解析出来 —— 本次读数作废")
                .hasSizeGreaterThan(5);
    }

    @Test
    @DisplayName("剥注释确实生效：javadoc 里提到的名字不算调用")
    void mentioningAMethodInACommentIsNotCallingIt() {
        String fake = "/** 见 stateMachineService.publishDraft(...) 的说明 */\n"
                + "class X { void f() { stateMachineService.getVersionHistory(a, b); } }";
        String stripped = stripCommentsAndStrings(fake);
        assertThat(stripped)
                .as("注释没被剥掉 —— 闸会把自己的说明文字当成被测对象（本仓记过四次）")
                .doesNotContain("publishDraft");
        assertThat(stripped).contains("getVersionHistory");
    }

    @Test
    @DisplayName("每个面向用户的 service 能力都有 controller 端点")
    void everyUserFacingCapabilityIsReachable() throws IOException {
        Set<String> exposed = exposedMethods();
        List<String> unreachable = serviceMethods().stream()
                .filter(m -> !exposed.contains(m))
                .filter(m -> !DELIBERATELY_NOT_EXPOSED.contains(m))
                .collect(Collectors.toList());

        assertThat(unreachable)
                .as("这些 service 方法实现了，但没有任何 controller 调它 —— "
                        + "生产上到不了。⛔ 不要往 DELIBERATELY_NOT_EXPOSED 里加名字，去加端点。")
                .isEmpty();
    }

    @Test
    @DisplayName("豁免清单不许留过期条目（方法删了就该从清单里去掉）")
    void theExemptionListHasNoStaleEntries() throws IOException {
        Set<String> methods = serviceMethods();
        List<String> stale = DELIBERATELY_NOT_EXPOSED.stream()
                .filter(m -> !methods.contains(m))
                .collect(Collectors.toList());
        assertThat(stale)
                .as("豁免清单里有 service 上已经不存在的方法 —— 清单在替不存在的东西挡着")
                .isEmpty();
    }

    @Test
    @DisplayName("设计器那三条路径的端点确实在 RuleController 上")
    void theThreeDesignerEndpointsExist() throws IOException {
        String src = read(CONTROLLER);
        assertThat(src)
                .as("保存端点没了 —— 设计器「保存草稿」会重新 404")
                .contains("@PostMapping(\"/state-machines/{entityType}\")");
        assertThat(src)
                .as("版本历史端点没了")
                .contains("@GetMapping(\"/state-machines/{entityType}/versions\")");
        assertThat(src)
                .as("发布端点没了")
                .contains("@PostMapping(\"/state-machines/{entityType}/publish/{draftId}\")");
    }
}
