package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.security.AuthenticatedActor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 保密范围本身: 按调用者判定、计划也算、认不出产品 / 身份时排除、取不到范围就抛、档位与 N 的口径。 */
class ProcessSecretLlmCostScopeTest {

    private static ProcessSecretLlmCostScope.Rows rowsAs(AuthenticatedActor actor) {
        try (var ignored = as(actor)) {
            return protectedScope().withoutProtectedBatches(FACTORY, weeklyRows());
        }
    }

    @Test
    void unauthorizedCallersLoseTheProtectedRowsAndGetANote() {
        for (AuthenticatedActor actor : new AuthenticatedActor[]{null, sameFactoryUser(), platform(), otherFactoryUser()}) {
            ProcessSecretLlmCostScope.Rows rows = rowsAs(actor);
            assertThat(rows.allowed()).as("caller %s", actor).extracting(r -> r.get("productTypeId"))
                    .containsExactly(NORMAL, NORMAL2);
            assertThat(rows.note()).contains("不含 1 个保密产品");
        }
    }

    @Test
    void ownerAndGranteeKeepEveryRow() {
        for (AuthenticatedActor actor : new AuthenticatedActor[]{owner(), grantee()}) {
            ProcessSecretLlmCostScope.Rows rows = rowsAs(actor);
            assertThat(rows.allowed()).as("caller %s", actor).hasSize(3);
            assertThat(rows.note()).isEmpty();
        }
    }

    @Test
    void aBatchOfAPlanThatProducesTheProtectedProductIsDroppedEvenWhenItsOwnProductIdIsNotProtected() {
        Map<String, Object> anchorRow = weeklyRow(9L, "RAW-ANCHOR", SECRET_PLAN, new BigDecimal("999.99"));

        assertThat(protectedScope().withoutProtectedBatches(FACTORY, List.of(anchorRow)).allowed()).isEmpty();
    }

    @Test
    void theNoteCountsOnlyRealProtectedProductsNotAnchorsOrSiblings() {
        // 多产出计划: 锚点是原料, 同计划还产一个普通产品; 三行都被排除, 但保密产品只有 1 个
        ProductionPlan anchorPlan = plan("PL-MULTI", "RAW-ANCHOR", null);
        anchorPlan.setTargetFinishedGoodIds(List.of(SECRET, "P-SIBLING"));
        ProcessSecretLlmCostScope scope = scope(Set.of(SECRET), List.of(anchorPlan));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(
                weeklyRow(11L, "RAW-ANCHOR", "PL-MULTI", new BigDecimal("10.00")),
                weeklyRow(12L, "P-SIBLING", "PL-MULTI", new BigDecimal("20.00")),
                weeklyRow(13L, SECRET, "PL-MULTI", new BigDecimal("30.00")),
                weeklyRow(14L, NORMAL, null, new BigDecimal("40.00"))));

        ProcessSecretLlmCostScope.Rows result = scope.withoutProtectedBatches(FACTORY, rows);

