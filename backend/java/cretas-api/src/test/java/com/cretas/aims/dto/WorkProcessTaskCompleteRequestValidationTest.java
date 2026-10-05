package com.cretas.aims.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST 完成工序 ({@code PUT /work-process-tasks/{id}/complete}, {@code @Valid CompleteRequest})
 * 与 AI 工具路径同一口径: 实际产量必填、不能为负、0 合法 (这道工序可能确实没做出来)。
 *
 * <p>之前 REST 是 {@code @Positive}, 拒 0; AI 路径放行 0 —— 两条路对同一个输入给出相反结论。
 */
class WorkProcessTaskCompleteRequestValidationTest {

    private static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    private static Set<ConstraintViolation<WorkProcessTaskDTO.CompleteRequest>> validate(String quantity) {
        WorkProcessTaskDTO.CompleteRequest req = WorkProcessTaskDTO.CompleteRequest.builder()
                .actualQuantity(quantity == null ? null : new BigDecimal(quantity))
                .build();
        return VALIDATOR.validate(req);
    }

    @Test
    @DisplayName("0 / 0.00 合法 —— 与 AI 工具路径一致")
    void zeroIsAccepted() {
        assertThat(validate("0")).isEmpty();
        assertThat(validate("0.00")).isEmpty();
    }

    @Test
    @DisplayName("正数合法 (对照)")
    void positiveIsAccepted() {
        assertThat(validate("0.01")).isEmpty();
        assertThat(validate("95.5")).isEmpty();
    }

    @Test
    @DisplayName("负数被拒, 且只报 actualQuantity 一条")
    void negativeIsRejected() {
        Set<ConstraintViolation<WorkProcessTaskDTO.CompleteRequest>> v = validate("-0.01");
        assertThat(v).hasSize(1);
        ConstraintViolation<WorkProcessTaskDTO.CompleteRequest> only = v.iterator().next();
        assertThat(only.getPropertyPath().toString()).isEqualTo("actualQuantity");
        assertThat(only.getMessage()).contains("不能是负数");
    }

    @Test
    @DisplayName("不填被拒")
    void nullIsRejected() {
        Set<ConstraintViolation<WorkProcessTaskDTO.CompleteRequest>> v = validate(null);
        assertThat(v).hasSize(1);
        assertThat(v.iterator().next().getPropertyPath().toString()).isEqualTo("actualQuantity");
    }
}
