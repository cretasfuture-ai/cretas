package com.cretas.aims.exception;

import com.cretas.aims.dto.common.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** 唯一约束冲突的用户可见提示不得露出内部列名 (factory_id, code)。 */
@DisplayName("唯一约束冲突提示不含内部字段名")
class GlobalExceptionHandlerUniqueConflictTextContractTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static final String PG_PRODUCT_CODE_DUP =
            "could not execute statement [ERROR: duplicate key value violates unique constraint "
                    + "\"uk_product_types_factory_code\"\n  Detail: Key (factory_id, code)=(F006, CPF0060039) already exists.]";

    @Test
    void productCodeConflictHasFriendlyTextWithoutColumnNames() {
        ApiResponse<?> resp = handler.handleDataIntegrityViolationException(
                new DataIntegrityViolationException(PG_PRODUCT_CODE_DUP),
                new MockHttpServletRequest("POST", "/api/mobile/F006/product-types"));

        assertThat(resp.getCode()).isEqualTo(409);
        assertThat(resp.getMessage()).isEqualTo("编码已存在，请换一个编码或留空自动生成");
        assertThat(resp.getActionHint()).contains("编码");
        for (String visible : new String[]{resp.getMessage(), resp.getActionHint()}) {
            assertThat(visible).doesNotContain("factory_id").doesNotContain("code)");
        }
    }

    @Test
    @DisplayName("阴性对照: 其它表 (factory_id, code) 撞约束只说换编码, 不提「留空自动生成」")
    void otherTablesCodeConflictDoesNotSuggestLeavingCodeBlank() {
        for (String constraint : new String[]{
                "uk_customers_factory_code", "uk_suppliers_factory_code", "uk_raw_material_types_factory_code",
                "ukf8x3k2factorycode"}) {
            ApiResponse<?> resp = handler.handleDataIntegrityViolationException(
                    new DataIntegrityViolationException("could not execute statement [ERROR: duplicate key value "
                            + "violates unique constraint \"" + constraint + "\"\n  Detail: Key (factory_id, code)="
                            + "(F006, C001) already exists.]"),
                    new MockHttpServletRequest("POST", "/api/mobile/F006/customers"));

            assertThat(resp.getCode()).as(constraint).isEqualTo(409);
            assertThat(resp.getMessage()).as(constraint).isEqualTo("编码已存在，请换一个编码");
            assertThat(resp.getHintTarget()).as(constraint).isEqualTo("code");
            for (String visible : new String[]{resp.getMessage(), resp.getActionHint()}) {
                assertThat(visible).as(constraint).doesNotContain("留空").doesNotContain("自动生成")
                        .doesNotContain("factory_id");
            }
        }
    }

    @Test
    void unknownColumnsFallBackToGenericTextWithoutLeakingThem() {
        ApiResponse<?> resp = handler.handleDataIntegrityViolationException(
                new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"x\"\n Detail: Key (factory_id, plan_no)=(F1, P1) already exists."),
                new MockHttpServletRequest("POST", "/x"));

        assertThat(resp.getMessage()).isEqualTo("数据已存在，请勿重复提交");
        assertThat(resp.getActionHint()).doesNotContain("factory_id").doesNotContain("plan_no");
    }

    @Test
    void describeSkipsTenantColumnRegardlessOfOrder() {
        assertThat(GlobalExceptionHandler.describeUniqueConflict("code, factory_id").hintTarget()).isEqualTo("code");
        assertThat(GlobalExceptionHandler.describeUniqueConflict("factory_id, name").actionHint()).contains("名称");
    }
}
