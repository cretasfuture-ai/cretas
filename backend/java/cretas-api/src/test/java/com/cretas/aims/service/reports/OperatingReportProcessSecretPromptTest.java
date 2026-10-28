package com.cretas.aims.service.reports;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.entity.AIAnalysisResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.AIReportPromptConfigRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 出口 3: 经营报告把逐产品毛利 / 毛利率和全厂毛利汇总发给模型。走真实入口 {@link OperatingReportGenerator#scheduled},
 * 毛利数字由真实的 {@link OperatingReportGrossMarginQuery#payload} 从「SQL 行」算出, 断言打在发给模型的正文上。
 */
class OperatingReportProcessSecretPromptTest {

    private final FactoryRepository factories = mock(FactoryRepository.class);
    private final OperatingReportCollector collector = mock(OperatingReportCollector.class);
    private final OperatingReportStore store = mock(OperatingReportStore.class);
    private final PythonLLMClient model = mock(PythonLLMClient.class);
    private final OperatingReportCodec codec = new OperatingReportCodec(new ObjectMapper());
    private final LocalDate day = LocalDate.of(2026, 10, 20);

    /** 销售毛利 SQL 的行: 保密产品 收入 9999.99 / 成本 7777.77, 两个普通产品。 */
    private final List<Map<String, Object>> sqlRows = List.of(
            sqlRow(SECRET, SECRET_NAME, "9999.99", SECRET_COST),
            sqlRow(NORMAL, "普通甲", "3000.00", NORMAL_COST),
            sqlRow(NORMAL2, "普通乙", "5000.00", NORMAL2_COST));

    private static Map<String, Object> sqlRow(String id, String name, String revenue, BigDecimal cost) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("product_type_id", id);
        row.put("product_name", name);
        row.put("revenue", new BigDecimal(revenue));
        row.put("costed_revenue", new BigDecimal(revenue));
        row.put("cost", cost);
        row.put("costed_line_count", 2);
        row.put("line_count", 2);
        return row;
    }

    private OperatingReportGenerator generator(ProcessSecretLlmCostScope scope) throws Exception {
        Factory factory = new Factory();
        factory.setId(FACTORY);
        factory.setType(FactoryType.FACTORY);
        factory.setIsActive(true);
        when(factories.findById(FACTORY)).thenReturn(Optional.of(factory));
        Map<String, Object> high = OperatingReportGrossMarginQuery.payload(sqlRows, false);
        Map<String, Object> low = OperatingReportGrossMarginQuery.payload(sqlRows, true);
        when(collector.collect(any(), eq("daily"), eq(day), eq(day))).thenReturn(new OperatingReportAssembler.Input(
                "daily", day, day, "2026-10-20T20:00:00Z", List.of(), Map.of(), Map.of(), Map.of(), Map.of(), high,
                low, List.of(), Map.of(), List.of(), Map.of()));
        when(store.save(anyString(), any(), any(Boolean.class))).thenAnswer(inv -> {
            AIAnalysisResult result = new AIAnalysisResult();
            result.setAnalysisText(codec.encode(inv.getArgument(1)));
            return result;
        });
        OperatingReportGenerator g = new OperatingReportGenerator(factories, mock(PermissionService.class), collector,
                new OperatingReportAssembler(), codec, store, model, mock(AIReportPromptConfigRepository.class));
        ReflectionTestUtils.setField(g, "llmCostScope", scope);
        return g;
    }

    private String prompt(ProcessSecretLlmCostScope scope) throws Exception {
        OperatingReportGenerator g = generator(scope);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(model.chat(anyString(), prompt.capture())).thenReturn("{\"insights\":[]}");
        g.scheduled(FACTORY, "daily", day, day);
        return prompt.getValue();
    }

    @Test
    void protectedProductMarginIsNotSentAndTheMarginSummaryIsSummedOverTheOtherProducts() throws Exception {
        String prompt = prompt(protectedScope());

        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal profit = BigDecimal.ZERO;
        for (Map<String, Object> row : sqlRows) {
            if (SECRET.equals(row.get("product_type_id"))) continue;
            cost = cost.add((BigDecimal) row.get("cost"));
            profit = profit.add(((BigDecimal) row.get("costed_revenue")).subtract((BigDecimal) row.get("cost")));
        }
        assertThat(prompt)
                .contains("不含 1 个保密产品")
                .contains(cost.toPlainString(), profit.toPlainString())
                // 保密产品的成本 / 毛利 (2222.22) / 毛利率, 以及含保密产品的全厂成本 13333.33 都不在
                .doesNotContain(SECRET_COST.toPlainString(), "2222.22", "22.2222", "13333.33", SECRET + ".");
    }

    @Test
    void anAuthorizedCallerGetsTheProtectedMarginAndTheSystemPromptCarriesTheHarness() throws Exception {
        OperatingReportGenerator g = generator(protectedScope());
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(model.chat(system.capture(), prompt.capture())).thenReturn("{\"insights\":[]}");

        try (var ignored = as(owner())) {
            g.scheduled(FACTORY, "daily", day, day);
        }

        assertThat(prompt.getValue()).contains("2222.22", "13333.33").doesNotContain("不含 1 个保密产品");
        assertThat(system.getValue()).contains(ProcessSecretLlmCostScope.HARNESS_INSTRUCTION);
    }

    @Test
    void negativeControlTheSameInstrumentSeesTheSecretMarginWhenNothingIsProtected() throws Exception {
        String prompt = prompt(unprotectedScope());

        // 快照里没有逐产品成本 (只有毛利 / 毛利率), 所以可见的是保密产品的毛利、毛利率和含它的全厂成本
        assertThat(prompt).contains("2222.22", "22.2222", "13333.33", SECRET + ".").doesNotContain("保密产品");
    }

    @Test
    void theStoredReportSnapshotIsUnchanged() throws Exception {
        prompt(protectedScope());

        ArgumentCaptor<OperatingReportSnapshot> saved = ArgumentCaptor.forClass(OperatingReportSnapshot.class);
        verify(store).save(eq(FACTORY), saved.capture(), eq(true));
        // 保存 / 展示的快照照旧 (展示时另有按调用者的价格权限投影), 只有发给模型的那份去掉了保密产品
        assertThat(codec.encode(saved.getValue())).contains("13333.33");
    }
}
