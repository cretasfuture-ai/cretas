package com.cretas.aims.ai.tool.impl.warehouse;

import com.cretas.aims.entity.warehouse.ReusableContainer;
import com.cretas.aims.entity.warehouse.ReusableContainerTransaction;
import com.cretas.aims.repository.warehouse.ReusableContainerRepository;
import com.cretas.aims.repository.warehouse.ReusableContainerTransactionRepository;
import com.cretas.aims.service.warehouse.ReusableContainerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.cretas.aims.ai.tool.PreviewToolTestSupport.assertPlainChinese;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.call;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.ctx;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.data;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.injectMapper;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.needMoreInfoQuestions;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.parse;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 周转框「发出 / 归还」只读预览。
 *
 * <p>走<b>真实的</b> {@link ReusableContainerService} (只桩掉两个 repository) —— 预览与写路径共用的
 * {@code assertShipOutAllowed / assertReturnInAllowed} 是真的在判, 不是被桩替身说了算。
 * 「没写」的证据是两个 repository 上 {@code save} 一次都没被调; 「必发生」的对照是同样的输入走 execute 时
 * {@code save} 确实被调了。
 */
class ReusableContainerPreviewToolTest {

    private static final String FACTORY = "F006";
    private static final String OTHER_FACTORY = "F999";
    private static final String CONTAINER_ID = "c-1";

    private ReusableContainerRepository containerRepo;
    private ReusableContainerTransactionRepository txRepo;
    private ReusableContainerShipOutTool shipOut;
    private ReusableContainerReturnInTool returnIn;

