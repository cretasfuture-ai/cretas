package com.cretas.aims.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸：`@Version` 是若干条业务链<b>唯一</b>的并发保护，不许被摘掉。
 *
 * <h4>为什么需要这道闸</h4>
 *
 * <p>2026-08-27 验「重复审批/并发提交不能重复调整库存」时查清楚的机制：</p>
 *
 * <p>盘点「应用差异」({@code FactoryStocktakeServiceImpl.apply}) 是 {@code @Transactional} 的，
 * 里面先读单据、判 {@code status == APPROVED}、再逐条改库存。
 * 两个并发请求会<b>同时读到 APPROVED</b>，两个都跑完库存调整 ——
 * 应用层那句 {@code if (status == APPLIED) throw} 拦不住它们，因为两边读的都是旧值。</p>
 *
 * <p>真正拦住的是 JPA {@code @Version}：Hibernate 提交时发的是
 * {@code UPDATE ... WHERE id = ? AND version = ?}，先提交的把 version 推到 N+1，
 * 后提交的命中 <b>0 行</b> → {@code OptimisticLockException} → <b>整个事务回滚，
 * 包括它已经做的那些库存调整</b>。</p>
 *
 * <p>⇒ 这个保护<b>不是应用代码给的，是数据库给的</b>。而 {@code assertExpectedVersion}
 * 那句是 {@code if (expectedVersion != null && ...)} —— <b>传 null 就跳过</b>，
 * 它只是让用户早点看到「请刷新」，⛔ 不是并发保护。</p>
 *
 * <h4>它会怎么复发</h4>
 *
 * <p>某天有人被「乐观锁老是冲突」的报错烦到，把注解删了。
 * 🔴 <b>所有测试照样绿</b> —— 单测是串行的，摘掉 {@code @Version} 一条都不会红。
 * 而并发保护从此无声消失，症状要等到某次双击把盘盈过账两遍才出现，
 * 那时已经是账实不符，且查不出是哪一笔。</p>
 *
 * <p>这正是「注释挡不住的，写成闸」：本类就是那道闸。</p>
 *
 * <h4>两层</h4>
 *
 * <ul>
 *   <li><b>具名关键集</b>：丢了它就会<b>重复过账</b>的实体，逐个点名，少一个就红。</li>
 *   <li><b>全局棘轮</b>：{@code @Version} 的总数只许涨不许跌 ——
 *       关键集之外的实体也在靠它，只是后果没那么直接。</li>
 * </ul>
 */
@DisplayName("@Version 是并发保护的唯一来源 —— 不许被摘掉")
class OptimisticLockConcurrencyGuardContractTest {

    private static final Path ENTITY_ROOT = Paths.get("src/main/java/com/cretas/aims/entity");

    /**
     * 摘掉 {@code @Version} 会导致<b>并发下重复过账</b>的实体 —— 每条写清楚后果。
     *
     * <p>⛔ 不许因为「锁冲突太烦」从这里删条目。冲突是保护在起作用，
     * 要治的是让两个人同时点同一张单的那个交互。</p>
     */
    private static final Map<String, String> CONCURRENCY_CRITICAL = Map.of(
            "factory/FactoryStocktake.java",
            "盘点「应用差异」并发双击 → 盘盈/盘亏被过账两遍, 账实不符且事后查不出是哪一笔",

            "inventory/PurchaseOrder.java",
            "采购订单并发审批 → 重复入库/重复计应付",

            "inventory/SalesOrder.java",
            "销售订单并发审批 → 重复出库/重复计应收",

            "workflow/ApprovalWorkflowInstance.java",
            "审批实例并发推进 → 同一节点被走两次, 审批链状态错乱",

            "MaterialBatch.java",
            "原料批次并发改数量 → 后写覆盖先写(lost update), 库存凭空多出或少掉",

            "inventory/FinishedGoodsBatch.java",
            "成品批次并发改数量 → 同上"
    );

    /**
     * 2026-08-27 实测基线：全 entity 树共 56 个类带 {@code @Version}。
     *
     * <p>⚠️ 这是<b>下界</b>不是等号 —— 新实体加锁是好事, 不该因此变红。</p>
     */
    private static final int VERSIONED_ENTITIES_BASELINE = 56;

    private static Set<String> entitiesWithVersion() throws IOException {
        try (Stream<Path> paths = Files.walk(ENTITY_ROOT)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return hasVersionAnnotation(p);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(p -> ENTITY_ROOT.relativize(p).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /**
     * 逐行剔注释再判。
     *
     * <p>🔴 不剔注释这道闸从第一天起就是假的 —— 本文件自己的 javadoc 里就逐字写着
     * {@code @Version}，而同类闸在本仓已经因为「把自己写的注释当被测对象」栽过多次。</p>
     */
    private static boolean hasVersionAnnotation(Path p) throws IOException {
        for (String line : Files.readString(p, StandardCharsets.UTF_8).split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                continue;
            }
            int slash = line.indexOf("//");
            String code = slash >= 0 ? line.substring(0, slash) : line;
            if (code.trim().equals("@Version") || code.trim().startsWith("@Version ")) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("阳性对照: 扫描器确实找得到带锁的实体")
    void scannerActuallyFindsVersionedEntities() throws IOException {
        Set<String> found = entitiesWithVersion();
        assertThat(found)
                .as("一个都扫不到 —— 扫描器坏了(路径? 剔注释逻辑?), 后面的绿不作数")
                .isNotEmpty();
        assertThat(found)
                .as("这个是 2026-08-27 已知带 @Version 的实体; 扫不到说明扫描器坏了")
                .contains("factory/FactoryStocktake.java");
    }

    @Test
    @DisplayName("关键实体的 @Version 一个都不许少")
    void concurrencyCriticalEntitiesKeepTheirLock() throws IOException {
        Set<String> versioned = entitiesWithVersion();
        Set<String> missing = new TreeSet<>(CONCURRENCY_CRITICAL.keySet());
        missing.removeAll(versioned);
        assertThat(missing)
                .as("这些实体的 @Version 是并发下**唯一**的重复过账保护。"
                        + "摘掉它单测一条都不会红, 但并发双击会把库存改两遍。"
                        + "后果逐个写在 CONCURRENCY_CRITICAL 里 —— 真要动, 先说服 Owner")
                .isEmpty();
    }

    @Test
    @DisplayName("名单不许烂掉: 登记的实体文件必须还存在")
    void registryHasNoStaleEntries() {
        Set<String> gone = new TreeSet<>();
        for (String name : CONCURRENCY_CRITICAL.keySet()) {
            if (!Files.exists(ENTITY_ROOT.resolve(name))) gone.add(name);
        }
        assertThat(gone)
                .as("这些实体文件已经不在了(改名? 删了?) —— 请同步更新 CONCURRENCY_CRITICAL, "
                        + "否则名单会静默地守着一个不存在的东西")
                .isEmpty();
    }

    @Test
    @DisplayName("棘轮: 带锁的实体总数只许涨")
    void versionedEntityCountOnlyGrows() throws IOException {
        assertThat(entitiesWithVersion())
                .as("带 @Version 的实体变少了 —— 有人摘了锁。"
                        + "⛔ 「锁冲突太烦」不是摘锁的理由: 冲突正是保护在起作用。"
                        + "确实该摘时, 先把 VERSIONED_ENTITIES_BASELINE 一起调下来并写明为什么")
                .hasSizeGreaterThanOrEqualTo(VERSIONED_ENTITIES_BASELINE);
    }
}
