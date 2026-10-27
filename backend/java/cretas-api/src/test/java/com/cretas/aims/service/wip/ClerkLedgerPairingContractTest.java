package com.cretas.aims.service.wip;

import com.cretas.aims.service.processentry.IntermediateOwnershipGuard.Owner;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import com.cretas.aims.service.yield.impl.YieldReportServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 逐道录入（clerk）这一族的进出必须<b>成对</b>记账，并且 WIP 过账只许有<b>一份</b>权威实现。
 *
 * <h2>为什么这两件事在同一条测试里</h2>
 *
 * <p>它们是 PR#2870 明确<b>登记为「没修」</b>的两条，而且 PR#2870 写清了它们不能分开做：
 *
 * <blockquote>
 * {@code postClerkOutput} 的 IN 与 {@code consumeClerkSemi(Strict)} 的 OUT
 * <b>必须成对修</b> —— 只补 IN 会让该行族出现「进 10 出 0 而余额说领了 5」的
 * <b>可见矛盾</b>，比空账更糟。
 * </blockquote>
 *
 * <p>所以这里的核心断言不是「有没有写流水」，而是<b>对账不变式</b>：
 * <pre>ΣIN + ΣOUT（OUT 为负） == 库存行的 availableQuantity</pre>
 * 只补一侧 ⇒ 这条恒等式当场破，测试红。它守的是<b>结果</b>，不是某一行源码写没写。
 *
 * <h2>生产实测（F006 {@code cretas_prod_db}，只读，2026-08-18）</h2>
 *
 * <pre>
 * semi_finished_inventory 里 CLK-SEMI* 的行:  2 行, produced 合计 12.00
 * 这两行名下的 IN 流水:                        <b>0 行</b>
 * </pre>
 *
 * <h2>第二件：WIP 过账的<b>单一权威出口</b></h2>
 *
 * <p>{@code YieldReportServiceImpl} 里曾经有一份完整的 WIP 过账拷贝
 * （{@code upsertProducedWip} / {@code consumeSourceWip}）。取证结论是它<b>整簇不可达</b>：
 * 那几个方法都是 private，而全文件搜下来一个调用点都没有 —— 过账早就收敛到
 * {@code WipInventoryService.postApprovedOutput} 了，旧拷贝没删。
 *
 * <p>它比「两份都在跑」更坏：两份都在跑至少会漂给你看，这一份<b>看起来像权威但永远不执行</b>，
 * 谁改到它上面，都会做完 TDD + 变异全绿而生产上一行都没变。所以这里钉的是
 * <b>它不许再长回来</b>，判据走<b>反射读编译后的类结构</b>（⛔ 不是 grep 源码文本 ——
 * 文本匹配会把注释和 javadoc 里提到的名字也数进去）。
 */
@DisplayName("逐道录入：进出成对记账 + WIP 过账只许一份权威实现")
class ClerkLedgerPairingContractTest {

    private static final String FACTORY = "F006";
    private static final String ANCHOR = "CLK-SEMI-61cb04bf-RMT_2ac4";

    // ────────────────────────── 装置 ──────────────────────────

    /** 把 clerk 三个入口需要的仓储装上，并把落下来的流水行收集起来。 */
    private static final class Rig {
        final SemiFinishedInventoryRepository wipRepo = mock(SemiFinishedInventoryRepository.class);
        final SemiFinishedInventoryTransactionRepository txnRepo =
                mock(SemiFinishedInventoryTransactionRepository.class);
        final ProductTypeRepository productTypeRepo = mock(ProductTypeRepository.class);
        final List<SemiFinishedInventoryTransaction> ledger = new ArrayList<>();
        final WipInventoryServiceImpl svc;
        SemiFinishedInventory row;