        assertThat(result.excludedRows()).isEqualTo(3);
        assertThat(result.excludedProducts()).isEqualTo(1);
        assertThat(result.note()).contains("不含 1 个保密产品");
    }

    @Test
    void aRowWithoutProductIsDroppedOnlyForCallersWithHiddenProducts() {
        Map<String, Object> unknown = weeklyRow(8L, null, null, new BigDecimal("55.55"));

        assertThat(protectedScope().withoutProtectedBatches(FACTORY, List.of(unknown)).allowed()).isEmpty();
        try (var ignored = as(owner())) {
            assertThat(protectedScope().withoutProtectedBatches(FACTORY, List.of(unknown)).allowed()).hasSize(1);
        }
        assertThat(unprotectedScope().withoutProtectedBatches(FACTORY, List.of(unknown)).allowed()).hasSize(1);
    }

    @Test
    void containsProtectedFindsANestedProtectedRowForUnauthorizedCallersOnly() {
        Map<String, Object> data = Map.of("reportType", "weekly", "batches", weeklyRows());

        assertThat(protectedScope().containsProtected(FACTORY, data)).isTrue();
        try (var ignored = as(owner())) {
            assertThat(protectedScope().containsProtected(FACTORY, data)).isFalse();
        }
    }

    @Test
    void accessTierSeparatesCallersByWhatTheyMayFullyView() {
        ProcessSecretLlmCostScope scope = protectedScope();
        String ownerTier;
        String granteeTier;
        String otherTier;
        String platformTier;
        try (var ignored = as(owner())) { ownerTier = scope.accessTier(FACTORY); }
        try (var ignored = as(grantee())) { granteeTier = scope.accessTier(FACTORY); }
        try (var ignored = as(sameFactoryUser())) { otherTier = scope.accessTier(FACTORY); }
        try (var ignored = as(platform())) { platformTier = scope.accessTier(FACTORY); }
        String unknownTier = scope.accessTier(FACTORY);

        assertThat(ownerTier).startsWith("ps-").isEqualTo(granteeTier);      // 同样能完整查看 = 同档
        assertThat(otherTier).startsWith("ps-").isNotEqualTo(ownerTier);     // 无权 = 另一档
        assertThat(platformTier).isEqualTo(otherTier).isEqualTo(unknownTier);
        assertThat(unprotectedScope().accessTier(FACTORY)).isEmpty();        // 没有保密产品: 沿用原缓存
    }

    @Test
    void protectingAnotherProductChangesEveryTierSoOldCachedResultsStopMatching() {
        String before = scope(Set.of(SECRET)).accessTier(FACTORY);          // 无权调用者: 可完整查看的集合都是空
        String after = scope(Set.of(SECRET, "P-NEW")).accessTier(FACTORY);

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    void readerViewDecidesWhoMayReadASharedAnalysisRow() {
        ProcessSecretLlmCostScope scope = protectedScope();
        String ownerStamp;
        String colleagueStamp;
        ProcessSecretLlmCostScope.ReaderView owner;
        ProcessSecretLlmCostScope.ReaderView grantee;
        ProcessSecretLlmCostScope.ReaderView colleague;
        try (var ignored = as(owner())) { owner = scope.readerView(FACTORY); ownerStamp = owner.stamp(); }
        try (var ignored = as(grantee())) { grantee = scope.readerView(FACTORY); }
        try (var ignored = as(sameFactoryUser())) { colleague = scope.readerView(FACTORY); colleagueStamp = colleague.stamp(); }

        assertThat(colleague.mayRead(ownerStamp)).isFalse();      // 负责人生成的 (含保密成本) 无权同事看不了
        assertThat(grantee.mayRead(ownerStamp)).isTrue();
        assertThat(owner.mayRead(colleagueStamp)).isTrue();       // 无权视角生成的 (不含保密成本) 谁都能看
        assertThat(colleague.mayRead(colleagueStamp)).isTrue();
        assertThat(colleague.mayRead(null)).isFalse();            // 没有戳的旧条目: 只给能看全的读者
        assertThat(owner.mayRead(null)).isTrue();
        // 之后又新设了一个保密产品: 旧戳对无权读者一律失效
        ProcessSecretLlmCostScope.ReaderView colleagueLater;
        try (var ignored = as(sameFactoryUser())) { colleagueLater = scope(Set.of(SECRET, "P-NEW")).readerView(FACTORY); }
        assertThat(colleagueLater.mayRead(colleagueStamp)).isFalse();
        assertThat(unprotectedScope().readerView(FACTORY).mayRead(null)).isTrue();
    }

    @Test
    void dataNestedTooDeepToInspectCountsAsContainingProtectedRows() {
        Object deep = Map.of("productTypeId", NORMAL);
        for (int i = 0; i < 20; i++) deep = Map.of("level" + i, deep);

        assertThat(protectedScope().containsProtected(FACTORY, deep)).isTrue();
        assertThat(unprotectedScope().containsProtected(FACTORY, deep)).isFalse();
    }

    @Test
    void harnessInstructionOnlyInFactoriesWithProtectedProducts() {
        assertThat(protectedScope().harnessFor(FACTORY)).isEqualTo(ProcessSecretLlmCostScope.HARNESS_INSTRUCTION);
        assertThat(unprotectedScope().harnessFor(FACTORY)).isEmpty();
    }

    @Test
    void scopeLookupFailurePropagatesSoCallersDoNotSend() {
        ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        when(access.protectedProductTypeIds(anyString())).thenThrow(new IllegalStateException("db down"));
        ProcessSecretLlmCostScope scope = new ProcessSecretLlmCostScope(new ProcessSecretAggregateExclusions(
                access, mock(com.cretas.aims.repository.ProductionPlanRepository.class),
                mock(com.cretas.aims.repository.bom.BomRecipeRepository.class)), access);

        assertThatThrownBy(() -> scope.withoutProtectedBatches(FACTORY, weeklyRows()))
                .isInstanceOf(IllegalStateException.class);
        // 判定失败时系统约束照样带上
        assertThat(scope.harnessFor(FACTORY)).isEqualTo(ProcessSecretLlmCostScope.HARNESS_INSTRUCTION);
    }
}
