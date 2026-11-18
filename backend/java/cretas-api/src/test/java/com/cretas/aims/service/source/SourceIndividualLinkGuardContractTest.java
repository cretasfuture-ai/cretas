package com.cretas.aims.service.source;

import com.cretas.aims.dto.material.CreateMaterialBatchRequest;
import com.cretas.aims.dto.material.UpdateMaterialBatchRequest;
import com.cretas.aims.dto.source.CreateSourceIndividualRequest;
import com.cretas.aims.dto.source.SourceIndividualDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #1844 复审 M-A / M-B: 源头个体与「引用它的东西」两个方向都要守。
 *
 * <ul>
 *   <li>M-A 批次 → 个体: 原料批次新建/修改时挂的个体必须本厂、未撤销(库上无 FK, 只有这道闸)。
 *       判据跑在真 JPA(@Where + 锁查询)上, 并经产品真实入口
 *       {@link MaterialBatchServiceImpl#createMaterialBatch} / {@code updateMaterialBatch} 证明接上了。</li>
 *   <li>M-B 个体 → 个体: 已被当作父本/母本的个体不许撤销(否则后代的父本悄悄变空)。</li>
 * </ul>
 * 并发串行(锁)见 {@code SourceIndividualRevokeLockContractTest}。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class SourceIndividualLinkGuardContractTest {

    private static final String FACTORY = "F-SI-LINK";
    private static final String OTHER = "F-SI-LINK-OTHER";
    private static final String MAT = "mt-si-link";

    @Autowired TestEntityManager em;
    @Autowired SourceIndividualRepository repository;
    @Autowired MaterialBatchRepository materialBatchRepository;
    private SourceIndividualService service;

    // MaterialBatchServiceImpl 的其余依赖用 mock: 这里只关心「来源个体校验有没有接在入口上」。
    private MaterialBatchRepository batchRepoMock;
    private MaterialBatchMapper mapperMock;
    private MaterialBatchServiceImpl batchService;

    @BeforeEach
    void setUp() {
        service = new SourceIndividualService(repository);
        ReflectionTestUtils.setField(service, "materialBatchRepository", materialBatchRepository);

        batchRepoMock = mock(MaterialBatchRepository.class);
        mapperMock = mock(MaterialBatchMapper.class);
        RawMaterialTypeRepository typeRepo = mock(RawMaterialTypeRepository.class);
        batchService = new MaterialBatchServiceImpl(batchRepoMock,
                mock(MaterialBatchAdjustmentRepository.class), typeRepo, mapperMock,
                mock(MaterialConsumptionRepository.class), mock(ProductionPlanBatchUsageRepository.class),
                mock(ExcelUtil.class), mock(FuturePlanMatchingService.class));
        ReflectionTestUtils.setField(batchService, "warehouseResolver", mock(WarehouseResolver.class));
        ReflectionTestUtils.setField(batchService, "sourceIndividualService", service);

        RawMaterialType type = new RawMaterialType();
        type.setId(MAT);
        type.setName("牛胴体");
        type.setUnit("kg");
        type.setFactoryId(FACTORY);
        type.setIsAbacaPackaging(false);
        when(typeRepo.findById(MAT)).thenReturn(Optional.of(type));
        when(mapperMock.toEntity(any(), anyString(), any(Long.class))).thenAnswer(inv -> {
            CreateMaterialBatchRequest req = inv.getArgument(0);
            MaterialBatch b = new MaterialBatch();
            b.setFactoryId(FACTORY);
            b.setWarehouseId("WH-1");
            b.setMaterialTypeId(req.getMaterialTypeId());
            b.setReceiptQuantity(req.getReceiptQuantity());
            b.setSourceIndividualId(req.getSourceIndividualId());
            return b;
        });
        when(batchRepoMock.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private SourceIndividualDTO create(String factory, String code) {
        return service.create(factory, CreateSourceIndividualRequest.builder().individualCode(code).build());
    }

    private SourceIndividualDTO createChild(String code, String sireId, String damId) {
        return service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode(code).sireIndividualId(sireId).damIndividualId(damId).build());
    }

    private static Integer codeOf(Throwable t) {
        return ((BusinessException) t).getCode();
    }

    // ── M-A: requireLinkable 判据(真 JPA) ──────────────────────────────

    @Test
    @DisplayName("M-A 判据: 本厂在档 → 通过并 trim; 空白 → null(不挂)")
    void activeSameFactoryIsLinkable() {
        String id = create(FACTORY, "L-1").getId();
        em.flush();

        assertThat(service.requireLinkable(FACTORY, " " + id + " ")).isEqualTo(id);
        assertThat(service.requireLinkable(FACTORY, "  ")).isNull();
        assertThat(service.requireLinkable(FACTORY, null)).isNull();
    }

    @Test
    @DisplayName("M-A 判据: 已撤销 → 409 SOURCE_INDIVIDUAL_REVOKED")
    void revokedIsRejected409() {
        String id = create(FACTORY, "L-2").getId();
        em.flush();
        assertThat(service.requireLinkable(FACTORY, id)).isEqualTo(id);   // 阳性对照: 撤销前可挂

        service.revoke(FACTORY, id, "录入错误", "1");
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.requireLinkable(FACTORY, id))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo("SOURCE_INDIVIDUAL_REVOKED");
                });
    }

    @Test
    @DisplayName("M-A 判据: 别厂的 / 不存在的 → 400")
    void otherFactoryOrMissingIsRejected400() {
        String foreign = create(OTHER, "L-3").getId();
        em.flush();

        assertThatThrownBy(() -> service.requireLinkable(FACTORY, foreign))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(400));
        assertThatThrownBy(() -> service.requireLinkable(FACTORY, "no-such-individual"))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(400));
        // 阳性对照: 在它自己的工厂里是可挂的 —— 上面的 400 不是因为这条记录根本不存在。
        assertThat(service.requireLinkable(OTHER, foreign)).isEqualTo(foreign);
    }

    // ── M-A: 产品真实入口(新建/修改原料批次) ─────────────────────────────

    @Test
    @DisplayName("M-A 入口: 撤销后再拿这个 ID 新建原料批次 → 409, 不落库")
    void createBatchWithRevokedIndividualIsRejected() {
        String id = create(FACTORY, "L-4").getId();
        em.flush();
        service.revoke(FACTORY, id, "录入错误", "1");
        em.flush();
        em.clear();

        assertThatThrownBy(() -> batchService.createMaterialBatch(FACTORY, inbound(id), 1L))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        verify(batchRepoMock, never()).save(any());
    }

    @Test
    @DisplayName("M-A 入口: 用别厂个体新建原料批次 → 400, 不落库")
    void createBatchWithForeignIndividualIsRejected() {
        String foreign = create(OTHER, "L-5").getId();
        em.flush();

        assertThatThrownBy(() -> batchService.createMaterialBatch(FACTORY, inbound(foreign), 1L))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(400));
        verify(batchRepoMock, never()).save(any());
    }

    @Test
    @DisplayName("M-A 入口(阳性对照): 本厂在档个体 → 批次落库并带上该个体")
    void createBatchWithActiveIndividualPersistsLink() {
        String id = create(FACTORY, "L-6").getId();
        em.flush();

        try {
            batchService.createMaterialBatch(FACTORY, inbound(" " + id + " "), 1L);
        } catch (RuntimeException ignored) {
            // save 之后的下游(事件/均价)在 mock 下可能抛错 —— 这里只看 save 收到了什么。
        }
        ArgumentCaptor<MaterialBatch> saved = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(batchRepoMock, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getSourceIndividualId()).isEqualTo(id);
    }

    @Test
    @DisplayName("M-A 入口: 修改批次改挂到已撤销个体 → 409, 不落库")
    void updateBatchToRevokedIndividualIsRejected() {
        String id = create(FACTORY, "L-7").getId();
        em.flush();
        service.revoke(FACTORY, id, "录入错误", "1");
        em.flush();
        em.clear();

        MaterialBatch existing = new MaterialBatch();
        existing.setId("MB-LINK-UPD");
        existing.setFactoryId(FACTORY);
        existing.setStatus(MaterialBatchStatus.AVAILABLE);
        existing.setReceiptQuantity(new BigDecimal("10"));
        existing.setUsedQuantity(BigDecimal.ZERO);
        existing.setReservedQuantity(BigDecimal.ZERO);
        existing.setCreatedBy(1L);
        when(batchRepoMock.findById("MB-LINK-UPD")).thenReturn(Optional.of(existing));

        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setSourceIndividualId(id);
        assertThatThrownBy(() -> batchService.updateMaterialBatch(FACTORY, "MB-LINK-UPD", req))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        verify(batchRepoMock, never()).save(any());
        assertThat(existing.getSourceIndividualId()).isNull();
    }

    // ── M-B: 系谱引用 ───────────────────────────────────────────────

    @Test
    @DisplayName("M-B: 已作为父本/母本被引用 → 撤销 409 IN_USE_PEDIGREE 并点名后代; 档案仍在")
    void parentWithOffspringCannotBeRevoked() {
        String sire = create(FACTORY, "P-SIRE").getId();
        String dam = create(FACTORY, "P-DAM").getId();
        em.flush();
        String child = createChild("P-CHILD", sire, dam).getId();
        em.flush();

        for (String parent : new String[]{sire, dam}) {
            assertThatThrownBy(() -> service.revoke(FACTORY, parent, "录入错误", "1"))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(409);
                        assertThat(e.getErrorCode()).isEqualTo("IN_USE_PEDIGREE");
                        assertThat(e.getMessage()).contains("P-CHILD");
                    });
            assertThat(service.getById(FACTORY, parent)).isNotNull();
        }

        // 阳性对照: 后代先撤销之后, 父本就能撤销 —— 409 来自系谱引用, 不是别的原因。
        service.revoke(FACTORY, child, "录入错误", "1");
        em.flush();
        assertThat(service.revoke(FACTORY, sire, "录入错误", "1").getRevokeReason()).isEqualTo("录入错误");
    }

    private static CreateMaterialBatchRequest inbound(String sourceIndividualId) {
        CreateMaterialBatchRequest req = new CreateMaterialBatchRequest();
        req.setMaterialTypeId(MAT);
        req.setWarehouseId("WH-1");
        req.setQuantityUnit("kg");
        req.setReceiptQuantity(BigDecimal.valueOf(100));
        req.setTotalWeight(BigDecimal.valueOf(100));
        req.setTotalValue(BigDecimal.valueOf(1000));
        req.setUnitPrice(BigDecimal.TEN);
        req.setReceiptDate(LocalDate.now());
        req.setSourceDocType("MANUAL_ADJUST");
        req.setSourceIndividualId(sourceIndividualId);
        return req;
    }
}
