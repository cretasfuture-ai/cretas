package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.MaterialBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.cretas.aims.ai.tool.PreviewToolTestSupport.assertPlainChinese;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.call;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.ctx;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.data;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.injectMapper;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「AI 对话里领用原料永远办不成」(2026-10-06 prod 真机, 仓库员 f006_warehouse_worker): 用户说的是批次号
 * (MT-20260829-9687) 或原料名, 工具却把它当<b>内部主键</b>去查, 永远「找不到这个原料批次」。
 *
 * <p>真实工具类 + 桩掉的 service / repository, 走 {@code preview} / {@code execute} 的真实入口。
 * 每条「不该发生」的断言都配一条「必发生」的对照 (硬约束 9):
 * <ul>
 *   <li>找不到 ⇒ 对照: 同一套桩里批次号命中的那条能领;</li>
 *   <li>预览不写 ⇒ 对照: 同一个工具的 execute 确实调了写方法;</li>
 *   <li>别厂被拒 ⇒ 对照: 本厂同名批次可领。</li>
 * </ul>
 */
class MaterialBatchUseBatchResolutionTest {

    private static final String FACTORY = "F006";
    private static final String OTHER_FACTORY = "F999";
    private static final String REAL_ID = "9f1c2e40-aaaa-bbbb-cccc-000000000001";
    private static final String BATCH_NO = "MT-20260829-9687";

    private MaterialBatchService service;
    private MaterialBatchRepository repo;
    private MaterialBatchUseTool tool;

    @BeforeEach
    void setUp() throws Exception {
        service = mock(MaterialBatchService.class);
        repo = mock(MaterialBatchRepository.class);
        tool = new MaterialBatchUseTool();
        ReflectionTestUtils.setField(tool, "materialBatchService", service);
        ReflectionTestUtils.setField(tool, "materialBatchRepository", repo);
        injectMapper(tool);
        // 未桩的批次 = service 查不到 (桩默认返回 null, 工具当「没有」); 别厂用 403 的桩单独写在 (f)
    }

    // ==================== (a) 主键直接命中 (老行为保持) ====================

    @Test
    @DisplayName("(a) 传的是主键: 预览可领、执行写路径拿到的就是这个主键; 没有绕去查批次号")
    void primaryKeyHit() throws Exception {
        stubOwnBatch(REAL_ID, BATCH_NO, "鸡胸肉", MaterialBatchStatus.AVAILABLE, "100");

        Map<String, Object> d = data(tool.preview(call("material_batch_use", args(REAL_ID, "30")), ctx(FACTORY, 42L)));
        assertThat(d.get("canDo")).isEqualTo(true);
        assertThat(String.valueOf(d.get("message"))).contains(BATCH_NO + "（鸡胸肉）").doesNotContain(REAL_ID);

        String exec = tool.execute(call("material_batch_use", args(REAL_ID, "30")), ctx(FACTORY, 42L));
        assertThat(parse(exec).get("success")).as(exec).isEqualTo(true);
        verify(service).useBatchMaterial(eq(FACTORY), eq(REAL_ID), qty("30"), any(), eq(42L));
        // 主键命中就不该再去查批次号 (老行为优先)
        verify(repo, never()).findByFactoryIdAndBatchNumber(anyString(), anyString());
    }

    // ==================== (b) 批次号命中, 写路径拿真实主键 ====================

