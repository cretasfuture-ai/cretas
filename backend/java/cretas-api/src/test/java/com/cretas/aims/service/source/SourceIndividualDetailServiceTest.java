package com.cretas.aims.service.source;

import com.cretas.aims.dto.source.SourceIndividualDetailDTO;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.DnaVerificationStatus;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.source.SourceIndividual;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import com.cretas.aims.service.lineage.LineageGraphAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SourceIndividualDetailService} —— 系谱三类关系 + 去向, 跑在真 Hibernate/H2 上。
 *
 * <h3>数据形状照演示那 7 头</h3>
 * <pre>
 *   SL-BULL-001 (公) ─┬─ SL-CATTLE-003 (父母齐)
 *   SL-COW-002  (母) ─┤  SL-CATTLE-004 (父母齐)
 *                      │  SL-CATTLE-005 (只有父)
 *                      │  SL-CATTLE-006 (只有母)
 *                      └─ SL-CATTLE-007 (只有父)
 * </pre>
 *
 * <h3>什么是真的、什么是桩</h3>
 * 个体与原料批次的仓储是<b>真</b>的(H2 上跑真 JPQL —— 「后代」那条手写查询的括号
 * 只有在真库上才验得到, 见 {@link #offspringQueryIsScopedToTheFactory}); 血缘引擎
 * {@link LineageGraphAssembler} 是桩 —— 它自己有一整套测试, 这里只验「每个批次各问一次、
 * 结果合并去重、没批次时三个数组是空的不是 null」。
 *
 * <h3>变异对照(动手做过, 2026-09-15)</h3>
 * 去掉服务里 {@code .filter(si -> !id.equals(si.getId()))} ⇒ 005 的同父兄弟里出现 005 自己
 * ⇒ {@link #onlySireRegistered} 红。做之前先打印过 siblings 的编码确认变异到达。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class SourceIndividualDetailServiceTest {

    private static final String FACTORY = "F-SI-DETAIL";
    private static final String OTHER_FACTORY = "F-SI-DETAIL-OTHER";

    @Autowired TestEntityManager entityManager;
    @Autowired SourceIndividualRepository repository;
    @Autowired MaterialBatchRepository materialBatchRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private LineageGraphAssembler assembler;
    private SourceIndividualDetailService service;

    private String bull;
    private String cow;
    private String c003;
    private String c004;
    private String c005;
    private String c006;
    private String c007;

    @BeforeEach
    void setUp() {
        // material_batches.created_by → users(id) FK: 只 seed 批次不建全依赖树, 关 H2 FK 校验 (连接级)
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        assembler = mock(LineageGraphAssembler.class);
        service = new SourceIndividualDetailService(repository, materialBatchRepository, assembler);

        bull = persist(FACTORY, "SL-BULL-001", "1100000000001", "公", null, null);
        cow = persist(FACTORY, "SL-COW-002", "1100000000002", "母", null, null);
        c003 = persist(FACTORY, "SL-CATTLE-003", "1100000000003", "公", bull, cow);
        c004 = persist(FACTORY, "SL-CATTLE-004", "1100000000004", "母", bull, cow);
        c005 = persist(FACTORY, "SL-CATTLE-005", "1100000000005", "公", bull, null);
        c006 = persist(FACTORY, "SL-CATTLE-006", "1100000000006", "母", null, cow);
        c007 = persist(FACTORY, "SL-CATTLE-007", "1100000000007", "公", bull, null);
        entityManager.flush();
        entityManager.clear();
    }

    // ── ① 三类关系 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("005 只有父: sire 是 BULL-001, dam 为 null; 同父兄弟 = 003/004/007 (不含自身)")
    void onlySireRegistered() {
        SourceIndividualDetailDTO d = service.getDetail(FACTORY, c005);

        assertThat(d.getIndividual().getIndividualCode()).isEqualTo("SL-CATTLE-005");
        assertThat(d.getSire()).isNotNull();
        assertThat(d.getSire().getId()).isEqualTo(bull);
        assertThat(d.getSire().getIndividualCode()).isEqualTo("SL-BULL-001");
        assertThat(d.getSire().getTagNumber()).isEqualTo("1100000000001");
        // 母本没登记 ⇒ null, 且本体上的 damIndividualId 也是 null —— 前端据此说「未登记」
        assertThat(d.getDam()).isNull();
        assertThat(d.getIndividual().getDamIndividualId()).isNull();

        assertThat(d.getSiblingsBySire())
                .extracting(SourceIndividualDetailDTO.Summary::getIndividualCode)
                .containsExactly("SL-CATTLE-003", "SL-CATTLE-004", "SL-CATTLE-007");
        // 阴性对照落在**同一个列表**上: 自己不是自己的兄弟。
        assertThat(d.getSiblingsBySire())
                .extracting(SourceIndividualDetailDTO.Summary::getId)
                .doesNotContain(c005);

        // 005 自己没有后代
        assertThat(d.getOffspring()).isEmpty();
    }

    @Test
    @DisplayName("种公牛 BULL-001: 后代 = 003/004/005/007 (按编号), 父母为 null, 同父兄弟为空数组")
    void bullHasFourOffspring() {
        SourceIndividualDetailDTO d = service.getDetail(FACTORY, bull);

        assertThat(d.getOffspring())
                .extracting(SourceIndividualDetailDTO.Summary::getIndividualCode)
                .containsExactly("SL-CATTLE-003", "SL-CATTLE-004", "SL-CATTLE-005", "SL-CATTLE-007");
        assertThat(d.getOffspring())
                .extracting(SourceIndividualDetailDTO.Summary::getIndividualCode)
                .as("006 只有母本, 不是公牛的后代")
                .doesNotContain("SL-CATTLE-006");
        assertThat(d.getSire()).isNull();
        assertThat(d.getDam()).isNull();
        // 父本未登记 ⇒ 同父兄弟是空数组, ⛔ 不是 null
        assertThat(d.getSiblingsBySire()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("母牛 COW-002: 后代 = 003/004/006 —— 「或母本是它」那一半查得到")
    void cowHasThreeOffspring() {
        SourceIndividualDetailDTO d = service.getDetail(FACTORY, cow);

        assertThat(d.getOffspring())
                .extracting(SourceIndividualDetailDTO.Summary::getIndividualCode)
                .containsExactly("SL-CATTLE-003", "SL-CATTLE-004", "SL-CATTLE-006");
    }

    @Test
    @DisplayName("父母齐的 003: sire/dam 都有, 同父兄弟 = 004/005/007")
    void bothParentsRegistered() {
        SourceIndividualDetailDTO d = service.getDetail(FACTORY, c003);

        assertThat(d.getSire().getIndividualCode()).isEqualTo("SL-BULL-001");
        assertThat(d.getDam().getIndividualCode()).isEqualTo("SL-COW-002");
        assertThat(d.getDam().getSex()).isEqualTo("母");
        assertThat(d.getSiblingsBySire())
                .extracting(SourceIndividualDetailDTO.Summary::getIndividualCode)
                .containsExactly("SL-CATTLE-004", "SL-CATTLE-005", "SL-CATTLE-007");
    }

    @Test
    @DisplayName("「后代」查询带工厂过滤: 别厂一头 sire 指向同一个 id 的个体不许漏进来 (括号守卫)")
    void offspringQueryIsScopedToTheFactory() {
        // 别厂的个体, 系谱字段恰好指向本厂公牛的 id —— 派生查询 A AND (B OR C) 写错成
        // (A AND B) OR C 时, 这一条会被「OR dam = ?」那半边捞进来。这里它指向 dam 那一侧。
        String stray = persist(OTHER_FACTORY, "STRAY-001", "9990000000001", "母", null, bull);
        entityManager.flush();
        entityManager.clear();

        SourceIndividualDetailDTO d = service.getDetail(FACTORY, bull);

        assertThat(d.getOffspring())
                .extracting(SourceIndividualDetailDTO.Summary::getId)
                .doesNotContain(stray);
        // 阳性对照: 那一行确实在库里 (否则上面「不包含」是恒真式)
        assertThat(repository.findByIdAndFactoryId(stray, OTHER_FACTORY)).isPresent();
        assertThat(d.getOffspring()).hasSize(4);
    }

    @Test
    @DisplayName("系谱指向一份已不可查的档案 ⇒ sire 为 null, 但本体上的 sireIndividualId 仍在 (两者可区分)")
    void danglingParentIsNullButIdIsKept() {
        String orphan = persist(FACTORY, "SL-ORPHAN-008", "1100000000008", "公", "no-such-parent-id", null);
        entityManager.flush();
        entityManager.clear();

        SourceIndividualDetailDTO d = service.getDetail(FACTORY, orphan);

        assertThat(d.getSire()).isNull();
        assertThat(d.getIndividual().getSireIndividualId()).isEqualTo("no-such-parent-id");
        // 父本查不到 ⇒ 同父兄弟按那个 id 查, 结果自然为空 (不含自身)
        assertThat(d.getSiblingsBySire()).isEmpty();
    }

    @Test
    @DisplayName("别厂的 id / 不存在的 id ⇒ 404")
    void notFoundIs404() {
        assertThatThrownBy(() -> service.getDetail(OTHER_FACTORY, c005))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.getDetail(FACTORY, "nope"))
                .isInstanceOf(BusinessException.class);
    }

    // ── ② 去向 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("没挂批次 ⇒ batches=[] 且 downstream 三个数组为空 (⛔ 不是 null), 且不问血缘引擎")
    void noBatchesGivesEmptyArraysNotNull() {
        SourceIndividualDetailDTO d = service.getDetail(FACTORY, c006);

        assertThat(d.getBatches()).isNotNull().isEmpty();
        assertThat(d.getDownstream()).isNotNull();
        assertThat(d.getDownstream().getProductionBatches()).isNotNull().isEmpty();
        assertThat(d.getDownstream().getFinishedBatches()).isNotNull().isEmpty();
        assertThat(d.getDownstream().getShipments()).isNotNull().isEmpty();
        verify(assembler, never()).assembleImpact(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("挂了 2 个批次 ⇒ 各问一次 impact, 同一个生产批次只留一条; 批次按入库日倒序")
    void batchesAndDownstreamAreMergedAndDeduped() {
        String mbOld = persistBatch(c005, "MB-OLD", LocalDate.of(2026, 9, 1), "120");
        String mbNew = persistBatch(c005, "MB-NEW", LocalDate.of(2026, 9, 10), "80");
        entityManager.flush();
        entityManager.clear();

        TraceabilityDTO.LineageNode pb1 = node("PRODUCTION_BATCH", "8001", "PB-8001", "酱牛肉");
        TraceabilityDTO.LineageNode fg1 = node("FINISHED_BATCH", "fg-1", "FG-0001", "酱牛肉");
        when(assembler.assembleImpact(eq(FACTORY), eq("MATERIAL_BATCH"), eq(mbNew), anyInt()))
                .thenReturn(impact(mbNew, List.of(pb1), List.of(fg1), List.of()));
        // 旧批次也进了同一个生产批次 —— 合并后不许出现两条 PB-8001
        when(assembler.assembleImpact(eq(FACTORY), eq("MATERIAL_BATCH"), eq(mbOld), anyInt()))
                .thenReturn(impact(mbOld, List.of(node("PRODUCTION_BATCH", "8001", "PB-8001", "酱牛肉")),
                        List.of(), List.of()));

        SourceIndividualDetailDTO d = service.getDetail(FACTORY, c005);

        assertThat(d.getBatches())
                .extracting(SourceIndividualDetailDTO.BatchSummary::getBatchNumber)
                .containsExactly("MB-NEW", "MB-OLD");
        assertThat(d.getBatches().get(0).getQuantity()).isEqualByComparingTo("80");
        assertThat(d.getBatches().get(0).getUnit()).isEqualTo("kg");
        assertThat(d.getBatches().get(0).getReceiptDate()).isEqualTo(LocalDate.of(2026, 9, 10));

        assertThat(d.getDownstream().getProductionBatches())
                .extracting(TraceabilityDTO.LineageNode::getBatchNumber)
                .containsExactly("PB-8001");
        assertThat(d.getDownstream().getFinishedBatches())
                .extracting(TraceabilityDTO.LineageNode::getBatchNumber)
                .containsExactly("FG-0001");
        assertThat(d.getDownstream().getShipments()).isEmpty();

        verify(assembler).assembleImpact(FACTORY, "MATERIAL_BATCH", mbNew, LineageGraphAssembler.DEFAULT_MAX_DEPTH);
        verify(assembler).assembleImpact(FACTORY, "MATERIAL_BATCH", mbOld, LineageGraphAssembler.DEFAULT_MAX_DEPTH);
    }

    @Test
    @DisplayName("别厂 / 别的个体的批次不算 —— 只取挂了**这个**个体的")
    void batchesAreScopedToThisIndividual() {
        persistBatch(c005, "MB-005", LocalDate.of(2026, 9, 1), "10");
        persistBatch(c007, "MB-007", LocalDate.of(2026, 9, 2), "10");
        entityManager.flush();
        entityManager.clear();
        when(assembler.assembleImpact(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(impact("x", List.of(), List.of(), List.of()));

        SourceIndividualDetailDTO d = service.getDetail(FACTORY, c005);

        assertThat(d.getBatches())
                .extracting(SourceIndividualDetailDTO.BatchSummary::getBatchNumber)
                .containsExactly("MB-005");
    }

    @Test
    @DisplayName("血缘引擎返回 null ⇒ 503, ⛔ 不静默当成「没有下游」")
    void nullImpactIsNotSilentlyEmpty() {
        persistBatch(c005, "MB-X", LocalDate.of(2026, 9, 1), "10");
        entityManager.flush();
        entityManager.clear();
        when(assembler.assembleImpact(anyString(), anyString(), anyString(), anyInt())).thenReturn(null);

        assertThatThrownBy(() -> service.getDetail(FACTORY, c005))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("血缘引擎");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private String persist(String factoryId, String code, String tag, String sex,
                           String sireId, String damId) {
        String id = UUID.randomUUID().toString();
        SourceIndividual si = SourceIndividual.builder()
                .id(id)
                .factoryId(factoryId)
                .individualCode(code)
                .tagNumber(tag)
                .species("CATTLE")
                .breed("西门塔尔")
                .sex(sex)
                .originFarm("胜龙一号牧场")
                .dnaVerificationStatus(DnaVerificationStatus.SAMPLED)
                .sireIndividualId(sireId)
                .damIndividualId(damId)
                .build();
        entityManager.persist(si);
        return id;
    }

    /** 最小可入库的原料批次。⚠️ materialTypeId 留空 ⇒ materialName 为 null (H2 上不建物料类型树)。 */
    private String persistBatch(String individualId, String batchNumber, LocalDate receiptDate, String qty) {
        String id = UUID.randomUUID().toString();
        MaterialBatch mb = new MaterialBatch();
        mb.setId(id);
        mb.setFactoryId(FACTORY);
        mb.setBatchNumber(batchNumber);
        mb.setReceiptDate(receiptDate);
        mb.setWarehouseId("WH-1");
        mb.setReceiptQuantity(new BigDecimal(qty));
        mb.setQuantityUnit("kg");
        mb.setUsedQuantity(BigDecimal.ZERO);
        mb.setReservedQuantity(BigDecimal.ZERO);
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setCreatedBy(1L);
        mb.setSourceIndividualId(individualId);
        // H2 把 jsonb 里的 "{}" 存成 JSON 字符串字面量, 清空一级缓存后再读会抛
        // 「"{}" cannot be transformed to Json object」—— 与被测行为无关, 置 null 绕开。
        mb.setCustomFields(null);
        entityManager.persist(mb);
        return id;
    }

    private static TraceabilityDTO.LineageNode node(String type, String id, String batchNumber, String name) {
        return TraceabilityDTO.LineageNode.builder()
                .nodeType(type).nodeId(id).batchNumber(batchNumber).name(name).resolved(true).build();
    }

    private static TraceabilityDTO.ImpactResponse impact(String rootId,
                                                         List<TraceabilityDTO.LineageNode> production,
                                                         List<TraceabilityDTO.LineageNode> finished,
                                                         List<TraceabilityDTO.LineageNode> shipments) {
        return TraceabilityDTO.ImpactResponse.builder()
                .rootType("MATERIAL_BATCH").rootId(rootId)
                .productionBatches(production).finishedBatches(finished).shipments(shipments)
                .productionBatchCount(production.size()).finishedBatchCount(finished.size())
                .shipmentCount(shipments.size())
                .build();
    }
}
