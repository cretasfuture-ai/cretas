package com.cretas.aims.service.execution;

import com.cretas.aims.util.RetiredAnalysisContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RetiredAnalysisContextContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"restaurant_owner", "restaurant_manager", "restaurant_chef", "restaurant_purchaser"})
    void historicalRolesCannotUseFactoryAnalysis(String role) {
        assertThat(RetiredAnalysisContext.isRetired("F006", "FACTORY", role)).isTrue();
    }

    @Test
    void serverResolvedDomainIsAuthoritativeAndFallbackDoesNotRejectOrdinaryFactories() {
        assertThat(RetiredAnalysisContext.isRetired("MOCK", "RESTAURANT", "factory_super_admin")).isTrue();
        assertThat(RetiredAnalysisContext.isRetired("RES_FACTORY", "FACTORY", "factory_super_admin")).isFalse();
        assertThat(RetiredAnalysisContext.isRetired("FACTORY_WITH_LONG_ID", null, "factory_super_admin")).isFalse();
        assertThat(RetiredAnalysisContext.isRetired(null, null, "platform_admin")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MOCK_REST", "DEMO_REST", "RES_OLD", "REST_OLD",
            "R_DONGMENKOU_REAL", "R_QINGHUAJIAO_REAL", "R_YUJIUJING_REAL"})
    void knownRetiredIdsFailClosedWhenMetadataIsUnavailable(String id) {
        assertThat(RetiredAnalysisContext.isRetired(id, null, "factory_super_admin")).isTrue();
    }

    @Test
    void branchDomainIsRetiredEvenForNonRestaurantRole() {
        assertThat(RetiredAnalysisContext.isRetired("BRANCH-1", "BRANCH", "finance_manager")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FACTORY", "HEADQUARTERS", "CENTRAL_KITCHEN"})
    void knownFactoryMetadataRemainsAvailableWithoutBroadRPrefixRetirement(String domain) {
        org.assertj.core.api.Assertions.assertThatCode(() ->
                RetiredAnalysisContext.requireFactory("R_FACTORY_CUSTOM_REAL", domain, "finance_manager"))
                .doesNotThrowAnyException();
        assertThat(RetiredAnalysisContext.isRetired("R_FACTORY_CUSTOM_REAL", null, "finance_manager")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "UNKNOWN", "RETAIL"})
    void unknownMetadataCannotBecomeManufacturing(String domain) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                RetiredAnalysisContext.requireFactory("F006", domain, "finance_manager"))
                .isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(503));
    }
}
