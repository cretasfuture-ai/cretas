package com.cretas.aims.service.lineage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 血缘图装配的两个入口必须<b>自带只读事务</b>。
 *
 * <p>实测长相 (prod CATTLE_3701_001, 2026-09-15 22:03): 手机端 AI 问答走 SSE 流式路径,
 * {@code lineage_query} 在 {@code intent-sse} 线程上直接调 {@code assemble} —— 没有
 * OSIV、没有事务, 结点解析碰到 {@code MaterialBatch.materialType} 懒代理当场
 * {@code LazyInitializationException: could not initialize proxy [RawMaterialType#…] - no Session},
 * 用户看到「操作执行失败，请稍后重试」。同一个问题走非流式 {@code /execute}（请求线程有 OSIV）
 * 答得出来 —— 所以探针脚本全绿、手机上红。
 *
 * <p>为什么钉注解而不是起 H2 复现: 装配器不做自调用（两个入口都由别的 bean 调），
 * 注解在 ⇒ Spring 代理必开事务, 这一条契约与运行环境无关；复现 lazy 代理要灌
 * 闭包表 + 原料批次 + 原料类型三张表, 收益不成比例。
 */
class LineageGraphAssemblerTransactionContractTest {

    private static Method entry(String name) {
        return Arrays.stream(LineageGraphAssembler.class.getMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("LineageGraphAssembler." + name + " 不存在"));
    }

    @Test
    @DisplayName("assemble / assembleImpact / assemblePublic 都带 @Transactional(readOnly = true)")
    void bothEntryPointsCarryReadOnlyTransaction() {
        for (String name : List.of("assemble", "assembleImpact", "assemblePublic")) {
            Transactional tx = AnnotatedElementUtils.findMergedAnnotation(entry(name), Transactional.class);
            assertThat(tx).as("%s 缺 @Transactional —— SSE 线程上会 LazyInitializationException", name).isNotNull();
            assertThat(tx.readOnly()).as("%s 是纯读, 必须 readOnly", name).isTrue();
        }
    }

    /** 对照: 私有/包内的装配细节不应带事务注解（事务边界只在入口）。 */
    @Test
    @DisplayName("对照: 非入口方法不带事务注解")
    void helpersDoNotCarryTransaction() {
        long annotatedNonEntry = Arrays.stream(LineageGraphAssembler.class.getDeclaredMethods())
                .filter(m -> !m.getName().equals("assemble") && !m.getName().equals("assembleImpact")
                        && !m.getName().equals("assemblePublic"))
                .filter(m -> AnnotatedElementUtils.findMergedAnnotation(m, Transactional.class) != null)
                .count();
        assertThat(annotatedNonEntry).isZero();
    }
}
