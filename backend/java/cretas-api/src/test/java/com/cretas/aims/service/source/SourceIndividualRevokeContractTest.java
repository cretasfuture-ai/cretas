package com.cretas.aims.service.source;

import com.cretas.aims.dto.source.CreateSourceIndividualRequest;
import com.cretas.aims.dto.source.SourceIndividualDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 源头个体 类型/入场日期/撤销: 真 Hibernate 上验证新列映射、撤销落库、被批次引用时拦截。 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class SourceIndividualRevokeContractTest {

    private static final String FACTORY = "F-SI-REVOKE";

    @Autowired TestEntityManager em;
    @Autowired SourceIndividualRepository repository;
    @Autowired MaterialBatchRepository materialBatchRepository;
    private SourceIndividualService service;

    @BeforeEach
    void setUp() {
        service = new SourceIndividualService(repository);
        ReflectionTestUtils.setField(service, "materialBatchRepository", materialBatchRepository);
    }

    private SourceIndividualDTO create(String code) {
        return service.create(FACTORY, CreateSourceIndividualRequest.builder().individualCode(code).build());
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
    @DisplayName("撤销(正向): 返回原因/撤销人/时间, 读路径看不到, 已撤销列表能查回")
    void revokeStoresReasonByAtAndIsListedAsRevoked() {
        SourceIndividualDTO dto = create("T-3");
        em.flush();

        SourceIndividualDTO revoked = service.revoke(FACTORY, dto.getId(), " 录入错误 ", "42");
        em.flush();
        em.clear();

        assertThat(revoked.getRevokeReason()).isEqualTo("录入错误");
        assertThat(revoked.getRevokedBy()).isEqualTo("42");
        assertThat(revoked.getRevokedAt()).isNotNull();
        assertThatThrownBy(() -> service.getById(FACTORY, dto.getId())).isInstanceOf(BusinessException.class);

        List<SourceIndividualDTO> listed = service.listRevoked(FACTORY);
        assertThat(listed).extracting(SourceIndividualDTO::getId).contains(dto.getId());
        assertThat(listed.stream().filter(x -> x.getId().equals(dto.getId())).findFirst().orElseThrow()
                .getRevokeReason()).isEqualTo("录入错误");
        // 租户隔离: 别的工厂看不到
        assertThat(service.listRevoked("F-OTHER")).isEmpty();
    }

    @Test
    @DisplayName("撤销(阴性): 空原因 / 超 255 字 → 400, 档案仍在")
    void blankOrTooLongReasonRejectedAndRowStays() {
        SourceIndividualDTO dto = create("T-4");
        em.flush();

        assertThatThrownBy(() -> service.revoke(FACTORY, dto.getId(), " ", "1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> service.revoke(FACTORY, dto.getId(), "x".repeat(256), "1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(400));
        assertThat(service.getById(FACTORY, dto.getId())).isNotNull();
        // 255 字刚好可以
        assertThat(service.revoke(FACTORY, dto.getId(), "y".repeat(255), "1").getRevokeReason()).hasSize(255);
    }

    @Test
    @DisplayName("撤销(阴性): 已被原料批次引用 → 409 并点名批次号, 档案仍在可溯源")
    void usedByBatchCannotBeRevoked() {
        SourceIndividualDTO dto = create("T-5");
        MaterialBatch mb = new MaterialBatch();
        mb.setId("MB-REVOKE-1");
        mb.setFactoryId(FACTORY);
        mb.setBatchNumber("MB-20261118-001");
        mb.setReceiptDate(LocalDate.of(2026, 11, 18));
        mb.setWarehouseId("WH-1");
        mb.setReceiptQuantity(new BigDecimal("10"));
        mb.setQuantityUnit("kg");
        mb.setUsedQuantity(BigDecimal.ZERO);
        mb.setReservedQuantity(BigDecimal.ZERO);
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        // created_by 有 FK: 用本用例自建的用户, ⛔ 不写死 1L(只在别的类恰好留下 id=1 时才过)。
        mb.setCreatedBy(SourceIndividualTestUsers.persistUser(em.getEntityManager(), FACTORY));
        mb.setSourceIndividualId(dto.getId());
        mb.setCustomFields(null);
        em.persist(mb);
        em.flush();

        assertThatThrownBy(() -> service.revoke(FACTORY, dto.getId(), "录入错误", "1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("MB-20261118-001");
                });
        assertThat(service.getById(FACTORY, dto.getId())).isNotNull();
        assertThat(service.listRevoked(FACTORY)).extracting(SourceIndividualDTO::getId)
                .doesNotContain(dto.getId());
    }
}
