package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ERP-S6-02-STATUS-FILTER-IGNORED-001: 批次列表 / AI 工具 material_batch_query 的 status 筛选被忽略。
 *
 * <p>以前 {@code PageRequest.status} 绑定进来却从没被读, 返回的是全部状态里最新的一页 (F006 实测
 * 全是 AVAILABLE, 而库里有 22 条 USED_UP)。
 *
 * <p>本测试走真实链路: AI 工具 → 真 {@link MaterialBatchServiceImpl} → 真 {@link MaterialBatchRepository}
 * (H2 PG 兼容)。种入混合状态的批次, 让<b>最新的一页全是 AVAILABLE</b> (正是缺陷里的样子),
 * 再逐个状态查, 断言只回这个状态、总数等于该状态的真实条数 —— 证明是在分页之前过滤的。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("批次列表 status 筛选 — AI 工具 / 服务 / 仓储整链 (ERP-S6-02)")
class MaterialBatchStatusFilterEntryTest {

    private static final String F = "F-MBS-1";
    private static final String OTHER_F = "F-MBS-OTHER";
    private static final long CREATOR = 7L;
    private static final long OTHER_CREATOR = 8L;
    private static final String OTHER_MATERIAL = "MT-MBS-2";
    private static final LocalDate JANUARY_RECEIPT = LocalDate.of(2026, 1, 15);

    /** 每个状态种几条; AVAILABLE 最多且最新, 撑满第一页。 */
    private static final Map<MaterialBatchStatus, Integer> SEEDED = Map.of(
            MaterialBatchStatus.USED_UP, 3,
            MaterialBatchStatus.EXPIRED, 2,
            MaterialBatchStatus.DEPLETED, 1,
            MaterialBatchStatus.AVAILABLE, 12);

    @Autowired private MaterialBatchRepository repo;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MaterialBatchServiceImpl service;
    private MaterialBatchQueryTool tool;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        LocalDateTime base = LocalDateTime.now().minusDays(30);
        int seq = 0;
        // 非 AVAILABLE 的先建 (更旧), AVAILABLE 后建 (更新) —— 默认 createdAt DESC 的第一页全是 AVAILABLE
        for (MaterialBatchStatus status : List.of(MaterialBatchStatus.USED_UP, MaterialBatchStatus.EXPIRED,
                MaterialBatchStatus.DEPLETED, MaterialBatchStatus.AVAILABLE)) {
            for (int i = 0; i < SEEDED.get(status); i++) {
                repo.saveAndFlush(batch(F, "B" + (seq++), status, CREATOR));
            }
        }
        // 阴性对照: 别的工厂同状态的批次不得被带出来
        repo.saveAndFlush(batch(OTHER_F, "X1", MaterialBatchStatus.USED_UP, CREATOR));
        // 阴性对照: 同厂别人建的 USED_UP 批次, DataScope=SELF 时不得出现
        repo.saveAndFlush(batch(F, "OTHERS-1", MaterialBatchStatus.USED_UP, OTHER_CREATOR));
        // 另一种原料的唯一一条批次, 建得很早 —— 默认排序下远在第 1 页之后 (原料类型筛选用)
        MaterialBatch otherMaterial = batch(F, "LATE-MT2", MaterialBatchStatus.SCRAPPED, CREATOR);
        otherMaterial.setMaterialTypeId(OTHER_MATERIAL);
        repo.saveAndFlush(otherMaterial);
        // 最早那条 (B0) 是 1 月入库的, 其余都是今天 (入库日期筛选用)
        jdbcTemplate.update("UPDATE material_batches SET inbound_date = ? WHERE id = ?", JANUARY_RECEIPT, "B0");
        // createdAt 由 @PrePersist 写 now(); 改成递增的旧时间, 让排序确定
        jdbcTemplate.update("UPDATE material_batches SET created_at = ? WHERE factory_id = ?",
                base, OTHER_F);
        List<MaterialBatch> all = repo.findAll();
        for (MaterialBatch b : all) {
            int n = Integer.parseInt(b.getBatchNumber().replaceAll("\\D", "").isEmpty()
                    ? "0" : b.getBatchNumber().replaceAll("\\D", ""));
            jdbcTemplate.update("UPDATE material_batches SET created_at = ? WHERE id = ?",
                    base.plusMinutes(n), b.getId());
        }

