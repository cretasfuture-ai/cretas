package com.cretas.aims.service.impl;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.controller.LabelController;
import com.cretas.aims.entity.Label;
import com.cretas.aims.exception.AuthorizationException;
import com.cretas.aims.repository.LabelRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.LabelService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 标签查询的租户隔离 (2026-08-26).
 *
 * <p>背景: {@code GET /api/mobile/{factoryId}/labels/trace/{traceCode}} 把路径上的
 * factoryId 收下之后**一次都没引用过** —— 查询无工厂过滤, 返回裸 Label 实体,
 * 方法和类上都没有权限注解。同一缺陷在 {@code /code/{labelCode}} 和
 * {@code /batch/{batchId}} 上各有一份 (label_code / batch_id 同样是全局查询键)。
 *
 * <p>这个类刻意**独立于 {@code LabelServiceSp4T5Test}**: 那个类在 origin/main 上
 * 本来就有 2 个 error (generateLabel_happyPath / generateLabel_noMaterialType_fallsBackToWL,
 * 成因是 getMaterialCategoryPrefix(null) 现在按设计抛异常), 混在一起会让这里的绿
 * 没有信息量。
 *
 * <p>⚠️ 每条正向断言都配了一条能让它红的对照, 且**两个方向**都钉:
 * 「越权不能被读成不存在」和「不存在不能被读成越权」——
 * 只钉前者的话, 把所有查不到都改成抛 403 也能全绿。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Label 查询租户隔离: trace / code / batch 三处")
class LabelTenantScopeTest {

    private static final String MY_FACTORY = "F006";
    private static final String OTHER_FACTORY = "F999";

    @Mock
    LabelRepository labelRepository;

    @Mock
    MaterialBatchRepository materialBatchRepository;

    @InjectMocks
    LabelServiceImpl labelService;

    /** 给 controller 用的替身 —— controller 那几条测的是**接线**, 不是服务逻辑。 */
    @Mock
    LabelService labelServiceStub;

    private static Label labelOf(String factoryId, String labelCode, String traceCode) {
        Label label = new Label();
        label.setId("id-" + labelCode);
        label.setFactoryId(factoryId);
        label.setLabelCode(labelCode);
        label.setTraceCode(traceCode);
        label.setStatus("ACTIVE");
        return label;
    }

    // ──────────────── getByTraceCode ────────────────

    @Test
    @DisplayName("TS-1: 本厂追溯码 → 正常返回 (前提: 隔离没有把合法查询一起挡掉)")
    void traceCode_ownFactory_returns() {
        String trace = "TRACE-F006-20260826-000001";
        when(labelRepository.findByTraceCodeAndDeletedAtIsNull(trace))
                .thenReturn(Optional.of(labelOf(MY_FACTORY, "YL-F006-20260826120000-0001", trace)));

        Optional<Label> found = labelService.getByTraceCode(MY_FACTORY, trace);

        assertTrue(found.isPresent(), "本厂标签必须查得到 —— 否则这道隔离是把功能一起关了");
        assertEquals(MY_FACTORY, found.get().getFactoryId());
    }

