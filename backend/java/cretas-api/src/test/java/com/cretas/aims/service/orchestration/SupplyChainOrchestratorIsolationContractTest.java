package com.cretas.aims.service.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 「catch 吞得掉异常，抹不掉 rollback-only 标记」—— 这个形状在本仓复发过至少三次。
 *
 * <ul>
 *   <li>2026-06-12 「doomed-tx 第 4 次复发」（onMaterialReceived / onBatchCompleted）</li>
 *   <li>2026-08-01 {@code expandBOM} 加入 {@code expandBomAndCheckMaterialIsolated}</li>
 *   <li><b>2026-08-24</b> 轮到隔壁的 {@code generateSuggestions} —— 上一次修复的文档里
 *       写着「刻意保持隔离范围小」，把它留在了外层事务里</li>
 * </ul>
 *
 * <h2>2026-08-24 prod 实测的因果链</h2>
 * <pre>
 * 13:38:31.397  自动创建生产计划: PP=PP-AUTO-20260824-0001   &lt;- PP 建成了
 * 13:38:31.446  insert into purchase_orders 撞 fk_po_supplier
 * 13:38:31.452  日志: "BOM展开/采购建议生成失败(不影响PP创建)"  &lt;- 这句话是假的
 * 13:38:31.470  UnexpectedRollbackException                  &lt;- 整个事务回滚, PP 一起没了
 * </pre>
 *
 * <p>六膳门(真实客户)两张单 ¥92,260 财审通过后既无生产计划也无库存预留，
 * 而接口<b>返回 200</b>，用户侧完全无感。
 *
 * <p>⚠️ 前两次修复都<b>没有留下闸</b>，所以第三次照样发生。这道闸补的是这个。
 *
 * <h2>⚠️ 这是代理判据，明确标出来</h2>
 *
 * <p>它扫的是<b>源码文本</b>，不是真的做事务传播分析 —— 真要验证 rollback-only 语义
 * 得起 Spring 上下文 + 真实数据源，代价远大于收益。
 * 代理判据的已知盲区：改用别的写法（比如换个包装方法名、或改成注入一个新的 helper bean）
 * 它就看不见了。⇒ 它拦的是「直接调回去」这一种最可能的退化，不是全部。
 */
class SupplyChainOrchestratorIsolationContractTest {

    private static final Path ORCHESTRATOR = Path.of(
            "src/main/java/com/cretas/aims/service/orchestration/SupplyChainOrchestrator.java");

    private String source() throws IOException {
        assertThat(ORCHESTRATOR).as("找不到被扫描的源码, 这道闸就没在守任何东西").exists();
        return Files.readString(ORCHESTRATOR, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("会写库的采购建议必须走独立事务包装, ⛔ 不许在联动事务里直接调")
    void procurementSuggestionRunsIsolated() throws IOException {
        String src = source();

        assertThat(src)
                .as("隔离包装方法不见了 —— 失败会重新把外层联动事务标记 rollback-only, "
                        + "连累已经建好的生产计划")
                .contains("generateSuggestionsIsolated");

        // 真正的调用只该出现在包装方法体内; 联动流程里应当调包装方法
        int directCalls = src.split("procurementSuggestionService\\.generateSuggestions\\(", -1).length - 1;
        assertThat(directCalls)
                .as("对 generateSuggestions 的直接调用只允许有 1 处(即隔离包装方法内部); "
                        + "多出来的那处就是绕过隔离")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("两个隔离包装都必须是 REQUIRES_NEW —— 否则它只是个换了名字的直接调用")
    void bothIsolationsUseRequiresNew() throws IOException {
        String src = source();

        int requiresNew = src.split("PROPAGATION_REQUIRES_NEW", -1).length - 1;
        assertThat(requiresNew)
                .as("expandBomAndCheckMaterialIsolated(2026-08-01) 与 "
                        + "generateSuggestionsIsolated(2026-08-24) 各需要一处; "
                        + "少一处就有一条路重新落回外层事务")
                .isGreaterThanOrEqualTo(2);

        assertThat(src)
                .as("2026-08-01 那道隔离不许被顺手删掉")
                .contains("expandBomAndCheckMaterialIsolated");
    }

    @Test
    @DisplayName("⛔ 阴性对照 · 隔离范围不许扩大到 isFullyMatched")
    void isFullyMatchedStaysInOuterTransaction() throws IOException {
        String src = source();

        // saved 是外层联动事务里刚 save、尚未提交的 PP 实体。
        // 把它的写入塞进独立子事务, 在 READ COMMITTED 下看不到那一行 —— 会静默落空。
        // 这条对照钉住「隔离」不要被无脑扩大, 与上面两条方向相反。
        assertThat(src)
                .as("isFullyMatched 必须仍在外层事务里写")
                .contains("saved.setIsFullyMatched(true)");
        int isolatedSetters = src.split("isolated\\.execute", -1).length - 1;
        assertThat(isolatedSetters)
                .as("隔离块的数量应当就是那两个包装方法, 不该无限膨胀")
                .isLessThanOrEqualTo(2);
    }
}
