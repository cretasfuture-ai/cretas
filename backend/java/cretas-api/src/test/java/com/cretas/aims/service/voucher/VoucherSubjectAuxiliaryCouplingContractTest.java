package com.cretas.aims.service.voucher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 科目一旦可配，<b>分录用的科目</b>和<b>辅助核算的匹配键</b>必须是同一个。
 *
 * <p>各生成器把会计科目代码同时用在两处：
 * <ol>
 *   <li>{@code debitEntry / creditEntryWithAuxiliary} —— 分录本身</li>
 *   <li>{@code attachAuxiliary(entries, SIDE, "1122", ...)} —— 模板路径下按科目<b>匹配</b>那一行，
 *       再把客户/供应商/存货维度挂上去</li>
 * </ol>
 *
 * <p>只把第 1 处改成可解析、第 2 处留着字面量，会造成
 * <b>配了覆盖之后辅助核算静默丢失</b>：分录科目变成 1131，匹配键还在找 1122，找不到就什么都不挂，
 * 而且<b>不报错</b>。客户应收明细账、供应商应付明细账会安静地少掉维度。
 *
 * <p>⚠️ 这一条在实现过程中真的发生过：第一版只改了分录，
 * {@code SalesReceipt / Return / Depreciation} 三个的匹配键还是裸字面量。
 *
 * <h2>⚠️ 代理判据，标出来</h2>
 *
 * <p>它扫的是<b>源码文本</b>：`attachAuxiliary` 的第三个实参不许是四位数字字面量。
 * 已知盲区：换成一个返回字面量的常量或 helper 它就看不见了。
 * ⇒ 它拦的是「直接写死」这一种最可能的退化，不是全部。
 */
class VoucherSubjectAuxiliaryCouplingContractTest {

    private static final Path IMPL =
            Path.of("src/main/java/com/cretas/aims/service/voucher/impl");

    /** {@code attachAuxiliary(entries, EntrySide.X, <第三个实参>} 里那个实参。 */
    private static final Pattern MATCH_KEY = Pattern.compile(
            "attachAuxiliary\\s*\\(\\s*entries\\s*,\\s*EntrySide\\.[A-Z]+\\s*,\\s*([^,]+),");

    /** 四位科目代码字面量, 例如 "1122" / "6602.02"。 */
    private static final Pattern SUBJECT_LITERAL = Pattern.compile("^\"\\d{4}(\\.\\d{2})?\"$");

    /**
     * ⛔ 登记在案的既有例外 —— {@code PurchasePaymentVoucherGenerator}。
     *
     * <p>它的贷方科目可以被 settlement 映射覆盖成 1002(预付) / 1001(现结)，
     * 而匹配键写死 "2202" ⇒ <b>那两种结算方式下供应商辅助核算本来就挂不上</b>。
     * 这是<b>本次改动之前就存在</b>的缺陷，不是本次引入的；
     * 修它要动 settlement 那条已有分支，超出本次范围。
     * ⚠️ 登记是留痕不是豁免：名单只许变短。
     */
    private static final List<String> KNOWN_EXCEPTIONS =
            List.of("PurchasePaymentVoucherGenerator.java");

    private List<String> offenders() throws IOException {
        List<String> bad = new ArrayList<>();
        try (var files = Files.list(IMPL)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = p.getFileName().toString();
                if (KNOWN_EXCEPTIONS.contains(name)) {
                    continue;
                }
                Matcher m = MATCH_KEY.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) {
                    String arg = m.group(1).trim();
                    if (SUBJECT_LITERAL.matcher(arg).matches()) {
                        bad.add(name + " -> " + arg);
                    }
                }
            }
        }
        return bad;
    }

    @Test
    @DisplayName("阳性对照: 真的扫到了 attachAuxiliary 调用(扫不到最像「一切正常」)")
    void positiveControl() throws IOException {
        assertThat(IMPL).as("生成器目录搬家了, 本闸路径要跟着改").exists();
        int calls = 0;
        try (var files = Files.list(IMPL)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = MATCH_KEY.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) {
                    calls++;
                }
            }
        }
        assertThat(calls)
                .as("一个 attachAuxiliary 都没扫到 ⇒ 仪器坏了, 不是代码干净")
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("🔴 辅助核算的匹配键不许是写死的科目字面量")
    void auxiliaryMatchKeyMustNotBeHardcoded() throws IOException {
        assertThat(offenders())
                .as("这些匹配键写死了科目代码 —— 一旦该科目被工厂覆盖, "
                        + "分录变了而匹配键没变, 辅助核算会**静默丢失**且不报错")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 例外名单只许变短: 名单里的文件必须真的还存在")
    void exceptionListMustNotRot() {
        for (String name : KNOWN_EXCEPTIONS) {
            assertThat(IMPL.resolve(name))
                    .as(name + " 已经不在了 —— 例外名单该划掉这一条")
                    .exists();
        }
    }
}
