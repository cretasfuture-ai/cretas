package com.cretas.aims.entity.enums;

/**
 * 源头个体的 DNA / 基因比对状态。
 *
 * <p>⚠️ 这四个值与 {@code source_individuals.dna_verification_status} 上的
 * CHECK 约束 {@code ck_source_individual_dna_status} 一一对应
 * (迁移 {@code V20261101_42__source_individual.sql})。
 * <b>加值必须两侧一起改</b> —— 只加枚举不改 CHECK 的后果是写入时撞 23514,
 * 而那个报错长得像「数据脏了」, 不像「我少改了一处」(形态 D: 同一个东西有两份就会漂)。
 *
 * <p>本系统<b>不存基因数据本身</b>: 检测在外部基因库/第三方机构做,
 * 这里只记「采没采样 / 比没比 / 比中没有」这一条状态线, 外加一把外部钥匙
 * ({@code external_gene_bank_id} / {@code dna_sample_code})。
 */
public enum DnaVerificationStatus {

    /** 未采样 —— 默认态。⛔ 不要把它读成「比对失败」。 */
    UNSAMPLED("未采样", "尚未采集 DNA 样本"),

    /** 已采样, 等待外部机构出比对结果。 */
    SAMPLED("已采样", "样本已采集并送检, 比对结果未回"),

    /** 外部比对结果与本档案一致。 */
    MATCHED("已比中", "外部基因库比对一致"),

    /** 外部比对结果与本档案<b>不一致</b> —— 需要人工核查, 不是「未知」。 */
    MISMATCH("不符", "外部基因库比对不一致, 需人工核查");

    private final String displayName;
    private final String description;

    DnaVerificationStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
