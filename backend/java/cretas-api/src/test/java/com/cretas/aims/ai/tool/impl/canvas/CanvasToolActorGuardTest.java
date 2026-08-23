package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.config.FactoryConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Canvas 配置工具的操作人：要么是真人，要么<b>明说没有</b> —— ⛔ 不许是 0，也不许是 null。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>这三个工具原来都写着 {@code configService.xxx(..., 0L)}。那个 {@code 0L} 顺着
 * {@code FactoryConfigServiceImpl#getOrCreateDraft} 进
 * {@code factory_configurations.created_by}。
 * prod 实测：{@code users} 表 79 行，{@code min(id) = 1309}，{@code id <= 10} 的行数是
 * <b>0</b> —— 那个「系统账号」不存在。
 *
 * <h2>🔴 为什么不是简单换成 {@code getUserId(context)}</h2>
 *
 * <p>{@code factory_configurations.created_by} 是 <b>NOT NULL</b>（prod 实测，无外键）。
 * 而 {@code getUserId(context)} 取不到会返回 {@code null} ——
 * 那会把「安静写错数据」换成「NOT NULL 约束违例 500」，
 * 报出来的还是 {@code null value in column "created_by"}，与「这个操作没有操作人」对不上。
 *
 * <p>而且这条路<b>真的会走到</b>：{@code CanvasAIController#buildToolContext} 解析 token
 * 失败时只 {@code log.warn}，{@code userId} 根本不会进 context。
 *
 * <p>⇒ 用 {@code requireUserId} 显式失败。三档必须都测到：
 * <b>有真人 → 传真人</b> / <b>没有 → 抛且不落库</b> / <b>⛔ 任何一档都不许出现 0L</b>。
 *
 * <p>⚠️ 阴性断言一律 {@code any()}，⛔ 不用 {@code anyLong()} ——
 * 它<b>不匹配 null</b>，本仓 2026-08-15 因此写出过一条两条路都成立的空断言。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Canvas 配置工具: 操作人要么是真人, 要么明说没有")
class CanvasToolActorGuardTest {

    private static final String FACTORY_ID = "F006";
    /** prod {@code users.min(id)} —— 一个真实存在的人。 */
    private static final Long REAL_USER = 1309L;

    @Mock private FactoryConfigService configService;

    private static Map<String, Object> contextWith(Long userId) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        if (userId != null) {
            ctx.put("userId", userId);
        }
        return ctx;
    }

    private <T> T wire(T tool) {
        ReflectionTestUtils.setField(tool, "configService", configService);
        return tool;
    }

    // ───────────────────────── toggleModule ─────────────────────────

    @Test
    @DisplayName("有真人 → 原样传给 toggleModule")
    void toggleModule_passesTheRealOperator() throws Exception {
        CanvasToggleModuleTool tool = wire(new CanvasToggleModuleTool());

        tool.doExecute(FACTORY_ID, Map.of("moduleCode", "purchase", "enabled", true),
                contextWith(REAL_USER));

        verify(configService).toggleModule(FACTORY_ID, "purchase", true, REAL_USER);
        // ⛔ 那个写死的 0 不许回来
        verify(configService, never()).toggleModule(anyString(), anyString(), any(Boolean.class), eq(0L));
    }

    @Test
    @DisplayName("没有操作人 → 抛 401 且一行都不写")
    void toggleModule_refusesWhenOperatorUnknown() {
        CanvasToggleModuleTool tool = wire(new CanvasToggleModuleTool());

        assertThatThrownBy(() -> tool.doExecute(
                FACTORY_ID, Map.of("moduleCode", "purchase", "enabled", true), contextWith(null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(401))
                .hasMessageContaining("无法确定操作人");

        // 最要紧的一条: 不是「写了个 null 进去然后 DB 报错」, 是**根本没调**。
        verifyNoInteractions(configService);
    }

    // ───────────────────────── updateFieldConfig ─────────────────────────

    @Test
    @DisplayName("有真人 → 原样传给 updateFieldConfig")
    void updateFieldConfig_passesTheRealOperator() throws Exception {
        CanvasUpdateFieldTool tool = wire(new CanvasUpdateFieldTool());

        tool.doExecute(FACTORY_ID,
                Map.of("moduleCode", "purchase", "fieldCode", "supplier",
                        "property", "label", "value", "供应商"),
                contextWith(REAL_USER));

        verify(configService).updateFieldConfig(
                eq(FACTORY_ID), eq("purchase"), eq("supplier"), any(), eq(REAL_USER));
        verify(configService, never()).updateFieldConfig(
                anyString(), anyString(), anyString(), any(), eq(0L));
    }

    @Test
    @DisplayName("没有操作人 → 抛 401 且一行都不写")
    void updateFieldConfig_refusesWhenOperatorUnknown() {
        CanvasUpdateFieldTool tool = wire(new CanvasUpdateFieldTool());

        assertThatThrownBy(() -> tool.doExecute(FACTORY_ID,
                Map.of("moduleCode", "purchase", "fieldCode", "supplier",
                        "property", "label", "value", "供应商"),
                contextWith(null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法确定操作人");

        verifyNoInteractions(configService);
    }

    // ───────────────────────── applyTemplate ─────────────────────────

    @Test
    @DisplayName("有真人 → 原样传给 applyTemplate")
    void applyTemplate_passesTheRealOperator() throws Exception {
        CanvasApplyTemplateTool tool = wire(new CanvasApplyTemplateTool());

        tool.doExecute(FACTORY_ID, Map.of("templateCode", "SEAFOOD"), contextWith(REAL_USER));

        verify(configService).applyTemplate(FACTORY_ID, "SEAFOOD", REAL_USER);
        verify(configService, never()).applyTemplate(anyString(), anyString(), eq(0L));
    }

    @Test
    @DisplayName("没有操作人 → 抛 401 且一行都不写")
    void applyTemplate_refusesWhenOperatorUnknown() {
        CanvasApplyTemplateTool tool = wire(new CanvasApplyTemplateTool());

        assertThatThrownBy(() -> tool.doExecute(
                FACTORY_ID, Map.of("templateCode", "SEAFOOD"), contextWith(null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法确定操作人");

        verifyNoInteractions(configService);
    }
}
