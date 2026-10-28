package com.cretas.aims.service.purchase.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.dto.purchase.AddSupplierPriceRequest;
import com.cretas.aims.dto.purchase.CreateInquiryQuoteRequest;
import com.cretas.aims.dto.purchase.InquiryQuoteComparisonDTO;
import com.cretas.aims.dto.purchase.InquiryQuotePriceImpactDTO;
import com.cretas.aims.dto.purchase.SelectAndConvertRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.SupplierMaterialPurchaseSpec;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.enums.TaxTreatment;
import com.cretas.aims.entity.enums.InquiryQuoteStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.PurchaseType;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.purchase.InquiryQuote;
import com.cretas.aims.entity.purchase.InquiryQuoteSupplierPrice;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.purchase.InquiryQuoteRepository;
import com.cretas.aims.repository.purchase.InquiryQuoteSupplierPriceRepository;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.cretas.aims.service.purchase.InquiryQuoteComparator;
import com.cretas.aims.service.shared.CostRollupUtil;
import com.cretas.aims.service.purchase.InquiryQuoteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 核价单服务实现 (P-NUCLEAR-1, 28-Backlog #30).
 *
 * <p>实现 询价 → 核价 → 采购 三阶段 pipeline. 满足防呆 R2 (response 含 context)
 * + R4 (selectAndConvert 幂等).
 *
 * @author Cretas Team
 * @since 2026-05-17
 */
@Slf4j
@Service
public class InquiryQuoteServiceImpl implements InquiryQuoteService {

    private final InquiryQuoteRepository inquiryQuoteRepository;
    private final InquiryQuoteSupplierPriceRepository supplierPriceRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseService purchaseService;
    private final RawMaterialTypeRepository materialTypeRepository;
    private final SupplierMaterialRepository supplierMaterialRepository;
    private final SupplierMaterialPurchaseSpecRepository purchaseSpecRepository;
    private final BomRecipeItemRepository bomRecipeItemRepository;
    private final BomRecipeRepository bomRecipeRepository;

    /** 工艺保密: BOM 影响估算只列当前用户有权看的配方。 */
    @Autowired(required = false)
    private ProcessSecretRecipeGuard processSecretGuard;

    /** 进行中的核价单状态 (同一草稿行同时只允许一张)。 */
    private static final EnumSet<InquiryQuoteStatus> OPEN_STATUSES = EnumSet.of(
            InquiryQuoteStatus.DRAFT, InquiryQuoteStatus.INQUIRING,
            InquiryQuoteStatus.QUOTED, InquiryQuoteStatus.SELECTED);

    @Autowired
    public InquiryQuoteServiceImpl(InquiryQuoteRepository inquiryQuoteRepository,
                                   InquiryQuoteSupplierPriceRepository supplierPriceRepository,
                                   SupplierRepository supplierRepository,
                                   PurchaseOrderRepository purchaseOrderRepository,
                                   PurchaseService purchaseService,
                                   RawMaterialTypeRepository materialTypeRepository,
                                   SupplierMaterialRepository supplierMaterialRepository,
                                   SupplierMaterialPurchaseSpecRepository purchaseSpecRepository,
                                   BomRecipeItemRepository bomRecipeItemRepository,
                                   BomRecipeRepository bomRecipeRepository) {
        this.inquiryQuoteRepository = inquiryQuoteRepository;
        this.supplierPriceRepository = supplierPriceRepository;
        this.supplierRepository = supplierRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseService = purchaseService;
        this.materialTypeRepository = materialTypeRepository;
        this.supplierMaterialRepository = supplierMaterialRepository;
        this.purchaseSpecRepository = purchaseSpecRepository;
        this.bomRecipeItemRepository = bomRecipeItemRepository;
        this.bomRecipeRepository = bomRecipeRepository;
    }

    // ==================== 核价单 ====================

    @Override
    @Transactional
    public InquiryQuote create(String factoryId, CreateInquiryQuoteRequest request, Long userId) {
        // 防呆 R4 (幂等防双击): 5min 窗口内同 工厂/创建人/物料类型/数量 的进行中核价单 → 409 + 已有单号 + 跳转提示
        if (request.getMaterialTypeId() != null) {
            List<InquiryQuote> dupes = inquiryQuoteRepository.findRecentDuplicates(
                    factoryId, userId, request.getMaterialTypeId(), request.getQuantity(),
                    LocalDateTime.now().minusMinutes(5));
            if (!dupes.isEmpty()) {
                InquiryQuote existing = dupes.get(0);
                throw new BusinessException(409, String.format(
                        "5 分钟内已创建相同核价单 (单号 %s, 物料 %s, 数量 %s), 请勿重复提交",
                        existing.getInquiryNumber(), existing.getMaterialName(), existing.getQuantity()))
                        .withHint("如需查看已有核价单请打开 " + existing.getInquiryNumber())
                        .withHintTarget(existing.getId());
            }
        }

        String sourcePurchaseOrderId = blankToNull(request.getSourcePurchaseOrderId());
        if (sourcePurchaseOrderId != null) {
            requireSourceDraftLine(factoryId, sourcePurchaseOrderId, request);
        }

        String inquiryNumber = generateInquiryNumber(factoryId);

        InquiryQuote q = new InquiryQuote();
        q.setFactoryId(factoryId);
        q.setInquiryNumber(inquiryNumber);
        q.setTitle(request.getTitle());
        q.setMaterialTypeId(request.getMaterialTypeId());
        q.setMaterialName(request.getMaterialName());
        q.setSpecification(request.getSpecification());
        q.setQuantity(request.getQuantity());
        q.setUnit(request.getUnit());
        q.setInquiryDate(request.getInquiryDate());
        q.setRequiredDate(request.getRequiredDate());
        q.setStatus(InquiryQuoteStatus.DRAFT);
        q.setCreatedBy(userId);
        q.setRemark(request.getRemark());
        q.setSourcePurchaseOrderId(sourcePurchaseOrderId);

        q = inquiryQuoteRepository.save(q);
        log.info("创建核价单: factoryId={}, inquiryNumber={}, materialName={}",
                factoryId, inquiryNumber, q.getMaterialName());
        return q;
    }

    @Override
    @Transactional
    public InquiryQuote submit(String factoryId, String inquiryId) {
        InquiryQuote q = loadOwned(factoryId, inquiryId);
        if (q.getStatus() != InquiryQuoteStatus.DRAFT) {
            // 防呆 R2: message 含 inquiryNumber + 当前状态
            throw new BusinessException(409,
                    String.format("核价单 %s 当前状态为 %s, 只有 DRAFT 状态可提交询价",
                            q.getInquiryNumber(), q.getStatus().getDisplayName()));
        }
        q.setStatus(InquiryQuoteStatus.INQUIRING);
        q = inquiryQuoteRepository.save(q);
        log.info("提交询价: factoryId={}, inquiryNumber={}", factoryId, q.getInquiryNumber());
        return q;
    }

    @Override
    @Transactional
    public InquiryQuote cancel(String factoryId, String inquiryId) {
        InquiryQuote q = loadOwned(factoryId, inquiryId);
        if (q.getStatus() == InquiryQuoteStatus.CONVERTED) {
            // 防呆 R2: 已转化的 inquiry 不能取消 — 已生成 PO
            throw new BusinessException(409,
                    String.format("核价单 %s 已转化为采购单 %s, 不能取消",
                            q.getInquiryNumber(),
                            q.getPurchaseOrderNumber() != null ? q.getPurchaseOrderNumber() : q.getPurchaseOrderId()));
        }
        q.setStatus(InquiryQuoteStatus.CANCELLED);
        q = inquiryQuoteRepository.save(q);
        log.info("取消核价单: factoryId={}, inquiryNumber={}", factoryId, q.getInquiryNumber());
        return q;
    }

    @Override
    public InquiryQuote getById(String factoryId, String inquiryId) {
        return loadOwned(factoryId, inquiryId);
    }

    @Override
    public PageResponse<InquiryQuote> list(String factoryId, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);
        Page<InquiryQuote> result = inquiryQuoteRepository
                .findByFactoryIdOrderByCreatedAtDesc(factoryId, PageRequest.of(p - 1, s));
        return PageResponse.of(result.getContent(), p, s, result.getTotalElements());
    }

    // ==================== 供应商报价 ====================

    @Override
    @Transactional
    public InquiryQuoteSupplierPrice addOrUpdateSupplierPrice(
            String factoryId, String inquiryId, AddSupplierPriceRequest request) {
        InquiryQuote q = loadOwned(factoryId, inquiryId);

        // 防呆: 只有 INQUIRING / QUOTED 状态可接受报价
        if (q.getStatus() != InquiryQuoteStatus.INQUIRING
                && q.getStatus() != InquiryQuoteStatus.QUOTED) {
            throw new BusinessException(409,
                    String.format("核价单 %s 当前状态为 %s, 不接受新报价",
                            q.getInquiryNumber(), q.getStatus().getDisplayName()));
        }

        // 验证供应商存在且属于本工厂
        Supplier supplier = supplierRepository.findByIdAndFactoryId(request.getSupplierId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        String.format("供应商不存在或不属于当前工厂: supplierId=%s", request.getSupplierId())));
        if (!Boolean.TRUE.equals(supplier.getIsActive())) {
            throw new BusinessException(409, "供应商已暂停合作，不能新增或更新报价")
                    .withHint("历史报价仍可查看；如需新报价请先恢复合作")
                    .withHintTarget("supplierId");
        }

        // upsert: 同供应商已报价则 UPDATE, 否则 INSERT
        Optional<InquiryQuoteSupplierPrice> existing =
                supplierPriceRepository.findByInquiryQuoteIdAndSupplierId(inquiryId, request.getSupplierId());

        InquiryQuoteSupplierPrice price = existing.orElseGet(InquiryQuoteSupplierPrice::new);
        price.setInquiryQuoteId(inquiryId);
        price.setSupplierId(request.getSupplierId());
        price.setUnitPrice(request.getUnitPrice());
        // 未知税率 ≠ 0%: 没填就存 NULL, 比价时标为不可比
        price.setTaxRate(request.getTaxRate());
        String currency = blankToNull(request.getCurrency());
        price.setCurrency(currency != null ? currency.toUpperCase(Locale.ROOT) : null);
        price.setQuotedUnit(blankToNull(request.getQuotedUnit()));
        price.setSpecification(blankToNull(request.getSpecification()));
        price.setMinOrderQuantity(request.getMinOrderQuantity());
        if (request.getPriceEffectiveDate() != null && request.getValidUntil() != null
                && request.getPriceEffectiveDate().isAfter(request.getValidUntil())) {
            throw new BusinessException(400, String.format(
                    "价格生效日 %s 晚于报价有效期 %s", request.getPriceEffectiveDate(), request.getValidUntil()))
                    .withHintTarget("priceEffectiveDate");
        }
        price.setPriceEffectiveDate(request.getPriceEffectiveDate());
        price.setValidUntil(request.getValidUntil());
        price.setDeliveryDays(request.getDeliveryDays());
        price.setRemark(request.getRemark());
        price.setQuotedAt(LocalDateTime.now());

        price = supplierPriceRepository.save(price);

        // 第一条报价加入后状态自动 INQUIRING → QUOTED
        if (q.getStatus() == InquiryQuoteStatus.INQUIRING) {
            q.setStatus(InquiryQuoteStatus.QUOTED);
            inquiryQuoteRepository.save(q);
        }

        log.info("供应商报价 {}: inquiryNumber={}, supplier={}, unitPrice={}",
                existing.isPresent() ? "更新" : "新增",
                q.getInquiryNumber(), supplier.getName(), price.getUnitPrice());
        return price;
    }

    @Override
    public List<InquiryQuoteSupplierPrice> listSupplierPrices(String factoryId, String inquiryId) {
        // 先校验 inquiry 归属
        loadOwned(factoryId, inquiryId);
        return supplierPriceRepository.findByInquiryQuoteIdOrderByUnitPriceAsc(inquiryId);
    }

    // ==================== 选定 + 转化 ====================

    @Override
    @Transactional
    public PurchaseOrder selectAndConvertToPurchaseOrder(
            String factoryId, String inquiryId,
            SelectAndConvertRequest request, Long userId) {
        InquiryQuote q = loadOwned(factoryId, inquiryId);

        // 防呆 R4 (idempotent): 已转化的 inquiry 拒绝重复
        if (q.getStatus() == InquiryQuoteStatus.CONVERTED || q.getPurchaseOrderId() != null) {
            String existingPoNumber = q.getPurchaseOrderNumber() != null
                    ? q.getPurchaseOrderNumber() : q.getPurchaseOrderId();
            throw new BusinessException(409,
                    String.format("核价单 %s 已转化为采购单 %s, 请勿重复创建",
                            q.getInquiryNumber(), existingPoNumber))
                    .withHint("点击查看已生成的采购单");
        }

        if (q.getStatus() != InquiryQuoteStatus.QUOTED
                && q.getStatus() != InquiryQuoteStatus.SELECTED) {
            throw new BusinessException(409,
                    String.format("核价单 %s 当前状态为 %s, 不可转化为采购单 (需 QUOTED / SELECTED)",
                            q.getInquiryNumber(), q.getStatus().getDisplayName()));
        }

        // 验证选定供应商有提交报价
        InquiryQuoteSupplierPrice selectedPrice = supplierPriceRepository
                .findByInquiryQuoteIdAndSupplierId(inquiryId, request.getSelectedSupplierId())
                .orElseThrow(() -> new BusinessException(400,
                        String.format("供应商 %s 未提交核价单 %s 的报价, 不能选定",
                                request.getSelectedSupplierId(), q.getInquiryNumber())));

        Supplier selectedSupplier = supplierRepository
                .findByIdAndFactoryId(selectedPrice.getSupplierId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "选定供应商不存在: " + selectedPrice.getSupplierId()));
        if (!Boolean.TRUE.equals(selectedSupplier.getIsActive())) {
            throw new BusinessException(409, "选定供应商已暂停合作，不能转化为新采购单")
                    .withHint("历史报价仍可查看；请恢复合作或选择其他供应商")
                    .withHintTarget("selectedSupplierId");
        }

        // 与比价页同一套规则: 不可比 / 过期 / 未生效 / 低于起订量 / 供应关系缺失 → 不能选定
        List<InquiryQuoteSupplierPrice> allPrices =
                supplierPriceRepository.findByInquiryQuoteIdOrderByUnitPriceAsc(inquiryId);
        InquiryQuoteComparisonDTO.Row verdict = InquiryQuoteComparator.evaluateOne(
                q, allPrices, selectedPrice, comparisonFacts(factoryId, q, allPrices));
        if (!verdict.isSelectable()) {
            throw new BusinessException(409, String.format(
                    "供应商 %s 的报价不能选定: %s", selectedSupplier.getName(),
                    verdict.getBlockingReasons().stream()
                            .map(InquiryQuoteComparisonDTO.Issue::getMessage)
                            .collect(Collectors.joining("; "))))
                    .withCode("QUOTE_NOT_SELECTABLE")
                    .withHintTarget("selectedSupplierId");
        }

        PurchaseOrder po;
        if (q.getSourcePurchaseOrderId() != null) {
            // 回填发起比价的那张采购草稿, ⛔ 不另建采购单
            po = purchaseService.applyQuoteToDraftLine(factoryId, q.getSourcePurchaseOrderId(),
                    q.getMaterialTypeId(), selectedSupplier.getId(), selectedPrice.getUnitPrice(),
                    selectedPrice.getQuotedUnit(), selectedPrice.getTaxRate(),
                    verdict.getPurchasePackagingSpecId());
        } else {
            po = purchaseService.createPurchaseOrder(factoryId,
                    newPurchaseOrderRequest(q, request, selectedSupplier, selectedPrice, verdict), userId);
        }

        // 回写 PO 的 inquiryQuoteId (back-link)
        po.setInquiryQuoteId(q.getId());
        po = purchaseOrderRepository.save(po);

        // 更新 inquiry 状态
        q.setSelectedSupplierId(selectedSupplier.getId());
        q.setSelectedUnitPrice(selectedPrice.getUnitPrice());
        q.setPurchaseOrderId(po.getId());
        q.setPurchaseOrderNumber(po.getOrderNumber());
        q.setStatus(InquiryQuoteStatus.CONVERTED);
        inquiryQuoteRepository.save(q);

        log.info("核价单 → 采购单: factoryId={}, inquiryNumber={}, supplier={}, " +
                        "unitPrice={}, poNumber={}, filledExistingDraft={}",
                factoryId, q.getInquiryNumber(), selectedSupplier.getName(),
                selectedPrice.getUnitPrice(), po.getOrderNumber(), q.getSourcePurchaseOrderId() != null);
        return po;
    }

    private CreatePurchaseOrderRequest newPurchaseOrderRequest(InquiryQuote q, SelectAndConvertRequest request,
                                                              Supplier selectedSupplier,
                                                              InquiryQuoteSupplierPrice selectedPrice,
                                                              InquiryQuoteComparisonDTO.Row verdict) {
        CreatePurchaseOrderRequest poRequest = new CreatePurchaseOrderRequest();
        poRequest.setSupplierId(selectedSupplier.getId());
        poRequest.setPurchaseType(PurchaseType.DIRECT.name());
        poRequest.setOrderDate(LocalDate.now());
        poRequest.setExpectedDeliveryDate(
                request.getExpectedDeliveryDate() != null
                        ? request.getExpectedDeliveryDate() : q.getRequiredDate());
        poRequest.setRemark(
                (request.getRemark() != null ? request.getRemark() + " | " : "")
                        + "由核价单 " + q.getInquiryNumber() + " 转化");

        CreatePurchaseOrderRequest.PurchaseOrderItemDTO item =
                new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        item.setMaterialTypeId(q.getMaterialTypeId());
        item.setMaterialName(q.getMaterialName());
        item.setQuantity(q.getQuantity());
        item.setUnit(q.getUnit());
        item.setQuantityUnit(q.getUnit());
        item.setUnitPrice(selectedPrice.getUnitPrice());
        item.setPriceUnit(selectedPrice.getQuotedUnit());
        item.setTaxRate(selectedPrice.getTaxRate());
        item.setPurchasePackagingSpecId(verdict.getPurchasePackagingSpecId());
        item.setSpecification(q.getSpecification());
        poRequest.setItems(List.of(item));
        return poRequest;
    }

    // ==================== 比价 (L3, S5-01 / S5-02) ====================

    @Override
    public InquiryQuoteComparisonDTO compare(String factoryId, String inquiryId) {
        InquiryQuote q = loadOwned(factoryId, inquiryId);
        List<InquiryQuoteSupplierPrice> prices =
                supplierPriceRepository.findByInquiryQuoteIdOrderByUnitPriceAsc(inquiryId);
        return InquiryQuoteComparator.compare(q, prices, comparisonFacts(factoryId, q, prices));
    }

    private InquiryQuoteComparator.Facts comparisonFacts(String factoryId, InquiryQuote q,
                                                         List<InquiryQuoteSupplierPrice> prices) {
        BigDecimal referenceTaxRate = null;
        String referenceTaxSource;
        RawMaterialType material = materialTypeRepository.findById(q.getMaterialTypeId())
                .filter(m -> factoryId.equals(m.getFactoryId()))
                .orElse(null);
        if (material == null) {
            referenceTaxSource = "物料档案不存在, 各报价税率必须彼此一致";
        } else if (material.getTaxTreatment() == TaxTreatment.EXEMPT) {
            referenceTaxRate = BigDecimal.ZERO;
            referenceTaxSource = "物料档案: 免税";
        } else if (material.getTaxRate() != null) {
            referenceTaxRate = material.getTaxRate().getRate().multiply(new BigDecimal("100"));
            referenceTaxSource = "物料档案税率";
        } else {
            referenceTaxSource = "物料档案未配置税率, 各报价税率必须彼此一致";
        }

        Map<String, Supplier> suppliers = new HashMap<>();
        Map<String, SupplierMaterial> relations = new HashMap<>();
        Map<String, List<SupplierMaterialPurchaseSpec>> specs = new HashMap<>();
        for (InquiryQuoteSupplierPrice price : prices) {
            String supplierId = price.getSupplierId();
            supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                    .ifPresent(s -> suppliers.put(supplierId, s));
            supplierMaterialRepository.findByFactoryIdAndSupplierIdAndMaterialTypeId(
                    factoryId, supplierId, q.getMaterialTypeId())
                    .ifPresent(relation -> {
                        relations.put(supplierId, relation);
                        specs.put(relation.getId(), purchaseSpecRepository
                                .findByFactoryIdAndSupplierMaterialIdAndActiveTrue(factoryId, relation.getId()));
                    });
        }
        return new InquiryQuoteComparator.Facts(referenceTaxRate, referenceTaxSource,
                LocalDate.now(), suppliers, relations, specs);
    }

    // ==================== 价格影响 (L3, S5-04) ====================

    @Override
    public InquiryQuotePriceImpactDTO priceImpact(String factoryId, String inquiryId, String priceId) {
        InquiryQuote q = loadOwned(factoryId, inquiryId);
        InquiryQuoteSupplierPrice price = supplierPriceRepository.findById(priceId)
                .filter(p -> inquiryId.equals(p.getInquiryQuoteId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        String.format("报价不存在或不属于核价单 %s: %s", q.getInquiryNumber(), priceId)));
        if (price.getPriceEffectiveDate() == null) {
            throw new BusinessException(422, "报价没有价格生效日, 不能估算对 BOM 的影响")
                    .withCode("PRICE_EFFECTIVE_DATE_REQUIRED")
                    .withHint("请先在报价里填写价格生效日")
                    .withHintTarget("priceEffectiveDate");
        }
        String currency = blankToNull(price.getCurrency());
        if (currency == null || !InquiryQuoteComparator.PURCHASE_CURRENCY.equalsIgnoreCase(currency)) {
            throw new BusinessException(422, String.format(
                    "报价币种为%s, BOM 按 %s 计价, 没有汇率依据不能估算",
                    currency == null ? "未知" : " " + currency, InquiryQuoteComparator.PURCHASE_CURRENCY))
                    .withCode("PRICE_CURRENCY_NOT_COMPARABLE");
        }
        String quotedUnit = blankToNull(price.getQuotedUnit());

        List<BomRecipeItem> items = bomRecipeItemRepository
                .findByFactoryIdAndMaterialTypeId(factoryId, q.getMaterialTypeId());
        Map<String, BomRecipe> recipes = new LinkedHashMap<>();
        bomRecipeRepository.findAllById(items.stream().map(BomRecipeItem::getRecipeId)
                        .filter(Objects::nonNull).distinct().collect(Collectors.toList()))
                .stream()
                .filter(r -> factoryId.equals(r.getFactoryId()))
                .forEach(r -> recipes.put(r.getId(), r));

        int hidden = 0;
        Map<String, Boolean> visible = new HashMap<>();
        for (BomRecipe recipe : recipes.values()) {
            boolean ok = processSecretGuard == null || processSecretGuard.permitsFullContent(
                    factoryId, recipe.getId(), "inquiry-quote price-impact " + priceId);
            visible.put(recipe.getId(), ok);
            if (!ok) {
                hidden++;
            }
        }

        List<InquiryQuotePriceImpactDTO.Row> rows = new ArrayList<>();
        for (BomRecipeItem item : items) {
            BomRecipe recipe = recipes.get(item.getRecipeId());
            if (recipe == null || !visible.getOrDefault(recipe.getId(), false)
                    || recipe.getStatus() == BomRecipe.Status.ARCHIVED) {
                continue;
            }
            String itemPriceUnit = blankToNull(item.getPriceUnit()) != null ? item.getPriceUnit() : item.getUnit();
            String reason = null;
            if (quotedUnit == null) {
                reason = "报价未注明计价单位";
            } else if (itemPriceUnit == null || !quotedUnit.equalsIgnoreCase(itemPriceUnit.trim())) {
                reason = String.format("BOM 计价单位「%s」与报价单位「%s」不同, 不做换算",
                        itemPriceUnit, quotedUnit);
            } else if (item.getStandardQuantity() == null) {
                reason = "BOM 行没有参考用量, 成本由实际报工归集";
            }
            BigDecimal currentCost = item.computeItemCost();
            BigDecimal estimated = null;
            BigDecimal delta = null;
            if (reason == null) {
                BigDecimal factor = item.getQuantityToPriceFactor() != null
                        ? item.getQuantityToPriceFactor() : BigDecimal.ONE;
                estimated = CostRollupUtil.calcItemCost(
                        item.calculateActualQuantity().multiply(factor), price.getUnitPrice());
                if (currentCost != null && estimated != null) {
                    delta = estimated.subtract(currentCost).setScale(4, RoundingMode.HALF_UP);
                }
            }
            rows.add(InquiryQuotePriceImpactDTO.Row.builder()
                    .recipeId(recipe.getId())
                    .recipeCode(recipe.getRecipeCode())
                    .productTypeId(recipe.getProductTypeId())
                    .productName(recipe.getProductName())
                    .recipeVersion(recipe.getVersion())
                    .recipeStatus(recipe.getStatus() != null ? recipe.getStatus().name() : null)
                    .isCurrent(recipe.getIsCurrent())
                    .recipeItemId(item.getId())
                    .standardQuantity(item.getStandardQuantity())
                    .priceUnit(itemPriceUnit)
                    .currentUnitPrice(item.getUnitPrice())
                    .currentItemCost(currentCost)
                    .estimatedItemCost(estimated)
                    .deltaItemCost(delta)
                    .computable(reason == null)
                    .notComputableReason(reason)
                    .build());
        }

        Supplier supplier = supplierRepository.findByIdAndFactoryId(price.getSupplierId(), factoryId).orElse(null);
        return InquiryQuotePriceImpactDTO.builder()
                .inquiryId(q.getId())
                .inquiryNumber(q.getInquiryNumber())
                .priceId(price.getId())
                .supplierId(price.getSupplierId())
                .supplierName(supplier != null ? supplier.getName() : null)
                .materialTypeId(q.getMaterialTypeId())
                .materialName(q.getMaterialName())
                .quotedUnitPrice(price.getUnitPrice())
                .quotedUnit(quotedUnit)
                .priceEffectiveDate(price.getPriceEffectiveDate())
                .evaluatedOn(LocalDate.now())
                .nature("ESTIMATE")
                .historicalCostsModified(false)
                .note("估算按报价未税单价重算 BOM 行成本, 不回写 BOM; 已投产批次成本取自批次实际记录, 保持不变。"
                        + "BOM 价格的正式变更请走 BOM 调价建议审批。")
                .affectedRecipeCount((int) rows.stream().map(InquiryQuotePriceImpactDTO.Row::getRecipeId)
                        .distinct().count())
                .computableRowCount((int) rows.stream().filter(InquiryQuotePriceImpactDTO.Row::isComputable).count())
                .hiddenRecipeCount(hidden)
                .rows(rows)
                .build();
    }

    // ==================== 内部 ====================

    /**
     * 发起比价的采购草稿必须: 本厂、DRAFT、恰好一行是本物料、单位一致,
     * 且这行上没有另一张进行中的核价单。
     */
    private void requireSourceDraftLine(String factoryId, String sourcePurchaseOrderId,
                                        CreateInquiryQuoteRequest request) {
        PurchaseOrder draft = purchaseService.getPurchaseOrderById(factoryId, sourcePurchaseOrderId);
        if (draft.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new BusinessException(409, String.format(
                    "采购单 %s 当前状态为 %s, 只有草稿可以发起比价回填",
                    draft.getOrderNumber(), draft.getStatus().getDisplayName()))
                    .withCode("PURCHASE_DRAFT_NOT_EDITABLE");
        }
        List<PurchaseOrderItem> lines = draft.getItems() == null ? List.of() : draft.getItems().stream()
                .filter(i -> request.getMaterialTypeId().equals(i.getMaterialTypeId()))
                .collect(Collectors.toList());
        if (lines.size() != 1) {
            throw new BusinessException(409, String.format(
                    "采购草稿 %s 中本物料有 %d 行, 需要恰好 1 行才能比价回填",
                    draft.getOrderNumber(), lines.size()))
                    .withCode("PURCHASE_DRAFT_LINE_AMBIGUOUS");
        }
        String lineUnit = lines.get(0).getUnit();
        if (lineUnit == null || !lineUnit.trim().equalsIgnoreCase(request.getUnit().trim())) {
            throw new BusinessException(400, String.format(
                    "询价单位「%s」与采购草稿 %s 该行单位「%s」不同",
                    request.getUnit(), draft.getOrderNumber(), lineUnit))
                    .withHintTarget("unit");
        }
        List<InquiryQuote> open = inquiryQuoteRepository
                .findByFactoryIdAndSourcePurchaseOrderIdAndMaterialTypeIdAndStatusIn(
                        factoryId, sourcePurchaseOrderId, request.getMaterialTypeId(), OPEN_STATUSES);
        if (!open.isEmpty()) {
            InquiryQuote existing = open.get(0);
            throw new BusinessException(409, String.format(
                    "采购草稿 %s 的这行物料已有进行中的核价单 %s",
                    draft.getOrderNumber(), existing.getInquiryNumber()))
                    .withCode("INQUIRY_FOR_DRAFT_LINE_EXISTS")
                    .withHint("请在已有核价单 " + existing.getInquiryNumber() + " 中继续比价")
                    .withHintTarget(existing.getId());
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private InquiryQuote loadOwned(String factoryId, String inquiryId) {
        InquiryQuote q = inquiryQuoteRepository.findById(inquiryId)
                .orElseThrow(() -> new ResourceNotFoundException("核价单不存在: " + inquiryId));
        if (!factoryId.equals(q.getFactoryId())) {
            throw new BusinessException(403,
                    "无权访问该核价单 (工厂归属不符)");
        }
        return q;
    }

    /**
     * 生成核价单号 INQ-YYYYMMDD-NNNN.
     * Pattern mirror PurchaseServiceImpl.generateOrderNumber.
     */
    private String generateInquiryNumber(String factoryId) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "INQ-" + dateStr + "-";
        Optional<String> maxNumber = inquiryQuoteRepository
                .findMaxInquiryNumberByPrefix(factoryId, prefix + "%");
        if (maxNumber.isPresent()) {
            try {
                int seq = Integer.parseInt(maxNumber.get().substring(prefix.length()));
                return String.format("%s%04d", prefix, seq + 1);
            } catch (NumberFormatException e) {
                log.warn("inquiry number seq parse failed: {}", maxNumber.get());
            }
        }
        return String.format("%s%04d", prefix, 1);
    }
}
