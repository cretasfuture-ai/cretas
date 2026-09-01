package com.cretas.aims.repository;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔒 Real Hibernate/H2 startup gate for {@link MaterialBatch#getSourceDocNumber()}.
 *
 * <p>这是 mapper 单测（{@code MaterialBatchMapperSourceDocIdTest}）覆盖不到的那一半：
 * mapper 测试只证明「entity 有值时 mapper 会搬过去」，不证明「Hibernate 的
 * {@code @Formula} 子查询真的能从 {@code purchase_receive_records} 解出收货单号」。
 * 两者缺一个都可能是「机制在、没接上」（见 measurement-and-wiring 形态 B）。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class MaterialBatchSourceDocNumberFormulaTest {

    @Autowired private PurchaseReceiveRecordRepository receiveRecordRepository;
    @Autowired private MaterialBatchRepository materialBatchRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private static final String FACTORY = "F-JPA-SRC-DOC-NUM";

    @Test
    void purchaseReceiveBatchResolvesHumanReadableReceiveNumber() {
        // 共享 H2 schema 上没有真实的 factory/supplier/user 行 —— 与
        // ProductionPlanRepositoryQueryValidationTest 同一惯用法, 只为验证
        // @Formula 子查询本身, 不测 FK 完整性。
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            PurchaseReceiveRecord record = new PurchaseReceiveRecord();
            record.setId("PRR-JPA-SRC-DOC-NUM");
            record.setFactoryId(FACTORY);
            record.setReceiveNumber("RCV-20260901-6529");
            record.setSupplierId("SUP-JPA-SRC-DOC-NUM");
            record.setReceiveDate(LocalDate.of(2026, 9, 1));
            record.setStatus(PurchaseReceiveStatus.CONFIRMED);
            record.setReceivedBy(1L);
            receiveRecordRepository.saveAndFlush(record);

            MaterialBatch batch = new MaterialBatch();
            batch.setId("MB-JPA-SRC-DOC-NUM-1");
            batch.setFactoryId(FACTORY);
            batch.setBatchNumber("MT-20260901-4022");
            batch.setWarehouseId("WH-JPA-SRC-DOC-NUM");
            batch.setReceiptDate(LocalDate.of(2026, 9, 1));
            batch.setReceiptQuantity(new BigDecimal("10"));
            batch.setQuantityUnit("kg");
            batch.setUsedQuantity(BigDecimal.ZERO);
            batch.setReservedQuantity(BigDecimal.ZERO);
            batch.setStatus(MaterialBatchStatus.AVAILABLE);
            batch.setCreatedBy(1L);
            batch.setSourceDocType("PURCHASE_RECEIVE");
            batch.setSourceDocId(record.getId());
            batch.setCustomFields(null);
            materialBatchRepository.saveAndFlush(batch);

            // 同一次收货第二个批次号 —— owner 场景「一个入库批次里面有三个批次号」的最小复现。
            MaterialBatch sibling = new MaterialBatch();
            sibling.setId("MB-JPA-SRC-DOC-NUM-2");
            sibling.setFactoryId(FACTORY);
            sibling.setBatchNumber("MT-20260901-4043");
            sibling.setWarehouseId("WH-JPA-SRC-DOC-NUM");
            sibling.setReceiptDate(LocalDate.of(2026, 9, 1));
            sibling.setReceiptQuantity(new BigDecimal("5"));
            sibling.setQuantityUnit("kg");
            sibling.setUsedQuantity(BigDecimal.ZERO);
            sibling.setReservedQuantity(BigDecimal.ZERO);
            sibling.setStatus(MaterialBatchStatus.AVAILABLE);
            sibling.setCreatedBy(1L);
            sibling.setSourceDocType("PURCHASE_RECEIVE");
            sibling.setSourceDocId(record.getId());
            sibling.setCustomFields(null);
            materialBatchRepository.saveAndFlush(sibling);

            // 🔴 关键: saveAndFlush 之后那两个 Java 对象仍然是当前持久化上下文的
            // 一级缓存实例。不 clear() 的话, 下面的 findById 会直接把它们原样递还
            // (@Formula 是"读时由 Hibernate 从 ResultSet 灌入", 不是"跟着 INSERT 一起赋值",
            // 于是断言读到的其实是从未被 Formula 赋过值的旧对象 —— 与 SQL 是否正确无关的假阴性,
            // 见 measurement-and-wiring 硬约束 6 类 "仪器本身的问题掩盖了真相")。
            // entityManager.clear() 逼下面两次 findById 真的发一趟 SELECT 去 DB 里读。
            entityManager.clear();

            MaterialBatch reloaded = materialBatchRepository.findById("MB-JPA-SRC-DOC-NUM-1").orElseThrow();
            assertThat(reloaded.getSourceDocNumber())
                    .as("@Formula 子查询没能从 purchase_receive_records 解出收货单号 —— "
                            + "分组表头会漏成 UUID 或空白")
                    .isEqualTo("RCV-20260901-6529");

            MaterialBatch reloadedSibling = materialBatchRepository.findById("MB-JPA-SRC-DOC-NUM-2").orElseThrow();
            assertThat(reloadedSibling.getSourceDocNumber())
                    .as("同一次收货的第二个批次号必须解出同一个收货单号 —— 这正是分组要用的等价关系")
                    .isEqualTo(reloaded.getSourceDocNumber());
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }

    @Test
    void nonPurchaseReceiveSourceStaysNull() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            MaterialBatch batch = new MaterialBatch();
            batch.setId("MB-JPA-SRC-DOC-NUM-TRANSFER");
            batch.setFactoryId(FACTORY);
            batch.setBatchNumber("MT-20260901-TRANSFER");
            batch.setWarehouseId("WH-JPA-SRC-DOC-NUM");
            batch.setReceiptDate(LocalDate.of(2026, 9, 1));
            batch.setReceiptQuantity(new BigDecimal("3"));
            batch.setQuantityUnit("kg");
            batch.setUsedQuantity(BigDecimal.ZERO);
            batch.setReservedQuantity(BigDecimal.ZERO);
            batch.setStatus(MaterialBatchStatus.AVAILABLE);
            batch.setCreatedBy(1L);
            // 调拨入库: sourceDocId 是一个真实存在的 UUID, 但不是 purchase_receive_records 的行 ——
            // 子查询的 AND source_doc_type='PURCHASE_RECEIVE' 应该让它如实解不出来, 不是碰巧对上别的表。
            batch.setSourceDocType("TRANSFER_IN");
            batch.setSourceDocId("TRANSFER-DOC-ID-NOT-A-RECEIVE");
            batch.setCustomFields(null);
            materialBatchRepository.saveAndFlush(batch);
            entityManager.clear(); // 同上, 逼 findById 真的从 DB 重新查

            MaterialBatch reloaded = materialBatchRepository.findById("MB-JPA-SRC-DOC-NUM-TRANSFER").orElseThrow();
            assertThat(reloaded.getSourceDocNumber())
                    .as("非采购收货来源必须如实为 null —— 前端据此走「无关联单据/其它单据类型」分支, "
                            + "而不是显示一个碰巧对上的收货单号")
                    .isNull();
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }
}
