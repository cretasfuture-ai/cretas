package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.SalesExceptionType;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesException;
import com.cretas.aims.repository.inventory.SalesExceptionRepository;
import com.cretas.aims.service.inventory.SalesExceptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 销售异常单 Service 实现 —— 给被拦下的超发尝试留痕。
 *
 * <h1>🔴 事务边界（本类存在的全部理由）</h1>
 *
 * <h2>问题</h2>
 * 调用点在 {@code SalesServiceImpl.warehouseConfirmDelivery} 里，
 * 紧接着<b>下一行就抛 409</b>。那个方法是 {@code @Transactional} 的，
 * 抛出去 = 整个事务回滚。<b>与它同事务写的任何东西都留不下来。</b>
 *
 * <h2>解法</h2>
 * {@link #persistAttempt} 标 {@code @Transactional(propagation = REQUIRES_NEW)}:
 * 调用时挂起外层事务、开一个新事务、<b>当场提交</b>、再恢复外层。
 * 外层随后回滚，不影响已经提交的这一行。
 *
 * <h2>⛔ 两个必须避开的坑</h2>
 * <ol>
 *   <li><b>自调用不过代理</b> —— Spring 的 {@code @Transactional} 靠代理生效。
 *       在同一个类里写 {@code this.persistAttempt(...)}，propagation
 *       <b>一个字都不起作用</b>，写入照样跟着外层回滚，而且<b>不报任何错</b>。
 *       所以这里走 {@link #self} 代理（{@code @Lazy} 打破自注入的循环依赖），
 *       与本仓 {@code PurchaseExceptionServiceImpl} / {@code ReverseTransferService}
 *       同一范式。单测直接 {@code new} 出实例时 {@code self == null}，
 *       {@link #proxy()} 退回 {@code this} —— ⚠️ 那种情况下<b>没有独立事务</b>，
 *       见本类下方「测试能证明什么」。</li>
 *   <li><b>不许碰外层锁着的行</b> —— 外层已经对 {@code sales_delivery_records} /
 *       {@code sales_orders} 上了 {@code FOR UPDATE} 行锁。独立事务跑在<b>另一条连接</b>上，
 *       去读那些行会当场死锁。⇒ 本类<b>只碰 {@code sales_exceptions} 一张表</b>，
 *       需要的字段（发货单号 / 产品名 / 订单 id）全部由调用方从<b>已在内存里</b>的
 *       实体上取出来传进来，本类不再回库查任何东西。</li>
 * </ol>
 *
 * <h2>失败一律吞掉</h2>
 * {@link #recordBlockedOverShip} 外层包 try/catch:
 * 留痕失败绝不能把「这次发货被拒绝」(409) 变成「服务器错误」(500)。
 * 失败打 ERROR 日志 + 返回 null。
 *
 * <h2>取号：怎么绕开软删发号器陷阱</h2>
 * 见 {@link #nextExceptionNumber} 与
 * {@code SalesExceptionRepository#findHighestNumberForPrefix}。
 *
 * @since 2026-08-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesExceptionServiceImpl implements SalesExceptionService {

    /** 单号前缀。 */
    private static final String NUMBER_PREFIX = "SE";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 单号尾部的序号，用来从历史最大号里解析出数值。 */
    private static final Pattern TRAILING_SEQ = Pattern.compile("-(\\d+)$");

    /**
     * 单号里工厂段的最大长度。
     *
     * <p>{@code exception_number} 是 {@code VARCHAR(50)}，而 {@code factory_id} 是
     * {@code VARCHAR(191)}。原样拼接会在长 factoryId 上把号截断/写失败。
     * ⚠️ 截断<b>不会</b>造成撞号：唯一约束是 {@code (factory_id, exception_number)}，
     * 两个工厂即使截出同一段也在不同的键上。
     */
    private static final int FACTORY_SEGMENT_MAX = 12;

    /**
     * 取号撞车的重试次数。
     *
     * <p>并发两次记录会读到同一个 max → 生成同一个号 → 其中一个撞
     * {@code uidx_sales_exceptions_number}。重试时重新读 max 就能拿到下一个号。
     * ⚠️ 每次重试都必须是<b>一个新事务</b>（撞过约束的事务在 PG 上已经 aborted，
     * 在里面再 save 只会得到 "current transaction is aborted"）——
     * 所以重试循环写在这里，而每一轮都重新走一次 {@code REQUIRES_NEW} 的
     * {@link #persistAttempt}。
     */
    private static final int MAX_NUMBER_RETRIES = 3;

    private final SalesExceptionRepository salesExceptionRepository;

    /**
     * 自身代理，用来真正跨过 {@code REQUIRES_NEW} 的事务边界。
     * {@code @Lazy} 打破自注入的循环依赖。单测直接 new 时为 null。
     */
    @Autowired
    @Lazy
    private SalesExceptionServiceImpl self;

    private SalesExceptionServiceImpl proxy() {
        return self != null ? self : this;
    }

    // ─── recordBlockedOverShip ────────────────────────────────────────────

    @Override
    public SalesException recordBlockedOverShip(String factoryId,
                                                SalesDeliveryRecord record,
                                                SalesDeliveryItem item,
                                                BigDecimal actual,
                                                BigDecimal limitRate,
                                                String limitSource,
                                                BigDecimal maxAllowed,
                                                Long userId) {
        // ⚠️ 本方法【故意没有】@Transactional：它只是重试循环 + 兜底 catch。
        //    真正的事务边界在 persistAttempt 上，每一轮重试都要是一个新事务。
        for (int attempt = 1; attempt <= MAX_NUMBER_RETRIES; attempt++) {
            try {
                return proxy().persistAttempt(factoryId, record, item, actual,
                        limitRate, limitSource, maxAllowed, userId);
            } catch (DataIntegrityViolationException collision) {
                // 取号撞车：并发的另一次记录抢先用掉了这个号。重读 max 再来一次。
                log.warn("SALES_EXCEPTION_NUMBER_COLLISION: factoryId={}, deliveryId={}, 第 {}/{} 次重试",
                        factoryId, record == null ? null : record.getId(), attempt, MAX_NUMBER_RETRIES);
            } catch (Exception e) {
                // 🔴 任何其它失败都在这里终止。⛔ 绝不允许把 409 变成 500 ——
                //    「这次发货被拒绝」是产品行为，留痕失败不该改变它。
                log.error("SALES_EXCEPTION_RECORD_FAILED: factoryId={}, deliveryId={}, itemId={}, "
                                + "本次超发尝试【没有留下记录】(发货仍按 409 拒绝)",
                        factoryId,
                        record == null ? null : record.getId(),
                        item == null ? null : item.getId(), e);
                return null;
            }
        }
        log.error("SALES_EXCEPTION_RECORD_FAILED: factoryId={}, deliveryId={}, 取号连续撞车 {} 次, "
                        + "本次超发尝试【没有留下记录】(发货仍按 409 拒绝)",
                factoryId, record == null ? null : record.getId(), MAX_NUMBER_RETRIES);
        return null;
    }

    /**
     * 🔴 独立事务写入 —— 本类的承重点。
     *
     * <p>{@code REQUIRES_NEW}: 挂起注定要回滚的外层事务，在新事务里提交这一行。
     *
     * <p>⚠️ 本方法<b>只写 {@code sales_exceptions}</b>，不读任何被外层
     * {@code FOR UPDATE} 锁住的表（那会跨连接死锁）。
     *
     * <p>{@code public} 是必须的：private 方法不会被 Spring 的 CGLIB 代理拦截，
     * 注解会被静默忽略。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SalesException persistAttempt(String factoryId,
                                         SalesDeliveryRecord record,
                                         SalesDeliveryItem item,
                                         BigDecimal actual,
                                         BigDecimal limitRate,
                                         String limitSource,
                                         BigDecimal maxAllowed,
                                         Long userId) {
        SalesException se = new SalesException();
        se.setFactoryId(factoryId);
        se.setExceptionNumber(nextExceptionNumber(factoryId));
        se.setExceptionType(SalesExceptionType.OVER_SHIP_BLOCKED);
        se.setStatus(SalesException.STATUS_BLOCKED);

        if (record != null) {
            se.setDeliveryRecordId(record.getId());
            se.setDeliveryNumber(record.getDeliveryNumber());
            se.setSalesOrderId(record.getSalesOrderId());
        }
        if (item != null) {
            se.setDeliveryItemId(item.getId());
            se.setProductTypeId(item.getProductTypeId());
            se.setProductName(item.getProductName());
            se.setUnit(item.getUnit());
            se.setPlannedQuantity(item.getDeliveredQuantity());
        }
        se.setAttemptedQuantity(actual);
        se.setMaxAllowedQuantity(maxAllowed);
        se.setLimitRate(limitRate);
        se.setLimitSource(limitSource);
        se.setAttemptedBy(userId);
        se.setAttemptedAt(LocalDateTime.now());

        SalesException saved = salesExceptionRepository.saveAndFlush(se);
        log.warn("SALES_OVERSHIP_BLOCKED: 异常单 {} 工厂={} 发货单={} 行={} 计划={} 尝试实发={} "
                        + "上限={} 来源={} 操作人={}",
                saved.getExceptionNumber(), factoryId,
                record == null ? null : record.getDeliveryNumber(),
                item == null ? null : item.getId(),
                se.getPlannedQuantity(), actual, maxAllowed, limitSource, userId);
        return saved;
    }

    // ─── listExceptions ───────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<SalesException> listExceptions(String factoryId, String deliveryRecordId) {
        if (deliveryRecordId != null && !deliveryRecordId.isBlank()) {
            return salesExceptionRepository
                    .findByFactoryIdAndDeliveryRecordIdOrderByAttemptedAtDesc(factoryId, deliveryRecordId);
        }
        return salesExceptionRepository.findByFactoryIdOrderByAttemptedAtDesc(factoryId);
    }

    // ─── 取号 ─────────────────────────────────────────────────────────────

    /**
     * 生成下一个单号 {@code SE-<factory>-<yyyyMMdd>-<0001>}。
     *
     * <h2>🔴 它是怎么绕开本仓的发号器陷阱的</h2>
     * 本仓软删全局生效，「这个号被占用了吗」的 JPQL 一律被答成
     * 「有没有一行<b>活着的</b>记录用了它」——57 个发号器里 20 个坏在这一条上。
     * 这里用三件事一起避开：
     * <ol>
     *   <li><b>唯一索引是全量的</b>（迁移里 ⛔ 不带 {@code WHERE deleted_at IS NULL}）
     *       ⇒ 软删的行仍然占着它的号，号永不回收。</li>
     *   <li><b>取号查询是 native SQL</b>（{@code @Where} 不作用于 native）
     *       ⇒ 读到的是物理表的真实最大号，含被软删的行。</li>
     *   <li><b>看「最大号」而不是「有几条」</b> ⇒ 不受 status / 软删导致的计数下降影响。
     *       采购侧 {@code countPendingByFactoryId() + 1} 正是坏在这里：
     *       一张异常单被决策成 RESOLVED，下一个号就退回去撞上已存在的号。</li>
     * </ol>
     * 三条都做完，剩下的只有并发同时读到同一个 max —— 那一格交给唯一索引 + 重试
     * （见 {@link #MAX_NUMBER_RETRIES}）。<b>唯一索引才是最终裁判，查询只是省重试次数。</b>
     */
    String nextExceptionNumber(String factoryId) {
        String factorySegment = factoryId == null ? "NA"
                : (factoryId.length() > FACTORY_SEGMENT_MAX
                        ? factoryId.substring(0, FACTORY_SEGMENT_MAX) : factoryId);
        String prefix = NUMBER_PREFIX + "-" + factorySegment + "-" + LocalDateTime.now().format(DAY) + "-";

        List<String> highest = salesExceptionRepository
                .findHighestNumberForPrefix(factoryId, prefix + "%");
        long next = 1L;
        if (highest != null && !highest.isEmpty() && highest.get(0) != null) {
            Matcher m = TRAILING_SEQ.matcher(highest.get(0));
            if (m.find()) {
                // ⚠️ parseLong 而不是 parseInt：单号是 VARCHAR(50)，别让一条脏数据把取号打崩。
                //    解析不出来就从 1 开始 —— 唯一索引会把真正的撞号挡住并触发重试。
                try {
                    next = Long.parseLong(m.group(1)) + 1L;
                } catch (NumberFormatException ignored) {
                    log.warn("SALES_EXCEPTION_NUMBER_UNPARSEABLE: factoryId={}, 历史最大号={}, 从 1 重新起号",
                            factoryId, highest.get(0));
                }
            }
        }
        return prefix + String.format("%04d", next);
    }
}
