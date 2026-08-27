package com.cretas.aims.service.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部迭代编号（Sprint N / Loop N / Phase N / H-EXP-1 这类）不许出现在
 * <b>后端产出的、会渲染给用户</b>的文案里。
 *
 * <h2>🔴 2026-08-28 逐屏视觉走查抓到 —— 而且是抓到前端那道闸的盲区</h2>
 *
 * <p>同一天先给 web-admin 建了一道闸，扫 {@code web-admin/src} 下的 {@code .vue}/{@code .ts}，
 * 清掉 40 处内部编号，闸绿。</p>
 *
 * <p>随后走到「系统管理 / 审批链配置」屏，一整列描述里全是：</p>
 *
 * <pre>
 *   报销审批      员工报销单审批 (Sprint 4 W2 H-EXP-1)
 *   工资记录审批  月底工资 batch 审批 (Sprint 5 E M-WAGE-INTEGRATION-1 + Sprint 6 W4-B PIECE_RATE/HOURLY/MIXED modes)
 *   预算审批      … 期间结账审批 (Sprint 7 T2 AccountingPeriodServiceImpl.requestClose 触发)
 *   采购订单默认审批  Phase 1 Canvas-Workflow — PO 提交时按金额 / 类别走配置审批链
 * </pre>
 *
 * <p>「预算审批」那条把 <b>Java 类名和方法名</b>写给了用户看。</p>
 *
 * <p>▎前端那道闸<b>一个都抓不到</b> —— 因为这些字符串不在前端源码里，
 * ▎它们是后端 {@link DecisionTypeMetadataRegistry} 的 {@code .description(...)}，
 * ▎由接口下发。<b>「我看到的是全部吗」在同一天开火了第二次。</b></p>
 *
 * <h2>另一类更糟: 写进业务单据</h2>
 *
 * <p>下面这些是 {@code setRemark} / {@code StringBuilder} 拼出来的备注，
 * 会<b>随单据存进数据库</b> —— 改了代码，历史数据里还留着：</p>
 *
 * <pre>
 *   RequisitionCreateTool      req.setRemark("Sprint 8 P4b 采购员 Workdesk 创建")
 *   ProductionBatchCreateTool  sb.append("Sprint 10 Loop 5 AI 1-click 创建. ")
 *   ProcurementOrderCreateTool "Sprint 10 Loop 3 — AI Workdesk 一键采购下单"
 *   ReceiveConfirmCreateTool   new StringBuilder("AI Workdesk Sprint 10 Loop 2 一键收货")
 * </pre>
 *
 * <h2>⚠️ 这道闸刻意只盯两类, 不做全仓禁字</h2>
 *
 * <p>后端源码里 {@code Sprint N} 出现 48 处，其中绝大多数<b>合法</b>：
 * {@code @Operation(summary=...)} 是 Swagger 开发者文档、{@code log.info} 是日志、
 * AI Tool 的 {@code description} 是给<b>模型</b>看的 prompt。
 * 一次性硬红 48 条会让这道闸当天被关掉 ——「宁可窄而可信，不要宽而被关掉」。</p>
 *
 * <p>⇒ 只扫两个<b>确定渲染给用户</b>的形状：</p>
 * <ol>
 *   <li>{@code DecisionTypeMetadataRegistry} 的 {@code .description(...)} / {@code .chineseName(...)}
 *       —— 「审批链配置」屏实拍渲染</li>
 *   <li>任何 {@code setRemark(" … ")} 字面量 —— 随单据落库</li>
 * </ol>
 *
 * <p>⚠️ 这是<b>源码级代理判据</b>：它看不见拼接出来的（{@code "Sprint " + n}）。
 * 作用是让「再写一个内部编号进用户文案」这个动作必须显式绕过一道写着理由的闸。</p>
 */
class NoInternalIterationMarkersInUserFacingTextTest {

    /** Sprint 8 / Loop 3 / Phase 1 / W4-B / H-EXP-1 / P-REQUISITION-1 这一族。 */
    private static final Pattern MARKER = Pattern.compile(
            "Sprint\\s*\\d+|Loop\\s*\\d+|Phase\\s*\\d+|\\bW\\d-[A-Z]|\\b[A-Z]-[A-Z]{2,}-\\d");

