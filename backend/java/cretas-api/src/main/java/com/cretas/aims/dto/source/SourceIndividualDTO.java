package com.cretas.aims.dto.source;

import com.cretas.aims.entity.enums.DnaVerificationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 源头个体档案 DTO。
 *
 * <p>⛔ 这里<b>不</b>把 {@code dnaVerificationStatus} 为 null 的情况翻译成 UNSAMPLED:
 * 实体侧那一列 {@code NOT NULL}, 读到 null 说明有别的地方坏了, 让它以 null 出去比
 * 编一个「未采样」诚实(核心原则 1: 禁止降级处理)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "源头个体档案")
public class SourceIndividualDTO {

    @Schema(description = "档案ID")
    private String id;

    @Schema(description = "工厂ID")
    private String factoryId;

    @Schema(description = "档案编号(工厂内唯一)")
    private String individualCode;

    @Schema(description = "物理标识: 耳标/脚环/芯片号 (不保证唯一)")
    private String tagNumber;

    @Schema(description = "物种 (CATTLE/DUCK/PIG/… 由租户自定口径)")
    private String species;

    @Schema(description = "品种/品系")
    private String breed;

    @Schema(description = "性别")
    private String sex;

    @Schema(description = "出生日期")
    private LocalDate birthDate;

    @Schema(description = "来源养殖场/牧场")
    private String originFarm;

    @Schema(description = "外部基因库样本主键 (本系统不存基因数据本身)")
    private String externalGeneBankId;

    @Schema(description = "采样编号")
    private String dnaSampleCode;

    @Schema(description = "DNA 比对状态 UNSAMPLED/SAMPLED/MATCHED/MISMATCH")
    private DnaVerificationStatus dnaVerificationStatus;

    @Schema(description = "DNA 比对状态中文名")
    private String dnaVerificationStatusDisplayName;

    @Schema(description = "父本个体ID (同表自引用, 可空)")
    private String sireIndividualId;

    @Schema(description = "母本个体ID (同表自引用, 可空)")
    private String damIndividualId;

    @Schema(description = "扩展属性")
    private Map<String, Object> extAttributes;

    @Schema(description = "备注")
    private String notes;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "乐观锁版本")
    private Long version;
}
