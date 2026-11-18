package com.cretas.aims.service.source;

import com.cretas.aims.dto.source.CreateSourceIndividualRequest;
import com.cretas.aims.dto.source.SourceIndividualDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 源头个体 类型/入场日期/撤销原因: 真 Hibernate 上验证新列映射与落库(不是 mock 仓储)。 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class SourceIndividualRevokeTest {

    private static final String FACTORY = "F-SI-REVOKE";

    @Autowired TestEntityManager em;
    @Autowired SourceIndividualRepository repository;
    private SourceIndividualService service;

    @BeforeEach
    void setUp() {
        service = new SourceIndividualService(repository);
    }

    @Test
    @DisplayName("建档: 类型归一为大写并和入场日期一起落库、读回")
    void typeAndEntryDatePersist() {
        SourceIndividualDTO dto = service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("T-1").individualType("carcass")
                .entryDate(LocalDate.of(2026, 11, 1)).build());
        em.flush();
        em.clear();
        SourceIndividualDTO back = service.getById(FACTORY, dto.getId());
        assertThat(back.getIndividualType()).isEqualTo("CARCASS");
        assertThat(back.getEntryDate()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    @DisplayName("建档: 未知类型被拒, 不落库")
    void unknownTypeRejected() {
        assertThatThrownBy(() -> service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("T-2").individualType("DEAD").build()))
                .isInstanceOf(BusinessException.class);
        assertThat(repository.existsByFactoryIdAndIndividualCode(FACTORY, "T-2")).isFalse();
    }

    @Test
    @DisplayName("撤销: 原因落库且档案从读路径消失; 空原因被拒且档案仍在")
    void revokeKeepsReasonAndHidesRow() {
        SourceIndividualDTO dto = service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("T-3").build());
        em.flush();

        assertThatThrownBy(() -> service.revoke(FACTORY, dto.getId(), " "))
                .isInstanceOf(BusinessException.class);
        assertThat(service.getById(FACTORY, dto.getId())).isNotNull();

        service.revoke(FACTORY, dto.getId(), " 录入错误 ");
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.getById(FACTORY, dto.getId()))
                .isInstanceOf(BusinessException.class);
        Object[] row = (Object[]) em.getEntityManager().createNativeQuery(
                "SELECT revoke_reason, deleted_at FROM source_individuals WHERE id = ?1")
                .setParameter(1, dto.getId()).getSingleResult();
        assertThat(row[0]).isEqualTo("录入错误");
        assertThat(row[1]).isNotNull();
    }
}
