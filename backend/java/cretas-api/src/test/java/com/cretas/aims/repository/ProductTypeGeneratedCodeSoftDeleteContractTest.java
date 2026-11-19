package com.cretas.aims.repository;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真 JPA Context: 自动编码取号必须把软删行算进去。
 * 唯一约束 uk_product_types_factory_code (factory_id, code) 对软删行同样生效;
 * 实体上的 @Where(deleted_at IS NULL) 会让 JPQL 看不到软删行 (F006 实测: 活行最大 0038,
 * 软删行占到 0048, 预览出 0039 保存即撞约束)。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ProductTypeGeneratedCodeSoftDeleteContractTest {

    @Autowired TestEntityManager entityManager;
    @Autowired ProductTypeRepository productRepository;

    @Test
    void generatedPrefixQuerySeesSoftDeletedRowsAndStaysFactoryScoped() {
        Factory factory = factory("F-GENCODE-A");
        Factory other = factory("F-GENCODE-B");
        User user = new User();
        user.setFactoryId(factory.getId());
        user.setUsername("gencode-user");
        user.setPasswordHash("test-only");
        user.setIsActive(true);
        entityManager.persist(user);

        entityManager.persist(product("P-LIVE", factory.getId(), user.getId(), "CPF0060038", false));
        entityManager.persist(product("P-DEL-1", factory.getId(), user.getId(), "CPF0060039", true));
        entityManager.persist(product("P-DEL-2", factory.getId(), user.getId(), "CPF0060048", true));
        entityManager.persist(product("P-OTHER-FACTORY", other.getId(), user.getId(), "CPF0060099", false));
        entityManager.persist(product("P-OTHER-PREFIX", factory.getId(), user.getId(), "CPDD0000077", false));
        entityManager.flush();
        entityManager.clear();

        // 阴性对照: 实体查询确实被 @Where 过滤掉软删行 (证明原缺陷成因真实存在)
        assertThat(productRepository.existsByFactoryIdAndCode(factory.getId(), "CPF0060039")).isFalse();
        assertThat(productRepository.existsByFactoryIdAndCode(factory.getId(), "CPF0060038")).isTrue();

        assertThat(productRepository.findCodesByFactoryIdAndGeneratedPrefix(factory.getId(), "CPF006"))
                .containsExactlyInAnyOrder("CPF0060038", "CPF0060039", "CPF0060048");
    }

    private Factory factory(String id) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName("gen code " + id);
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);
        return factory;
    }

    private ProductType product(String id, String factoryId, Long userId, String code, boolean softDeleted) {
        ProductType product = new ProductType();
        product.setId(id);
        product.setFactoryId(factoryId);
        product.setCreatedBy(userId);
        product.setCode(code);
        product.setName("n-" + id);
        product.setUnit("盒");
        product.setCategory("FINISHED_PRODUCT");
        product.setProductCategory("FINISHED_PRODUCT");
        product.setIsActive(true);
        if (softDeleted) {
            product.setDeletedAt(LocalDateTime.now());
        }
        return product;
    }
}
