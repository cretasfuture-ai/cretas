package com.cretas.aims.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QuantityFormat} 的行为 + 一道**只许减不许增**的棘轮。
 *
 * <h3>棘轮为什么是棘轮</h3>
 *
 * 建这道闸时仓里还有 7 份私有 {@code stripZeros}（本来 8 份，
 * 走查里我自己加的那一份已经并进 {@code QuantityFormat}）。
 * 一上来硬红 = 一道当天就被人注掉的闸（本仓形态 E）。
 * ⇒ 只禁增长，逐步收敛。
 *
 * <h3>剩下 7 份是「故意不改」，理由写在这里（⛔ 登记是留痕，不是豁免）</h3>
 *
 * 它们全在 {@code ai/tool/impl/**} 下，产出的文案是**喂给 LLM** 的，
 * 不是直接给人看的。把舍入策略换掉会改变模型看到的输入，
 * 那需要自己的一轮验证（本仓有「改了产出侧没查谁在消费」的先例），
 * ⛔ 不搭在这次的走查修复里。
 */
@DisplayName("数量文案：一份实现 + 只许减不许增")
class QuantityFormatTest {

    /** 建闸当天的存量。⛔ 只许调小。 */
    private static final int BASELINE_PRIVATE_COPIES = 7;

    @Test
    @DisplayName("拿不到就写 - ，⛔ 不写 0（0 会被下游当成真实读数）")
    void nullBecomesDashNotZero() {
        assertThat(QuantityFormat.orDash(null)).isEqualTo("-");
        // 阴性对照：真的是 0 的时候照旧写 0，⛔ 防「一律返回 -」也能过
        assertThat(QuantityFormat.orDash(BigDecimal.ZERO)).isEqualTo("0");
    }

    @Test
    @DisplayName("orZero 只在 0 是合法读数时用 —— 它确实把 null 写成 0")
    void orZeroKeepsTheOldPolicy() {
        assertThat(QuantityFormat.orZero((BigDecimal) null)).isEqualTo("0");
        assertThat(QuantityFormat.orZero(new BigDecimal("3.50"))).isEqualTo("3.5");
    }

    @Test
    @DisplayName("面向人的数量最多 2 位小数（这条就是屏上 1475.4333kg 的修复）")
    void roundsToTwoDecimals() {
        assertThat(QuantityFormat.orDash(new BigDecimal("1475.4333"))).isEqualTo("1475.43");
        assertThat(QuantityFormat.orDash(new BigDecimal("1475.4367"))).isEqualTo("1475.44");
        // 阴性对照：位数本来就少的不许被补零
        assertThat(QuantityFormat.orDash(new BigDecimal("12"))).isEqualTo("12");
        assertThat(QuantityFormat.orDash(new BigDecimal("12.500"))).isEqualTo("12.5");
    }

    @Test
    @DisplayName("整百不许变成科学计数法 1E+2 —— stripTrailingZeros 的经典坑")
    void hundredStaysHundred() {
        assertThat(QuantityFormat.orDash(new BigDecimal("100"))).isEqualTo("100");
        assertThat(QuantityFormat.orDash(new BigDecimal("100.00"))).isEqualTo("100");
        assertThat(QuantityFormat.orZero(100.0d)).isEqualTo("100");
    }

    @Test
    @DisplayName("棘轮：私有 stripZeros 的份数只许减不许增")
    void privateCopiesMustNotGrow() throws IOException {
        Path root = Path.of("src", "main", "java");
        assertThat(Files.isDirectory(root))
                .as("源码根目录找不到 —— 仪器坏了，这条断言的读数作废")
                .isTrue();

        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                if (src.contains("private static String stripZeros")) {
                    hits.add(root.relativize(f).toString());
                }
            }
        }

        // 阳性对照：闸必须真的能数到东西。数到 0 说明扫错了目录，不是「已经清干净了」。
        assertThat(hits)
                .as("一份都没数到 —— 先查是不是扫错目录，⛔ 不要读成「已清零」")
                .isNotEmpty();

        assertThat(hits)
                .as("私有 stripZeros 又长回来了。新增数量文案请用 QuantityFormat，"
                        + "⛔ 不要再抄一份：现存这 %d 份彼此已经不一致（null 有的写 0 有的写 -，"
                        + "有的舍入有的不舍入）。", BASELINE_PRIVATE_COPIES)
                .hasSizeLessThanOrEqualTo(BASELINE_PRIVATE_COPIES);
    }
}
