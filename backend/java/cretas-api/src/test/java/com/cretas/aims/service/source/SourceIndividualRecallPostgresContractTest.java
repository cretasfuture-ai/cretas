package com.cretas.aims.service.source;

import com.cretas.aims.controller.MaterialBatchController;
import com.cretas.aims.controller.SourceIndividualController;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.datacenter.OperationLog;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.lineage.BatchLineageClosure;
import com.cretas.aims.entity.source.SourceIndividual;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.datacenter.OperationLogRepository;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import com.cretas.aims.service.lineage.LineageGraphAssembler;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** HTTP contract through the real source detail service, lineage assembler and PostgreSQL repositories. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({SourceIndividualDetailService.class, LineageGraphAssembler.class})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class SourceIndividualRecallPostgresContractTest {

    private static final String FACTORY = "F-SI-RECALL";
    private static final String OTHER_FACTORY = "F-SI-RECALL-OTHER";
    private static final String BATCH_URL = "/api/mobile/{factoryId}/material-batches/{batchId}";
    private static final String DETAIL_URL = "/api/mobile/{factoryId}/source-individuals/{id}/detail";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        SourceIndividualLockPostgresContractTest.database(registry);
    }

    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired MaterialBatchRepository batchRepository;
    @Autowired MaterialConsumptionRepository consumptionRepository;
    @Autowired OperationLogRepository operationLogRepository;
    @Autowired SourceIndividualRepository individualRepository;
    @Autowired SourceIndividualDetailService detailService;

    private MockMvc mvc;
    private Long operatorId;

    @BeforeEach
    void setUp() {
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        operatorId = SourceIndividualTestUsers.persistUser(em, FACTORY);
        var mobileService = mock(MobileService.class);
        when(mobileService.getUserFromToken("test-actor"))
                .thenReturn(UserDTO.builder().id(operatorId).factoryId(FACTORY).build());
        var individualService = new SourceIndividualService(individualRepository);
        var batchService = new MaterialBatchServiceImpl(
                batchRepository, null, null, new MaterialBatchMapper(), consumptionRepository, null, null, null);
        ReflectionTestUtils.setField(batchService, "operationLogRepository", operationLogRepository);
        ReflectionTestUtils.setField(batchService, "sourceIndividualService", individualService);
        mvc = standaloneSetup(
                new SourceIndividualController(individualService, detailService, mobileService),
                new MaterialBatchController(batchService, mobileService, null, null))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void unlinkMovesBatchAndShipmentIntoHistoricalRecall() throws Exception {
        String individualId = individual("CATTLE-A");
        String batchId = batch("MB-UNLINKED", individualId);
        String stillLinked = batch("MB-STILL-LINKED", individualId);
        shipmentClosure(batchId, "SHIP-UNLINKED");
        shipmentClosure(stillLinked, "SHIP-STILL-LINKED");
        flushAndClear();

        mvc.perform(get(DETAIL_URL, FACTORY, individualId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batches", hasSize(2)))
                .andExpect(jsonPath("$.data.downstream.shipments", hasSize(2)))
                .andExpect(jsonPath("$.data.previouslyLinkedBatches").isEmpty());

        mvc.perform(post(BATCH_URL + "/source-individual/unlink", FACTORY, batchId)
                        .header("Authorization", "Bearer test-actor")
                        .requestAttr("role", "warehouse_manager")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"核对耳标后解除"}
                                """))
                .andExpect(status().isOk());
        flushAndClear();
        assertThat(batchRepository.findById(batchId).orElseThrow().getSourceIndividualId()).isNull();

        mvc.perform(get(DETAIL_URL, FACTORY, individualId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batches", hasSize(1)))
                .andExpect(jsonPath("$.data.batches[0].id").value(stillLinked))
                .andExpect(jsonPath("$.data.downstream.shipments", hasSize(1)))
                .andExpect(jsonPath("$.data.downstream.shipments[0].nodeId").value("SHIP-STILL-LINKED"))
                .andExpect(jsonPath("$.data.previouslyLinkedBatches", hasSize(1)))
                .andExpect(jsonPath("$.data.previouslyLinkedBatches[0].batchId").value(batchId))
                .andExpect(jsonPath("$.data.previouslyLinkedBatches[0].batchNumber").value("MB-UNLINKED"))
                .andExpect(jsonPath("$.data.previouslyLinkedBatches[0].unlinkedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.previouslyLinkedBatches[0].reason").value("核对耳标后解除"))
                .andExpect(jsonPath("$.data.previouslyLinkedBatches[0].operatorId").value(operatorId.intValue()))
                .andExpect(jsonPath("$.data.previouslyLinkedDownstream.shipments", hasSize(1)))
                .andExpect(jsonPath("$.data.previouslyLinkedDownstream.shipments[0].nodeId").value("SHIP-UNLINKED"));
    }

    @Test
    void putRejectsDirectReplacementAndAcceptsTheCurrentIndividual() throws Exception {
        String a = individual("CATTLE-A");
        String b = individual("CATTLE-B");
        String batchId = batch("MB-EDIT", a);
        flushAndClear();

        mvc.perform(put(BATCH_URL, FACTORY, batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceIndividualId\":\"" + b + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SOURCE_INDIVIDUAL_ALREADY_LINKED"));
        flushAndClear();
        assertThat(batchRepository.findById(batchId).orElseThrow().getSourceIndividualId()).isEqualTo(a);

        mvc.perform(put(BATCH_URL, FACTORY, batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceIndividualId\":\"" + a + "\",\"notes\":\"核对完成\"}"))
                .andExpect(status().isOk());
        flushAndClear();
        assertThat(batchRepository.findById(batchId).orElseThrow().getNotes()).isEqualTo("核对完成");
        assertThat(operationLogRepository.findMaterialBatchUnlinksByIndividual(FACTORY, a)).isEmpty();
    }

    @Test
    void historyQueryReturnsLegacyAuditAndIgnoresAnotherFactoryWithTheSameIndividualId() throws Exception {
        String individualId = individual("CATTLE-A");
        Long otherOperatorId = SourceIndividualTestUsers.persistUser(em, OTHER_FACTORY);
        legacyLog(FACTORY, individualId, "ARCHIVED-BATCH", "MB-LOCAL", "核对; 角色: 原记录", operatorId);
        legacyLog(OTHER_FACTORY, individualId, "FOREIGN-BATCH", "MB-FOREIGN", "他厂原因", otherOperatorId);
        flushAndClear();

        assertThat(operationLogRepository.findMaterialBatchUnlinksByIndividual(OTHER_FACTORY, individualId))
                .extracting(OperationLog::getEntityId).containsExactly("FOREIGN-BATCH");
        mvc.perform(get(DETAIL_URL, FACTORY, individualId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batches").isEmpty())
                .andExpect(jsonPath("$.data.previouslyLinkedBatches", hasSize(1)))
                .andExpect(jsonPath("$.data.previouslyLinkedBatches[0].batchId").value("ARCHIVED-BATCH"))
                .andExpect(jsonPath("$.data.previouslyLinkedBatches[0].batchNumber").value("MB-LOCAL"))
                .andExpect(jsonPath("$.data.previouslyLinkedBatches[0].reason").value("核对; 角色: 原记录"))
                .andExpect(jsonPath("$.data.previouslyLinkedBatches[0].operatorId").value(operatorId.intValue()));
    }

    @Test
    void historyUsesLatestUnlinkAndOmitsABatchCurrentlyLinkedToTheSameIndividual() throws Exception {
        String individualId = individual("CATTLE-A");
        String currentBatchId = batch("MB-RELINKED", individualId);
        legacyLog(FACTORY, individualId, currentBatchId, "MB-RELINKED", "后来重新挂载", operatorId);
        legacyLog(FACTORY, individualId, "HISTORICAL-BATCH", "MB-HIST", "第一次解除", operatorId);
        legacyLog(FACTORY, individualId, "HISTORICAL-BATCH", "MB-HIST", "最近解除", operatorId);
        flushAndClear();

        mvc.perform(get(DETAIL_URL, FACTORY, individualId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batches", hasSize(1)))
                .andExpect(jsonPath("$.data.batches[0].id").value(currentBatchId))
                .andExpect(jsonPath("$.data.previouslyLinkedBatches", hasSize(1)))
                .andExpect(jsonPath("$.data.previouslyLinkedBatches[0].batchId").value("HISTORICAL-BATCH"))
                .andExpect(jsonPath("$.data.previouslyLinkedBatches[0].reason").value("最近解除"));
    }

    private String individual(String code) {
        String id = UUID.randomUUID().toString();
        em.persist(SourceIndividual.builder().id(id).factoryId(FACTORY).individualCode(code).build());
        return id;
    }

    private String batch(String number, String individualId) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(UUID.randomUUID().toString());
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber(number);
        batch.setReceiptDate(LocalDate.of(2026, 11, 19));
        batch.setWarehouseId("WH-1");
        batch.setReceiptQuantity(BigDecimal.TEN);
        batch.setQuantityUnit("kg");
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setCreatedBy(operatorId);
        batch.setSourceIndividualId(individualId);
        batch.setCustomFields(null);
        em.persist(batch);
        return batch.getId();
    }

    private void shipmentClosure(String batchId, String shipmentId) {
        var closure = new BatchLineageClosure();
        closure.setFactoryId(FACTORY);
        closure.setAncestorType("MATERIAL_BATCH");
        closure.setAncestorId(batchId);
        closure.setDescendantType("SHIPMENT_RECORD");
        closure.setDescendantId(shipmentId);
        closure.setDepth(2);
        em.persist(closure);
    }

    private void legacyLog(String factoryId, String individualId, String batchId,
                           String batchNumber, String reason, Long userId) {
        em.persist(OperationLog.builder()
                .factoryId(factoryId)
                .userId(userId)
                .module("MATERIAL_BATCH")
                .action("UNLINK_SOURCE_INDIVIDUAL")
                .entityType("MaterialBatch")
                .entityId(batchId)
                .oldValue(Map.of("sourceIndividualId", individualId))
                .summary("解除批次 " + batchNumber + " 的来源个体 " + individualId
                        + "; 原因: " + reason + "; 角色: warehouse_manager")
                .build());
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
