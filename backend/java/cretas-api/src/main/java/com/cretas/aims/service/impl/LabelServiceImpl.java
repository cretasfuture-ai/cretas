package com.cretas.aims.service.impl;

import com.cretas.aims.dto.label.MaterialBatchLabelScanResponse;
import com.cretas.aims.entity.Label;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.exception.AuthorizationException;
import com.cretas.aims.repository.LabelRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.LabelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 标签服务实现类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabelServiceImpl implements LabelService {

    private final LabelRepository labelRepository;
    private final MaterialBatchRepository materialBatchRepository;

    @Override
    public Page<Label> getLabels(String factoryId, Pageable pageable) {
        return labelRepository.findByFactoryIdAndDeletedAtIsNull(factoryId, pageable);
    }

    @Override
    public Page<Label> getLabelsByType(String factoryId, String labelType, Pageable pageable) {
        return labelRepository.findByFactoryIdAndLabelTypeAndDeletedAtIsNull(factoryId, labelType, pageable);
    }

    @Override
    public Page<Label> getLabelsByStatus(String factoryId, String status, Pageable pageable) {
        return labelRepository.findByFactoryIdAndStatusAndDeletedAtIsNull(factoryId, status, pageable);
    }

    @Override
    public Optional<Label> getLabelById(String factoryId, String id) {
        return labelRepository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId);
    }

    @Override
    public Optional<Label> getByLabelCode(String factoryId, String labelCode) {
        return labelRepository.findByLabelCodeAndDeletedAtIsNull(labelCode)
                .map(label -> requireSameFactory(factoryId, label));
    }

    @Override
    public Optional<Label> getByTraceCode(String factoryId, String traceCode) {
        return labelRepository.findByTraceCodeAndDeletedAtIsNull(traceCode)
                .map(label -> requireSameFactory(factoryId, label));
    }

    /**
     * 租户隔离的唯一落点 —— label_code / trace_code / batch_id 都是**全局**查询键,
     * 三条读路径共用这一个判定, 不各写各的 (三份一定会漂).
     *
     * <p>⛔ 不匹配时抛 403 而不是返回空 —— 把越权伪装成「不存在」会让真实的
     * 跨租户探测在 404 里消失, 既看不见也告不了警。参见 {@code scanLabel} 步骤 2,
     * 那里是同一条判据的另一处实例 (它抛 RuntimeException → 400, 保持原状: 那条
     * 路径 RN 端在用, 改状态码会撞上前端把 403 当「登录失效」的处理).
     */
    private Label requireSameFactory(String factoryId, Label label) {
        if (factoryId == null || !factoryId.equals(label.getFactoryId())) {
            log.warn("跨工厂标签访问被拒绝: 请求工厂={}, 标签工厂={}, labelId={}",
                    factoryId, label.getFactoryId(), label.getId());
            // 措辞不回显对方工厂号 —— 拒绝要可区分, 但没必要把归属也一起告诉调用方
            throw new AuthorizationException("无权访问该标签: 它不属于当前工厂 " + factoryId);
        }
        return label;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Label createLabel(Label label) {
        // 检查标签编码是否已存在
        if (label.getLabelCode() != null &&
            labelRepository.existsByLabelCodeAndDeletedAtIsNull(label.getLabelCode())) {
            throw new RuntimeException("标签编码已存在: " + label.getLabelCode());
        }

        if (label.getId() == null || label.getId().isEmpty()) {
            label.setId(UUID.randomUUID().toString());
        }
        if (label.getLabelCode() == null || label.getLabelCode().isEmpty()) {
            label.setLabelCode(generateLabelCode(label.getFactoryId(), label.getLabelType()));
        }
        if (label.getStatus() == null) {
            label.setStatus("ACTIVE");
        }
        if (label.getPrintCount() == null) {
            label.setPrintCount(0);
        }

        log.info("创建标签: factoryId={}, labelCode={}", label.getFactoryId(), label.getLabelCode());
        return labelRepository.save(label);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Label> createLabels(List<Label> labels) {
        List<Label> created = new ArrayList<>();
        for (Label label : labels) {
            created.add(createLabel(label));
        }
        log.info("批量创建标签: count={}", created.size());
        return created;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Label updateLabel(String id, Label updateData) {
        Label existing = labelRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("标签不存在: " + id));

        if (updateData.getProductName() != null) existing.setProductName(updateData.getProductName());
        if (updateData.getSpecification() != null) existing.setSpecification(updateData.getSpecification());
        if (updateData.getProductionDate() != null) existing.setProductionDate(updateData.getProductionDate());
        if (updateData.getShelfLifeDays() != null) existing.setShelfLifeDays(updateData.getShelfLifeDays());
        if (updateData.getExpiryDate() != null) existing.setExpiryDate(updateData.getExpiryDate());
        if (updateData.getExtraData() != null) existing.setExtraData(updateData.getExtraData());

        return labelRepository.save(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Label printLabel(String id, Long printedBy) {
        Label label = labelRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("标签不存在: " + id));
        label.setStatus("PRINTED");
        label.setPrintCount(label.getPrintCount() + 1);
        label.setLastPrintedAt(LocalDateTime.now());
        label.setPrintedBy(printedBy);
        log.info("打印标签: id={}, printCount={}", id, label.getPrintCount());
        return labelRepository.save(label);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Label applyLabel(String id, Long appliedBy) {
        Label label = labelRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("标签不存在: " + id));
        label.setStatus("APPLIED");
        label.setAppliedAt(LocalDateTime.now());
        label.setAppliedBy(appliedBy);
        log.info("贴标: id={}", id);
        return labelRepository.save(label);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Label voidLabel(String id, Long updatedBy) {
        Label label = labelRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("标签不存在: " + id));
        label.setStatus("VOIDED");
        log.info("作废标签: id={}", id);
        return labelRepository.save(label);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteLabel(String id) {
        Label label = labelRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("标签不存在: " + id));
        label.setDeletedAt(LocalDateTime.now());
        labelRepository.save(label);
        log.info("删除标签: id={}", id);
    }

    @Override
    public List<Label> getLabelsByBatchId(String factoryId, String batchId) {
        return labelRepository.findByBatchIdAndDeletedAtIsNull(batchId).stream()
                .map(label -> requireSameFactory(factoryId, label))
                .toList();
    }

    @Override
    public List<Label> getLabelsByProductionBatchId(Long productionBatchId) {
        return labelRepository.findByProductionBatchIdAndDeletedAtIsNull(productionBatchId);
    }

    @Override
    public List<Label> getExpiringLabels(String factoryId, int daysAhead) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.plusDays(daysAhead);
        return labelRepository.findExpiringLabels(factoryId, now, threshold);
    }

    @Override
    public List<Label> getExpiredLabels(String factoryId) {
        return labelRepository.findExpiredLabels(factoryId, LocalDateTime.now());
    }

    @Override
    public long countByFactory(String factoryId) {
        return labelRepository.countByFactoryIdAndDeletedAtIsNull(factoryId);
    }

    @Override
    public long countByStatus(String factoryId, String status) {
        return labelRepository.countByFactoryIdAndStatusAndDeletedAtIsNull(factoryId, status);
    }

    @Override
    public String generateLabelCode(String factoryId, String labelType) {
        String prefix = labelType != null ? labelType.substring(0, 2) : "LB";
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String seq = String.format("%04d", (int) (Math.random() * 10000));
        return prefix + "-" + factoryId + "-" + dateStr + "-" + seq;
    }

    @Override
    public String generateTraceCode(String factoryId, String batchNumber) {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String seq = String.format("%06d", (int) (Math.random() * 1000000));
        return "TRACE-" + factoryId + "-" + dateStr + "-" + seq;
    }

    // ========== SP4-T5: 物料批次标签生成 + 扫码查询 ==========

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Label generateMaterialBatchLabel(String factoryId, String batchId, Long userId) {
        // 1. 批次存在校验
        MaterialBatch batch = materialBatchRepository.findByIdAndFactoryId(batchId, factoryId)
                .orElseThrow(() -> new RuntimeException("物料批次不存在: batchId=" + batchId));

        // 2. 防呆: 已有 ACTIVE 标签则拒绝重复生成
        List<Label> existing = labelRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        boolean hasActive = existing.stream()
                .anyMatch(l -> "ACTIVE".equals(l.getStatus())
                        || "PRINTED".equals(l.getStatus()));
        if (hasActive) {
            throw new RuntimeException("该物料批次已存在有效标签, batchId=" + batchId
                    + " — 请先作废旧标签再重新生成 (already have active/printed label)");
        }

        // 3. 构建 MATERIAL 类型标签
        Label label = new Label();
        label.setId(UUID.randomUUID().toString());
        label.setFactoryId(factoryId);
        label.setBatchType("MATERIAL");
        label.setBatchId(batchId);
        label.setStatus("ACTIVE");
        label.setPrintCount(0);
        label.setCreatedBy(userId);

        // A3: 数字前缀复用 SP8 primaryCode (RawMaterialType.primaryCode 前三位), 避免双编码体系。
        // 修 (2026-06-12, Codex Gate2 靶6): 无 primaryCode 时不再硬编码 "MA"(失真), 改用 SP4 类别业务前缀
        // (原料/主材→YL, 肉类→RL, 包材→BC, 辅料/调料等→WL), 与编码生成规则同源
        // (RawMaterialTypeServiceImpl 同包 static)。
        // ⚠️ 2026-08-09 (20202d7758) 起 getMaterialCategoryPrefix 对 null/未知类别一律抛
        // BusinessException, 不再兜底 WL —— 本方法因此继承了同一条闸: materialType 或其
        // category 缺失时, 生成标签会显式失败, 而不是打印一张类别猜测出来的标签。
        String sp8Prefix = (batch.getMaterialType() != null
                && batch.getMaterialType().getPrimaryCode() != null
                && !batch.getMaterialType().getPrimaryCode().isBlank())
                ? batch.getMaterialType().getPrimaryCode()
                : RawMaterialTypeServiceImpl.getMaterialCategoryPrefix(
                        batch.getMaterialType() != null ? batch.getMaterialType().getCategory() : null);
        label.setLabelCode(generateLabelCode(factoryId, sp8Prefix));
        label.setTraceCode(generateTraceCode(factoryId, batch.getBatchNumber()));
        // 物料名称从 materialType 关联实体获取 (lazy, but within transaction)
        if (batch.getMaterialType() != null) {
            label.setProductName(batch.getMaterialType().getName());
        }
        log.info("SP4-T5: 生成物料批次标签 batchId={}, labelCode={}", batchId, label.getLabelCode());
        return labelRepository.save(label);
    }

    @Override
    @Transactional(readOnly = true)
    public MaterialBatchLabelScanResponse scanLabel(String factoryId, String labelCode) {
        // 1. 标签存在 — 先按 labelCode 查, 查不到再按 traceCode 兜底.
        //
        // Why (2026-08-25, 往返缺陷): 打印机印进二维码的**不是** labelCode 而是 traceCode —
        //   WHBatchDetailScreen.tsx:217   qrContent = genRes.data?.traceCode ?? genRes.data?.labelCode
        //   BatchLabelPrintService.ts:65  const qrText = qrContent ?? batchNumber
        // 而 generateMaterialBatchLabel (本文件 :252) 恒设 traceCode, 于是 traceCode 恒胜出,
        // 贴在实物上的二维码里永远是 "TRACE-...". 只查 label_code 会把**每一张已打印的标签**
        // 都扫成"标签不存在". 兜底能同时接住已经贴出去的旧标签和以后新印的标签.
        //
        // ⛔ 兜底只换查询方式, 不换后面的校验 — 跨工厂防护(步骤2)与 VOIDED 检查(步骤3)
        //    对两条查询路径一视同仁, 走兜底分支不构成任何绕过.
        //
        // 撞车风险: 结构上不可能.
        //   generateLabelCode  → "<2字符>-<factoryId>-<14位>-<4位>"  (prefix = substring(0,2), 如 YL/RL/BC/WL 或 2 位数字)
        //   generateTraceCode  → "TRACE-<factoryId>-<8位>-<6位>"
        // 首个 "-" 之前一个恒为 2 字符、另一个恒为 5 字符 ("TRACE"), 两者恒不相等.
        // 唯一的非生成路径是 createLabel 允许调用方自带 labelCode; 即便有人自带一个长得像
        // traceCode 的串, 先查 labelCode 也保证精确匹配优先命中, 语义仍然唯一确定.
        final String scannedCode = labelCode;
        Label label = labelRepository.findByLabelCodeAndDeletedAtIsNull(scannedCode)
                .orElseGet(() -> labelRepository.findByTraceCodeAndDeletedAtIsNull(scannedCode)
                        .map(byTrace -> {
                            // 留痕: 不打这条日志就没人知道有多少标签实际走的是兜底
                            log.info("SP4-T5: 扫码经 traceCode 兜底命中 — scannedCode={}, labelCode={}",
                                    scannedCode, byTrace.getLabelCode());
                            return byTrace;
                        })
                        .orElseThrow(() -> new RuntimeException(
                                "标签不存在: code=" + scannedCode + " (已按 labelCode 与 traceCode 两种方式查过)")));

        // 2. 跨工厂访问防护
        if (!factoryId.equals(label.getFactoryId())) {
            throw new RuntimeException("无权访问该标签: 标签属于工厂 "
                    + label.getFactoryId() + ", 当前工厂 " + factoryId
                    + " (cross-factory access denied)");
        }

        // 3. VOIDED 标签禁止使用
        if ("VOIDED".equals(label.getStatus())) {
            throw new RuntimeException("标签已作废 (VOIDED), 禁止使用: labelCode=" + labelCode);
        }

        // 4. 获取批次信息 (MATERIAL 类型绑定到 MaterialBatch)
        MaterialBatch batch = null;
        if ("MATERIAL".equals(label.getBatchType()) && label.getBatchId() != null) {
            batch = materialBatchRepository
                    .findByIdAndFactoryId(label.getBatchId(), factoryId)
                    .orElse(null); // 标签可能在批次删除后仍存在
        }

        // 5. 组装响应 DTO
        MaterialBatchLabelScanResponse.MaterialBatchLabelScanResponseBuilder builder =
                MaterialBatchLabelScanResponse.builder()
                        .labelId(label.getId())
                        .labelCode(label.getLabelCode())
                        .labelStatus(label.getStatus())
                        .traceCode(label.getTraceCode())
                        .batchId(label.getBatchId())
                        .labelCreatedAt(label.getCreatedAt());

        if (batch != null) {
            // 物料名称从 materialType 关联实体获取, specification 用数量单位代替
            String materialName = (batch.getMaterialType() != null)
                    ? batch.getMaterialType().getName() : null;
            String specification = batch.getQuantityUnit();   // e.g. "kg" / "箱"
            // A5 补缺: 填入重量/数量字段 — 缺值传 null，不补 0
            java.math.BigDecimal receiptQty = batch.getReceiptQuantity();
            java.math.BigDecimal remainingQty = (receiptQty != null) ? batch.getRemainingQuantity() : null;
            builder.batchNumber(batch.getBatchNumber())
                    .materialName(materialName)
                    .specification(specification)
                    .factoryNumber(batch.getFactoryNumber())
                    .originPlace(batch.getOriginPlace())
                    .receiptQuantity(receiptQty)
                    .remainingQuantity(remainingQty)
                    .quantityUnit(batch.getQuantityUnit())
                    .batchCreatedAt(batch.getCreatedAt());
        }

        log.info("SP4-T5: 扫码查询 labelCode={}, batchId={}", labelCode, label.getBatchId());
        return builder.build();
    }
}
