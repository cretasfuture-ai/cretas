package com.cretas.aims.service.source;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.impl.source.SourceIndividualQueryTool;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.datacenter.OperationLog;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.lineage.BatchLineageClosure;
import com.cretas.aims.entity.source.SourceIndividual;
import com.cretas.aims.service.lineage.LineageGraphAssembler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** Exercises the serialized tool reply with historical recall data from PostgreSQL. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({SourceIndividualQueryTool.class, SourceIndividualDetailService.class, LineageGraphAssembler.class})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class SourceIndividualRecallToolPostgresContractTest {

    private static final String FACTORY = "F-SI-RECALL-TOOL";
    private static final String INDIVIDUAL_ID = "SI-RECALL-TOOL";
    private static final String HISTORICAL_BATCH_ID = "MB-ID-HISTORICAL";
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 11, 19);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        SourceIndividualLockPostgresContractTest.database(registry);
    }

    @Autowired EntityManager em;
    @Autowired SourceIndividualQueryTool tool;
    @Autowired ObjectMapper objectMapper;

    @ParameterizedTest
    @CsvSource({"downstream,false", "profile,false", "downstream,true", "profile,true"})
    void replyKeepsHistoricalBatchAndShipmentSeparate(String ask, boolean hasCurrentBatch) throws Exception {
        Long operatorId = SourceIndividualTestUsers.persistUser(em, FACTORY);
        em.persist(SourceIndividual.builder().id(INDIVIDUAL_ID).factoryId(FACTORY)
                .individualCode("CATTLE-A").build());
        if (hasCurrentBatch) {
            persistCurrentBatch(operatorId);
        }
        persistHistoricalRecall(operatorId);
        em.flush();
        em.clear();

        String arguments = objectMapper.writeValueAsString(Map.of("individual_code", "CATTLE-A", "ask", ask));
        JsonNode response = objectMapper.readTree(tool.execute(ToolCall.of("recall-1", "source_individual_query", arguments),
                Map.of("factoryId", FACTORY, "userId", operatorId)));
        assertThat(response.path("success").asBoolean()).isTrue();
        JsonNode reply = response.path("data");
        JsonNode data = reply.path("data");
        String currentText = "downstream".equals(ask)
                ? (hasCurrentBatch ? "现挂载原料批次 MB-CURRENT" : "现挂载 0 批")
                : (hasCurrentBatch ? "现挂载 1 个原料批次" : "现挂载 0 个原料批次");
        String summary = data.path("summary").asText();

        assertAll(
                () -> assertThat(data.path("previouslyLinkedBatchCount").asInt()).isEqualTo(1),
                () -> assertThat(data.path("previouslyLinkedBatches").path(0).path("batchId").asText())
                        .isEqualTo(HISTORICAL_BATCH_ID),
                () -> assertThat(data.path("previouslyLinkedDownstream").path("shipments").path(0)
                        .path("batchNumber").asText()).isEqualTo("DN-HISTORICAL"),
                () -> assertThat(summary).contains(currentText, "曾挂载 1 批 MB-HISTORICAL",
                        "原因 核对耳标后解除", "→ 出货 DN-HISTORICAL").doesNotContain("没有去向"),
                () -> assertThat(reply.path("message").asText()).isEqualTo(summary));
        if ("downstream".equals(ask)) {
            assertThat(data.path("batches").size()).isEqualTo(hasCurrentBatch ? 1 : 0);
            assertThat(data.path("downstream").path("shipments").isEmpty()).isTrue();
        } else {
            assertThat(data.path("batchCount").asInt(-1)).isEqualTo(hasCurrentBatch ? 1 : 0);
        }
    }

    private void persistCurrentBatch(Long operatorId) {
        var batch = new MaterialBatch();
        batch.setId("MB-ID-CURRENT");
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("MB-CURRENT");
        batch.setSourceIndividualId(INDIVIDUAL_ID);
        batch.setReceiptDate(BUSINESS_DATE);
        batch.setWarehouseId("WH-1");
        batch.setReceiptQuantity(BigDecimal.TEN);
        batch.setQuantityUnit("kg");
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setCreatedBy(operatorId);
        batch.setCustomFields(null);
        em.persist(batch);
    }

    private void persistHistoricalRecall(Long operatorId) {
        em.persist(OperationLog.builder().factoryId(FACTORY).userId(operatorId)
                .module("MATERIAL_BATCH").action("UNLINK_SOURCE_INDIVIDUAL")
                .entityType("MaterialBatch").entityId(HISTORICAL_BATCH_ID)
                .oldValue(Map.of("sourceIndividualId", INDIVIDUAL_ID, "batchNumber", "MB-HISTORICAL"))
                .newValue(Map.of("unlinkReason", "核对耳标后解除"))
                .build());

        var customer = new Customer();
        customer.setId("CUSTOMER-RECALL");
        customer.setFactoryId(FACTORY);
        customer.setCode("CUSTOMER-RECALL");
        customer.setCustomerCode("CUSTOMER-RECALL");
        customer.setName("召回测试客户");
        customer.setCreatedBy(operatorId);
        em.persist(customer);

        var shipment = new SalesDeliveryRecord();
        shipment.setId("SHIP-HISTORICAL");
        shipment.setFactoryId(FACTORY);
        shipment.setCustomerId(customer.getId());
        shipment.setDeliveryNumber("DN-HISTORICAL");
        shipment.setDeliveryDate(BUSINESS_DATE);
        shipment.setShippedBy(operatorId);
        shipment.setStatus(SalesDeliveryStatus.SHIPPED);
        em.persist(shipment);

        var closure = new BatchLineageClosure();
        closure.setFactoryId(FACTORY);
        closure.setAncestorType("MATERIAL_BATCH");
        closure.setAncestorId(HISTORICAL_BATCH_ID);
        closure.setDescendantType("SHIPMENT_RECORD");
        closure.setDescendantId(shipment.getId());
        closure.setDepth(2);
        em.persist(closure);
    }
}