    @Test
    @DisplayName("(b) 传的是批次号 MT-...: 预览可领; 执行时写方法拿到的是真实主键, 不是批次号")
    void batchNumberHitWritesWithRealPrimaryKey() throws Exception {
        stubOwnBatch(REAL_ID, BATCH_NO, "鸡胸肉", MaterialBatchStatus.AVAILABLE, "100");
        stubNumberLookup(FACTORY, BATCH_NO, REAL_ID);

        Map<String, Object> d = data(tool.preview(call("material_batch_use", args(BATCH_NO, "0.1")), ctx(FACTORY, 42L)));
        assertThat(d.get("canDo")).as(String.valueOf(d)).isEqualTo(true);
        assertThat(String.valueOf(d.get("message"))).contains("从 " + BATCH_NO + "（鸡胸肉） 领用 0.1 kg").doesNotContain(REAL_ID);
        // 预览是只读: 一次写都没有 (「必发生」对照在下一步 execute 里)
        verify(service, never()).useBatchMaterial(anyString(), anyString(), any(), any(), any());
        verify(service, never()).useBatchMaterial(anyString(), anyString(), any(), any(), any(), any());

        String exec = tool.execute(call("material_batch_use", args(BATCH_NO, "0.1")), ctx(FACTORY, 42L));
        assertThat(parse(exec).get("success")).as(exec).isEqualTo(true);
        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(service, atLeastOnce()).useBatchMaterial(eq(FACTORY), idCaptor.capture(), qty("0.1"), any(), eq(42L));
        assertThat(idCaptor.getAllValues()).as("写路径收到的批次标识").containsOnly(REAL_ID);
        assertThat(idCaptor.getAllValues()).doesNotContain(BATCH_NO);
        // 查批次号的那一次确实带着本工厂 (工厂隔离的查法, 不是全库按批次号查)
        verify(repo, atLeastOnce()).findByFactoryIdAndBatchNumber(FACTORY, BATCH_NO);
        verify(repo, never()).findByBatchNumber(anyString());
    }

    @Test
    @DisplayName("(b2) 批次号前后有空格 (语音转写常见) 也能命中; 解析后的校验不变: 数量超过剩余照样拒")
    void batchNumberWithSpacesAndLaterChecksStillApply() throws Exception {
        stubOwnBatch(REAL_ID, BATCH_NO, "鸡胸肉", MaterialBatchStatus.AVAILABLE, "5");
        stubNumberLookup(FACTORY, BATCH_NO, REAL_ID);

        Map<String, Object> ok = data(tool.preview(call("material_batch_use", args(" " + BATCH_NO + " ", "5")), ctx(FACTORY, 42L)));
        assertThat(ok.get("canDo")).isEqualTo(true);

        Map<String, Object> over = data(tool.preview(call("material_batch_use", args(BATCH_NO, "5.001")), ctx(FACTORY, 42L)));
        assertThat(over.get("canDo")).isEqualTo(false);
        assertThat(over.get("status")).isEqualTo("INSUFFICIENT_STOCK");

        // 状态不可投产同样拒 (按批次号进来也绕不过状态闸)
        stubOwnBatch(REAL_ID, BATCH_NO, "鸡胸肉", MaterialBatchStatus.EXPIRED, "100");
        Map<String, Object> expired = data(tool.preview(call("material_batch_use", args(BATCH_NO, "1")), ctx(FACTORY, 42L)));
        assertThat(expired.get("canDo")).isEqualTo(false);
        assertThat(expired.get("status")).isEqualTo("INVALID_STATUS");
    }

    // ==================== (c) 都不命中 → 中文原因 ====================

    @Test
    @DisplayName("(c) 主键、批次号、原料名都查不到: 预览/执行都回同一句中文原因, 不回显输入, 一次写都没有; 对照: 批次号命中的那条能领")
    void nothingMatches() throws Exception {
        stubOwnBatch(REAL_ID, BATCH_NO, "鸡胸肉", MaterialBatchStatus.AVAILABLE, "100");
        stubNumberLookup(FACTORY, BATCH_NO, REAL_ID);
        when(service.getMaterialBatchList(eq(FACTORY), any(PageRequest.class)))
                .thenReturn(PageResponse.of(List.<MaterialBatchDTO>of(), 1, 50, 0L));

        Map<String, Object> d = data(tool.preview(call("material_batch_use", args("MT-NOPE-0000", "1")), ctx(FACTORY, 42L)));
        assertThat(d.get("canDo")).isEqualTo(false);
        assertThat(d.get("status")).isEqualTo("BATCH_NOT_FOUND");
        assertThat(String.valueOf(d.get("message"))).contains("找不到这个原料批次").doesNotContain("MT-NOPE-0000");
        assertPlainChinese(String.valueOf(d.get("message")));

        String exec = tool.execute(call("material_batch_use", args("MT-NOPE-0000", "1")), ctx(FACTORY, 42L));
        Map<String, Object> er = parse(exec);
        assertThat(er.get("success")).as(exec).isEqualTo(false);
        assertThat(String.valueOf(er.get("error"))).contains("找不到这个原料批次");
        verify(service, never()).useBatchMaterial(anyString(), anyString(), any(), any(), any());

        // 对照: 同一套桩里, 批次号命中的那条是放行的 —— 上面的「找不到」不是因为桩把一切都挡了
        assertThat(data(tool.preview(call("material_batch_use", args(BATCH_NO, "1")), ctx(FACTORY, 42L))).get("canDo"))
                .isEqualTo(true);
    }

