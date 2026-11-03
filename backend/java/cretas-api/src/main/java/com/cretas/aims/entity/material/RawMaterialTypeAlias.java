package com.cretas.aims.entity.material;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 物料别名: 配方单 / 供应商单据上对同一个物料的其它叫法。
 *
 * <p>同一工厂内按 {@link #normalize(String)} 后的写法唯一 (DB {@code uk_rmt_alias_factory_normalized}),
 * 并且不能与任何物料的名称撞名 (service 层校验) —— 否则「搜大骨」会落到两个物料上。</p>
 *
 * <p>删别名是硬删除: 别名只是检索入口, 不被任何单据引用, 不需要软删历史。</p>
 */
@Entity
@Table(name = "raw_material_type_aliases",
        uniqueConstraints = @UniqueConstraint(name = "uk_rmt_alias_factory_normalized",
                columnNames = {"factory_id", "normalized_alias"}))
@Getter
@Setter
@NoArgsConstructor
public class RawMaterialTypeAlias {

    public static final int MAX_LENGTH = 100;
    public static final String SOURCE_MANUAL = "MANUAL";
    /** 合并重复物料时, 被合并物料的原名自动成为主物料的别名。 */
    public static final String SOURCE_MERGE = "MERGE";

    @Id
    @Column(name = "id", length = 50)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "material_type_id", nullable = false, length = 191)
    private String materialTypeId;

    @Column(name = "alias", nullable = false, length = MAX_LENGTH)
    private String alias;

    @Column(name = "normalized_alias", nullable = false, length = MAX_LENGTH)
    private String normalizedAlias;

    @Column(name = "source", nullable = false, length = 16)
    private String source = SOURCE_MANUAL;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /** 全角转半角、去掉所有空白 (「海天 酱油」=「海天酱油」)、转小写 —— 别名与物料名比对都走这一个口径。 */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }
}
