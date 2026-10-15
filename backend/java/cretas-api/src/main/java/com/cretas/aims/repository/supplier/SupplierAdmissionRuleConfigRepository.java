package com.cretas.aims.repository.supplier;

import com.cretas.aims.entity.supplier.SupplierAdmissionRuleConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 工厂级供应商准入/验收规则配置 repository —— 一厂一行。
 *
 * <p>0 行是合法状态: 该厂沿用代码里的默认门槛。见
 * {@link SupplierAdmissionRuleConfig} 的类注释。
 *
 * @since 2026-08-14
 */
@Repository
public interface SupplierAdmissionRuleConfigRepository
        extends JpaRepository<SupplierAdmissionRuleConfig, String> {

    Optional<SupplierAdmissionRuleConfig> findByFactoryId(String factoryId);
}
