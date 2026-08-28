package com.cretas.aims.dto.bom;

import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一条 BOM 配方项<b>恰有一个</b>物料身份: 原料档案 或 半成品(回料)引用编码。
 *
 * <p>与 DB 约束 {@code chk_bri_material_ref_xor} 同一条判据。写在 DTO 上是为了让用户
 * 拿到 400 + 明确措辞而不是 500 + 一句 PSQLException;
 * ⛔ 它<b>不是</b>唯一守卫 —— 绕过 DTO 的写路径由 DB 约束兜底
 * (那一侧的行为由 {@code BomSemiFinishedRowMigrationContractTest} 在真库上跑)。
 */
@DisplayName("BOM 配方项 — 物料身份必须恰有一个")
class BomRecipeItemMaterialIdentityValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static final String IDENTITY_PROPERTY = "exactlyOneMaterialIdentity";

    private BomRecipeItemDTO dto(String materialTypeId, String semiFinishedRefCode) {
        BomRecipeItemDTO dto = new BomRecipeItemDTO();
        dto.setMaterialTypeId(materialTypeId);
        dto.setSemiFinishedRefCode(semiFinishedRefCode);
        dto.setUnit("kg");
        dto.setStandardQuantity(new BigDecimal("0.1951"));
        return dto;
    }

    private Set<String> violatedProperties(BomRecipeItemDTO dto) {
        Set<ConstraintViolation<BomRecipeItemDTO>> violations = validator.validate(dto);
        return violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    @DisplayName("存量长相: 只有 materialTypeId → 放行")
    void plainMaterialRowPasses() {
        assertThat(violatedProperties(dto("MT-001", null))).doesNotContain(IDENTITY_PROPERTY);
    }

    @Test
    @DisplayName("新长相: 只有 semiFinishedRefCode → 放行")
    void semiFinishedRowPasses() {
        assertThat(violatedProperties(dto(null, "WIP-F006-REWORK-20260829")))
                .doesNotContain(IDENTITY_PROPERTY);
    }

    @Test
    @DisplayName("闸红方向一: 两个都填 → 拒")
    void bothIdentitiesRejected() {
        assertThat(violatedProperties(dto("MT-001", "WIP-F006-REWORK-20260829")))
                .contains(IDENTITY_PROPERTY);
    }

    @Test
    @DisplayName("闸红方向二: 两个都不填 → 拒 (旧的 @NotBlank 撤了之后, 空行必须由它接住)")
    void neitherIdentityRejected() {
        assertThat(violatedProperties(dto(null, null))).contains(IDENTITY_PROPERTY);
    }

    @Test
    @DisplayName("空白串不算身份 —— 否则「填了一个空格」会绕过整条判据")
    void blankStringsAreNotIdentities() {
        assertThat(violatedProperties(dto("   ", null))).contains(IDENTITY_PROPERTY);
        assertThat(violatedProperties(dto(null, "  "))).contains(IDENTITY_PROPERTY);
        assertThat(violatedProperties(dto("MT-001", "   ")))
                .as("一个真身份 + 一个空白串 = 恰有一个, 应放行")
                .doesNotContain(IDENTITY_PROPERTY);
    }

    @Test
    @DisplayName("SEMI_FINISHED 已在 materialCategory 白名单里 —— 否则回料行会被 @Pattern 挡在前面")
    void semiFinishedCategoryIsAccepted() {
        BomRecipeItemDTO dto = dto(null, "WIP-F006-REWORK-20260829");
        dto.setMaterialCategory("SEMI_FINISHED");
        assertThat(violatedProperties(dto)).doesNotContain("materialCategory");
    }

    @Test
    @DisplayName("阴性对照: 白名单没被放宽成「什么都收」")
    void unknownCategoryIsStillRejected() {
        BomRecipeItemDTO dto = dto("MT-001", null);
        dto.setMaterialCategory("REWORK");
        assertThat(violatedProperties(dto)).contains("materialCategory");
    }
}
