package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.PurchaseRequisitionStatus;
import com.cretas.aims.entity.inventory.PurchaseRequisition;
import com.cretas.aims.repository.inventory.PurchaseRequisitionRepository;
import com.cretas.aims.service.inventory.impl.PurchaseRequisitionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 请购「我的 + 状态」必须同时生效 (原型缺口 G2): 真实 JPA Context + 真实 Repository. */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class PurchaseRequisitionListFilterRepositoryQueryValidationTest {

    private static final String F = "IT-PR-FILTER";
    @Autowired private PurchaseRequisitionRepository repo;
    private PurchaseRequisitionServiceImpl service;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        service = new PurchaseRequisitionServiceImpl(repo, mock(PurchaseService.class),
                mock(com.cretas.aims.repository.UserRepository.class),
                mock(com.cretas.aims.repository.inventory.PurchaseRequisitionApprovalRecordRepository.class));
        save("A", 1L, PurchaseRequisitionStatus.DRAFT);
        save("B", 1L, PurchaseRequisitionStatus.PENDING_APPROVAL);
        save("C", 2L, PurchaseRequisitionStatus.PENDING_APPROVAL);
        save("D", 2L, PurchaseRequisitionStatus.DRAFT);
        repo.flush();
    }

    private void save(String no, Long requester, PurchaseRequisitionStatus st) {
        PurchaseRequisition pr = new PurchaseRequisition();
        pr.setFactoryId(F);
        pr.setRequisitionNumber("PR-" + no);
        pr.setRequesterId(requester);
        pr.setStatus(st);
        pr.setRequestedItems(List.of(Map.of("materialName", "盐", "quantity", 1, "unit", "kg")));
        repo.save(pr);
    }

    private List<String> numbers(PageResponse<PurchaseRequisition> r) {
        return r.getContent().stream().map(PurchaseRequisition::getRequisitionNumber).sorted().toList();
    }

    @Test
    void mineAndStatusApplyTogether() {
        assertThat(numbers(service.list(F, PurchaseRequisitionStatus.PENDING_APPROVAL, 1L, 1, 20)))
                .containsExactly("PR-B");
        assertThat(numbers(service.list(F, PurchaseRequisitionStatus.PENDING_APPROVAL, 2L, 1, 20)))
                .containsExactly("PR-C");
    }

    @Test
    void singleConditionsStillWork() {
        assertThat(numbers(service.list(F, PurchaseRequisitionStatus.DRAFT, null, 1, 20))).containsExactly("PR-A", "PR-D");
        assertThat(numbers(service.list(F, null, 2L, 1, 20))).containsExactly("PR-C", "PR-D");
        assertThat(numbers(service.list(F, null, null, 1, 20))).hasSize(4);
    }
}
