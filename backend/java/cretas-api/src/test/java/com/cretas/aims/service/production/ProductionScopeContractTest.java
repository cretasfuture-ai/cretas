package com.cretas.aims.service.production;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real service behavior gate for production-plan creator scope and DB pagination semantics. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ProductionScopeContractTest {

    private static final String FACTORY = "F-SCOPE-SERVICE";
    private static final String OTHER_FACTORY = "F-SCOPE-OTHER";

    @Autowired TestEntityManager entityManager;
    @Autowired ProductionPlanRepository productionPlanRepository;

    private BusinessRowScope businessRowScope;
    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        businessRowScope = mock(BusinessRowScope.class);
        ProductionPlanMapper mapper = mock(ProductionPlanMapper.class);
        when(mapper.toDTO(any(ProductionPlan.class))).thenAnswer(invocation -> {
            ProductionPlan plan = invocation.getArgument(0);
            ProductionPlanDTO dto = new ProductionPlanDTO();
            dto.setId(plan.getId());
            dto.setPlanNumber(plan.getPlanNumber());
            return dto;
        });
        ConversionRepository conversions = mock(ConversionRepository.class);
        when(conversions.findByFactoryIdAndProductTypeId(anyString(), anyString())).thenReturn(List.of());

        service = new ProductionPlanServiceImpl(
                productionPlanRepository,
                mock(ProductionBatchRepository.class),
                mock(MaterialBatchRepository.class),
                mock(MaterialConsumptionRepository.class),
                mock(ProductionPlanBatchUsageRepository.class),
                mock(ProductTypeRepository.class),
                mapper,
                conversions,
                mock(SchedulingService.class),
                mock(ProductionLineRepository.class),
                mock(UserRepository.class),
                mock(ExcelUtil.class),
                mock(SalesOrderRepository.class),
                mock(SalesOrderItemRepository.class));
        ReflectionTestUtils.setField(service, "businessRowScope", businessRowScope);
    }

    @Test
    void ownerScopeFiltersBeforePageCountAndAppliesKeywordAndStatusInDb() {
        Fixture fixture = fixture();
        when(businessRowScope.creatorIds(FACTORY)).thenReturn(List.of(fixture.ownerId()));

        PageResponse<ProductionPlanDTO> firstPage = service.getProductionPlanList(
                FACTORY, PageRequest.of(1, 1, "createdAt", "ASC"));
        assertThat(firstPage.getTotalElements()).isEqualTo(2L);
        assertThat(firstPage.getContent()).hasSize(1);
        assertThat(firstPage.getContent()).allMatch(dto -> dto.getId().startsWith("SCOPE-OWN-"));

        PageRequest filtered = PageRequest.of(1, 20, "createdAt", "DESC");
        filtered.setKeyword("OWN-PENDING");
        filtered.setStatus("PENDING");
        PageResponse<ProductionPlanDTO> keywordStatus = service.getProductionPlanList(FACTORY, filtered);
        assertThat(keywordStatus.getTotalElements()).isEqualTo(1L);
        assertThat(keywordStatus.getContent()).extracting(ProductionPlanDTO::getId)
                .containsExactly("SCOPE-OWN-PENDING");
        assertThat(service.getProductionPlansByStatus(FACTORY, ProductionPlanStatus.PENDING))
                .extracting(ProductionPlanDTO::getId)
                .containsExactly("SCOPE-OWN-PENDING");
    }

    @Test
    void ownerScopeRejectsOutsideDetailAndKeepsStatisticsScoped() {
        Fixture fixture = fixture();
        when(businessRowScope.creatorIds(FACTORY)).thenReturn(List.of(fixture.ownerId()));

        assertThatThrownBy(() -> service.getProductionPlanById(FACTORY, "SCOPE-OUTSIDE"))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("数据范围");

        Map<String, Object> statistics = service.getProductionStatistics(
                FACTORY, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(statistics)
                .containsEntry("totalPlans", 2)
                .containsEntry("completedPlans", 1L)
                .containsEntry("pendingPlans", 1L)
                .containsEntry("inProgressPlans", 0L)
                .containsEntry("totalCost", new BigDecimal("7.00"));
    }

    @Test
    void emptyScopeHasNoRowsAndAllOrUninjectedScopeRetainsLegacyVisibility() {
        fixture();
        when(businessRowScope.creatorIds(FACTORY)).thenReturn(List.of());
        assertThat(service.getProductionPlanList(FACTORY, PageRequest.of(1, 20)).getTotalElements()).isZero();
        assertThat(service.getProductionStatistics(
                FACTORY, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .containsEntry("totalPlans", 0);

        when(businessRowScope.creatorIds(FACTORY)).thenReturn(null); // ALL / no explicit factory policy
        assertThat(service.getProductionPlanList(FACTORY, PageRequest.of(1, 20)).getTotalElements()).isEqualTo(3L);

        ReflectionTestUtils.setField(service, "businessRowScope", null); // internal/no HTTP compatibility
        assertThat(service.getProductionPlanList(FACTORY, PageRequest.of(1, 20)).getTotalElements()).isEqualTo(3L);
    }

    private Fixture fixture() {
        Factory factory = factory(FACTORY, "scope-service-factory");
        Factory otherFactory = factory(OTHER_FACTORY, "scope-other-factory");
        entityManager.persist(factory);
        entityManager.persist(otherFactory);

        User owner = user("scope-owner", FACTORY);
        User outside = user("scope-outside", FACTORY);
        User other = user("scope-other", OTHER_FACTORY);
        entityManager.persist(owner);
        entityManager.persist(outside);
        entityManager.persist(other);
        entityManager.flush();

        ProductType product = product("SCOPE-PRODUCT", FACTORY, owner.getId());
        ProductType otherProduct = product("SCOPE-OTHER-PRODUCT", OTHER_FACTORY, other.getId());
        entityManager.persist(product);
        entityManager.persist(otherProduct);
        entityManager.flush();

        entityManager.persist(plan("SCOPE-OWN-DONE", FACTORY, product.getId(), owner.getId(),
                ProductionPlanStatus.COMPLETED, new BigDecimal("5.00")));
        entityManager.persist(plan("SCOPE-OWN-PENDING", FACTORY, product.getId(), owner.getId(),
                ProductionPlanStatus.PENDING, BigDecimal.ZERO));
        entityManager.persist(plan("SCOPE-OUTSIDE", FACTORY, product.getId(), outside.getId(),
                ProductionPlanStatus.COMPLETED, new BigDecimal("100.00")));
        ProductionPlan deleted = plan("SCOPE-DELETED", FACTORY, product.getId(), owner.getId(),
                ProductionPlanStatus.COMPLETED, new BigDecimal("1000.00"));
        entityManager.persist(deleted);
        entityManager.persist(plan("SCOPE-CROSS-FACTORY", OTHER_FACTORY, otherProduct.getId(), other.getId(),
                ProductionPlanStatus.COMPLETED, new BigDecimal("1000.00")));
        entityManager.flush();
        deleted.softDelete();
        entityManager.flush();
        entityManager.clear();
        return new Fixture(owner.getId());
    }

    private Factory factory(String id, String name) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName(name);
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        return factory;
    }

    private User user(String username, String factoryId) {
        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername(username);
        user.setPasswordHash("test-only");
        user.setIsActive(true);
        return user;
    }

    private ProductType product(String id, String factoryId, Long createdBy) {
        ProductType product = new ProductType();
        product.setId(id);
        product.setFactoryId(factoryId);
        product.setCreatedBy(createdBy);
        product.setCode(id);
        product.setName(id);
        product.setUnit("box");
        product.setCategory("FINISHED_PRODUCT");
        product.setProductCategory("FINISHED_PRODUCT");
        product.setIsActive(true);
        return product;
    }

    private ProductionPlan plan(String id, String factoryId, String productId, Long createdBy,
                                ProductionPlanStatus status, BigDecimal cost) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(id);
        plan.setFactoryId(factoryId);
        plan.setPlanNumber(id);
        plan.setProductTypeId(productId);
        plan.setPlannedQuantity(BigDecimal.TEN);
        plan.setPlannedUnit("box");
        plan.setPlannedDate(LocalDate.of(2026, 10, 10));
        plan.setStatus(status);
        plan.setSourceType(PlanSourceType.MANUAL);
        plan.setCreatedBy(createdBy);
        plan.setActualMaterialCost(cost);
        plan.setActualLaborCost(status == ProductionPlanStatus.COMPLETED
                ? new BigDecimal("2.00") : BigDecimal.ZERO);
        plan.setSourceOrderIds(null);
        plan.setWorkflowOutputUnitsByProduct(null);
        plan.setSelectedBomRecipeIdsByProduct(null);
        plan.setSelectedBomVersionsByProduct(null);
        return plan;
    }

    private record Fixture(Long ownerId) {}
}
