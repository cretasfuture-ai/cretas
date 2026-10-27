package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.BomYieldApplyRequest;
import com.cretas.aims.dto.bom.BomYieldApplyResultDTO;
import com.cretas.aims.dto.bom.BomYieldEstimateDTO;
import com.cretas.aims.dto.bom.BomYieldPreviewItemDTO;
import com.cretas.aims.dto.bom.BomYieldStaleRowDTO;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.bom.BomChangeLog;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.exception.BomYieldStaleException;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.bom.BomChangeLogRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.bom.BomYieldEstimateService;
import com.cretas.aims.service.yield.YieldReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Estimates the product-level yield stored on the current ACTIVE {@link BomRecipe}.
 * Recipe items identify inputs only; they do not own the product yield.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BomYieldEstimateServiceImpl implements BomYieldEstimateService {

    static final int MIN_SAMPLES = 3;

    private final ProductionBatchRepository productionBatchRepository;
    private final ProductTypeRepository productTypeRepository;
    private final BomRecipeRepository bomRecipeRepository;
    private final BomRecipeItemRepository bomRecipeItemRepository;
    private final BomChangeLogRepository bomChangeLogRepository;
    private final YieldReportService yieldReportService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard processSecretGuard;

    @Override
    public BomYieldEstimateDTO estimateForProduct(
            String factoryId, String productTypeId, String materialCategory) {
        Optional<ProductType> product = productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId);
        BigDecimal suggestedStandardQuantity = product.map(ProductType::getGramsPerUnit).orElse(null);
        List<BigDecimal> samples = collectYieldSamples(factoryId, productTypeId);

        if (samples.size() < MIN_SAMPLES) {
            boolean hasStandardQuantity = suggestedStandardQuantity != null;
            return BomYieldEstimateDTO.builder()
                    .productTypeId(productTypeId)
                    .materialCategory(materialCategory)
                    .suggestedStandardQuantity(suggestedStandardQuantity)
                    .suggestedYieldRate(null)
                    .sampleCount(samples.size())
                    .yieldMin(null)
                    .yieldMax(null)
                    .source(hasStandardQuantity ? "STANDARD_WEIGHT_ONLY" : "NONE")
                    .reason(hasStandardQuantity ? "INSUFFICIENT_SAMPLES" : "NO_GRAMS_PER_UNIT")
                    .actionHint(hasStandardQuantity ? null : "请先在产品档案中填写标准克重")
                    .build();
        }

        BigDecimal yieldMin = samples.stream().min(BigDecimal::compareTo)
                .map(this::toPercentage).orElse(null);
        BigDecimal yieldMax = samples.stream().max(BigDecimal::compareTo)
                .map(this::toPercentage).orElse(null);
        return BomYieldEstimateDTO.builder()
                .productTypeId(productTypeId)
                .materialCategory(materialCategory)
                .suggestedStandardQuantity(suggestedStandardQuantity)
                .suggestedYieldRate(computeMedian(samples))
                .sampleCount(samples.size())
                .yieldMin(yieldMin)
                .yieldMax(yieldMax)
                .source("BATCH_REPORTING")
                .reason(suggestedStandardQuantity == null ? "NO_GRAMS_PER_UNIT" : null)
                .actionHint(suggestedStandardQuantity == null ? "请先在产品档案中填写标准克重" : null)
                .build();
    }

    @Override
    public List<BomYieldPreviewItemDTO> recalculatePreview(
            String factoryId, List<String> productTypeIds) {
        List<String> targetIds = resolveTargetProductIds(factoryId, productTypeIds);
        if (targetIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<BomYieldPreviewItemDTO> result = new ArrayList<>();
        for (String productTypeId : targetIds) {
            Optional<BomRecipe> recipeOptional = findActiveRecipe(factoryId, productTypeId);
            if (recipeOptional.isEmpty()) {
                continue;
            }
            BomRecipe recipe = recipeOptional.get();
            List<BomRecipeItem> items = bomRecipeItemRepository.findCurrentByProduct(factoryId, productTypeId);
            if (items.isEmpty()) {
                continue;
            }
            BomRecipeItem displayItem = items.stream()
                    .filter(item -> "RAW".equalsIgnoreCase(item.getMaterialCategory()))
                    .findFirst()
                    .orElse(items.get(0));
            List<BigDecimal> samples = collectYieldSamples(factoryId, productTypeId);
            BigDecimal suggested = samples.size() >= MIN_SAMPLES ? computeMedian(samples) : null;
            BigDecimal current = recipe.getOverallYieldRate();
            String status = suggested == null
                    ? "INSUFFICIENT_SAMPLES"
                    : ((current == null || current.compareTo(suggested) != 0) ? "UPDATABLE" : "SKIP");

            result.add(BomYieldPreviewItemDTO.builder()
                    .productTypeId(productTypeId)
                    .productName(recipe.getProductName())
                    .recipeId(recipe.getId())
                    .materialName(displayItem.getMaterialName())
                    .currentYieldRate(current)
                    .suggestedYieldRate(suggested)
                    .sampleCount(samples.size())
                    .status(status)
                    .build());
        }
        return result;
    }

    @Override
    @Transactional
    public BomYieldApplyResultDTO recalculateApply(
            String factoryId, List<BomYieldApplyRequest> requests) {
        for (BomYieldApplyRequest request : requests) {
            assertYieldRateInRange(request);
        }
        // 工艺保密 (写入侧): 点名的配方里任一属于调用者无编辑权的保密产品, 整批 403、一条都不写。
        // ⛔ 必须在下面的「过期校验」之前: 过期 409 会把库里的标准出成率 (dbCurrent) 原样带回去 (独立审查 V2.1 H1)
        if (processSecretGuard != null) {
            List<BomRecipe> targets = new ArrayList<>();
            for (BomYieldApplyRequest request : requests) {
                findOwnedActiveRecipe(factoryId, request.getRecipeId()).ifPresent(targets::add);
            }
            processSecretGuard.requireRecipes(factoryId, targets,
                    com.cretas.aims.entity.processsecret.ProcessSecretAction.EDIT, "bom yield recalculate-apply");
        }
        List<BomYieldStaleRowDTO> staleRows = new ArrayList<>();
        for (BomYieldApplyRequest request : requests) {
            if (request.getExpectedCurrentYieldRate() == null) {
                continue;
            }
            Optional<BomRecipe> recipeOptional = findOwnedActiveRecipe(factoryId, request.getRecipeId());
            if (recipeOptional.isEmpty()) {
                continue;
            }
            BigDecimal current = recipeOptional.get().getOverallYieldRate();
            BigDecimal expected = request.getExpectedCurrentYieldRate();
            if (current == null || current.compareTo(expected) != 0) {
                staleRows.add(BomYieldStaleRowDTO.builder()
                        .recipeId(request.getRecipeId())
                        .dbCurrent(current)
                        .expected(expected)
                        .build());
            }
        }
        if (!staleRows.isEmpty()) {
            throw new BomYieldStaleException(staleRows,
                    "部分配方的出成率已发生变化，请重新预览后再应用。共 " + staleRows.size() + " 条数据过期。");
        }

        Long actingUserId = readActingUserId();
        String actingUsername = readRequestAttribute("username");
        int applied = 0;
        List<String> changeLogIds = new ArrayList<>();
        for (BomYieldApplyRequest request : requests) {
            Optional<BomRecipe> recipeOptional = findOwnedActiveRecipe(factoryId, request.getRecipeId());
            if (recipeOptional.isEmpty()) {
                log.warn("[BomYieldApply] current ACTIVE recipe not found: factoryId={}, recipeId={}",
                        factoryId, request.getRecipeId());
                continue;
            }
            BomRecipe recipe = recipeOptional.get();
            Map<String, Object> oldSnapshot = snapshotRecipe(recipe);
            BigDecimal oldYieldRate = recipe.getOverallYieldRate();
            BigDecimal newYieldRate = request.getYieldRate().setScale(2, RoundingMode.HALF_UP);
            recipe.setOverallYieldRate(newYieldRate);
            bomRecipeRepository.save(recipe);

            BomChangeLog changeLog = new BomChangeLog();
            changeLog.setFactoryId(factoryId);
            changeLog.setBomRecipeId(recipe.getId());
            changeLog.setBomRecipeItemId(null);
            changeLog.setChangeType(BomChangeLog.ChangeType.UPDATE);
            changeLog.setOldValue(oldSnapshot);
            changeLog.setNewValue(snapshotRecipe(recipe));
            // 🔴 2026-09-06 订正: 这条审计理由原来写「系统根据正式批次建议更新」—— #594 删掉自动
            //    apply 之后, 走到这里的**每一条**都是人按的(权限 YIELD_STANDARD_WRITE_PERMISSION,
            //    操作人记在 changedBy/changedByName)。说成「系统更新」等于把责任人写错。
            changeLog.setChangeReason("人工设置产品标准出成率：" + oldYieldRate + " → " + newYieldRate);
            changeLog.setChangedBy(actingUserId);
            changeLog.setChangedByName(actingUsername);
            changeLogIds.add(bomChangeLogRepository.save(changeLog).getId());
            applied++;
        }

        return BomYieldApplyResultDTO.builder()
                .applied(applied)
                .changeLogIds(changeLogIds)
                .build();
    }

    /** 标准出成率合法区间的下界(不含) —— 与 {@code BomYieldApplyRequest} / DB CHECK 同一条规则。 */
    static final BigDecimal YIELD_RATE_MIN_EXCLUSIVE = BigDecimal.ZERO;
    /** 标准出成率防手滑上限(含)。⚠️ 改这个数要同步改 DTO 注解、DB {@code chk_br_yield}、前端输入校验。 */
    static final BigDecimal YIELD_RATE_MAX_INCLUSIVE = new BigDecimal("300");

    /**
     * 🔴 2026-09-06 (RECON-LEGAL-VALUE-SENTINEL-20260906): 显式区间守卫。
     *
     * <p>为什么服务层还要再判一次 —— DTO 上的 {@code @DecimalMin/@DecimalMax} 只在
     * 带 {@code @Valid} 的 controller 入口生效; 这个方法是 {@code BomYieldEstimateService}
     * 的公开契约, 任何内部调用方绕过 controller 就没有校验。而下游是
     * PostgreSQL 的 {@code chk_br_yield}, 越界会变成 500 + 事务回滚
     * (H2 测试 profile 关了 Flyway, 看不见那条 CHECK ⇒ 单测全绿也不能证明能过 prod)。
     *
     * <p>⛔ {@code 100.00} <b>不</b>在拒绝之列 —— 它是合法的零损耗值。
     */
    private void assertYieldRateInRange(BomYieldApplyRequest request) {
        BigDecimal rate = request == null ? null : request.getYieldRate();
        if (rate == null) {
            throw new BusinessException(400, "标准出成率不能为空")
                    .withCode("BOM_YIELD_RATE_REQUIRED")
                    .withHint("请填写一个大于 0、不超过 " + YIELD_RATE_MAX_INCLUSIVE + "% 的数字")
                    .withHintTarget("yieldRate")
                    .withSeverity("BLOCKING");
        }
        if (rate.compareTo(YIELD_RATE_MIN_EXCLUSIVE) <= 0
                || rate.compareTo(YIELD_RATE_MAX_INCLUSIVE) > 0) {
            throw new BusinessException(400,
                    "标准出成率必须大于 0 且不超过 " + YIELD_RATE_MAX_INCLUSIVE + "%，收到 " + rate.toPlainString() + "%")
                    .withCode("BOM_YIELD_RATE_OUT_OF_RANGE")
                    .withHint("100% 表示零损耗（合法）；保水/腌制工序可以超过 100%，"
                            + "但超过 " + YIELD_RATE_MAX_INCLUSIVE + "% 通常是小数点点错了，请核对后重填")
                    .withHintTarget("yieldRate")
                    .withSeverity("BLOCKING");
        }
    }

    List<BigDecimal> collectYieldSamples(String factoryId, String productTypeId) {
        List<ProductionBatch> batches = productionBatchRepository
                .findRecentCompletedByFactoryAndProductType(factoryId, productTypeId, Pageable.unpaged());
        List<BigDecimal> samples = new ArrayList<>();
        for (ProductionBatch batch : batches) {
            try {
                BatchYieldDTO yield = yieldReportService.getYield(factoryId, batch.getId());
                if (yield != null && yield.getCumulativeYieldRate() != null) {
                    samples.add(yield.getCumulativeYieldRate());
                }
            } catch (Exception exception) {
                log.debug("[BomYieldEstimate] batch yield unavailable: batchId={}, error={}",
                        batch.getId(), exception.getMessage());
            }
        }
        return samples;
    }

    BigDecimal computeMedian(List<BigDecimal> samples) {
        List<BigDecimal> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int size = sorted.size();
        BigDecimal median = size % 2 == 1
                ? sorted.get(size / 2)
                : sorted.get(size / 2 - 1).add(sorted.get(size / 2))
                        .divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
        return toPercentage(median);
    }

    private BigDecimal toPercentage(BigDecimal decimalRate) {
        return decimalRate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    private List<String> resolveTargetProductIds(String factoryId, List<String> productTypeIds) {
        return productTypeIds != null && !productTypeIds.isEmpty()
                ? productTypeIds
                : bomRecipeItemRepository.findDistinctCurrentProductTypeIds(factoryId);
    }

    private Optional<BomRecipe> findActiveRecipe(String factoryId, String productTypeId) {
        return bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                factoryId, productTypeId, BomRecipe.Status.ACTIVE);
    }

    private Optional<BomRecipe> findOwnedActiveRecipe(String factoryId, String recipeId) {
        return bomRecipeRepository.findById(recipeId)
                .filter(recipe -> factoryId.equals(recipe.getFactoryId()))
                .filter(recipe -> Boolean.TRUE.equals(recipe.getIsCurrent()))
                .filter(recipe -> recipe.getStatus() == BomRecipe.Status.ACTIVE);
    }

    private Map<String, Object> snapshotRecipe(BomRecipe recipe) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", recipe.getId());
        snapshot.put("recipeCode", recipe.getRecipeCode());
        snapshot.put("productTypeId", recipe.getProductTypeId());
        snapshot.put("productName", recipe.getProductName());
        snapshot.put("version", recipe.getVersion());
        snapshot.put("overallYieldRate", recipe.getOverallYieldRate());
        snapshot.put("status", recipe.getStatus());
        snapshot.put("isCurrent", recipe.getIsCurrent());
        return snapshot;
    }

    private Long readActingUserId() {
        String raw = readRequestAttribute("userId");
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String readRequestAttribute(String name) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object value = attributes.getAttribute(name, RequestAttributes.SCOPE_REQUEST);
        return value == null ? null : value.toString();
    }
}