    @Test
    @DisplayName("TS-2: 别厂追溯码 → 403 越权, 且措辞不得是「不存在」")
    void traceCode_otherFactory_throwsAuthorization() {
        String trace = "TRACE-F999-20260826-000001";
        when(labelRepository.findByTraceCodeAndDeletedAtIsNull(trace))
                .thenReturn(Optional.of(labelOf(OTHER_FACTORY, "YL-F999-20260826120000-0001", trace)));

        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> labelService.getByTraceCode(MY_FACTORY, trace),
                "别厂的 traceCode 必须被拒 —— 这正是原来那个跨租户泄露");
        assertEquals(403, ex.getCode(), "越权应映射成 403 (GlobalExceptionHandler:292), 不是 400");
        assertFalse(ex.getMessage().contains("不存在"),
                "⛔ 别把越权伪装成「标签不存在」—— 那会让真实的跨租户探测埋进 404 里: " + ex.getMessage());
        assertFalse(ex.getMessage().contains(OTHER_FACTORY),
                "拒绝要可区分, 但没必要把归属工厂号也回显给调用方: " + ex.getMessage());
    }

    @Test
    @DisplayName("TS-3: 阴性对照 — 库里真的没有这个码 → 空 Optional (404), 不是 403")
    void traceCode_absent_returnsEmpty() {
        when(labelRepository.findByTraceCodeAndDeletedAtIsNull("TRACE-NOBODY-00000000-000000"))
                .thenReturn(Optional.empty());

        Optional<Label> found = assertDoesNotThrow(
                () -> labelService.getByTraceCode(MY_FACTORY, "TRACE-NOBODY-00000000-000000"),
                "⛔ 反方向也要钉住: 把所有查不到都改成抛 403, TS-2 照样绿, 但 404 就没了");
        assertTrue(found.isEmpty());
    }

    // ──────────────── getByLabelCode (同一缺陷的第二处) ────────────────

    @Test
    @DisplayName("TS-4: 别厂标签码 → 403 (label_code 同样全局唯一, 只修 trace 一处就是半真半假)")
    void labelCode_otherFactory_throwsAuthorization() {
        String code = "YL-F999-20260826120000-0001";
        when(labelRepository.findByLabelCodeAndDeletedAtIsNull(code))
                .thenReturn(Optional.of(labelOf(OTHER_FACTORY, code, "TRACE-F999-20260826-000001")));

        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> labelService.getByLabelCode(MY_FACTORY, code));
        assertEquals(403, ex.getCode());
        assertFalse(ex.getMessage().contains("不存在"), ex.getMessage());
    }

    @Test
    @DisplayName("TS-5: 本厂标签码 → 正常返回")
    void labelCode_ownFactory_returns() {
        String code = "YL-F006-20260826120000-0001";
        when(labelRepository.findByLabelCodeAndDeletedAtIsNull(code))
                .thenReturn(Optional.of(labelOf(MY_FACTORY, code, "TRACE-F006-20260826-000001")));

        assertTrue(labelService.getByLabelCode(MY_FACTORY, code).isPresent());
    }

    // ──────────────── getLabelsByBatchId (第三处) ────────────────

    @Test
    @DisplayName("TS-6: 批次里混进别厂标签 → 抛 403, ⛔ 不许静默过滤成空列表")
    void batchId_foreignLabel_throwsInsteadOfFilteringToEmpty() {
        String batchId = "MB-2026-001";
        when(labelRepository.findByBatchIdAndDeletedAtIsNull(batchId))
                .thenReturn(List.of(labelOf(OTHER_FACTORY, "YL-F999-1", "TRACE-F999-20260826-000001")));

        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> labelService.getLabelsByBatchId(MY_FACTORY, batchId),
                "过滤成空列表会让越权长得和「这个批次没有标签」一模一样");
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("TS-7: 本厂批次 → 全部返回")
    void batchId_ownFactory_returnsAll() {
        String batchId = "MB-2026-001";
        when(labelRepository.findByBatchIdAndDeletedAtIsNull(batchId))
                .thenReturn(List.of(
                        labelOf(MY_FACTORY, "YL-F006-1", "TRACE-F006-20260826-000001"),
                        labelOf(MY_FACTORY, "YL-F006-2", "TRACE-F006-20260826-000002")));

        assertEquals(2, labelService.getLabelsByBatchId(MY_FACTORY, batchId).size());
    }

    @Test
    @DisplayName("TS-8: 阴性对照 — 批次没有任何标签 → 空列表, 不是 403")
    void batchId_noLabels_returnsEmptyList() {
        when(labelRepository.findByBatchIdAndDeletedAtIsNull("MB-EMPTY"))
                .thenReturn(List.of());

        assertTrue(assertDoesNotThrow(() -> labelService.getLabelsByBatchId(MY_FACTORY, "MB-EMPTY")).isEmpty());
    }

    // ──────────────── 产品真实入口: controller 接线 ────────────────
    //
    // 上面 8 条全都直接调 service, 绕过了「谁调它」。原缺陷恰恰就在那一层:
    // controller 收了 factoryId 却没往下传。所以下面这几条跑在 controller 上。

    @Test
    @DisplayName("TS-9: controller 必须把**路径上的** factoryId 传到服务层 (原缺陷就在这一步)")
    void controller_passesPathFactoryIdDownToService() {
        LabelController controller = new LabelController(labelServiceStub);
        String trace = "TRACE-F006-20260826-000001";
        when(labelServiceStub.getByTraceCode(MY_FACTORY, trace)).thenReturn(Optional.empty());

        controller.getByTraceCode(MY_FACTORY, trace);

        verify(labelServiceStub).getByTraceCode(MY_FACTORY, trace);
    }

    @Test
    @DisplayName("TS-10: controller 不得把越权压成 400 —— AuthorizationException 必须原样抛出")
    void controller_rethrowsAuthorizationExceptionInsteadOfSwallowingInto400() {
        LabelController controller = new LabelController(labelServiceStub);
        String trace = "TRACE-F999-20260826-000001";
        when(labelServiceStub.getByTraceCode(MY_FACTORY, trace))
                .thenThrow(new AuthorizationException("无权访问该标签: 它不属于当前工厂 " + MY_FACTORY));

        assertThrows(AuthorizationException.class,
                () -> controller.getByTraceCode(MY_FACTORY, trace),
                "被 catch(Exception) 吞掉就会变成 400 + 脱敏文案, 403 和 WARN 日志一起消失");
    }

    @Test
    @DisplayName("TS-11: 阴性对照 — 查不到时 controller 仍然返回 404, 没有被顺手改成抛异常")
    void controller_stillReturns404WhenAbsent() {
        LabelController controller = new LabelController(labelServiceStub);
        when(labelServiceStub.getByTraceCode(MY_FACTORY, "TRACE-NOBODY")).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.getByTraceCode(MY_FACTORY, "TRACE-NOBODY");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ──────────────── 结构闸 ────────────────

    @Test
    @DisplayName("TS-12: 不带 factoryId 的旧重载必须**不存在** —— 否则下一个人还能调到无隔离的那个")
    void unscopedOverloadsAreGone() throws Exception {
        // 阳性对照: 新的两参版本确实在 (否则下面三条 assertThrows 会因为方法名写错而恒真)
        assertNotNull(LabelService.class.getMethod("getByTraceCode", String.class, String.class));
        assertNotNull(LabelService.class.getMethod("getByLabelCode", String.class, String.class));
        assertNotNull(LabelService.class.getMethod("getLabelsByBatchId", String.class, String.class));

        assertThrows(NoSuchMethodException.class,
                () -> LabelService.class.getMethod("getByTraceCode", String.class));
        assertThrows(NoSuchMethodException.class,
                () -> LabelService.class.getMethod("getByLabelCode", String.class));
        assertThrows(NoSuchMethodException.class,
                () -> LabelService.class.getMethod("getLabelsByBatchId", String.class));
    }

    @Test
    @DisplayName("TS-13: 零调用方的两个端点必须有权限注解; /batch/ 的**故意不加**在此登记")
    void permissionGateRegistry() throws Exception {
        Method trace = LabelController.class.getDeclaredMethod("getByTraceCode", String.class, String.class);
        Method code = LabelController.class.getDeclaredMethod("getByLabelCode", String.class, String.class);
        Method batch = LabelController.class.getDeclaredMethod("getByBatchId", String.class, String.class);

        assertNotNull(trace.getAnnotation(RequirePermission.class),
                "/trace/{traceCode} 零调用方, 加权限闸零 403 风险 —— 必须有");
        assertNotNull(code.getAnnotation(RequirePermission.class),
                "/code/{labelCode} 同上");

        // ⚠️ 登记, 不是豁免。/batch/{batchId} 是三处里唯一有活调用方的
        // (WHBatchDetailScreen.tsx:220, 且是在 generateMaterialBatchLabel 失败的 catch
        // 分支里被调 —— 一个已经在生成那步被权限拒了的用户恰恰会走到这里)。
        // 本仓有多次「给读端点补 read 闸 → 线上 legacy write-only 角色全线 403」的先例,
        // 而权威权限来自 DB (platform_role_permissions), 本地读不到。
        // ⇒ 要加这道闸, 请对着 prod 角色矩阵实测之后再改这条断言, 不要顺手删掉它。
        assertNull(batch.getAnnotation(RequirePermission.class),
                "如果你是在补这道闸: 很好, 但请先对着 prod 角色矩阵验一遍仓储角色不会被 403, "
                        + "再更新这条登记 (见 LabelController.getByBatchId 的注释)");

        // 阳性对照: 类级没有注解, 所以上面读到的确实是方法级的那一个
        assertNull(LabelController.class.getAnnotation(RequirePermission.class),
                "类级若有注解, 方法级的有无就不再是判据了");
    }
}
