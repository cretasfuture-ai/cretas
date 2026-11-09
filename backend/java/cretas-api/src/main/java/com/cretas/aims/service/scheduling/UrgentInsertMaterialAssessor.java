package com.cretas.aims.service.scheduling;

import com.cretas.aims.dto.orchestration.MaterialAllocation;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.shortage.ShortageSupplyQueryRepository;
import com.cretas.aims.service.orchestration.BomExpansionService;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.cretas.aims.service.unit.UnitContractService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 紧急插单的原料可行性评估 (缺口 17)。
 *
 * <p>按「当前生效配方」展开原料需求 (配方 id + 版本随结果返回, 确认建计划时锁定到计划上),
 * 对照车间/物流/原料仓可领库存, 不够时逐料给出「缺什么、缺多少」, 并把已下单未到的在途量
 * 单独列出。<b>在途不计入可用</b>: 货没到不能开工, 它只用来告诉人「缺口有没有已经在补」。
 *
 * <p>结果带一个指纹 (配方 id/版本 + 每种料的需求与缺口 + 总判定)。预览时存下, 确认时重算比对,
 * 不一致 = 数据在预览之后变了 ⇒ 拒绝并要求重新预览。
 *
 * <p>没有真实数据可核对 (没选产品/没数量/无生效配方/单位不可换算/数据源异常) 一律 UNVERIFIED,
 * ⛔ 不当成够。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrgentInsertMaterialAssessor {

    public enum Status { OK, SHORT, UNVERIFIED }

    /** 一种原料的核对行; 无完整查看权时数量为 null (只说哪些料不足)。 */
    public record Line(String materialTypeId, String materialName, String unit,
                       BigDecimal required, BigDecimal available, BigDecimal shortfall,
                       BigDecimal onOrder, int onOrderUnitMismatchLines, BigDecimal gapAfterOnOrder) {
    }

    public record Assessment(Status status, String message, String bomRecipeId, Integer bomVersion,
                             List<Line> lines, String fingerprint) {

        public boolean ok() {
            return status == Status.OK;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("status", status.name());
            m.put("message", message);
            m.put("bomRecipeId", bomRecipeId);
            m.put("bomVersion", bomVersion);
            m.put("fingerprint", fingerprint);
            m.put("shortLines", lines.stream().map(l -> {
                Map<String, Object> row = new HashMap<>();
                row.put("materialTypeId", l.materialTypeId());
                row.put("materialName", l.materialName());
                row.put("unit", l.unit());
                row.put("required", l.required());
                row.put("available", l.available());
                row.put("shortfall", l.shortfall());
                row.put("onOrder", l.onOrder());
                row.put("onOrderUnitMismatchLines", l.onOrderUnitMismatchLines());
                row.put("gapAfterOnOrder", l.gapAfterOnOrder());
                return row;
            }).collect(Collectors.toList()));
            return m;
        }
    }

    private final BomExpansionService bomExpansionService;
    private final BomRecipeRepository bomRecipeRepository;
    private final ShortageSupplyQueryRepository supplyQueries;
    private final UnitContractService unitContractService;
    private final ProcessSecretRecipeGuard processSecretGuard;
    private final PlatformTransactionManager transactionManager;

    public Assessment assess(String factoryId, String productTypeId, BigDecimal quantity) {
        if (productTypeId == null || productTypeId.isBlank()) {
            return unverified("原料未验证: 未指定产品, 无法按配方核对原料");
        }
        if (quantity == null || quantity.signum() <= 0) {
            return unverified("原料未验证: 未指定插单数量, 无法核对原料");
        }
        try {
            return assessIsolated(factoryId, productTypeId, quantity);
        } catch (BusinessException e) {
            log.warn("插单原料无法核对: factoryId={}, productTypeId={}, reason={}", factoryId, productTypeId, e.getMessage());
            return unverified("原料未验证: " + e.getMessage());
        } catch (Exception e) {
            log.error("插单原料核对失败: factoryId={}, productTypeId={}", factoryId, productTypeId, e);
            return unverified("原料未验证: 库存数据读取失败");
        }
    }

    /** 配方展开 + 库存读取放进独立只读子事务: 无生效配方时的异常不能把外层事务标成 rollback-only。 */
    private Assessment assessIsolated(String factoryId, String productTypeId, BigDecimal quantity) {
        TransactionTemplate isolated = new TransactionTemplate(transactionManager);
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        isolated.setReadOnly(true);
        isolated.setName("UrgentInsertMaterialAssessor.assess");
        return isolated.execute(status -> compute(factoryId, productTypeId, quantity));
    }

    private Assessment compute(String factoryId, String productTypeId, BigDecimal quantity) {
        BomRecipe recipe = bomRecipeRepository
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                        factoryId, productTypeId, BomRecipe.Status.ACTIVE)
                .orElse(null);
        if (recipe == null) {
            return unverified("原料未验证: 该产品没有生效的 BOM 配方版本, 无法核对原料");
        }
        List<MaterialRequirement> requirements = bomExpansionService.expandBOM(factoryId, productTypeId, quantity);
        if (requirements == null || requirements.isEmpty()) {
            return unverified("原料未验证: 该产品配方没有可核对的原料投入行");
        }
        MaterialCheckResult check = bomExpansionService.checkPickableAvailability(factoryId, requirements);
        if (check == null) {
            return unverified("原料未验证: 原料核对没有返回结果");
        }
        boolean reveal = processSecretGuard == null || processSecretGuard.permitsProduct(factoryId, productTypeId,
                ProcessSecretAction.VIEW_FULL, "urgent-insert material assessment");

        List<MaterialShortfall> shortfalls = check.getShortfalls() == null
                ? Collections.emptyList() : check.getShortfalls();
        Map<String, BigDecimal> allocatedByMaterial = new HashMap<>();
        if (check.getAllocations() != null) {
            for (MaterialAllocation a : check.getAllocations()) {
                if (a.getMaterialTypeId() != null && a.getAllocatedQuantity() != null) {
                    allocatedByMaterial.merge(a.getMaterialTypeId(), a.getAllocatedQuantity(), BigDecimal::add);
                }
            }
        }

        List<Line> shortLines = new ArrayList<>();
        for (MaterialShortfall sf : shortfalls) {
            String unit = sf.getQuantityUnit();
            BigDecimal onOrder = BigDecimal.ZERO;
            int mismatch = 0;
            for (Object[] row : supplyQueries.findOpenOrderRemaining(factoryId, sf.getMaterialTypeId())) {
                String rowUnit = row[0] == null ? null : row[0].toString();
                BigDecimal remaining = row[1] instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(row[1]));
                if (unit != null && rowUnit != null && unitContractService.areEquivalent(factoryId, unit, rowUnit)) {
                    onOrder = onOrder.add(remaining);
                } else {
                    mismatch++; // 不换算: 单位不同的采购行只计行数, 不计量
                }
            }
            BigDecimal shortfall = nz(sf.getShortfallQuantity());
            String name = sf.getMaterialTypeName() != null ? sf.getMaterialTypeName() : sf.getMaterialTypeId();
            shortLines.add(new Line(sf.getMaterialTypeId(), name, unit,
                    reveal ? sf.getRequiredQuantity() : null,
                    reveal ? sf.getAvailableQuantity() : null,
                    reveal ? shortfall : null,
                    reveal ? onOrder : null,
                    mismatch,
                    reveal ? shortfall.subtract(onOrder).max(BigDecimal.ZERO) : null));
        }

        // 指纹用真实数值 (不受保密遮挡影响): 配方版本 + 每种料需求/缺口 + 判定
        StringBuilder canon = new StringBuilder()
                .append(recipe.getId()).append('|').append(recipe.getVersion()).append('|')
                .append(shortfalls.isEmpty() ? "OK" : "SHORT").append('|');
        requirements.stream()
                .sorted((x, y) -> String.valueOf(x.getMaterialTypeId()).compareTo(String.valueOf(y.getMaterialTypeId())))
                .forEach(r -> canon.append(r.getMaterialTypeId()).append('=')
                        .append(plain(r.getRequiredQuantity())).append(':')
                        .append(plain(allocatedByMaterial.get(r.getMaterialTypeId()))).append(';'));
        shortfalls.stream()
                .sorted((x, y) -> String.valueOf(x.getMaterialTypeId()).compareTo(String.valueOf(y.getMaterialTypeId())))
                .forEach(s -> canon.append("S:").append(s.getMaterialTypeId()).append('=')
                        .append(plain(s.getRequiredQuantity())).append('/')
                        .append(plain(s.getAvailableQuantity())).append(';'));
        String fingerprint = sha256(canon.toString());

        if (shortLines.isEmpty()) {
            return new Assessment(Status.OK, null, recipe.getId(), recipe.getVersion(), List.of(), fingerprint);
        }
        String detail = shortLines.stream().map(l -> describe(l, reveal)).collect(Collectors.joining("; "));
        return new Assessment(Status.SHORT, detail, recipe.getId(), recipe.getVersion(), shortLines, fingerprint);
    }

    private static String describe(Line l, boolean reveal) {
        if (!reveal) {
            return l.materialName() + " 不足";
        }
        String unit = l.unit() == null ? "" : l.unit();
        StringBuilder sb = new StringBuilder()
                .append(l.materialName()).append(" 缺 ").append(plain(l.shortfall())).append(unit)
                .append("(需要 ").append(plain(l.required())).append(unit)
                .append(", 可领库存 ").append(plain(l.available())).append(unit).append(")");
        if (l.onOrder() != null && l.onOrder().signum() > 0) {
            sb.append(", 在途未到 ").append(plain(l.onOrder())).append(unit).append(", 到货前不能插");
        } else {
            sb.append(", 没有在途采购");
        }
        if (l.onOrderUnitMismatchLines() > 0) {
            sb.append(", 另有 ").append(l.onOrderUnitMismatchLines()).append(" 行在途采购单位不同未计入");
        }
        return sb.toString();
    }

    private static Assessment unverified(String message) {
        return new Assessment(Status.UNVERIFIED, message, null, null, List.of(), null);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