    // ==================== (d) 只给物料名 → 问批次并列出候选 ====================

    @Test
    @DisplayName("(d) 只说了原料名: 预览回 NEED_MORE_INFO, 用人话问「要领用哪个批次？」并列出本工厂该原料可用且有剩余的批次; 用完的 / 过期的 / 余量 0 的不列; 一次写都没有")
    void nameOnlyAsksWhichBatchAndListsOnlyUsableCandidates() throws Exception {
        List<MaterialBatchDTO> found = new ArrayList<>();
        found.add(batch("id-1", "MT-20260829-9687", "鸡胸肉", MaterialBatchStatus.AVAILABLE, "12.5", FACTORY));
        found.add(batch("id-2", "MT-20260830-0002", "鸡胸肉", MaterialBatchStatus.AVAILABLE, "3", FACTORY));
        found.add(batch("id-3", "MT-20260701-USED", "鸡胸肉", MaterialBatchStatus.USED_UP, "0", FACTORY));
        found.add(batch("id-4", "MT-20260702-EXPD", "鸡胸肉", MaterialBatchStatus.EXPIRED, "40", FACTORY));
        found.add(batch("id-5", "MT-20260703-ZERO", "鸡胸肉", MaterialBatchStatus.AVAILABLE, "0", FACTORY));
        found.add(batch("id-6", "MT-20260704-FOREIGN", "鸡胸肉", MaterialBatchStatus.AVAILABLE, "9", OTHER_FACTORY));
        when(service.getMaterialBatchList(eq(FACTORY), any(PageRequest.class)))
                .thenReturn(PageResponse.of(found, 1, 50, 6L));

        String json = tool.preview(call("material_batch_use", args("胸肉", "0.1")), ctx(FACTORY, 42L));
        Map<String, Object> root = parse(json);
        assertThat(root.get("success")).as(json).isEqualTo(false);
        assertThat(root.get("status")).isEqualTo("NEED_MORE_INFO");
        assertThat(root.get("needMoreInfo")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) root.get("clarificationQuestions");
        assertThat(questions).hasSize(1);
        String q = questions.get(0);
        assertThat(q).startsWith("要领用哪个批次？")
                .contains("MT-20260829-9687").contains("剩 12.5").contains("MT-20260830-0002").contains("剩 3");
        // 不可领的一个都不许进候选 (状态 / 余量 / 别厂)
        assertThat(q).doesNotContain("MT-20260701-USED").doesNotContain("MT-20260702-EXPD")
                .doesNotContain("MT-20260703-ZERO").doesNotContain("MT-20260704-FOREIGN");
        assertThat(String.valueOf(root.get("message"))).isEqualTo(q);
        assertThat(q).doesNotContain("id-1");
        // 问的是批次, 不是「找不到」
        assertThat(q).doesNotContain("找不到");

        // 搜索走的是本工厂 + 用户说的名字
        ArgumentCaptor<PageRequest> pr = ArgumentCaptor.forClass(PageRequest.class);
        verify(service, atLeastOnce()).getMaterialBatchList(eq(FACTORY), pr.capture());
        assertThat(pr.getValue().getKeyword()).isEqualTo("胸肉");
        verify(service, never()).useBatchMaterial(anyString(), anyString(), any(), any(), any());
        verify(service, never()).useBatchMaterial(anyString(), anyString(), any(), any(), any(), any());

        // 「必发生」对照: 用户照着候选回了批次号 → 同一个工具能进入可领的确认卡
        stubOwnBatch("id-1", "MT-20260829-9687", "鸡胸肉", MaterialBatchStatus.AVAILABLE, "12.5");
        stubNumberLookup(FACTORY, "MT-20260829-9687", "id-1");
        assertThat(data(tool.preview(call("material_batch_use", args("MT-20260829-9687", "0.1")), ctx(FACTORY, 42L))).get("canDo"))
                .isEqualTo(true);
    }

