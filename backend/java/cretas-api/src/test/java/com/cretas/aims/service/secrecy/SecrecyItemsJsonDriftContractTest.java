package com.cretas.aims.service.secrecy;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸: 保密配置项只有一个来源 —— Java 枚举 {@link SecrecyItem}。Python 和两个前端读的 {@code secrecy-items.json}
 * 是从它生成、提交进仓库的拷贝; 任何一份与枚举渲染结果不是逐字节一致就红。
 *
 * <p>改了枚举 (加项 / 改文字) 后重新生成:
 * {@code mvn test -Dtest=SecrecyItemsJsonDriftContractTest -Dsecrecy.items.write=true}。</p>
 */
class SecrecyItemsJsonDriftContractTest {

    /** 相对 backend/java/cretas-api (surefire 的工作目录)。 */
    static final List<Path> COPIES = List.of(
            Path.of("src/main/resources/secrecy/secrecy-items.json"),
            Path.of("../../python/common/secrecy_items.json"),
            Path.of("../../../web-admin/src/config/secrecy-items.json"),
            Path.of("../../../frontend/CretasFoodTrace/src/config/secrecy-items.json"));

    static String render() throws IOException {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        ObjectMapper mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return mapper.writer(printer).writeValueAsString(SecrecyItem.toJsonModel()) + "\n";
    }

    @Test
    void everyCommittedCopyMatchesTheEnum() throws IOException {
        String expected = render();
        boolean write = Boolean.getBoolean("secrecy.items.write");
        List<String> drifted = new ArrayList<>();
        for (Path copy : COPIES) {
            if (write) {
                Files.createDirectories(copy.toAbsolutePath().getParent());
                Files.writeString(copy, expected, StandardCharsets.UTF_8);
                continue;
            }
            if (!Files.exists(copy)) {
                drifted.add(copy + " (缺失)");
                continue;
            }
            String actual = Files.readString(copy, StandardCharsets.UTF_8);
            if (!expected.equals(actual)) drifted.add(copy.toString());
        }
        assertThat(drifted)
                .as("secrecy-items.json 与 SecrecyItem 枚举不一致; 重新生成: "
                        + "mvn test -Dtest=SecrecyItemsJsonDriftContractTest -Dsecrecy.items.write=true")
                .isEmpty();
    }

    @Test
    void renderedModelCarriesEveryItemOnceWithItsCodeAndDefault() throws IOException {
        String json = render();
        for (SecrecyItem item : SecrecyItem.values()) {
            assertThat(json).contains("\"code\" : \"" + item.name() + "\"");
        }
        // 「关闭后会怎样」每项都要有, 给不懂技术的人看
        for (SecrecyItem item : SecrecyItem.values()) {
            assertThat(item.offEffect()).as(item.name()).isNotBlank();
        }
        // b 依赖 a; e 关闭要二次确认且带合同提示; f 是三态、默认跟随 a
        assertThat(SecrecyItem.PLATFORM_RECIPE_BLOCK.effectiveOnlyWhen()).isEqualTo("PROCESS_SECRET");
        // Steve 2026-11-04: b 开 = 平台人员不能看 / 改配方和成本, 但授权的平台 AI 入口不受影响、每次留痕;
        // 文案还要说清 b 只在工艺保密开着时起作用
        assertThat(SecrecyItem.PLATFORM_RECIPE_BLOCK.offEffect())
                .contains("开启时，平台人员不能自己查看、编辑配方和成本")
                .contains("这一项不影响平台授权的 AI 分析入口")
                .contains("包括保密产品的成本")
                .contains("每次使用都记入保密访问记录")
                .contains("平台超级管理员不需要授权，其他平台账号须经授权")
                .contains("只在「工艺保密」开启时起作用");
        assertThat(json).contains("这一项不影响平台授权的 AI 分析入口");
        assertThat(SecrecyItem.CUSTOMER_HELD.offRequiresConfirmation()).isTrue();
        assertThat(SecrecyItem.CUSTOMER_HELD.offConfirmWarning()).contains("这会撤销与客户合同中约定的云端不保管工艺");
        assertThat(SecrecyItem.AI_SECRECY_AUDIT.triState()).isTrue();
        assertThat(SecrecyItem.AI_SECRECY_AUDIT.defaultValue()).isEqualTo(SecrecyItem.Default.FOLLOW_PROCESS_SECRET);
        assertThat(List.of(SecrecyItem.values()).stream().filter(SecrecyItem::requiresReason).toList())
                .containsExactlyInAnyOrder(SecrecyItem.PROCESS_SECRET, SecrecyItem.PLATFORM_RECIPE_BLOCK,
                        SecrecyItem.EGRESS_REDACTION, SecrecyItem.CUSTOMER_HELD);
    }
}
