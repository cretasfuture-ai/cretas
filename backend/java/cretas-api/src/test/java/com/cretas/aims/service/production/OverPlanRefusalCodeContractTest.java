package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionSettlementPrefillResponse;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * D-N-01: 一键确认前的客供守卫只拦<b>客供</b>阻塞, 并带它自己的码。
 *
 * <p>线上 SEC-F006-20261109-4661D1 (非客供的返工计划) 因「超产」被拦时收到
 * {@code CUSTOMER_SUPPLIED_MATERIAL_NOT_ON_ORDER}, 而且守卫在调用方按确认请求放行「已填超产原因」之前就抛了。
 * 走真结单入口的阳性 / 阴性见 {@code SettlementOverPlanUnitPostgresContractTest}。
 */
class OverPlanRefusalCodeContractTest {

    private static CustomerSuppliedSettlementCoverageGuard guard() throws Exception {
        Constructor<?> ctor = Arrays.stream(CustomerSuppliedSettlementCoverageGuard.class.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = Arrays.stream(ctor.getParameterTypes()).map(type -> (Object) mock(type)).toArray();
        return (CustomerSuppliedSettlementCoverageGuard) ctor.newInstance(args);
    }

    private static ProductionSettlementPrefillResponse blocked(String... codes) {
        List<ProductionSettlementPrefillResponse.Issue> issues = new ArrayList<>();
        for (String code : codes) {
            issues.add(ProductionSettlementPrefillResponse.Issue.builder()
                    .code(code).message(code + " 的说明").field("x")
                    .severity(ProductionSettlementPrefillResponse.Severity.BLOCKER).build());
        }
        return ProductionSettlementPrefillResponse.builder()
                .audit(ProductionSettlementPrefillResponse.Audit.builder().clean(false).issues(issues).build())
                .build();
    }

    @Test
    void overProductionAloneIsNotACustomerSuppliedRefusal() throws Exception {
        // 超产能被「已填超产原因」解除 —— 这一步不能替调用方拒绝, 更不能套客供的码
        assertThatCode(() -> guard().requireCleanForOneClick(blocked("QUANTITY_VARIANCE_OVER_PLAN")))
                .doesNotThrowAnyException();
    }

    @Test
    void customerSuppliedBlockersStillRefuseWithTheirOwnCode() throws Exception {
        CustomerSuppliedSettlementCoverageGuard guard = guard();
        assertThatThrownBy(() -> guard.requireCleanForOneClick(blocked("QUANTITY_VARIANCE_OVER_PLAN",
                CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_OVER_RECEIVED)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_OVER_RECEIVED);
                    assertThat(e.getMessage()).doesNotContain("QUANTITY_VARIANCE_OVER_PLAN");
                });
        assertThatThrownBy(() -> guard.requireCleanForOneClick(
                blocked(CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_NOT_LISTED)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_NOT_LISTED));
    }
}
