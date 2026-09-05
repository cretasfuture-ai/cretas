package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.yield.PendingInterimFeedService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PendingInterimFeedService} 实现 — 解析待小结逐工序行的 {@code upstreamSources},
 * 聚合待扣 SFI 投料量 (来源原生单位), 与 {@code InterimSettleServiceImpl} §②A 的 strict 扣减集对齐。
 *
 * <p>只读; 折算失败 (诚实 null) 跳过不减。见接口 Javadoc 的口径论证。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingInterimFeedServiceImpl implements PendingInterimFeedService {

    private final ProcessSheetRowRepository rowRepository;
    private final WipInventoryService wipInventoryService;
    private final ObjectMapper objectMapper;
    /** 只用来回答「这一行的计划还结不结得掉」—— 见 {@link #reachablePlanStatuses}。 */
    private final ProductionPlanRepository planRepository;

    /**
     * factoryId → 上一次<b>喊出去过</b>的异常指纹。见 {@link #shouldAnnounce}。
     *
     * <p>容量上界 = 租户数 (个位数), 且异常清零时会自行删除条目, 不会无限增长。
     * 用 {@code ConcurrentHashMap}: 快照是只读查询, 多个请求线程会并发进来。
     *
     * <p>⚠️ 有初始化器的 final 字段不进 {@code @RequiredArgsConstructor} 生成的构造 ——
     * 构造签名仍是四参, 调用方不受影响。
     */
    private final Map<String, String> lastAnnouncedFingerprint = new ConcurrentHashMap<>();

    @Override
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> pendingSemiFeedByBatchNo(String factoryId) {
        return pendingSemiFeedSnapshot(factoryId).pendingByBatchNo();
    }

    @Override
    @Transactional(readOnly = true)
    public Snapshot pendingSemiFeedSnapshot(String factoryId) {
        Map<String, BigDecimal> out = new HashMap<>();
        Set<String> unresolved = new HashSet<>();
        List<ProcessSheetRow> rows = rowRepository.findUnsettledStockFeedRows(factoryId);

        // 🔴🔒 幽灵预扣根修 (BUG-GHOST-PENDING-FEED-DELETED-PLANS, 2026-09-06):
        //   findUnsettledStockFeedRows 的谓词只问「这一行结了没有」, 从来不问「这一行还结不结得掉」。
        //   计划被删掉 (硬删/软删) 或已取消之后, 小结与结单两条打戳路径都够不着它 ⇒
        //   interim_settled_at 永久 IS NULL ⇒ 它贡献的待扣量【永远不会清】, 而没有任何操作能释放,
        //   于是那个批次的可用量被永久压低。这不是"暂时预扣", 是账面漏洞。
        //   ⇒ 在这里 (唯一的遍历点, 三个消费方都从这儿拿数) 一次性剔掉, 并把剔了什么留痕。
        Map<String, ProductionPlanStatus> reachablePlans = reachablePlanStatuses(factoryId, rows);
        int orphanRows = 0;
        int cancelledRows = 0;
        Set<String> orphanPlanIds = new HashSet<>();
        Set<String> cancelledPlanIds = new HashSet<>();
        Set<String> anomalousTerminalPlanIds = new HashSet<>();

        for (ProcessSheetRow row : rows) {
            String planId = row.getPlanId();
            //   ① 计划够不着 (硬删: 没有行; 软删: @Where 挡掉) —— 两条结算路径都 load 不到它。
            if (planId == null || !reachablePlans.containsKey(planId)) {
                orphanRows++;
                if (planId != null) {
                    orphanPlanIds.add(planId);
                }
                continue;
            }
            ProductionPlanStatus planStatus = reachablePlans.get(planId);
            //   ② CANCELLED —— 小结显式拒绝 (InterimSettleServiceImpl 计划状态守卫), 结单只收
            //      IN_PROGRESS/PENDING (validateSettlementRequest) ⇒ 同样永远盖不上戳。
            //      且取消审批路径 (reverseBatchReportsForApprovedCancel) 已把库存冲销恢复,
            //      再算一次待扣 = 对同一笔货预扣两遍。
            if (planStatus == ProductionPlanStatus.CANCELLED) {
                cancelledRows++;
                cancelledPlanIds.add(planId);
                continue;
            }
            //   ③ COMPLETED / PENDING_APPROVAL 上还有未结行 —— ⛔【不剔】, 只记账。
            //      结单会在同一事务里给本计划全部未结行盖戳, 所以出现未结行只有两种可能:
            //      (a) 盖戳漏了 → 是缺陷, 剔掉就把它藏起来了;
            //      (b) 被撤回冲销后重新变回待结 → 待扣是【真的】(货已退回库存等重结), 剔掉就是幻库存。
            //      两种都不该静默丢, 所以照常计入待扣, 只把计划 id 喊出来。
            if (planStatus == ProductionPlanStatus.COMPLETED
                    || planStatus == ProductionPlanStatus.PENDING_APPROVAL) {
                anomalousTerminalPlanIds.add(planId);
            }

            ProcessSheetRowRequest req = parse(row.getRowPayload());
            if (req == null || req.getUpstreamSources() == null) {
                continue;
            }
            for (ProcessSheetRowRequest.UpstreamRef ref : req.getUpstreamSources()) {
                // 只覆盖引用常驻 SFI 的 strict 投料 (与小结 §②A consumeClerkSemiStrict 一致)。
                // 其余 (in-plan 在制 WIP / FG 投料 / 计划自身前序小结半成品的 anchor 容忍扣减) 不减 (见接口 Javadoc)。
                if (!ref.isSemiFinished()) {
                    continue;
                }
                // 🔴 2026-09-06: 回料 (手工选的 / 按配比自动追加的) 在【正式报工事务内】就已经严格扣减过了
                //    (ProcessSheetServiceImpl#consumeReworkInputs → consumeClerkSemiStrict), 行上打的标记就是
                //    stockConsumedAtSubmit=true。而这一行仍然 interim_settled_at IS NULL, 所以照样落进
                //    findUnsettledStockFeedRows 的谓词里 —— 把它算成「待扣」= 把同一笔货扣两遍。
                //
                //    ⚠️ 这是「同一个东西有三份」漂出来的第三份: 两条真正的扣减路径都跳过它 ——
                //      InterimSettleServiceImpl:293 (小结) 与 ProductionPlanServiceImpl:6830 (结单) ——
                //      只有这个聚合器没跟上。后果在盘点侧是把账面多减一遍 → 仓管诚实数出来的反而"盘盈",
                //      在投料下拉侧 (2026-09 新增的消费方) 是把可领量凭空压低。
                //    ⛔ 改这里之前先看那两处: 三处必须同一个谓词。
                if (ref.isStockConsumedAtSubmit()) {
                    continue;
                }
                String srcBatchNo = ref.getSourceBatchNumber();
                BigDecimal feed = ref.getFeedQuantityKg();
                if (srcBatchNo == null || feed == null || feed.signum() <= 0) {
                    continue;
                }
                // 折算 kg 投料 → 来源原生单位 (盒装折盒数; kg 源原值), 与 strict 扣减折算同源。
                //   诚实 null (批次缺失 / 盒装缺每盒克重) → 跳过不减: 此时小结 strict 扣减亦 loud-fail 无法扣,
                //   货实际不离库, 不减 = 账面=实物 口径一致 (强减反而造假差异)。
                //   🔴 但「不减」这件事本身要留痕 —— 记进 unresolved, 否则下游分不清「没有待扣」和「算不出来」。
                BigDecimal qtyInSourceUnit =
                        wipInventoryService.resolveSemiFeedQtyInSourceUnit(factoryId, srcBatchNo, feed);
                if (qtyInSourceUnit == null || qtyInSourceUnit.signum() <= 0) {
                    if (qtyInSourceUnit == null) {
                        unresolved.add(srcBatchNo);
                        log.debug("[pending-feed] SFI 折算诚实 null 跳过 (待扣量【未知】, 不是 0): factory={}, sfi={}, feedKg={}",
                                factoryId, srcBatchNo, feed);
                    }
                    continue;
                }
                out.merge(srcBatchNo, qtyInSourceUnit, BigDecimal::add);
            }
        }

        UnsettlablePlans excluded = new UnsettlablePlans(
                orphanRows, Set.copyOf(orphanPlanIds),
                cancelledRows, Set.copyOf(cancelledPlanIds),
                Set.copyOf(anomalousTerminalPlanIds));
        // 🔴 「剔掉了 N 行」必须是个能读到的数, 否则静默剔除与「本来就没有这些行」完全同形
        //    (验证「X 没发生」要配一个「Y 一定会发生」的读数)。⛔ 不许无条件打「(空)」之类的硬编码
        //    结论, 下面每个数都是实际算出来的。
        //
        // ⚠️ 但这一行 ⛔ 不能每次快照都打 (形态 E): 两个投料下拉每次加载 + 半成品盘点都走这里,
        //    而 prod 上 F006 那 2 行挂在 COMPLETED 计划上的异常是【长期存在】的 ⇒ 每次刷同一句话,
        //    最后被人默认忽略 —— 那时这道提示的覆盖率归零。
        //    ⇒ 【边沿触发】: 只在这个工厂的异常【指纹发生变化】时打 (见 shouldAnnounce)。
        //    ⛔ 没用时间窗节流 (DataScopeResolver.warnThrottled 那套) —— 时间窗会制造一个盲区:
        //    窗口内【新】出现的幽灵计划会被一起吞掉, 而那恰恰是唯一需要立刻知道的事件。
        //    指纹变了就立刻再喊, 一次都不漏; 没变就闭嘴。
        //    ⚠️ 节流只管【日志】—— 读数本身 (UnsettlablePlans) 每次都完整返回, 消费方与断言
        //    拿到的东西不受影响 (形态 E′: 收窄告警不能把「判断」一起收掉)。
        if (shouldAnnounce(factoryId, excluded)) {
            log.warn("[pending-feed] 待扣遍历剔除结不掉的行: factory={}, 扫描 {} 行, "
                            + "ghostRowsExcluded={} (计划够不着 {} 行 plans={}; 计划已取消 {} 行 plans={}), "
                            + "终态计划仍有未结行(未剔, 需人工核对) plans={}",
                    factoryId, rows.size(), excluded.totalExcludedRows(),
                    excluded.orphanRowCount(), excluded.orphanPlanIds(),
                    excluded.cancelledRowCount(), excluded.cancelledPlanIds(),
                    excluded.anomalousTerminalPlanIds());
        }
        return new Snapshot(Map.copyOf(out), Set.copyOf(unresolved), excluded);
    }

    /**
     * 这一次的异常读数值不值得再喊一遍 —— <b>边沿触发</b>, 不是每次都喊。
     *
     * <p>形态 E: 一道天天重复的提示, 最终结局是被人默认忽略, <b>那时它的覆盖率归零</b>。
     * 本方法记住每个工厂<b>上一次喊出去的那个指纹</b>, 指纹没变就只留 debug, 不再刷 WARN。
     *
     * <p>指纹 = 三个计划 id 集合 (排序后) + 两个行数。⚠️ <b>行数也进指纹</b>是有意的:
     * 同一批幽灵计划上行数从 1 涨到 50, 是爆炸半径变了, 值得再喊一次。
     *
     * <p>🔴 异常清掉之后<b>要把记录删掉</b> —— 否则它再次出现时会被当成「和上次一样」而不喊,
     * 那就把一个复发的缺陷永久静音了。(这正是 {@code isSilent()} 分支存在的全部理由,
     * ⛔ 不要图省事写成 {@code if (!isSilent() && shouldAnnounce(...))} —— 短路会跳过这次清理。)
     *
     * <p>⚠️ 只影响<b>日志</b>。{@link UnsettlablePlans} 每次都完整返回给调用方,
     * 断言与消费方读到的东西与节流无关 (形态 E′: 收窄告警不能把判断本身一起收掉)。
     */
    private boolean shouldAnnounce(String factoryId, UnsettlablePlans excluded) {
        if (excluded.isSilent()) {
            lastAnnouncedFingerprint.remove(factoryId);   // 清零 → 复发时能重新喊
            return false;
        }
        String fingerprint = excluded.orphanRowCount() + "|" + sorted(excluded.orphanPlanIds())
                + "|" + excluded.cancelledRowCount() + "|" + sorted(excluded.cancelledPlanIds())
                + "|" + sorted(excluded.anomalousTerminalPlanIds());
        String previous = lastAnnouncedFingerprint.put(factoryId, fingerprint);
        if (fingerprint.equals(previous)) {
            log.debug("[pending-feed] 幽灵行读数未变 (已喊过, 不再刷 WARN): factory={}, fingerprint={}",
                    factoryId, fingerprint);
            return false;
        }
        return true;
    }

    /** 集合进指纹前先定序 —— HashSet 的迭代序不稳, 会让同一个状态产生不同指纹从而反复喊。 */
    private static String sorted(Set<String> ids) {
        return new TreeSet<>(ids).toString();
    }

    /**
     * 取这批行涉及的计划里<b>还够得着</b>的那些的状态。
     *
     * <p>走 JPQL ⇒ {@code ProductionPlan} 上的 {@code @Where(deleted_at IS NULL)} 生效,
     * 软删计划不会回来; 硬删计划本来就没有行。⇒ <b>请求了却没回来的 id = 结算路径够不着的计划</b>,
     * 与 {@code settleProduction} / {@code interimSettle} 用 {@code findByIdAndFactoryId} 时看到的
     * 是同一个可见性口径 —— 这一点正是本方法唯一的用处, ⛔ 不要为了"顺便区分硬删/软删"改成 nativeQuery。
     *
     * <p>factory-scoped: 跨租户的 planId 不会解析成功 ⇒ 落进 orphan 被剔除并留痕 (fail-closed)。
     */
    private Map<String, ProductionPlanStatus> reachablePlanStatuses(
            String factoryId, List<ProcessSheetRow> rows) {
        Set<String> planIds = new HashSet<>();
        for (ProcessSheetRow row : rows) {
            if (row.getPlanId() != null) {
                planIds.add(row.getPlanId());
            }
        }
        if (planIds.isEmpty()) {
            return Map.of();   // IN () 在 JPQL 上不合法, 而且没行可查
        }
        Map<String, ProductionPlanStatus> byId = new HashMap<>();
        for (Object[] tuple : planRepository.findPlanStatusesByFactoryIdAndIdIn(factoryId, planIds)) {
            if (tuple == null || tuple.length < 2 || tuple[0] == null) {
                continue;
            }
            // status 可能为 null (历史行) —— 保留 key, 让它按「够得着但状态未知」处理 = 不剔除。
            byId.put((String) tuple[0], (ProductionPlanStatus) tuple[1]);
        }
        return byId;
    }

    private ProcessSheetRowRequest parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, ProcessSheetRowRequest.class);
        } catch (Exception e) {
            log.warn("[pending-feed] 无法解析 process_sheet_row payload: {}", e.getMessage());
            return null;
        }
    }
}
