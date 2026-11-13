package com.cretas.aims.event;

import com.cretas.aims.entity.Commission;
import com.cretas.aims.entity.CommissionRule;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.SalesOpportunity;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.OpportunityStage;
import com.cretas.aims.repository.CommissionRepository;
import com.cretas.aims.service.CommissionService;
import com.cretas.aims.service.SalesOpportunityService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商机赢单 → 提成落库的真提交链路 (真 SalesOpportunityService 入口 + 真监听器 + 真 CommissionService + 真仓储)。
 *
 * <p>为什么要有这一条: {@link CommissionEventListener} 是同步的 AFTER_COMMIT 监听器。回调时赢单事务
 * 的资源仍绑在线程上, {@code CommissionService.calculate} 的 {@code @Transactional}(REQUIRED) 会
 * 「加入」那个已提交的事务, {@code save} 进了持久化上下文却再也没人 flush —— 提成静默丢失,
 * 监听器的 catch 什么都没抓到, 日志里还打着「Calculated commission」。单测 mock 掉 service
 * 看不见这件事。这里赢单走产品真实入口 {@code transitionStage} (它自己提交), 再在新事务里读回。
 *
 * <p>阳性对照: 不经过事件、直接调 {@code calculate} 必须落库 —— 证明夹具 (规则 / 外键 / H2) 本身可用,
 * 红只能归因到「加入了已提交的事务」。阴性对照: 外层回滚的赢单不产生提成; 别厂商机一行不动。
 *
 * <p>独立 H2 库名: 不与共享 testdb 上的其它 @DataJpaTest 上下文互相 create-drop。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:commission_after_commit;MODE=PostgreSQL;"
        + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=MONTH,YEAR,VALUE")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({SalesOpportunityService.class, CommissionService.class, CommissionEventListener.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CommissionEventListenerAfterCommitContractTest {

    @Autowired SalesOpportunityService opportunityService;
    @Autowired CommissionService commissionService;
    @Autowired CommissionRepository commissionRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager entityManager;

    @Test
    void committedClosedWonWritesExactlyOneDurableCommission() {
        String factory = "F-CM-1";
        Long owner = tenant(factory);
        rule(factory, owner, "5.00");
        String opp = opportunity("OPP-CM-1", factory, owner, "20000.00");
        String otherFactory = "F-CM-1B";
        Long otherOwner = tenant(otherFactory);
        rule(otherFactory, otherOwner, "5.00");
        String untouched = opportunity("OPP-CM-1B", otherFactory, otherOwner, "20000.00");

        opportunityService.transitionStage(factory, opp, OpportunityStage.CLOSED_WON, null, false, null, owner);

        Optional<Commission> written = commission(opp);
        assertThat(written).as("赢单提交后提成必须真的落库").isPresent();
        assertThat(written.get().getAmount()).isEqualByComparingTo("1000.00");
        assertThat(written.get().getFactoryId()).isEqualTo(factory);
        assertThat(written.get().getSalesId()).isEqualTo(owner);
        assertThat(commission(untouched)).as("别厂商机没有赢单, 不应有提成").isEmpty();
    }

    @Test
    void positiveControlDirectCalculateWithoutAnOuterTransactionIsDurable() {
        String factory = "F-CM-2";
        Long owner = tenant(factory);
        rule(factory, owner, "10.00");
        String opp = opportunity("OPP-CM-2", factory, owner, "3000.00");
        tx().executeWithoutResult(s -> {
            SalesOpportunity o = entityManager.find(SalesOpportunity.class, opp);
            o.setStage(OpportunityStage.CLOSED_WON);
        });

        commissionService.calculate(opp);

        assertThat(commission(opp)).isPresent()
                .get().extracting(Commission::getAmount).satisfies(a -> assertThat(a).isEqualByComparingTo("300.00"));
    }

    @Test
    void rolledBackClosedWonLeavesNoCommission() {
        String factory = "F-CM-3";
        Long owner = tenant(factory);
        rule(factory, owner, "5.00");
        String opp = opportunity("OPP-CM-3", factory, owner, "20000.00");

        tx().executeWithoutResult(s -> {
            opportunityService.transitionStage(factory, opp, OpportunityStage.CLOSED_WON, null, false, null, owner);
            s.setRollbackOnly();
        });

        assertThat(commission(opp)).isEmpty();
        OpportunityStage stage = tx().execute(s -> entityManager.find(SalesOpportunity.class, opp).getStage());
        assertThat(stage).isEqualTo(OpportunityStage.LEAD);
    }

    private Optional<Commission> commission(String opportunityId) {
        return tx().execute(s -> {
            Optional<Commission> c = commissionRepository.findBySalesOpportunityIdAndDeletedAtIsNull(opportunityId);
            c.ifPresent(entityManager::detach);
            return c;
        });
    }

    private void rule(String factoryId, Long salesId, String percentage) {
        tx().executeWithoutResult(s -> entityManager.persist(CommissionRule.builder()
                .factoryId(factoryId)
                .salesId(salesId)
                .percentage(new BigDecimal(percentage))
                .effectiveFrom(LocalDate.of(2020, 1, 1))
                .createdBy(salesId)
                .build()));
    }

    private String opportunity(String id, String factoryId, Long owner, String value) {
        tx().executeWithoutResult(s -> entityManager.persist(SalesOpportunity.builder()
                .id(id)
                .factoryId(factoryId)
                .customerId("C-" + factoryId)
                .title("after-commit " + id)
                .stage(OpportunityStage.LEAD)
                .valueAmount(new BigDecimal(value))
                .ownerId(owner)
                .createdBy(owner)
                .build()));
        return id;
    }

    private Long tenant(String factoryId) {
        return tx().execute(s -> {
            Factory factory = new Factory();
            factory.setId(factoryId);
            factory.setName(factoryId);
            factory.setType(FactoryType.FACTORY);
            factory.setLevel(0);
            factory.setIsActive(true);
            factory.setManuallyVerified(false);
            factory.setAiWeeklyQuota(20);
            entityManager.persist(factory);

            User user = new User();
            user.setFactoryId(factoryId);
            user.setUsername("commission-ac-" + factoryId);
            user.setPasswordHash("test-only");
            user.setIsActive(true);
            entityManager.persist(user);
            entityManager.flush();

            Customer customer = new Customer();
            customer.setId("C-" + factoryId);
            customer.setFactoryId(factoryId);
            customer.setCode("C-" + factoryId);
            customer.setCustomerCode("C-" + factoryId);
            customer.setName("Customer " + factoryId);
            customer.setIsActive(true);
            customer.setCreatedBy(user.getId());
            entityManager.persist(customer);
            return user.getId();
        });
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