        MaterialBatchMapper mapper = mock(MaterialBatchMapper.class);
        when(mapper.toDTO(any(MaterialBatch.class))).thenAnswer(inv -> {
            MaterialBatch b = inv.getArgument(0);
            return MaterialBatchDTO.builder().id(b.getId()).status(b.getStatus()).build();
        });
        service = new MaterialBatchServiceImpl(repo, null, null, mapper, null, null, null, null);
        tool = new MaterialBatchQueryTool();
        ReflectionTestUtils.setField(tool, "materialBatchService", service);
        DataScopeContext.push(scope(DataScope.ALL));
    }

    @AfterEach
    void clearScope() {
        DataScopeContext.pop();
    }

    @ParameterizedTest(name = "AI 工具 status={0} 只回 {0}")
    @EnumSource(value = MaterialBatchStatus.class, names = {"USED_UP", "EXPIRED", "DEPLETED", "AVAILABLE"})
    void aiToolReturnsOnlyTheRequestedStatus(MaterialBatchStatus status) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("status", status.name());
        params.put("size", 5);

        Map<String, Object> result = tool.doExecute(F, params, Map.of());

        @SuppressWarnings("unchecked")
        List<MaterialBatchDTO> content = (List<MaterialBatchDTO>) result.get("content");
        assertThat(content).isNotEmpty();
        assertThat(content).extracting(MaterialBatchDTO::getStatus).containsOnly(status);
        // ALL 范围: 同厂别人建的那条 USED_UP 也算; 别厂的 X1 不算
        int expected = SEEDED.get(status) + (status == MaterialBatchStatus.USED_UP ? 1 : 0);
        assertThat(content).hasSize(Math.min(5, expected));
        assertThat(content).extracting(MaterialBatchDTO::getId).doesNotContain("X1");
        assertThat(result.get("queryConditions")).isEqualTo(Map.of("status", status.name()));
    }

    @ParameterizedTest(name = "REST 列表 status={0}: 总数 = 该状态真实条数 (分页前过滤)")
    @EnumSource(value = MaterialBatchStatus.class, names = {"USED_UP", "EXPIRED", "DEPLETED", "AVAILABLE"})
    void listFiltersBeforePaging(MaterialBatchStatus status) {
        PageRequest request = new PageRequest();
        request.setPage(1);
        request.setSize(2);
        request.setStatus(status.name());

        PageResponse<MaterialBatchDTO> page = service.getMaterialBatchList(F, request);

        assertThat(page.getContent()).extracting(MaterialBatchDTO::getStatus).containsOnly(status);
        // 同厂别人建的那条 USED_UP 在 ALL 范围内应计入
        int expected = SEEDED.get(status) + (status == MaterialBatchStatus.USED_UP ? 1 : 0);
        assertThat(page.getTotalElements()).isEqualTo((long) expected);
    }

    @Test
    @DisplayName("阳性对照: 不带 status 时第一页全是 AVAILABLE (缺陷现场就是这个样子)")
    void unfilteredFirstPageIsAllAvailable() {
        PageRequest request = new PageRequest();
        request.setPage(1);
        request.setSize(5);

        PageResponse<MaterialBatchDTO> page = service.getMaterialBatchList(F, request);

        assertThat(page.getContent()).extracting(MaterialBatchDTO::getStatus)
                .containsOnly(MaterialBatchStatus.AVAILABLE);
        assertThat(page.getTotalElements()).isEqualTo(20L);
    }

    @Test
    @DisplayName("AI 工具 materialTypeId: 唯一匹配远在第 1 页之后也能查到, 总数是真实条数")
    void aiToolMaterialTypeMatchBeyondFirstPage() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("materialTypeId", OTHER_MATERIAL);
        params.put("size", 5);

        Map<String, Object> result = tool.doExecute(F, params, Map.of());

        @SuppressWarnings("unchecked")
        List<MaterialBatchDTO> content = (List<MaterialBatchDTO>) result.get("content");
        assertThat(content).extracting(MaterialBatchDTO::getId).containsExactly("LATE-MT2");
        assertThat(((Number) result.get("totalElements")).longValue()).isEqualTo(1L);
    }

    @Test
    @DisplayName("AI 工具入库日期范围: 唯一匹配是最早的一条 (最后一页) 也能查到, 总数是真实条数")
    void aiToolDateRangeMatchBeyondFirstPage() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("startDate", "2026-01-01");
        params.put("endDate", "2026-01-31");
        params.put("size", 5);

        Map<String, Object> result = tool.doExecute(F, params, Map.of());

        @SuppressWarnings("unchecked")
        List<MaterialBatchDTO> content = (List<MaterialBatchDTO>) result.get("content");
        assertThat(content).extracting(MaterialBatchDTO::getId).containsExactly("B0");
        assertThat(((Number) result.get("totalElements")).longValue()).isEqualTo(1L);
    }

    @Test
    @DisplayName("原料类型 + 状态组合: 两个条件在数据库里同时生效")
    void materialTypeAndStatusTogether() {
        PageRequest request = new PageRequest();
        request.setSize(5);
        request.setStatus("AVAILABLE");

        PageResponse<MaterialBatchDTO> page = service.getMaterialBatchList(F, request,
                new com.cretas.aims.dto.material.MaterialBatchListFilter(OTHER_MATERIAL, null, null));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("日期写错 → 400, 不静默当成「不限日期」")
    void unparseableDateIsRejected() {
        Map<String, Object> params = new HashMap<>();
        params.put("startDate", "2026/01/01");

        assertThatThrownBy(() -> tool.doExecute(F, params, Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2026/01/01");
    }

    @Test
    @DisplayName("DataScope=SELF + status: 只回自己建的该状态批次")
    void selfScopeAndStatus() {
        DataScopeContext.pop();
        DataScopeContext.push(scope(DataScope.SELF));
        PageRequest request = new PageRequest();
        request.setSize(10);
        request.setStatus("USED_UP");

        PageResponse<MaterialBatchDTO> page = service.getMaterialBatchList(F, request);

        assertThat(page.getContent()).extracting(MaterialBatchDTO::getStatus)
                .containsOnly(MaterialBatchStatus.USED_UP);
        assertThat(page.getContent()).extracting(MaterialBatchDTO::getId).doesNotContain("OTHERS-1");
        assertThat(page.getTotalElements()).isEqualTo(3L);
    }

    @Test
    @DisplayName("关键词 + status: 两个条件同时生效")
    void keywordAndStatus() {
        PageRequest request = new PageRequest();
        request.setSize(10);
        request.setKeyword("BN-B1");      // BN-B1, BN-B10, BN-B11 ... 右模糊
        request.setStatus("AVAILABLE");

        PageResponse<MaterialBatchDTO> page = service.getMaterialBatchList(F, request);

        assertThat(page.getContent()).extracting(MaterialBatchDTO::getStatus)
                .containsOnly(MaterialBatchStatus.AVAILABLE);
        // B1 是 USED_UP (第 2 条), 不得出现; B10..B17 是 AVAILABLE
        assertThat(page.getContent()).extracting(MaterialBatchDTO::getId).doesNotContain("B1");
        assertThat(page.getContent()).isNotEmpty();
    }

    @Test
    @DisplayName("认不出的状态 400 并列出可选值, 不静默当成「不筛」")
    void unknownStatusIsRejected() {
        PageRequest request = new PageRequest();
        request.setStatus("IN_USE");

        assertThatThrownBy(() -> service.getMaterialBatchList(F, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("IN_USE");
    }

    @Test
    @DisplayName("AI 工具 schema 的状态枚举 = 批次状态枚举本身 (含 DEPLETED, 不含不存在的 IN_USE)")
    void toolSchemaListsRealStatuses() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) tool.getParametersSchema().get("properties");
        @SuppressWarnings("unchecked")
        List<String> allowed = (List<String>) ((Map<String, Object>) props.get("status")).get("enum");
        assertThat(allowed).contains("DEPLETED", "USED_UP", "EXPIRED").doesNotContain("IN_USE", "PENDING", "REJECTED");
    }

    private static DataScopeContext scope(DataScope scope) {
        return DataScopeContext.builder().userId(CREATOR).factoryId(F).roleCode("warehouse_manager")
                .scope(scope).column("created_by").build();
    }

    private static MaterialBatch batch(String factoryId, String id, MaterialBatchStatus status, long createdBy) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(factoryId);
        b.setBatchNumber("BN-" + id);
        b.setMaterialTypeId("MT-MBS-1");
        b.setWarehouseId("WH-MBS-1");
        b.setReceiptDate(LocalDate.now());
        b.setReceiptQuantity(new BigDecimal("10"));
        b.setQuantityUnit("kg");
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setStatus(status);
        b.setCreatedBy(createdBy);
        return b;
    }
}
