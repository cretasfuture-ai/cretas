package com.cretas.aims.entity.hr;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.hr.enums.SalaryStatus;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 月度工资单 — 每个 user 每个 yearMonth 一条记录.
 *
 * <p>字段语义 (人民币元, 2 decimal):
 * <ul>
 *   <li>{@code baseSalary} — 基本工资 (本月应发底数)</li>
 *   <li>{@code socialInsuranceEmployee} — 个人社保扣款 (~10.5% of base)</li>
 *   <li>{@code socialInsuranceEmployer} — 单位社保 (~26% of base, info-only,不扣员工)</li>
 *   <li>{@code providentFundEmployee} — 个人公积金 (默认 8%)</li>
 *   <li>{@code providentFundEmployer} — 单位公积金 (默认 8%, info-only)</li>
 *   <li>{@code taxableIncome} — 应税收入 (base - 个人社保 - 个人公积金 - 5000 起征点)</li>
 *   <li>{@code personalTax} — 个税 (简化 bracket)</li>
 *   <li>{@code netSalary} — 实发工资 (base - 个人社保 - 个人公积金 - 个税)</li>
 * </ul>
 *
 * <p>R4 防呆: {@code uq_salary_factory_user_month} unique (factory_id, user_id, year_month)
 * 防止同 user 同月重复工资单.
 *
 * <p>状态流转: DRAFT → CONFIRMED → PAID (见 SalaryStatus).
 *
 * @author Cretas Team — P1-40 H-WAGE-FULL MVP
 * @since 2026-05-17
 */
@Entity
// 🔴 实体不再声明这张表的业务唯一键 (2026-09-07, FIX-ENTITY-FULL-UNIQUE-VS-PROD-PARTIAL):
// prod 上是 uq_salary_factory_user_month —— `WHERE deleted_at IS NULL` 的**部分**唯一索引; 而 JPA 表达不了部分唯一,
// 实体里的 @UniqueConstraint 只能在 H2 建出**全量**键 ⇒ H2 比 prod 严。
// 补 @SQLDelete 之后「软删一行再建同键」在 prod 合法、在 H2 撞键, 表现为测试红而线上是对的
// (#701 合入后 logistics 三个测试类 4 个 error 就是这么来的, 全部只在 H2 复现)。
// ⇒ 唯一键以 prod 的部分索引为准, 实体侧不再重复声明。
// ⚠️ 代价: H2 里这张表**没有**唯一性保护 —— 与 #712 对 material_product_conversions 的处置一致。
@Table(name = "salary_items",
       indexes = {
           @Index(name = "idx_salary_factory_month", columnList = "factory_id,year_month"),
           @Index(name = "idx_salary_factory_status", columnList = "factory_id,status,year_month")
       })
@SQLDelete(sql = "UPDATE salary_items SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalaryItem extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 工资归属月份, ISO YearMonth 格式 "YYYY-MM" (e.g. "2026-05"). */
    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;

    @Column(name = "base_salary", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseSalary;

    @Column(name = "social_insurance_employee", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal socialInsuranceEmployee = BigDecimal.ZERO;

    @Column(name = "social_insurance_employer", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal socialInsuranceEmployer = BigDecimal.ZERO;

    @Column(name = "provident_fund_employee", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal providentFundEmployee = BigDecimal.ZERO;

    @Column(name = "provident_fund_employer", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal providentFundEmployer = BigDecimal.ZERO;

    @Column(name = "taxable_income", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal taxableIncome = BigDecimal.ZERO;

    @Column(name = "personal_tax", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal personalTax = BigDecimal.ZERO;

    @Column(name = "net_salary", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal netSalary = BigDecimal.ZERO;

    /**
     * 年终奖金额 (¥). #833 follow-up — Flyway V20260606_24.
     * Nullable: 大多数月份无年终奖, 设为 null 节省语义噪音.
     * 计税独立于月度工资 (一次性年终奖政策, 按月度等价 bonusAmount/12 找税率档).
     */
    @Column(name = "annual_bonus", precision = 12, scale = 2)
    private BigDecimal annualBonus;

    /**
     * 年终奖个税 (¥). #833 follow-up.
     * Nullable: 仅在 annualBonus 设定后计算填写.
     * 公式: tax = bonusAmount × bracket_rate − quick_deduction (China 一次性年终奖税法).
     */
    @Column(name = "annual_bonus_tax", precision = 12, scale = 2)
    private BigDecimal annualBonusTax;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SalaryStatus status = SalaryStatus.DRAFT;

    @Column(name = "remark", length = 500)
    private String remark;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "paid_by")
    private Long paidBy;
}