    private static final Path MAIN = Path.of("src/main/java/com/cretas/aims");
    private static final Path REGISTRY =
            MAIN.resolve("service/workflow/DecisionTypeMetadataRegistry.java");

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 剥掉注释 —— 注释里写内部编号是**对的**，那是给开发者的。 */
    private static String stripComments(String src) {
        String s = src.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n")) {
            out.append(line.replaceAll("//.*$", "")).append('\n');
        }
        return out.toString();
    }

    @Test
    @DisplayName("阳性对照: 真的读到了 registry, 且它确实在造 description —— 否则下面是恒真式")
    void positiveControl() throws IOException {
        assertTrue(Files.exists(REGISTRY), "找不到 " + REGISTRY + " —— 代码搬家了, 本闸的路径要跟着改");
        String body = stripComments(read(REGISTRY));
        long descriptions = Pattern.compile("\\.description\\(\\s*\"").matcher(body).results().count();
        assertTrue(descriptions > 20,
                "只解析出 " + descriptions + " 条 description ⇒ 这份读数无效, 不要拿它下结论");
        assertTrue(body.contains(".chineseName("), "registry 应有 chineseName —— 抓不到说明结构变了");
    }

    @Test
    @DisplayName("阴性对照: 判据认得出那些形状, 也不误伤正常中文")
    void negativeControl() {
        for (String bad : new String[]{
                "员工报销单审批 (Sprint 4 W2 H-EXP-1)",
                "Phase 1 Canvas-Workflow — PO 提交时按金额走审批链",
                "Sprint 8 P4b 采购员 Workdesk 创建",
                "月底工资 batch 审批 (Sprint 6 W4-B PIECE_RATE modes)",
                "生产 / 采购前的物料申请单审批 (P-REQUISITION-1)",
        }) {
            assertTrue(MARKER.matcher(bad).find(), "应认出内部编号: " + bad);
        }
        for (String ok : new String[]{
                "员工报销单审批",
                "采购订单提交时, 按金额 / 类别走配置的部门审批链",
                "月底工资审批 (支持计件 / 计时 / 混合三种工资模式)",
                "采购员工作台创建",
                "年度 / 季度 / 月度预算 + 超预算授权 + 期间结账审批 (期间结账时自动触发)",
        }) {
            assertFalse(MARKER.matcher(ok).find(), "不该误伤正常中文: " + ok);
        }
    }

    @Test
    @DisplayName("⛔ 审批业务的中文名与描述里没有内部迭代编号(「审批链配置」屏直接渲染它们)")
    void registryTextIsUserReadable() throws IOException {
        String body = stripComments(read(REGISTRY));
        List<String> hits = new ArrayList<>();
        Matcher m = Pattern.compile("\\.(description|chineseName)\\(\\s*\"([^\"]*)\"").matcher(body);
        while (m.find()) {
            if (MARKER.matcher(m.group(2)).find()) {
                hits.add("." + m.group(1) + ": " + m.group(2));
            }
        }
        assertEquals(List.of(), hits,
                "这些字符串**直接渲染在「系统管理 / 审批链配置」屏上**。\n"
                        + "Sprint N / H-EXP-1 是我们内部的迭代编号, 客户不知道它指哪一天;\n"
                        + "而 `AccountingPeriodServiceImpl.requestClose` 是 Java 类名和方法名。\n"
                        + "⇒ 写成用户读得懂的话, 或者删掉。\n"
                        + String.join("\n", hits));
    }

    @Test
    @DisplayName("⛔ setRemark 的字面量里没有内部迭代编号(它会随单据存进数据库)")
    void remarksWrittenToBusinessRecordsAreClean() throws IOException {
        List<String> hits = new ArrayList<>();
        try (var paths = Files.walk(MAIN)) {
            for (Path p : paths.filter(x -> x.toString().endsWith(".java")).toList()) {
                String body = stripComments(read(p));
                Matcher m = Pattern.compile("setRemark\\(\\s*\"([^\"]*)\"").matcher(body);
                while (m.find()) {
                    if (MARKER.matcher(m.group(1)).find()) {
                        hits.add(MAIN.relativize(p) + " → setRemark(\"" + m.group(1) + "\")");
                    }
                }
            }
        }
        assertEquals(List.of(), hits,
                "备注会**随单据落库** —— 改了代码, 历史数据里还留着内部黑话。\n"
                        + String.join("\n", hits));
    }
}
