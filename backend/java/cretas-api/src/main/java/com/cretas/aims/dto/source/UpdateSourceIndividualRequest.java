package com.cretas.aims.dto.source;

import com.cretas.aims.entity.enums.DnaVerificationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Map;

/**
 * 更新请求 —— partial body: 只有非 null 的字段会被写回。
 *
 * <p>🔴 代价写在这里: <b>partial 语义下「清空一个字段」表达不了</b>(传 null 等于「别动它」)。
 * 这是本仓 {@code UpdateMaterialBatchRequest} 已有的口径, 保持一致 ⇒ ⛔ 不在这一个接口上
 * 长出第二种语义。真要清空, 后面加一个显式的 {@code clearFields: [...]}, 而不是把 null
 * 重新解释一遍。
 *
 * <p>{@code individualCode} <b>不可改</b> —— 它是档案编号, 改了等于换一个档案,
 * 而下游(原料批次的 {@code sourceIndividualId})引的是 id 不是编号, 改编号会让
 * 已打印的标签/单据与系统对不上。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "更新源头个体档案 (partial body: null = 不修改)")
public class UpdateSourceIndividualRequest {

    @Size(max = 64, message = "耳标号长度不能超过64")
    @Schema(description = "物理标识: 耳标/脚环/芯片号")
    private String tagNumber;

    @Size(max = 32, message = "物种长度不能超过32")
    @Schema(description = "物种")
    private String species;

    @Size(max = 64, message = "品种长度不能超过64")
    @Schema(description = "品种/品系")
    private String breed;

    @Size(max = 16, message = "性别长度不能超过16")
    @Schema(description = "性别")
    private String sex;

    @Schema(description = "出生日期")
    private LocalDate birthDate;

    @Size(max = 128, message = "来源场长度不能超过128")
    @Schema(description = "来源养殖场/牧场")
    private String originFarm;

    @Size(max = 128, message = "外部基因库ID长度不能超过128")
    @Schema(description = "外部基因库样本主键")
    private String externalGeneBankId;

    @Size(max = 64, message = "采样编号长度不能超过64")
    @Schema(description = "采样编号")
    private String dnaSampleCode;

    @Schema(description = "DNA 比对状态")
    private DnaVerificationStatus dnaVerificationStatus;

    /** 父本个体 ID。必须是同厂已存在的个体, 且⛔ 不能指向自己; 查不到 422。 */
    @Size(max = 191, message = "父本个体ID不能超过191个字符")
    @Schema(description = "父本个体ID (同表自引用, 可空)")
    private String sireIndividualId;

    /** 母本个体 ID。规则同 {@link #sireIndividualId}。 */
    @Size(max = 191, message = "母本个体ID不能超过191个字符")
    @Schema(description = "母本个体ID (同表自引用, 可空)")
    private String damIndividualId;

    @Schema(description = "扩展属性 (整体替换)")
    private Map<String, Object> extAttributes;

    @Schema(description = "备注")
    private String notes;
}
