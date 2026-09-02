package com.cretas.aims.contract.fixture;

import jakarta.persistence.Column;
import jakarta.persistence.Version;

/**
 * Fixture 独立装置, 仅供
 * {@link com.cretas.aims.contract.NotNullColumnsHaveEntityDefaultsContractTest} 的阳性对照使用。
 *
 * <p>不是 {@code @Entity} (不需要真的映射到表), 只是给
 * {@code hasSourceInitializer} / {@code isStructurallyDefended} 这两条核心判定
 * 提供受控输入 —— 不用每次靠临时改写生产实体 (e.g. {@code ProductType.java}) 来验证
 * 扫描器本身有没有坏。
 *
 * <p>{@link #flagWithoutInitializer} 刻意模拟 {@code ProductType.acceptsRework}
 * 修复前 (#508 之前) 的形状: 有 {@code @Column}, wrapper 类型, 无初始化。
 */
public class NotNullDefaultScannerFixture {

    /** 已防御: 字段声明本身带初始化表达式。 */
    @Column(name = "flag_with_initializer")
    private Boolean flagWithInitializer = Boolean.TRUE;

    /** 未防御: 模拟 accepts_rework 修复前的形状 —— 扫描器必须把它判成 offender。 */
    @Column(name = "flag_without_initializer")
    private Boolean flagWithoutInitializer;

    /** 已防御(结构性): {@code @Version} 由 JPA/Hibernate 规范保证 persist 时自动赋值。 */
    @Version
    @Column(name = "version_flag")
    private Long versionFlag;

    /** 已防御(结构性): {@code insertable = false} —— 这一列根本不出现在 INSERT 语句里。 */
    @Column(name = "trigger_populated_flag", insertable = false, updatable = false)
    private Boolean triggerPopulatedFlag;
}
