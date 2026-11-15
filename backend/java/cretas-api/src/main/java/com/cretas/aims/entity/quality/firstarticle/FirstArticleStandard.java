package com.cretas.aims.entity.quality.firstarticle;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

/**
 * 首检标准: 哪道工序 ({@code work_processes.id}) 要首检、检哪些项。
 *
 * <p>每道工序同时最多一条启用标准 (部分唯一索引 {@code uk_fa_standards_active_process})。
 * 改标准 = 同一行 {@code revision + 1}; 已送检的首检单存当时的标准快照, 不受后改影响。
 * 停用写原因; 不物理删除, 也不走软删 (没有删除入口)。
 *
 * <p>{@code itemsJson} 的形状与校验只在 {@code FirstArticleItemRules} 一处 (⛔ 别处不要再解析一份)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Entity
@Table(name = "first_article_standards")
@Where(clause = "deleted_at IS NULL")
public class FirstArticleStandard extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "work_process_id", nullable = false, length = 50)
    private String workProcessId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "items_json", nullable = false, columnDefinition = "TEXT")
    private String itemsJson;

    @Column(name = "is_active", nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "revision", nullable = false)
    private Integer revision = 1;

    @Column(name = "deactivated_reason", length = 500)
    private String deactivatedReason;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;
}
