package com.cretas.aims.service.processsecret;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 保密范围本身: 与调用者无关、计划也算、认不出产品时排除、取不到范围就抛。 */
class ProcessSecretLlmCostScopeTest {

    @Test
    void protectedRowsAreDroppedAndTheRestKeptWithANote() {
        ProcessSecretLlmCostScope.Rows rows = protectedScope().withoutProtectedBatches(FACTORY, weeklyRows());

        assertThat(rows.allowed()).extracting(r -> r.get("productTypeId")).containsExactly(NORMAL, NORMAL2);
        assertThat(rows.excludedRows()).isEqualTo(1);
        assertThat(rows.note()).contains("不含 1 个保密产品");
    }

    @Test
    void aBatchOfAPlanThatProducesTheProtectedProductIsDroppedEvenWhenItsOwnProductIdIsNotProtected() {
        // 多产出计划: 批次挂在原料锚点上, 只看产品会漏
        Map<String, Object> anchorRow = weeklyRow(9L, "RAW-ANCHOR", SECRET_PLAN, new BigDecimal("999.99"));

        ProcessSecretLlmCostScope.Rows rows = protectedScope().withoutProtectedBatches(FACTORY, List.of(anchorRow));

        assertThat(rows.allowed()).isEmpty();
    }

    @Test
    void aRowWithoutProductIsDroppedWhenTheFactoryHasProtectedProducts() {
        Map<String, Object> unknown = weeklyRow(8L, null, null, new BigDecimal("55.55"));

        assertThat(protectedScope().withoutProtectedBatches(FACTORY, List.of(unknown)).allowed()).isEmpty();
        assertThat(unprotectedScope().withoutProtectedBatches(FACTORY, List.of(unknown)).allowed()).hasSize(1);
    }

    @Test
    void nothingIsDroppedInAFactoryWithoutProtectedProducts() {
        ProcessSecretLlmCostScope.Rows rows = unprotectedScope().withoutProtectedBatches(FACTORY, weeklyRows());

        assertThat(rows.allowed()).hasSize(3);
        assertThat(rows.note()).isEmpty();
    }

    @Test
    void containsProtectedFindsANestedProtectedRow() {
        Map<String, Object> data = Map.of("reportType", "weekly", "batches", weeklyRows());

        assertThat(protectedScope().containsProtected(FACTORY, data)).isTrue();
        assertThat(protectedScope().containsProtected(FACTORY,
                Map.of("batches", protectedScope().withoutProtectedBatches(FACTORY, weeklyRows()).allowed()))).isFalse();
    }

    @Test
    void scopeLookupFailurePropagatesSoCallersDoNotSend() {
        ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        when(access.protectedProductTypeIds(anyString())).thenThrow(new IllegalStateException("db down"));
        ProcessSecretLlmCostScope scope = new ProcessSecretLlmCostScope(new ProcessSecretAggregateExclusions(
                access, mock(com.cretas.aims.repository.ProductionPlanRepository.class),
                mock(com.cretas.aims.repository.bom.BomRecipeRepository.class)));

        assertThatThrownBy(() -> scope.withoutProtectedBatches(FACTORY, weeklyRows()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void scopeDoesNotDependOnTheCaller() {
        // 没有任何 AuthenticatedActor (定时任务) 时, 保密产品照样整行排除 —— 判定不看调用者是谁
        assertThat(scope(Set.of(SECRET)).forFactory(FACTORY).productTypeIds()).contains(SECRET);
        assertThat(scope(Set.of(SECRET)).forFactory(FACTORY).planIds()).containsExactly(SECRET_PLAN);
    }
}