    @Test
    @DisplayName("(d2) 候选最多 5 个并说明只列了前 5 个; 名字搜到了但一个都不能领 → 拦下并说清原因 (不是「找不到」, 也不问批次)")
    void candidateCapAndNothingUsable() throws Exception {
        List<MaterialBatchDTO> seven = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            seven.add(batch("id-" + i, "MT-2026083" + i + "-000" + i, "带鱼", MaterialBatchStatus.AVAILABLE, "10", FACTORY));
        }
        when(service.getMaterialBatchList(eq(FACTORY), any(PageRequest.class)))
                .thenReturn(PageResponse.of(seven, 1, 50, 7L));
        Map<String, Object> root = parse(tool.preview(call("material_batch_use", args("带鱼", "1")), ctx(FACTORY, 42L)));
        String q = ((List<?>) root.get("clarificationQuestions")).get(0).toString();
        for (int i = 1; i <= 5; i++) {
            assertThat(q).contains("MT-2026083" + i + "-000" + i);
        }
        assertThat(q).doesNotContain("MT-20260836-0006").doesNotContain("MT-20260837-0007").contains("只列出前 5 个");

        List<MaterialBatchDTO> none = List.of(
                batch("id-9", "MT-20260601-0009", "带鱼", MaterialBatchStatus.USED_UP, "0", FACTORY),
                batch("id-8", "MT-20260602-0008", "带鱼", MaterialBatchStatus.EXPIRED, "7", FACTORY));
        when(service.getMaterialBatchList(eq(FACTORY), any(PageRequest.class)))
                .thenReturn(PageResponse.of(none, 1, 50, 2L));
        Map<String, Object> d = data(tool.preview(call("material_batch_use", args("带鱼", "1")), ctx(FACTORY, 42L)));
        assertThat(d.get("canDo")).isEqualTo(false);
        assertThat(d.get("status")).isEqualTo("NO_AVAILABLE_BATCH");
        assertThat(String.valueOf(d.get("message"))).contains("没有能领用的批次").doesNotContain("MT-20260601-0009");
        assertPlainChinese(String.valueOf(d.get("message")));
    }

    // ==================== (f) 别厂批次仍被拒 ====================

    @Test
    @DisplayName("(f) 别厂的批次 —— 按主键 / 按批次号 / 服务端桩得很宽松三种进法都被拒, 与「不存在」同一句话, 一次写都没有; 对照: 本厂同名批次可领")
    void otherFactoryBatchIsStillRefused() throws Exception {
        // 1) 主键属于别厂: 真实 service 在这里抛 403
        when(service.getMaterialBatchById(FACTORY, "foreign-id")).thenThrow(new BusinessException(403, "无权查看该批次"));
        // 2) 批次号: 工厂隔离的仓储查法, 在本厂里查不到别厂的批次号
        when(repo.findByFactoryIdAndBatchNumber(FACTORY, "MT-FOREIGN-1")).thenReturn(Optional.empty());
        // 3) 就算下层(仓储 / service)桩得很宽松、把别厂的东西递了上来, 工具这一层也要再认一次工厂
        MaterialBatch foreignEntity = entity("foreign-id-2", "MT-FOREIGN-2", OTHER_FACTORY);
        when(repo.findByFactoryIdAndBatchNumber(FACTORY, "MT-FOREIGN-2")).thenReturn(Optional.of(foreignEntity));
        // 下层只剩工具自己这一道: 仓储递来别厂实体, service 又把这个主键读成「本厂」的 ⇒ 只有工具认实体的 factoryId 才拦得住
        when(service.getMaterialBatchById(FACTORY, "foreign-id-2"))
                .thenReturn(batch("foreign-id-2", "MT-FOREIGN-2", "鸡胸肉", MaterialBatchStatus.AVAILABLE, "100", FACTORY));
        when(service.getMaterialBatchById(FACTORY, "foreign-id-3"))
                .thenReturn(batch("foreign-id-3", "MT-FOREIGN-3", "鸡胸肉", MaterialBatchStatus.AVAILABLE, "100", OTHER_FACTORY));
        when(repo.findByFactoryIdAndBatchNumber(FACTORY, "MT-FOREIGN-4")).thenReturn(Optional.of(entity("foreign-id-4", "MT-FOREIGN-4", FACTORY)));
        when(service.getMaterialBatchById(FACTORY, "foreign-id-4"))
                .thenReturn(batch("foreign-id-4", "MT-FOREIGN-4", "鸡胸肉", MaterialBatchStatus.AVAILABLE, "100", OTHER_FACTORY));
        when(service.getMaterialBatchList(eq(FACTORY), any(PageRequest.class)))
                .thenReturn(PageResponse.of(List.<MaterialBatchDTO>of(), 1, 50, 0L));

        int refused = 0;
        for (String ref : new String[]{"foreign-id", "MT-FOREIGN-1", "MT-FOREIGN-2", "foreign-id-3", "MT-FOREIGN-4"}) {
            Map<String, Object> d = data(tool.preview(call("material_batch_use", args(ref, "1")), ctx(FACTORY, 42L)));
            assertThat(d.get("canDo")).as(ref).isEqualTo(false);
            assertThat(d.get("status")).as(ref).isEqualTo("BATCH_NOT_FOUND");
            assertThat(String.valueOf(d.get("message"))).as(ref).contains("找不到这个原料批次").doesNotContain("无权");
            Map<String, Object> er = parse(tool.execute(call("material_batch_use", args(ref, "1")), ctx(FACTORY, 42L)));
            assertThat(er.get("success")).as(ref).isEqualTo(false);
            refused++;
        }
        assertThat(refused).isEqualTo(5);
        verify(service, never()).useBatchMaterial(anyString(), anyString(), any(), any(), any());

        // 对照: 本厂同名批次可领 —— 上面全拒不是因为桩把一切都挡了
        stubOwnBatch(REAL_ID, BATCH_NO, "鸡胸肉", MaterialBatchStatus.AVAILABLE, "100");
        stubNumberLookup(FACTORY, BATCH_NO, REAL_ID);
        assertThat(data(tool.preview(call("material_batch_use", args(BATCH_NO, "1")), ctx(FACTORY, 42L))).get("canDo"))
                .isEqualTo(true);
    }

    // ==================== 抽参槽: 不让大模型编 reason ====================

    @Test
    @DisplayName("给大模型抽参的 schema: 没有 reason 槽 (写路径从不落库它, 留着只会被编); batchId 的说明里写明批次号 / 没说批次号不要编")
    void schemaDoesNotOfferAReasonSlot() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) tool.getParametersSchema().get("properties");
        assertThat(props).containsKeys("batchId", "quantity", "productionPlanId").doesNotContainKey("reason");
        @SuppressWarnings("unchecked")
        String desc = String.valueOf(((Map<String, Object>) props.get("batchId")).get("description"));
        assertThat(desc).contains("批次号").contains("不要编造");
    }

    // ==================== helpers ====================

    private void stubOwnBatch(String id, String number, String material, MaterialBatchStatus status, String remaining) {
        when(service.getMaterialBatchById(FACTORY, id)).thenReturn(batch(id, number, material, status, remaining, FACTORY));
        when(service.useBatchMaterial(eq(FACTORY), eq(id), any(), any(), any()))
                .thenReturn(batch(id, number, material, status, remaining, FACTORY));
    }

    private void stubNumberLookup(String factory, String number, String id) {
        when(repo.findByFactoryIdAndBatchNumber(factory, number)).thenReturn(Optional.of(entity(id, number, factory)));
    }

    /** JSON 回来的 30 是 30.0 (scale 不同, equals 不等) —— 按数值比。 */
    private static BigDecimal qty(String v) {
        return argThat(q -> q != null && q.compareTo(new BigDecimal(v)) == 0);
    }

    private static Map<String, Object> args(String batchRef, String quantity) {
        Map<String, Object> a = new HashMap<>();
        a.put("batchId", batchRef);
        a.put("quantity", new BigDecimal(quantity));
        return a;
    }

    private static MaterialBatchDTO batch(String id, String number, String material, MaterialBatchStatus status,
                                          String remaining, String factory) {
        return MaterialBatchDTO.builder()
                .id(id)
                .factoryId(factory)
                .batchNumber(number)
                .materialName(material)
                .quantityUnit("kg")
                .currentQuantity(new BigDecimal(remaining))
                .status(status)
                .build();
    }

    private static MaterialBatch entity(String id, String number, String factory) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(factory);
        b.setBatchNumber(number);
        return b;
    }
}
