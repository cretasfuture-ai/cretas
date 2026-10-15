package com.cretas.aims.entity.formschemadraft;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

@Entity
@Table(name = "form_schema_draft_fields",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_form_schema_field_id_factory",
                        columnNames = {"id", "factory_id"}),
                @UniqueConstraint(name = "uk_form_schema_field_form_ordinal",
                        columnNames = {"form_id", "factory_id", "ordinal"}),
                @UniqueConstraint(name = "uk_form_schema_field_form_key",
                        columnNames = {"form_id", "factory_id", "field_key"})
        }, indexes = @Index(name = "idx_form_schema_field_form",
                columnList = "factory_id,form_id,ordinal"))
@org.hibernate.annotations.SQLDelete(sql = "UPDATE form_schema_draft_fields SET deleted_at = NOW() WHERE id = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FormSchemaDraftField extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false) private String id;
    @Column(name = "form_id", length = 36, nullable = false, updatable = false) private String formId;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false) private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(nullable = false) private Integer ordinal;
    @Column(name = "field_key", length = 60, nullable = false) private String fieldKey;
    @Column(name = "label", length = 200, nullable = false) private String label;
    @Column(name = "field_type", length = 30, nullable = false) private String fieldType;
    @Column(name = "required", nullable = false) private Boolean required;
    @Column(name = "max_length") private Integer maxLength;
    @Column(name = "min_value", precision = 18, scale = 6) private BigDecimal minValue;
    @Column(name = "max_value", precision = 18, scale = 6) private BigDecimal maxValue;
    @Column(name = "decimal_scale") private Integer decimalScale;
    @Column(name = "unit_code", length = 30) private String unitCode;
    @Column(name = "option_codes", length = 2000) private String optionCodes;
    @Column(name = "help_text", length = 500) private String helpText;
}