    @BeforeEach
    void setUp() throws Exception {
        containerRepo = mock(ReusableContainerRepository.class);
        txRepo = mock(ReusableContainerTransactionRepository.class);
        ReusableContainerService service = new ReusableContainerService(containerRepo, txRepo);
        shipOut = new ReusableContainerShipOutTool();
        returnIn = new ReusableContainerReturnInTool();
        ReflectionTestUtils.setField(shipOut, "service", service);
        ReflectionTestUtils.setField(returnIn, "service", service);
        injectMapper(shipOut);
        injectMapper(returnIn);
        when(containerRepo.findByIdAndFactoryId(CONTAINER_ID, FACTORY)).thenReturn(Optional.of(container(50, 10)));
        when(containerRepo.save(any(ReusableContainer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(txRepo.save(any(ReusableContainerTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== 发出 ====================

    @Test
    @DisplayName("发出: 在库够 → canDo=true, 确认卡写名字和前后数量, 两个 repository 都没有写")
    void shipOutAllowed() throws Exception {
        Map<String, Object> d = data(shipOut.preview(call("reusable_container_ship_out", args(10)), ctx(FACTORY, 42L)));

        assertThat(shipOut.supportsPreview()).isTrue();
        assertThat(d.get("canDo")).isEqualTo(true);
        assertThat(d.get("status")).isEqualTo("PREVIEW");
        assertThat(rows(d, "currentValues")).containsEntry("周转框", "塑料周转筐（TK-01）")
                .containsEntry("客户", "永辉超市").containsEntry("在库", "50 个").containsEntry("在途（客户处）", "10 个");
        assertThat(rows(d, "newValues")).containsEntry("在库", "40 个").containsEntry("在途（客户处）", "20 个");
        assertThat(String.valueOf(d.get("message")))
                .contains("发出 10 个「塑料周转筐（TK-01）」给「永辉超市」").doesNotContain(CONTAINER_ID);
        assertPlainChinese(String.valueOf(d.get("message")));
        assertNoWrite();
    }

    @Test
    @DisplayName("发出 对照: 数量超过在库 → canDo=false + 原因; 刚好等于在库 → 放行 (边界)")
    void shipOutRefusedBeyondStockAllowedAtTheEdge() throws Exception {
        Map<String, Object> over = data(shipOut.preview(call("reusable_container_ship_out", args(51)), ctx(FACTORY, 42L)));
        assertThat(over.get("canDo")).isEqualTo(false);
        assertThat(String.valueOf(over.get("message"))).contains("塑料周转筐").contains("在库数量不足").contains("当前 50");
        assertThat(over).doesNotContainKey("newValues");

        Map<String, Object> edge = data(shipOut.preview(call("reusable_container_ship_out", args(50)), ctx(FACTORY, 42L)));
        assertThat(edge.get("canDo")).isEqualTo(true);
        assertThat(rows(edge, "newValues")).containsEntry("在库", "0 个");

        Map<String, Object> zero = data(shipOut.preview(call("reusable_container_ship_out", args(0)), ctx(FACTORY, 42L)));
        assertThat(zero.get("canDo")).isEqualTo(false);
        assertThat(String.valueOf(zero.get("message"))).contains("发出数量必须大于 0");
        assertNoWrite();
    }

    @Test
    @DisplayName("发出: 周转框不存在 → canDo=false, 不回显内部编号; 别厂的周转框 = 不存在, 同一个输入在它自己的厂能发")
    void shipOutNotFoundAndFactoryIsolation() throws Exception {
        when(containerRepo.findByIdAndFactoryId("c-missing", FACTORY)).thenReturn(Optional.empty());
        Map<String, Object> missing = args(1);
        missing.put("containerId", "c-missing");
        Map<String, Object> d = data(shipOut.preview(call("reusable_container_ship_out", missing), ctx(FACTORY, 42L)));
        assertThat(d.get("canDo")).isEqualTo(false);
        assertThat(d.get("status")).isEqualTo("CONTAINER_NOT_FOUND");
        assertThat(String.valueOf(d.get("message"))).contains("找不到这种周转框").doesNotContain("c-missing");

        // 同一个 containerId 只在 F999 读得到
        when(containerRepo.findByIdAndFactoryId(CONTAINER_ID, OTHER_FACTORY)).thenReturn(Optional.of(container(5, 0)));
        when(containerRepo.findByIdAndFactoryId(CONTAINER_ID, FACTORY)).thenReturn(Optional.empty());
        Map<String, Object> mine = data(shipOut.preview(call("reusable_container_ship_out", args(1)), ctx(FACTORY, 42L)));
        assertThat(mine.get("canDo")).isEqualTo(false);
        Map<String, Object> theirs = data(shipOut.preview(call("reusable_container_ship_out", args(1)), ctx(OTHER_FACTORY, 42L)));
        assertThat(theirs.get("canDo")).isEqualTo(true);
        assertNoWrite();
    }

    @Test
    @DisplayName("发出: 缺哪个必填就问哪个, 四个必填各缺一次, 追问都是人话且互不相同")
    void shipOutAsksForEachMissingRequiredParam() throws Exception {
        assertEachMissingParamIsAsked(shipOut, "reusable_container_ship_out");
        assertNoWrite();
    }

    // ==================== 归还 ====================

    @Test
    @DisplayName("归还: 在途够 → canDo=true, 在途减 / 在库加; 数量超过在途 → canDo=false; 刚好等于在途放行")
    void returnInAllowedAndRefused() throws Exception {
        Map<String, Object> ok = data(returnIn.preview(call("reusable_container_return_in", args(4)), ctx(FACTORY, 42L)));
        assertThat(returnIn.supportsPreview()).isTrue();
        assertThat(ok.get("canDo")).isEqualTo(true);
        assertThat(rows(ok, "currentValues")).containsEntry("在途（客户处）", "10 个").containsEntry("在库", "50 个");
        assertThat(rows(ok, "newValues")).containsEntry("在途（客户处）", "6 个").containsEntry("在库", "54 个");
        assertThat(String.valueOf(ok.get("message"))).contains("「永辉超市」归还 4 个「塑料周转筐（TK-01）」入库");

        Map<String, Object> over = data(returnIn.preview(call("reusable_container_return_in", args(11)), ctx(FACTORY, 42L)));
        assertThat(over.get("canDo")).isEqualTo(false);
        assertThat(String.valueOf(over.get("message"))).contains("在途(客户处)数量不足").contains("当前 10");

        Map<String, Object> edge = data(returnIn.preview(call("reusable_container_return_in", args(10)), ctx(FACTORY, 42L)));
        assertThat(edge.get("canDo")).isEqualTo(true);
        assertNoWrite();
    }

    @Test
    @DisplayName("归还: 缺哪个必填就问哪个")
    void returnInAsksForEachMissingRequiredParam() throws Exception {
        assertEachMissingParamIsAsked(returnIn, "reusable_container_return_in");
        assertNoWrite();
    }

    // ==================== 预览与 execute 判断一致 + 「必发生」对照 ====================

    @Test
    @DisplayName("对照: 预览说能发的输入 execute 真的写 (save 被调); 预览说不能发的输入 execute 也不写 —— 预览不是在对空气说话")
    void previewVerdictMatchesExecuteAndExecuteReallyWrites() throws Exception {
        // 预览拒绝的输入: execute 同样拒绝, 且不写
        String refusedPreview = shipOut.preview(call("reusable_container_ship_out", args(51)), ctx(FACTORY, 42L));
        assertThat(data(refusedPreview).get("canDo")).isEqualTo(false);
        String refusedExec = shipOut.execute(call("reusable_container_ship_out", args(51)), ctx(FACTORY, 42L));
        assertThat(parse(refusedExec).get("success")).isEqualTo(false);
        verify(containerRepo, never()).save(any(ReusableContainer.class));

        // 预览放行的输入: execute 真的写了 (必发生)
        assertThat(data(shipOut.preview(call("reusable_container_ship_out", args(10)), ctx(FACTORY, 42L))).get("canDo"))
                .isEqualTo(true);
        verify(containerRepo, never()).save(any(ReusableContainer.class));   // 预览之后仍没写
        String ok = shipOut.execute(call("reusable_container_ship_out", args(10)), ctx(FACTORY, 42L));
        assertThat(parse(ok).get("success")).isEqualTo(true);
        verify(containerRepo).save(any(ReusableContainer.class));
        verify(txRepo).save(any(ReusableContainerTransaction.class));
    }

    // ==================== helpers ====================

    private void assertEachMissingParamIsAsked(Object tool, String toolName) throws Exception {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String missing : List.of("containerId", "quantity", "customerId", "customerName")) {
            Map<String, Object> a = args(3);
            a.remove(missing);
            String json = tool instanceof ReusableContainerShipOutTool
                    ? ((ReusableContainerShipOutTool) tool).preview(call(toolName, a), ctx(FACTORY, 42L))
                    : ((ReusableContainerReturnInTool) tool).preview(call(toolName, a), ctx(FACTORY, 42L));
            List<String> questions = needMoreInfoQuestions(json);
            assertThat(questions).as("缺 " + missing).hasSize(1);
            assertThat(seen.add(questions.get(0))).as("追问互不相同 (被参数化的那一维真的不同): " + questions.get(0)).isTrue();
        }
    }

    private void assertNoWrite() {
        verify(containerRepo, never()).save(any(ReusableContainer.class));
        verify(containerRepo, never()).saveAll(any());
        verify(containerRepo, never()).delete(any(ReusableContainer.class));
        verifyNoMoreInteractions(txRepo);   // 预览根本没碰流水表
    }

    private static Map<String, Object> args(int quantity) {
        Map<String, Object> a = new HashMap<>();
        a.put("containerId", CONTAINER_ID);
        a.put("quantity", quantity);
        a.put("customerId", "cust-1");
        a.put("customerName", "永辉超市");
        return a;
    }

    private static ReusableContainer container(int inWarehouse, int inTransit) {
        ReusableContainer c = new ReusableContainer();
        c.setId(CONTAINER_ID);
        c.setFactoryId(FACTORY);
        c.setContainerCode("TK-01");
        c.setContainerName("塑料周转筐");
        c.setTotalQuantity(inWarehouse + inTransit);
        c.setInWarehouseQuantity(inWarehouse);
        c.setInTransitQuantity(inTransit);
        return c;
    }
}
