package com.cretas.aims.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KPI 的「产能利用率」必须与产能报表<b>同源</b>。
 *
 * <h3>它守的那次事故（2026-08-29 逐屏走查）</h3>
 * 同一个租户、同一天，两个接口：
 * <pre>
 *   GET /reports/capacity-utilization  ->  averageUtilization = 2.3
 *   GET /reports/kpi-metrics           ->  capacityUtilization = 0.0
 * </pre>
 * 老板在「报表」tab 看到 2.3%，在「数据洞察」看到 0.0%。
 * <b>同一个名字两个数，而用户没有任何办法知道哪个是对的。</b>
 *
 * <p>根因不是「两份实现漂了」，比那更隐蔽：KPI 那边写的是
 * {@code .capacityUtilization(oeeReport.getAvailability())} ——
 * <b>产能利用率不是设备可用率</b>。而同一个 DTO 里 {@code equipmentAvailability}
 * 已经装了 availability 一次，于是同一个量装了两次，其中一次挂着别人的名字。
 *
 * <p>▎形态 D 的一种更隐蔽的长相：<b>不是两份会漂，是两个量共用一个标签。</b>
 *
 * <h3>这道闸是代理判据</h3>
 * 它只看 {@code getKpiMetricsDTO} 方法体里那一行怎么写，
 * 看不见「传进来的值本身对不对」。真正的兜底是部署后同时打两个接口比一次 ——
 * 那一步写在 PR 的判据里。
 */
class KpiCapacityUtilizationSourceContractTest {

    private static final Path SERVICE = Paths.get(
            "src/main/java/com/cretas/aims/service/report/impl/ProductionReportServiceImpl.java");

    private static String stripComments(String source) {
        String noBlock = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return Pattern.compile("//.*$", Pattern.MULTILINE).matcher(noBlock).replaceAll("");
    }

    /** 取 {@code getKpiMetricsDTO} 的方法体（配平大括号）。 */
    private static String kpiMethodBody() throws IOException {
        String code = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        int sig = code.indexOf("KpiMetricsDTO getKpiMetricsDTO(");
        if (sig < 0) {
            return "";
        }
        int open = code.indexOf('{', sig);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(open, i + 1);
                }
            }
        }
        return code.substring(open);
    }

    @Test
    @DisplayName("阳性对照: 真的取到了 getKpiMetricsDTO 的方法体 —— 否则下面两条都没意义")
    void methodBodyIsActuallyFound() throws IOException {
        String body = kpiMethodBody();
        assertThat(body)
                .as("扫描器没找到 getKpiMetricsDTO 的方法体, 后面的断言全是无意义的绿")
                .isNotBlank();
        assertThat(body)
                .as("方法体里应当能看到 KpiMetricsDTO 的构造")
                .contains("KpiMetricsDTO.builder()");
        assertThat(body).contains(".capacityUtilization(");
    }

    @Test
    @DisplayName("🔴 产能利用率不许拿 availability 顶 —— 那是另一个量")
    void capacityUtilizationIsNotAvailability() throws IOException {
        String body = kpiMethodBody();
        int at = body.indexOf(".capacityUtilization(");
        assertThat(at).as("找不到 .capacityUtilization( —— 扫描器坏了").isGreaterThanOrEqualTo(0);

        // 只看这一个 builder 调用的实参, ⛔ 不用整段 grep(整段里 availability 本来就该出现一次)
        int end = body.indexOf('\n', at);
        String arg = body.substring(at, end < 0 ? body.length() : end);

        assertThat(arg)
                .as("产能利用率 != 设备可用率。这一行原来是 .capacityUtilization(oeeReport.getAvailability()), "
                        + "导致「报表」tab 与「数据洞察」对同一个指标给出两个数。"
                        + " 实参: %s", arg.trim())
                .doesNotContain("getAvailability");
    }

    @Test
    @DisplayName("产能利用率必须与产能报表同源 —— 走 capacityUtilizationFromReport")
    void capacityUtilizationComesFromTheCapacityReport() throws IOException {
        String body = kpiMethodBody();
        int at = body.indexOf(".capacityUtilization(");
        int end = body.indexOf('\n', at);
        String arg = body.substring(at, end < 0 ? body.length() : end);

        assertThat(arg)
                .as("⛔ 不许在 KPI 里重算一遍产能利用率 —— 那就真成了两份, 一定会漂。"
                        + " 取 getCapacityUtilizationReport 那一份(它有来源、有口径说明)。")
                .contains("capacityUtilizationFromReport");
    }

    @Test
    @DisplayName("阴性对照: equipmentAvailability 那一行仍然**应该**用 availability")
    void equipmentAvailabilityStillUsesAvailability() throws IOException {
        String body = kpiMethodBody();
        assertThat(body)
                .as("闸不许宽到把「设备可用率取 availability」也判成违例 —— 那一行是对的")
                .contains(".equipmentAvailability(");
    }
}
