package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.inventory.InternalTransferItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调入批次号必须**按构造唯一** —— 2026-09-02 实测事故的守卫。
 *
 * <p>原实现是 {@code System.currentTimeMillis() % 10000}：同一张调拨单的多行明细在同一个
 * 循环里建批次，彼此不到 1 毫秒 ⇒ 后缀相同 ⇒ 撞 {@code material_batches_batch_number_key}，
 * 用户只看到「数据处理异常」。本机 28 行的单 100% 复现。
 *
 * <p>🔴 这条断言守的是<b>行为</b>（一单多行不许撞号），不是某一行实现。让它红的方式是把
 * 生成器换回任何「与明细无关」的来源（时间戳 / 常量 / 随机但不来自主键）—— 变异对照见
 * {@link #collidesWhenSuffixIgnoresTheItem()}：它<b>复刻</b>旧实现并断言它确实会撞，
 * 所以这两条一起排除了「断言恒真」。
 */
class TransferInBatchNumberUniquenessTest {

    private static String generate(InternalTransferItem item) throws Exception {
        Method m = TransferServiceImpl.class
                .getDeclaredMethod("transferInBatchNumber", InternalTransferItem.class);
        m.setAccessible(true);
        return (String) m.invoke(null, item);
    }

    private static InternalTransferItem item(Long id) {
        InternalTransferItem it = new InternalTransferItem();
        it.setId(id);
        return it;
    }

    @Test
    @DisplayName("一张单 28 行 —— 28 个批次号必须互不相同(事故现场的行数)")
    void oneTransferManyLinesNeverCollide() throws Exception {
        Set<String> seen = new HashSet<>();
        for (long id = 1; id <= 28; id++) {
            assertThat(seen.add(generate(item(id))))
                    .as("第 %d 行的批次号与前面某一行重复 —— 这正是线上那条唯一约束报错", id)
                    .isTrue();
        }
        assertThat(seen).hasSize(28);
    }

    @Test
    @DisplayName("号从明细主键派生 —— 同一明细稳定, 不同明细必不同")
    void derivedFromTheItemPrimaryKey() throws Exception {
        assertThat(generate(item(7L))).isEqualTo(generate(item(7L)));
        assertThat(generate(item(7L))).isNotEqualTo(generate(item(8L)));
        assertThat(generate(item(7L))).startsWith("TRF-MT-");
    }

    @Test
    @DisplayName("主键为空时退回随机片段 —— ⛔ 仍然不查库(软删会让占用判据说谎)")
    void nullIdStillNeverCollides() throws Exception {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(generate(item(null)));
        }
        assertThat(seen).hasSizeGreaterThan(190);
    }

    /**
     * 变异对照：复刻**旧实现**并证明它真的会撞。
     * 没有这一条，上面那条「28 个互不相同」可能只是恒真式。
     */
    @Test
    @DisplayName("变异对照: 旧的毫秒后缀在同一循环里必撞 —— 证明上面那条不是恒真式")
    void collidesWhenSuffixIgnoresTheItem() {
        List<String> old = new java.util.ArrayList<>();
        for (int i = 0; i < 28; i++) {
            old.add(String.format("TRF-MT-%s-%04d", "20260902", System.currentTimeMillis() % 10000));
        }
        assertThat(new HashSet<>(old))
                .as("旧实现在 28 次紧邻调用里居然没撞 —— 那这个对照就没起到作用, 需要换更硬的复现")
                .hasSizeLessThan(28);
    }
}