        Rig(SemiFinishedInventory seed) {
            this.row = seed;
            svc = new WipInventoryServiceImpl(
                    wipRepo, txnRepo, mock(ProductionReportRepository.class),
                    mock(BatchLineageEdgeRepository.class),
                    mock(WorkProcessTaskRepository.class), mock(WorkProcessRepository.class),
                    productTypeRepo,
                    mock(ProductionWorkflowInstanceRepository.class),
                    mock(WorkflowTaskPortRepository.class),
                    mock(ProductFamilyResolver.class),
                    mock(ApplicationEventPublisher.class), org.mockito.Mockito.mock(com.cretas.aims.service.processentry.IntermediateOwnershipGuard.class));

            lenient().when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                    anyString(), anyString())).thenAnswer(i -> Optional.ofNullable(row));
            lenient().when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(i -> i.getArgument(0));
            lenient().when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(i -> {
                SemiFinishedInventoryTransaction t = i.getArgument(0);
                t.setId(7000L + ledger.size());
                ledger.add(t);
                return t;
            });
        }

        List<SemiFinishedInventoryTransaction> ofType(String type) {
            return ledger.stream().filter(t -> type.equals(t.getTxnType())).collect(Collectors.toList());
        }

        /** ΣIN + ΣOUT（OUT 存负量）—— 流水账自己算出来的余额。 */
        BigDecimal ledgerBalance() {
            return ledger.stream()
                    .filter(t -> !SemiFinishedInventoryTransaction.TxnType.REVERSE.equals(t.getTxnType()))
                    .map(SemiFinishedInventoryTransaction::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private static SemiFinishedInventory emptyRow(String unit) {
        return SemiFinishedInventory.builder()
                .id(344L).factoryId(FACTORY).intermediateBatchNo(ANCHOR)
                .productTypeId("RMT_2ac4")
                .producedQuantity(BigDecimal.ZERO)
                .consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(BigDecimal.ZERO)
                .adjustmentQuantity(BigDecimal.ZERO)
                .unit(unit)
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
    }

    // ────────────────────────── ① 成对记账（真实入口） ──────────────────────────

    @Test
    @DisplayName("进 10 出 4 —— 流水账自己算出来的余额必须等于库存行的余额（对账不变式）")
    void clerkInAndOutMustBothLandInLedgerAndReconcile() {
        Rig rig = new Rig(emptyRow("kg"));

        // 真实入口, 不是 helper: 逐道录入产出 10 → 下游领 4
        rig.svc.postClerkOutput(FACTORY, ANCHOR, "RMT_2ac4",
                new BigDecimal("10"), "kg", new BigDecimal("3.00"), null, 2);
        rig.svc.consumeClerkSemi(FACTORY, ANCHOR, new BigDecimal("4"));

        List<SemiFinishedInventoryTransaction> ins = rig.ofType(SemiFinishedInventoryTransaction.TxnType.IN);
        List<SemiFinishedInventoryTransaction> outs = rig.ofType(SemiFinishedInventoryTransaction.TxnType.OUT);

        assertThat(ins).as("产出入库必须留下 IN 流水 —— 只改余额 = 半成品凭空出现").hasSize(1);
        assertThat(outs).as("下游领用必须留下 OUT 流水 —— 只补 IN 会造出"
                + "「进 10 出 0 而余额说领了 4」的可见矛盾, 比空账更糟").hasSize(1);

        assertThat(ins.get(0).getQuantity())
                .as("IN 按实体 javadoc 记正数量").isEqualByComparingTo(new BigDecimal("10"));
        assertThat(outs.get(0).getQuantity())
                .as("OUT 按实体 javadoc 记负数量").isEqualByComparingTo(new BigDecimal("-4"));

        // 🔴 这一条才是本测试的核心: 守的是对账结果, 不是「某一行源码写没写」。
        assertThat(rig.ledgerBalance())
                .as("对账不变式: ΣIN + ΣOUT 必须等于库存行余额。"
                        + "只补一侧 ⇒ 这条恒等式当场破")
                .isEqualByComparingTo(rig.row.getAvailableQuantity());
        assertThat(rig.row.getAvailableQuantity()).isEqualByComparingTo(new BigDecimal("6"));
    }

    @Test
    @DisplayName("OUT 记的是【实际扣减量】而不是【请求量】—— 超扣被 clamp 时流水不许说谎")
    void clampedConsumptionLedgersTheActualDeductionNotTheRequest() {
        Rig rig = new Rig(emptyRow("kg"));
        rig.svc.postClerkOutput(FACTORY, ANCHOR, "RMT_2ac4",
                new BigDecimal("10"), "kg", new BigDecimal("3.00"), null, 2);

        // 只有 10, 却要领 25 → consumeClerkSemi 把 consumed clamp 到 produced ⇒ 实际只扣掉 10。
        rig.svc.consumeClerkSemi(FACTORY, ANCHOR, new BigDecimal("25"));

        List<SemiFinishedInventoryTransaction> outs = rig.ofType(SemiFinishedInventoryTransaction.TxnType.OUT);
        assertThat(outs).hasSize(1);
        assertThat(outs.get(0).getQuantity())
                .as("clamp 之后真正扣掉的是 10, 不是请求的 25。"
                        + "按请求量记 = 账本自己在说一个没发生的数")
                .isEqualByComparingTo(new BigDecimal("-10"));

        assertThat(rig.ledgerBalance())
                .as("clamp 之后对账不变式仍须成立")
                .isEqualByComparingTo(rig.row.getAvailableQuantity());
        assertThat(rig.row.getAvailableQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("盒装来源：OUT 记折算后的【盒数】，不是入参的 kg 投料量")
    void strictConsumptionLedgersConvertedQuantityForCountUnitSources() {
        SemiFinishedInventory row = emptyRow("盒");
        Rig rig = new Rig(row);
        ProductType boxed = new ProductType();
        boxed.setId("RMT_2ac4");
        boxed.setGramsPerUnit(new BigDecimal("500"));   // 每盒 500g
        when(rig.productTypeRepo.findById("RMT_2ac4")).thenReturn(Optional.of(boxed));

        // 入库 10 盒
        rig.svc.postClerkOutput(FACTORY, ANCHOR, "RMT_2ac4",
                new BigDecimal("10"), "盒", new BigDecimal("3.00"), null, 2);
        // 投 2 kg，每盒 500g ⇒ 折算 4 盒
        BigDecimal deducted = rig.svc.consumeClerkSemiStrict(FACTORY, Owner.COMPANY, ANCHOR, new BigDecimal("2"));

        assertThat(deducted).as("返回的是来源原生单位的实际扣减量 (盒)")
                .isEqualByComparingTo(new BigDecimal("4"));

        List<SemiFinishedInventoryTransaction> outs = rig.ofType(SemiFinishedInventoryTransaction.TxnType.OUT);
        assertThat(outs).as("严格投料同样必须留下 OUT 流水").hasSize(1);
        assertThat(outs.get(0).getQuantity())
                .as("记折算后的盒数 (−4), ⛔ 不是入参的 kg 数 (−2) —— "
                        + "否则流水与库存行用两个不同单位的数, 对不了账")
                .isEqualByComparingTo(new BigDecimal("-4"));

        assertThat(rig.ledgerBalance())
                .as("盒⇄kg 折算之后对账不变式仍须成立 (全部以盒计)")
                .isEqualByComparingTo(rig.row.getAvailableQuantity());
    }

    @Test
    @DisplayName("余额快照读的是【改完之后】的可用量 —— 口径放错边会落负数余额")
    void ledgerBalanceAfterReflectsPostUpdateAvailableQuantity() {
        Rig rig = new Rig(emptyRow("kg"));
        rig.svc.postClerkOutput(FACTORY, ANCHOR, "RMT_2ac4",
                new BigDecimal("10"), "kg", new BigDecimal("3.00"), null, 2);
        rig.svc.consumeClerkSemi(FACTORY, ANCHOR, new BigDecimal("4"));

        assertThat(rig.ofType(SemiFinishedInventoryTransaction.TxnType.IN).get(0).getBalanceAfter())
                .as("入库后余额 10").isEqualByComparingTo(new BigDecimal("10"));
        assertThat(rig.ofType(SemiFinishedInventoryTransaction.TxnType.OUT).get(0).getBalanceAfter())
                .as("领用后余额 6。2026-08-17 就是把构造点放错边, 生产上落过一条 balance_after=-2.000000")
                .isEqualByComparingTo(new BigDecimal("6"));

        for (SemiFinishedInventoryTransaction t : rig.ledger) {
            assertThat(t.getBalanceAfter())
                    .as("任何一条流水的余额快照都不许为负").isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
    }

    @Test
    @DisplayName("🔴 clerk 的 OUT sourceRef 不许撞上整单撤回 G1 守卫的精确相等")
    void clerkOutSourceRefMustNotCollideWithDownstreamConsumedGuard() {
        // G1 (existsDownstreamConsumed) 判的是 sourceRef == String.valueOf(batchId)
        // AND txnType='OUT' AND sourceType IN ('SECONDARY_CONSUME','TRANSFER_OUT')。
        // clerk 的 OUT 正好是 SECONDARY_CONSUME ⇒ 只剩 sourceRef 这一道保险。
        // 写成裸批次号就会【凭空】把可以撤回的批次一律拦成 409 DOWNSTREAM_CONSUMED。
        Rig rig = new Rig(emptyRow("kg"));
        rig.svc.postClerkOutput(FACTORY, ANCHOR, "RMT_2ac4",
                new BigDecimal("10"), "kg", new BigDecimal("3.00"), null, 2);
        rig.svc.consumeClerkSemi(FACTORY, ANCHOR, new BigDecimal("4"));

        SemiFinishedInventoryTransaction out =
                rig.ofType(SemiFinishedInventoryTransaction.TxnType.OUT).get(0);
        assertThat(out.getSourceRef())
                .as("必须带 clerk 前缀, 才与 G1 的精确相等永不相撞")
                .startsWith(WipLedgerEntries.CLERK_OUT_REF_PREFIX);
        assertThat(out.getSourceRef().matches("\\d+"))
                .as("sourceRef 一旦是纯数字就可能等于某个 batchId ⇒ 撞上 G1")
                .isFalse();

        SemiFinishedInventoryTransaction in =
                rig.ofType(SemiFinishedInventoryTransaction.TxnType.IN).get(0);
        assertThat(in.getSourceRef()).startsWith(WipLedgerEntries.CLERK_IN_REF_PREFIX);
        assertThat(in.getReportId())
                .as("逐道录入没有报工 —— reportId 诚实留空, ⛔ 不许编一个指向不存在报工的 id")
                .isNull();
    }

    @Test
    @DisplayName("阴性对照：产出 0 / 领用 0 不凭空造流水")
    void zeroQuantitiesProduceNoLedgerRows() {
        Rig rig = new Rig(emptyRow("kg"));
        rig.svc.postClerkOutput(FACTORY, ANCHOR, "RMT_2ac4",
                BigDecimal.ZERO, "kg", new BigDecimal("3.00"), null, 2);
        rig.svc.consumeClerkSemi(FACTORY, ANCHOR, BigDecimal.ZERO);

        assertThat(rig.ledger)
                .as("0 量不该留下任何流水行 —— 空转也记账会把对账噪声做进去")
                .isEmpty();
    }

    // ────────────────────────── ② WIP 过账只许一份权威实现 ──────────────────────────

    @Test
    @DisplayName("WIP 过账的权威实现只许有一份 —— YieldReportServiceImpl 不许再长出第二份拷贝")
    void wipPostingMustHaveExactlyOneAuthority() {
        // 判据走【反射读编译后的类结构】, ⛔ 不是 grep 源码文本:
        // 文本匹配会把 javadoc / 注释里提到的方法名也数进去 (本仓栽过三次同形)。
        Set<String> yieldMethods = Arrays.stream(YieldReportServiceImpl.class.getDeclaredMethods())
                .map(Method::getName).collect(Collectors.toSet());
        Set<String> wipMethods = Arrays.stream(WipInventoryServiceImpl.class.getDeclaredMethods())
                .map(Method::getName).collect(Collectors.toSet());

        // 阳性对照: 反射探针确实读得到方法名。少了这一条,
        // 「yieldMethods 里没有 upsertProducedWip」分不清是真没有还是探针读出了空集。
        assertThat(yieldMethods)
                .as("阳性对照: 反射读得到 YieldReportServiceImpl 的方法。"
                        + "这一条红 ⇒ 探针坏了, 下面那条否定断言无意义")
                .contains("submitReport");
        assertThat(wipMethods)
                .as("阳性对照: 权威实现确实在 WipInventoryServiceImpl 上")
                .contains("upsertProducedWip", "consumeSourceWip", "postApprovedOutput");

        // 本体: 第二份拷贝不许回来。
        assertThat(yieldMethods)
                .as("WIP 过账已收敛到 WipInventoryService.postApprovedOutput。"
                        + "YieldReportServiceImpl 里那份是【不可达】的死拷贝 —— 它看起来像权威但永远不执行, "
                        + "谁改到它上面都会做完 TDD + 变异全绿而生产上一行都没变")
                .doesNotContain("upsertProducedWip", "consumeSourceWip", "recordWipLineageEdge");
    }
}
