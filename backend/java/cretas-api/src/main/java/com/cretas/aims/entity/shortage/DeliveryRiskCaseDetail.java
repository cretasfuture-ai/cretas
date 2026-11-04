package com.cretas.aims.entity.shortage;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 交付风险实例最近一次评估 (V20261104_42): 跟着实例走, 一条实例一行, 每次评估覆盖。 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "delivery_risk_case_details")
public class DeliveryRiskCaseDetail {

    @Id
    @Column(name = "case_id", length = 36, nullable = false)
    private String caseId;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "risk_level", length = 16, nullable = false)
    private String riskLevel;

    @Column(name = "assessment_json", columnDefinition = "text", nullable = false)
    private String assessmentJson;

    @Column(name = "assessed_at", nullable = false)
    private LocalDateTime assessedAt;
}
