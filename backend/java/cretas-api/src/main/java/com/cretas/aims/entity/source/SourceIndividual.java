package com.cretas.aims.entity.source;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.DnaVerificationStatus;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.time.LocalDate;
import java.util.Map;

/**
 * 源头个体档案 —— 溯源链<b>最前面</b>那个有生命周期的个体。
 *
 * <h3>它是通用的, 不是为某一个物种做的</h3>
 * 一头牛 / 一只鸭 / 一头猪 / 一尾鱼 都是「源头个体」。<b>物种是字段值</b>({@link #species}),
 * ⛔ 不是模型名, 也不该长出物种专属列 —— 养殖场特有的属性走 {@link #extAttributes}。
 *
 * <h3>两个标识列不是重复</h3>
 * <ul>
 *   <li>{@link #individualCode} —— <b>系统内</b>档案编号, 工厂内唯一。唯一性由迁移里的
 *       <b>部分</b>唯一索引 {@code uk_source_individual_code … WHERE deleted_at IS NULL}
 *       承担, ⛔ <b>实体里不声明</b> {@code @UniqueConstraint} / {@code unique = true}:
 *       JPA 表达不了部分唯一, 声明了只会在 H2 建出全量键, 让「软删一行再建同编号」
 *       在测试里红而在生产上是对的(见 {@code SoftDeleteEntityDeclaredUniqueContractTest})。</li>
 *   <li>{@link #tagNumber} —— <b>物理标识</b>(耳标/脚环/芯片号), 拍照核验与扫码读到的就是它。
 *       ⚠️ <b>不保证唯一</b>: 耳标会脱落重挂、跨场引进可能撞号 ⇒ 按它查可能命中多条,
 *       由 service 层显式处理并把命中条数交出去, ⛔ 不在任何一层假装它唯一。</li>
 * </ul>
 *
 * <h3>软删</h3>
 * 类级 {@code @SQLDelete} + {@code @Where} <b>都写在本类上</b> —— {@code BaseEntity}
 * ({@code @MappedSuperclass}) 上那两个 Hibernate 静默忽略(R68, 2026-04-29),
 * 只有子类自己声明才生效。{@code @SQLDelete} 绑 {@code version} 列是因为本实体有
 * {@link #version}(见 {@code SqlDeleteBindsVersionColumnContractTest})。
 *
 * @since 2026-09-14 (FEAT-SOURCE-INDIVIDUAL-VISION-VERIFY-20260914)
 */
@Entity
@Table(name = "source_individuals",
       indexes = {
           @Index(name = "idx_source_individual_tag", columnList = "factory_id, tag_number"),
           @Index(name = "idx_source_individual_species", columnList = "factory_id, species")
       })
@SQLDelete(sql = "UPDATE source_individuals SET deleted_at = NOW() WHERE id = ? AND version = ?")
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SourceIndividual extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    /** 系统内档案编号, 工厂内唯一(部分唯一索引, 软删后可复用)。 */
    @Column(name = "individual_code", nullable = false, length = 64)
    private String individualCode;

    /** 物理标识: 耳标号 / 脚环号 / 芯片号。⚠️ 不唯一, 见类文档。 */
    @Column(name = "tag_number", length = 64)
    private String tagNumber;

    /** 物种。自由文本(CATTLE / DUCK / PIG / …), 由租户自定口径; ⛔ 不做成枚举——新客户的物种我们事先不知道。 */
    @Column(name = "species", length = 32)
    private String species;

    /** 品种/品系(西门塔尔、樱桃谷…)。 */
    @Column(name = "breed", length = 64)
    private String breed;

    /** 性别。自由文本, 不同物种口径不同(公/母、阉割…), ⛔ 不做成两值枚举。 */
    @Column(name = "sex", length = 16)
    private String sex;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    /** 来源养殖场/牧场/基地名称。 */
    @Column(name = "origin_farm", length = 128)
    private String originFarm;

    /**
     * 外部基因库 / 第三方检测机构里的样本主键。
     * <p>本系统<b>不存基因数据本身</b>, 只存这一把外部钥匙。
     */
    @Column(name = "external_gene_bank_id", length = 128)
    private String externalGeneBankId;

    /** 采样编号(送检单上的那个号)。 */
    @Column(name = "dna_sample_code", length = 64)
    private String dnaSampleCode;

    /**
     * DNA 比对状态。
     *
     * <p>⚠️ Java 侧默认值 {@code UNSAMPLED} 与 DB {@code DEFAULT 'UNSAMPLED'} 成对存在,
     * 缺了它 Hibernate 会对省略该字段的构造路径<b>显式插 NULL</b>(不是省略该列) ⇒ 撞 23502。
     * 本仓 {@code accepts_rework} 同型缺陷 (#522/#508), 由
     * {@code NotNullColumnsHaveEntityDefaultsContractTest} 守。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "dna_verification_status", nullable = false, length = 16)
    @Builder.Default
    private DnaVerificationStatus dnaVerificationStatus = DnaVerificationStatus.UNSAMPLED;

    /**
     * 父本 —— 同表自引用, 存的是 {@link #id}, 可空。
     *
     * <p>⚠️ 这里<b>刻意用裸 ID 而不是 {@code @ManyToOne}</b>:
     * 系谱是一张可以很深的图(祖代/曾祖代), JPA 关联会把「读一头个体」变成一串
     * 不受控的懒加载; 而产品上只需要「谁是父本」这一跳, 需要更多层时由查询显式做。
     *
     * <p>⛔ 库上的 FK 是 {@code ON DELETE SET NULL} —— 删父本<b>不连坐</b>后代。
     * 「同厂」这一条 FK 管不着(PK 只有 id), 由 service 层校验; 两道各管一半。
     */
    @Column(name = "sire_individual_id", length = 191)
    private String sireIndividualId;

    /** 母本 —— 同 {@link #sireIndividualId}。 */
    @Column(name = "dam_individual_id", length = 191)
    private String damIndividualId;

    /**
     * 租户/物种特有的扩展属性(体重、免疫记录、圈舍号…)。
     * <p>⛔ 这里是<b>扩展</b>位, 不是「什么都往里塞」的口袋: 一旦某个键跨租户通用,
     * 就该升成正式列并配迁移。
     */
    @Type(JsonBinaryType.class)
    @Column(name = "ext_attributes", columnDefinition = "jsonb")
    private Map<String, Object> extAttributes;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    /**
     * 与 {@code MaterialBatch.getVersion()} 同一处理: 历史 SQL 直接插入的行可能 version 为 NULL,
     * Hibernate 的版本号生成会 NPE。DB 侧 {@code NOT NULL DEFAULT 0} 覆盖已落库的行,
     * 这里覆盖已经加载进内存的实例。
     */
    public Long getVersion() {
        return version != null ? version : 0L;
    }
}
