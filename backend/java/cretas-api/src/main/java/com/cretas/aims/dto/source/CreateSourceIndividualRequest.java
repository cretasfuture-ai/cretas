package com.cretas.aims.dto.source;

import com.cretas.aims.entity.enums.DnaVerificationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Map;

/**
 * 建档请求。
 *
 * <p>只有 {@code individualCode} 必填 —— 现场建档常常只知道一个编号和一张耳标照,
 * 其余信息后补。⛔ 不把 species/breed 设成必填去「保证数据完整」: 那只会逼现场
 * 随便填一个值, 结果是假数据而不是空数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "创建源头个体档案")
public class CreateSourceIndividualRequest {

    @NotBlank(message = "档案编号不能为空")
    @Size(max = 64, message = "档案编号长度不能超过64")
    @Schema(description = "档案编号(工厂内唯一)", example = "SI-2026-0001")
    private String individualCode;

    @Size(max = 64, message = "耳标号长度不能超过64")
    @Schema(description = "物理标识: 耳标/脚环/芯片号", example = "1180245")
    private String tagNumber;

    @Size(max = 32, message = "物种长度不能超过32")
    @Schema(description = "物种", example = "CATTLE")
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

    /** 不传 = UNSAMPLED(实体默认)。⛔ 不在这里写 Java 默认值——两处默认会漂。 */
    @Schema(description = "DNA 比对状态, 不传则为 UNSAMPLED")
    private DnaVerificationStatus dnaVerificationStatus;

    /**
     * 父本个体 ID。可空。
     * <p>填了就必须是<b>同一个工厂</b>里已存在的活档案 —— 查不到就 422 明确报错,
     * ⛔ 不静默置空(静默置空会让用户以为系谱记上了)。
     */
    @Size(max = 191, message = "父本个体ID不能超过191个字符")
    @Schema(description = "父本个体ID (同表自引用, 可空; 必须是同厂已存在的个体)")
    private String sireIndividualId;

    /** 母本个体 ID。规则同 {@link #sireIndividualId}。 */
    @Size(max = 191, message = "母本个体ID不能超过191个字符")
    @Schema(description = "母本个体ID (同表自引用, 可空; 必须是同厂已存在的个体)")
    private String damIndividualId;

    @Schema(description = "扩展属性")
    private Map<String, Object> extAttributes;

    @Schema(description = "备注")
    private String notes;
}
