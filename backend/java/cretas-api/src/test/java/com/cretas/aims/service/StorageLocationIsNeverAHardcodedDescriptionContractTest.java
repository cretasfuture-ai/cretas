package com.cretas.aims.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code storage_location} 只能存**本厂配置过的位置编码**, 不许存一句来源描述。
 *
 * <p>🔴 2026-08-25 prod 实测: F006 的 18 条成品批次里 16 条的 storage_location 是
 * <pre>
 *   "库存生产小结入库"                              9 条
 *   "Workflow terminal output warehouse receipt"    7 条   ← 英文调试串直接显示给中文用户
 * </pre>
 * 与本厂 WAREHOUSE_LAYOUT 里配置的位置编码**匹配数为 0**。后果:
 * 仓储空间的位置筛选、位置卡片「N 批」、{@code countAt()} 在生产数据上**恒为 0** ——
 * 「哪个位置放了什么」这个核心价值被这几行硬编码掏空了。
 *
 * <p>当时全后端共 <b>4</b> 处这样写(另两处是 {@code "默认仓位"} / {@code "仓库确认入库"}),
 * 已全部改为不写(留 null = 未分配, 这是**真的**)。来源本来就记在
 * {@code productionPlanId} 等字段上, 不需要借位置字段再存一份(形态 D)。
 *
 * <p>⚠️ 这道闸只拦**字面量**。变量传入的值拦不住 —— 真正的兜底是
 * {@code WarehouseLayoutController#moveFinishedGoods} 里的存在性校验(fail closed)。
 */
class StorageLocationIsNeverAHardcodedDescriptionContractTest {

    private static final Path MAIN = Path.of("src/main/java/com/cretas/aims");

    /**
     * 只找 {@code xxx.setStorageLocation("字面量")} —— 变量入参不匹配, 那不是本闸的目标。
     *
     * ⛔ 刻意<b>不用正则</b>: 这个模式里全是引号和括号, 而本仓多次栽在
     * 「转义经过 shell 被吃掉一层」上(硬约束 6), 写成正则字面量最容易悄悄失效。
     * 纯字符串扫描没有转义, 也就没有那个失效通道。
     */
    private static final String CALL = "etStorageLocation(";

    @Test
    void noProductionCodeWritesALiteralStringIntoStorageLocation() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                scanned++;
                String source = Files.readString(file, StandardCharsets.UTF_8);
                int from = 0;
                while (true) {
                    int call = source.indexOf(CALL, from);
                    if (call < 0) break;
                    int argStart = call + CALL.length();
                    from = argStart;
                    // 跳过调用括号后的空白, 看第一个非空白字符是不是引号
                    int i = argStart;
                    while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
                    if (i >= source.length() || source.charAt(i) != '"') continue;   // 变量入参 → 放行
                    int end = source.indexOf('"', i + 1);
                    if (end < 0) continue;
                    offenders.add(file.getFileName() + " -> " + source.substring(i, end + 1));
                }
            }
        }
        // 阳性对照: 确实扫到了源码树。扫 0 个文件时上面的断言会假绿。
        assertThat(scanned).as("没有扫到任何 java 文件 —— 是闸的路径错了, 不是产品干净了").isGreaterThan(500);
        assertThat(offenders)
                .as("storage_location 只能是本厂配置过的位置编码; 写死一句描述会让位置筛选与批次统计恒为 0")
                .isEmpty();
    }
}
