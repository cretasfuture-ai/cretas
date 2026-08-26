package com.cretas.aims.service;

import com.cretas.aims.dto.label.MaterialBatchLabelScanResponse;
import com.cretas.aims.entity.Label;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/**
 * 标签服务接口
 * 用于管理产品追溯标签、条码、二维码
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
public interface LabelService {

    /**
     * 分页查询标签列表
     */
    Page<Label> getLabels(String factoryId, Pageable pageable);

    /**
     * 根据类型分页查询
     */
    Page<Label> getLabelsByType(String factoryId, String labelType, Pageable pageable);

    /**
     * 根据状态分页查询
     */
    Page<Label> getLabelsByStatus(String factoryId, String status, Pageable pageable);

    /**
     * 获取单个标签详情
     */
    Optional<Label> getLabelById(String factoryId, String id);

    /**
     * 根据标签编码查询 (按工厂隔离).
     *
     * <p>⛔ 2026-08-26 之前这个方法**不收 factoryId** —— label_code 是全局唯一的,
     * 于是任何一个已登录用户都能读到别的工厂的标签实体。加 factoryId 参数而不是
     * 在 repository 上加 {@code AndFactoryId} 派生查询, 是为了让**越权和不存在
     * 保持可区分**: 见 {@link #getByTraceCode} 的说明。
     *
     * @return 命中且属于本工厂 → 该标签; 库里没有这个码 → {@code Optional.empty()}
     * @throws com.cretas.aims.exception.AuthorizationException 码存在但属于别的工厂
     */
    Optional<Label> getByLabelCode(String factoryId, String labelCode);

    /**
     * 根据追溯码查询 (按工厂隔离).
     *
     * <p>⛔ 2026-08-26 修复跨租户泄露: 原签名不收 factoryId, 而
     * {@code GET /api/mobile/{factoryId}/labels/trace/{traceCode}} 把路径上的
     * factoryId 收下之后**一次都没用过** —— 查询无工厂过滤, 返回的还是裸 Label
     * 实体。traceCode 的形状是 {@code TRACE-<factoryId>-<yyyyMMdd>-<6位>}, 六位随机
     * 数来自 {@code Math.random()} 而不是 CSPRNG, 可枚举。
     *
     * <p>⚠️ 越权**不能**被伪装成「不存在」: 那会把真实的跨租户探测埋进 404 里,
     * 日志上看不出来, 也无法告警。所以这里先取再比 factoryId, 不匹配抛
     * {@link com.cretas.aims.exception.AuthorizationException} (→ 403 + WARN 日志),
     * 与 {@code scanLabel} 步骤 2 的做法一致。
     * <p>代价: 保留了一个「这个码存在但不归你」的存在性 oracle。这是有意的取舍 ——
     * 掩盖越权的代价更大, 且 403 会留下可审计的 WARN, 枚举行为反而变得可见。
     *
     * @return 命中且属于本工厂 → 该标签; 库里没有这个码 → {@code Optional.empty()}
     * @throws com.cretas.aims.exception.AuthorizationException 码存在但属于别的工厂
     */
    Optional<Label> getByTraceCode(String factoryId, String traceCode);

    /**
     * 创建标签
     */
    Label createLabel(Label label);

    /**
     * 批量创建标签
     */
    List<Label> createLabels(List<Label> labels);

    /**
     * 更新标签
     */
    Label updateLabel(String id, Label updateData);

    /**
     * 打印标签（更新打印记录）
     */
    Label printLabel(String id, Long printedBy);

    /**
     * 贴标操作
     */
    Label applyLabel(String id, Long appliedBy);

    /**
     * 作废标签
     */
    Label voidLabel(String id, Long updatedBy);

    /**
     * 删除标签（软删除）
     */
    void deleteLabel(String id);

    /**
     * 查询批次关联的标签 (按工厂隔离).
     *
     * <p>⛔ 2026-08-26 同批修复: 原签名不收 factoryId, batchId 又是可猜的
     * (物料批次号带日期+序号), 于是能读到别厂批次的全部标签。
     *
     * <p>⚠️ 命中别厂标签时**抛异常而不是过滤成空列表** —— 过滤会让越权长得和
     * 「这个批次没有标签」一模一样, 正是上面 {@link #getByTraceCode} 要避免的那件事。
     *
     * @throws com.cretas.aims.exception.AuthorizationException 批次的标签属于别的工厂
     */
    List<Label> getLabelsByBatchId(String factoryId, String batchId);

    /**
     * 查询生产批次关联的标签
     */
    List<Label> getLabelsByProductionBatchId(Long productionBatchId);

    /**
     * 查询即将过期的标签
     */
    List<Label> getExpiringLabels(String factoryId, int daysAhead);

    /**
     * 查询已过期的标签
     */
    List<Label> getExpiredLabels(String factoryId);

    /**
     * 统计标签数量
     */
    long countByFactory(String factoryId);

    /**
     * 统计特定状态的标签数量
     */
    long countByStatus(String factoryId, String status);

    /**
     * 生成标签编码
     */
    String generateLabelCode(String factoryId, String labelType);

    /**
     * 生成追溯码
     */
    String generateTraceCode(String factoryId, String batchNumber);

    // ========== SP4-T5: 物料批次标签生成 + 扫码查询 ==========

    /**
     * SP4-T5: 为物料批次生成 MATERIAL 类型标签.
     * 防呆: 同一批次已有 ACTIVE 标签时抛出 RuntimeException (409 场景).
     *
     * @param factoryId 工厂 ID
     * @param batchId   物料批次 ID
     * @param userId    操作人 ID
     * @return 新建的 ACTIVE 标签
     */
    Label generateMaterialBatchLabel(String factoryId, String batchId, Long userId);

    /**
     * SP4-T5: 扫描物料标签, 返回批次完整信息.
     * VOIDED 标签返回 HTTP 400 (通过抛 RuntimeException).
     * 跨工厂访问返回 HTTP 400.
     *
     * @param factoryId 工厂 ID
     * @param labelCode 标签编码 (扫描值)
     * @return 标签 + 批次关键信息 DTO
     */
    MaterialBatchLabelScanResponse scanLabel(String factoryId, String labelCode);
}
