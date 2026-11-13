package com.cretas.aims.service.bom.impl;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomPriceAdjustmentAudit;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal.SourceType;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal.Status;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomPriceAdjustmentAuditRepository;
import com.cretas.aims.repository.bom.BomPriceAdjustmentProposalRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BOM 调价建议的两条成本口径 (F006 验收 2026-11-10, D-S5-10 / D-S5-11)。
 *
 * <p>复现形状取自线上: 包材移动均价 0.5, 采购收货 100 个 @0.8 后移动均价变成 0.7501;
 * 同一物料挂在 ACTIVE 版本与多个 ARCHIVED 历史版本上。</p>
 *
 * <ul>
 *   <li>D-S5-10: 历史 (归档) 版本的成本不得回写 —— 生成端不出建议, 批准端对已存在的旧建议 409 且一行不改。</li>
 *   <li>D-S5-11: 批准写入的就是建议行上审批人看到的价; 建议价 = 将要继承的档案价, 档案价变了就 409 STALE,
 *       ⛔ 不静默按批准时的均价重算、不改写建议行。</li>
 * </ul>
 */
@DisplayName("BOM 调价建议: 历史版本不回写 + 批准即所见")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BomPriceAdjustmentHistoricalVersionContractTest {

    private static final String F = "F006";
    private static final String MAT = "RMT-PKG";

    @Mock private BomRecipeItemRepository itemRepository;
    @Mock private BomRecipeRepository recipeRepository;
    @Mock private BomPriceAdjustmentProposalRepository proposalRepository;
    @Mock private BomPriceAdjustmentAuditRepository auditRepository;
    @Mock private RawMaterialTypeRepository materialRepository;
    @Mock private com.cretas.aims.service.bom.BomRecipeService bomRecipeService;

    private BomPriceAdjustmentServiceImpl service;
    /** item() 造出的行 → 所属配方 id; 供 findRecipeIdById 桥接 (不能在 item() 里打桩: 它常在别的 when(...) 里被调用)。 */
    private final java.util.Map<Long, String> recipeIdByItem = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        service = new BomPriceAdjustmentServiceImpl(
                itemRepository, recipeRepository, proposalRepository, auditRepository, materialRepository,
                bomRecipeService);

        // 锁读桥接到各用例已打桩的普通读: 这里的用例只关心「读到什么」; 锁序与并发由真 PG 契约测试守。
        when(proposalRepository.lockByIdAndFactoryId(any(), any())).thenAnswer(inv ->
                proposalRepository.findByIdAndFactoryId(inv.getArgument(0), inv.getArgument(1)));
        when(proposalRepository.findMaterialTypeIdByIdAndFactoryId(any(), any())).thenAnswer(inv ->
                proposalRepository.findByIdAndFactoryId(inv.getArgument(0), inv.getArgument(1))
                        .map(BomPriceAdjustmentProposal::getMaterialTypeId));
        when(materialRepository.lockByFactoryIdAndIdIn(any(), any())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(1);
            return ids.stream().map(id -> materialRepository.findById(id))
                    .flatMap(java.util.Optional::stream).toList();
        });
        when(proposalRepository.save(any(BomPriceAdjustmentProposal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(auditRepository.save(any(BomPriceAdjustmentAudit.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.save(any(BomRecipeItem.class))).thenAnswer(inv -> inv.getArgument(0));
        // 红线 3 锁序「整族配方头 → 行」: 批准先只取行所属配方 id (标量) 定位要锁的族
        when(itemRepository.findRecipeIdById(any())).thenAnswer(inv ->
                Optional.ofNullable(recipeIdByItem.get(inv.<Long>getArgument(0))));
    }

    // ---------------------------------------------------------------- a) 生成端

    @Test
    @DisplayName("a) 收货后只为 ACTIVE / DRAFT 版本出建议, 归档与已删除版本不出; 建议价是档案移动均价 0.7501 而不是收货价 0.8")
    void generation_skipsArchivedAndDeletedVersions_andProposesTheMasterPrice() {
        BomRecipe active = recipe("R-SRC-V3", "PT-SRC", 3, BomRecipe.Status.ACTIVE);
        BomRecipe draft = recipe("R-NEW-V1", "PT-NEW", 1, BomRecipe.Status.DRAFT);
        BomRecipe archivedV1 = recipe("R-SRC-V1", "PT-SRC", 1, BomRecipe.Status.ARCHIVED);
        BomRecipe archivedRework = recipe("R-RW-V2", "PT-RW", 2, BomRecipe.Status.ARCHIVED);
        when(itemRepository.findByFactoryIdAndMaterialTypeId(F, MAT)).thenReturn(List.of(
                item(1609L, "R-SRC-V3", "0.5"),
                item(1700L, "R-NEW-V1", "0.5"),
                item(1573L, "R-SRC-V1", "0.5"),
                item(1581L, "R-RW-V2", "0.5"),
                item(1999L, "R-GONE", "0.5")));   // 配方已软删, findAllById 读不到
        when(recipeRepository.findAllById(any())).thenReturn(List.of(active, draft, archivedV1, archivedRework));
        when(materialRepository.findById(MAT)).thenReturn(Optional.of(material("0.7501")));

        List<BomPriceAdjustmentProposal> proposals = service.generateFromReceive(F, receive("0.8"));

        assertThat(proposals).extracting(BomPriceAdjustmentProposal::getRecipeItemId)
                .as("只有可改版本 (ACTIVE v3 / DRAFT) 出建议").containsExactly(1609L, 1700L);
        assertThat(proposals).extracting(BomPriceAdjustmentProposal::getRecipeId)
                .doesNotContain("R-SRC-V1", "R-RW-V2", "R-GONE");
        assertThat(proposals).allSatisfy(p -> {
            assertThat(p.getProposedUnitPrice()).as("建议价 = 批准时会写入的档案价").isEqualByComparingTo("0.7501");
            assertThat(p.getCurrentUnitPrice()).isEqualByComparingTo("0.5");
            assertThat(p.getAffectedProductCount()).as("受影响产品只数可改版本的产品").isEqualTo(2);
            assertThat(p.getStatus()).isEqualTo(Status.PENDING);
        });
        verify(itemRepository, never()).save(any(BomRecipeItem.class));
    }

    // ---------------------------------------------------------------- b) 批准端: 历史版本

    @Test
    @DisplayName("b) 已存在的归档版本建议批准 -> 409 RECIPE_NOT_EDITABLE, 配方行单价/行成本不变, 建议仍待审批, 不写审计")
    void approvingAnExistingProposalOnAnArchivedVersion_isRejectedAndChangesNothing() {
        BomRecipeItem archivedLine = item(1575L, "R-RW-V1", "0.5");
        archivedLine.setItemCost(archivedLine.computeItemCost());
        BigDecimal costBefore = archivedLine.getItemCost();
        BomPriceAdjustmentProposal proposal = pending(280L, archivedLine, "0.7501");
        when(proposalRepository.findByIdAndFactoryId(280L, F)).thenReturn(Optional.of(proposal));
        when(itemRepository.findByIdForUpdate(1575L)).thenReturn(Optional.of(archivedLine));
        when(recipeRepository.lockByIdAndFactoryId("R-RW-V1", F))
                .thenReturn(Optional.of(recipe("R-RW-V1", "PT-RW", 1, BomRecipe.Status.ARCHIVED)));
        when(materialRepository.findById(MAT)).thenReturn(Optional.of(material("0.7501")));

        BusinessException rejected = catchThrowableOfType(
                () -> service.approve(F, 280L, 9L, "ok"), BusinessException.class);

        assertThat(rejected).as("归档版本必须被拒").isNotNull();
        assertThat(rejected.getCode()).isEqualTo(409);
        assertThat(rejected.getErrorCode()).isEqualTo(BomPriceAdjustmentServiceImpl.ERROR_RECIPE_NOT_EDITABLE);
        assertThat(rejected.getMessage()).contains("已归档").contains("v1").contains("未做任何修改");
        assertThat(archivedLine.getUnitPrice()).isEqualByComparingTo("0.5");
        assertThat(archivedLine.getItemCost()).isEqualByComparingTo(costBefore);
        assertThat(proposal.getStatus()).isEqualTo(Status.PENDING);
        assertThat(proposal.getProposedUnitPrice()).isEqualByComparingTo("0.7501");
        verify(itemRepository, never()).save(any(BomRecipeItem.class));
        verify(auditRepository, never()).save(any(BomPriceAdjustmentAudit.class));
        verify(proposalRepository, never()).save(any(BomPriceAdjustmentProposal.class));
        verify(bomRecipeService, never()).recomputeCostsAfterItemPriceChange(any());
    }

    @Test
    @DisplayName("b') 配方版本已删除 (加锁读不到) 同样 409, 一行不改")
    void approvingAProposalWhoseVersionIsGone_isRejected() {
        BomRecipeItem line = item(1576L, "R-GONE", "0.5");
        BomPriceAdjustmentProposal proposal = pending(290L, line, "0.7501");
        when(proposalRepository.findByIdAndFactoryId(290L, F)).thenReturn(Optional.of(proposal));
        when(itemRepository.findByIdForUpdate(1576L)).thenReturn(Optional.of(line));
        when(recipeRepository.lockByIdAndFactoryId("R-GONE", F)).thenReturn(Optional.empty());
        when(materialRepository.findById(MAT)).thenReturn(Optional.of(material("0.7501")));

        BusinessException rejected = catchThrowableOfType(
                () -> service.approve(F, 290L, 9L, "ok"), BusinessException.class);

        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(409);
        assertThat(rejected.getErrorCode()).isEqualTo(BomPriceAdjustmentServiceImpl.ERROR_RECIPE_NOT_EDITABLE);
        assertThat(line.getUnitPrice()).isEqualByComparingTo("0.5");
        verify(itemRepository, never()).save(any(BomRecipeItem.class));
    }

    // ---------------------------------------------------------------- c) 批准端: 批准即所见

    @Test
    @DisplayName("c) ACTIVE 版本批准: 行单价 = 建议行 proposedUnitPrice, 行成本按它重算, 建议行的价不被改写")
    void approvingOnActiveVersion_appliesExactlyTheProposalPrice_andLeavesTheProposalUntouched() {
        BomRecipeItem line = item(1604L, "R-RW-V4", "0.5");
        line.setStandardQuantity(new BigDecimal("3"));
        BomPriceAdjustmentProposal proposal = pending(287L, line, "0.7501");
        when(proposalRepository.findByIdAndFactoryId(287L, F)).thenReturn(Optional.of(proposal));
        when(itemRepository.findByIdForUpdate(1604L)).thenReturn(Optional.of(line));
        BomRecipe activeV4 = recipe("R-RW-V4", "PT-RW", 4, BomRecipe.Status.ACTIVE);
        when(recipeRepository.lockByIdAndFactoryId("R-RW-V4", F)).thenReturn(Optional.of(activeV4));
        when(materialRepository.findById(MAT)).thenReturn(Optional.of(material("0.7501")));

        BomPriceAdjustmentProposal approved = service.approve(F, 287L, 9L, "ok", new BigDecimal("0.75010"));

        assertThat(approved.getStatus()).isEqualTo(Status.APPROVED);
        assertThat(line.getUnitPrice()).isEqualByComparingTo(proposal.getProposedUnitPrice());
        assertThat(line.getUnitPrice()).isEqualByComparingTo("0.7501");
        assertThat(line.getItemCost()).as("3 个 × 0.7501").isEqualByComparingTo("2.2503");
        assertThat(approved.getProposedUnitPrice()).as("建议行不被改写").isEqualByComparingTo("0.7501");
        assertThat(approved.getCurrentUnitPrice()).isEqualByComparingTo("0.5");
        // 审查 D: 行写完之后, 同一事务里用配方服务那套重算刷新配方头成本
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(itemRepository, bomRecipeService);
        order.verify(itemRepository).save(line);
        order.verify(bomRecipeService).recomputeCostsAfterItemPriceChange(activeV4);
    }

    @Test
    @DisplayName("A) 配方头先加锁读, 再做工艺保密检查 (保密检查会无锁 findById 配方, 先读进来的托管对象 FOR UPDATE 不刷新)")
    void approval_locksTheRecipeBeforeTheProcessSecretCheckReadsIt() {
        BomRecipeItem line = item(1604L, "R-RW-V4", "0.5");
        BomPriceAdjustmentProposal proposal = pending(287L, line, "0.7501");
        when(proposalRepository.findByIdAndFactoryId(287L, F)).thenReturn(Optional.of(proposal));
        when(itemRepository.findByIdForUpdate(1604L)).thenReturn(Optional.of(line));
        when(recipeRepository.lockByIdAndFactoryId("R-RW-V4", F))
                .thenReturn(Optional.of(recipe("R-RW-V4", "PT-RW", 4, BomRecipe.Status.ACTIVE)));
        when(materialRepository.findById(MAT)).thenReturn(Optional.of(material("0.7501")));
        com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard guard =
                org.mockito.Mockito.mock(com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "processSecretGuard", guard);

        service.approve(F, 287L, 9L, "ok");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(materialRepository, proposalRepository,
                itemRepository, recipeRepository, guard);
        order.verify(materialRepository).lockByFactoryIdAndIdIn(F, List.of(MAT));
        order.verify(proposalRepository).lockByIdAndFactoryId(287L, F);
        // 红线 3: 先头后行 (与激活 / 整表更新 / 草稿明细编辑同一方向), 两者都在保密检查之前
        order.verify(recipeRepository).lockByIdAndFactoryId("R-RW-V4", F);
        order.verify(itemRepository).findByIdForUpdate(1604L);
        order.verify(guard).requireRecipe(org.mockito.ArgumentMatchers.eq(F), org.mockito.ArgumentMatchers.eq("R-RW-V4"),
                any(), any());
    }

    @Test
    @DisplayName("C) 审批人传了他看到的建议价, 而建议已被新收货改成别的价 -> 409 STALE, 一行不改")
    void approvalWithAnExpectedPriceThatNoLongerMatchesTheProposal_isRejected() {
        BomRecipeItem line = item(1604L, "R-RW-V4", "0.5");
        BomPriceAdjustmentProposal proposal = pending(287L, line, "0.7501");
        when(proposalRepository.findByIdAndFactoryId(287L, F)).thenReturn(Optional.of(proposal));
        when(itemRepository.findByIdForUpdate(1604L)).thenReturn(Optional.of(line));
        when(recipeRepository.lockByIdAndFactoryId("R-RW-V4", F))
                .thenReturn(Optional.of(recipe("R-RW-V4", "PT-RW", 4, BomRecipe.Status.ACTIVE)));
        when(materialRepository.findById(MAT)).thenReturn(Optional.of(material("0.7501")));

        BusinessException rejected = catchThrowableOfType(
                () -> service.approve(F, 287L, 9L, "ok", new BigDecimal("0.7")), BusinessException.class);

        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(409);
        assertThat(rejected.getErrorCode()).isEqualTo(BomPriceAdjustmentServiceImpl.ERROR_STALE);
        assertThat(line.getUnitPrice()).isEqualByComparingTo("0.5");
        assertThat(proposal.getStatus()).isEqualTo(Status.PENDING);
        verify(itemRepository, never()).save(any(BomRecipeItem.class));
        verify(bomRecipeService, never()).recomputeCostsAfterItemPriceChange(any());
    }

    @Test
    @DisplayName("配方行已换成别的物料 -> 409 STALE (建议说的不再是这一行)")
    void approvalWhenTheLineNoLongerUsesTheProposalMaterial_isRejected() {
        BomRecipeItem line = item(1604L, "R-RW-V4", "0.5");
        BomPriceAdjustmentProposal proposal = pending(287L, line, "0.7501");
        line.setMaterialTypeId("RMT-OTHER");
        when(proposalRepository.findByIdAndFactoryId(287L, F)).thenReturn(Optional.of(proposal));
        when(itemRepository.findByIdForUpdate(1604L)).thenReturn(Optional.of(line));
        when(recipeRepository.lockByIdAndFactoryId("R-RW-V4", F))
                .thenReturn(Optional.of(recipe("R-RW-V4", "PT-RW", 4, BomRecipe.Status.ACTIVE)));
        when(materialRepository.findById(MAT)).thenReturn(Optional.of(material("0.7501")));

        BusinessException rejected = catchThrowableOfType(
                () -> service.approve(F, 287L, 9L, "ok"), BusinessException.class);

        assertThat(rejected).isNotNull();
        assertThat(rejected.getErrorCode()).isEqualTo(BomPriceAdjustmentServiceImpl.ERROR_STALE);
        verify(itemRepository, never()).save(any(BomRecipeItem.class));
    }

    @Test
    @DisplayName("c') 线上原形: 建议写着 0.8, 批准时档案均价 0.7501 -> 409 STALE, ⛔ 不按均价写入, 建议行仍是 0.8")
    void approvingWhenTheMasterPriceMovedAwayFromTheProposal_isRejectedNotSilentlyReplaced() {
        BomRecipeItem line = item(1609L, "R-SRC-V3", "0.5");
        line.setItemCost(line.computeItemCost());
        BomPriceAdjustmentProposal proposal = pending(286L, line, "0.8");
        when(proposalRepository.findByIdAndFactoryId(286L, F)).thenReturn(Optional.of(proposal));
        when(itemRepository.findByIdForUpdate(1609L)).thenReturn(Optional.of(line));
        when(recipeRepository.lockByIdAndFactoryId("R-SRC-V3", F))
                .thenReturn(Optional.of(recipe("R-SRC-V3", "PT-SRC", 3, BomRecipe.Status.ACTIVE)));
        when(materialRepository.findById(MAT)).thenReturn(Optional.of(material("0.7501")));

        BusinessException rejected = catchThrowableOfType(
                () -> service.approve(F, 286L, 9L, "ok"), BusinessException.class);

        assertThat(rejected).as("建议价与档案价不一致必须拒绝, 不能静默换价").isNotNull();
        assertThat(rejected.getCode()).isEqualTo(409);
        assertThat(rejected.getErrorCode()).isEqualTo(BomPriceAdjustmentServiceImpl.ERROR_STALE);
        assertThat(rejected.getMessage()).contains("过期").doesNotContain("0.7501").doesNotContain("0.8");
        assertThat(line.getUnitPrice()).isEqualByComparingTo("0.5");
        assertThat(line.getItemCost()).isEqualByComparingTo("0.5");
        assertThat(proposal.getProposedUnitPrice()).isEqualByComparingTo("0.8");
        assertThat(proposal.getStatus()).isEqualTo(Status.PENDING);
        verify(itemRepository, never()).save(any(BomRecipeItem.class));
        verify(auditRepository, never()).save(any(BomPriceAdjustmentAudit.class));
        verify(bomRecipeService, never()).recomputeCostsAfterItemPriceChange(any());
    }

    // ---------------------------------------------------------------- fixtures

    private PurchaseReceiveRecord receive(String unitPrice) {
        PurchaseReceiveRecord record = new PurchaseReceiveRecord();
        record.setId("RCV-S5-04");
        record.setFactoryId(F);
        PurchaseReceiveItem line = new PurchaseReceiveItem();
        line.setId(501L);
        line.setMaterialTypeId(MAT);
        line.setMaterialName("QA-S3-1104-包材");
        line.setUnitPrice(new BigDecimal(unitPrice));
        record.setItems(List.of(line));
        return record;
    }

    private BomPriceAdjustmentProposal pending(Long id, BomRecipeItem line, String proposed) {
        BomPriceAdjustmentProposal p = new BomPriceAdjustmentProposal();
        p.setId(id);
        p.setFactoryId(F);
        p.setRecipeId(line.getRecipeId());
        p.setRecipeItemId(line.getId());
        p.setMaterialTypeId(MAT);
        p.setCurrentUnitPrice(new BigDecimal("0.5"));
        p.setProposedUnitPrice(new BigDecimal(proposed));
        p.setDeltaAmount(new BigDecimal(proposed).subtract(new BigDecimal("0.5")));
        p.setStatus(Status.PENDING);
        p.setSourceType(SourceType.PURCHASE_RECEIVE);
        return p;
    }

    private BomRecipeItem item(Long id, String recipeId, String unitPrice) {
        BomRecipeItem item = new BomRecipeItem();
        item.setId(id);
        item.setFactoryId(F);
        item.setRecipeId(recipeId);
        item.setMaterialTypeId(MAT);
        item.setMaterialName("QA-S3-1104-包材");
        item.setUnit("个");
        item.setStandardQuantity(BigDecimal.ONE);
        item.setYieldRate(new BigDecimal("100.00"));
        item.setUnitPrice(new BigDecimal(unitPrice));
        recipeIdByItem.put(id, recipeId);
        return item;
    }

    private BomRecipe recipe(String id, String productTypeId, int version, BomRecipe.Status status) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(id);
        recipe.setFactoryId(F);
        recipe.setRecipeCode("BOM-" + id);
        recipe.setProductTypeId(productTypeId);
        recipe.setProductName(productTypeId);
        recipe.setVersion(version);
        recipe.setStatus(status);
        recipe.setIsCurrent(status == BomRecipe.Status.ACTIVE);
        return recipe;
    }

    private RawMaterialType material(String movingAvgPrice) {
        RawMaterialType material = new RawMaterialType();
        material.setId(MAT);
        material.setFactoryId(F);
        material.setMovingAvgPrice(new BigDecimal(movingAvgPrice));
        material.setUnitPrice(new BigDecimal("0.5"));
        return material;
    }
}
