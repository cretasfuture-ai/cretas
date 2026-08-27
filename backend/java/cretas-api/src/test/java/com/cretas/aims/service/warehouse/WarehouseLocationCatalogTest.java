package com.cretas.aims.service.warehouse;

import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WarehouseLocationCatalog} 的行为测试。
 *
 * <p>⚠️ 与 {@code StorageLocationIsAControlledVocabularyTest} 分工不同：那道闸只管
 * <b>「每个写入点接上了没有」</b>，这里管 <b>「判得对不对」</b>。两层不混 ——
 * 闸绿不代表判据对，判据对不代表有人在调它。</p>
 *
 * <p>夹具用的编码是 2026-08-27 生产实测 F006 的真值
 * （{@code DK01/DK02/GC01/JD01/LC01/LC02/LC03}），⛔ 不自己编一套。</p>
 */
@DisplayName("储存位置受控词表判据")
class WarehouseLocationCatalogTest {

    private static final String FACTORY = "F006";

    /** 2026-08-27 生产实测: F006 配了 7 个位置。 */
    private static final List<String> REAL_CODES =
            List.of("DK01", "DK02", "GC01", "JD01", "LC01", "LC02", "LC03");

    private FactoryFeatureConfigRepository repository;
    private WarehouseLocationCatalog catalog;

    @BeforeEach
    void setUp() {
        repository = mock(FactoryFeatureConfigRepository.class);
        catalog = new WarehouseLocationCatalog(repository);
        configure(REAL_CODES);
    }

    private void configure(List<String> codes) {
        FactoryFeatureConfig config = new FactoryFeatureConfig();
        config.setConfig(Map.of("locations",
                codes.stream().map(c -> Map.<String, Object>of("code", c, "name", c + " 位")).toList()));
        when(repository.findByFactoryIdAndModuleIdAndDeletedAtIsNull(eq(FACTORY), any()))
                .thenReturn(Optional.of(config));
    }

    // ── 放行 ─────────────────────────────────────────────

    @Test
    @DisplayName("已配置的编码原样放行")
    void configuredCodePasses() {
        assertThat(catalog.requireConfiguredLocation(FACTORY, "LC01")).isEqualTo("LC01");
    }

    @Test
    @DisplayName("前后空格被 trim 掉, 而不是判成不存在")
    void trimsBeforeComparing() {
        assertThat(catalog.requireConfiguredLocation(FACTORY, "  DK02 ")).isEqualTo("DK02");
    }

    @Test
    @DisplayName("null = 未分配, 是合法状态 —— ⛔ 不许当成缺陷去拦")
    void nullMeansUnassigned() {
        // 2026-08-27 生产实测: F006 的 139 条原料批次 storage_location 全是 NULL。
        // 把「没填」拦成错误, 等于把一个正常业务状态变成故障。
        assertThat(catalog.requireConfiguredLocation(FACTORY, null)).isNull();
    }

    @Test
    @DisplayName("空串 = 显式清空, 归一成 null")
    void blankClearsToNull() {
        assertThat(catalog.requireConfiguredLocation(FACTORY, "   ")).isNull();
    }

    // ── 拦住 ─────────────────────────────────────────────

    @Test
    @DisplayName("🔴 自由文本被拦: 「冷冻库」不是编码")
    void freeTextIsRejected() {
        // 这个字面量来自 RN 的 MaterialBatchManagementScreen「转冻品」,
        // 2026-08-27 实测它在全库任何工厂都不是合法编码(0 命中)。
        assertThatThrownBy(() -> catalog.requireConfiguredLocation(FACTORY, "冷冻库"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在本厂仓储配置中");
    }

    @Test
    @DisplayName("🔴 来源描述被拦 —— 生产上真出现过这种值")
    void sourceDescriptionIsRejected() {
        assertThatThrownBy(() -> catalog.requireConfiguredLocation(
                FACTORY, "Workflow terminal output warehouse receipt"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("长度超限被拦")
    void overlongIsRejected() {
        assertThatThrownBy(() -> catalog.requireConfiguredLocation(FACTORY, "X".repeat(101)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("别厂的编码不算数 —— 受控是按工厂受控")
    void anotherFactorysCodeIsRejected() {
        when(repository.findByFactoryIdAndModuleIdAndDeletedAtIsNull(eq("LIUSHANMEN"), any()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> catalog.requireConfiguredLocation("LIUSHANMEN", "LC01"))
                .isInstanceOf(BusinessException.class);
    }

    // ── fail closed ──────────────────────────────────────

    @Test
    @DisplayName("一个位置都没配时, 任何赋位置都被拒(fail closed)")
    void failsClosedWhenNothingConfigured() {
        configure(List.of());
        assertThatThrownBy(() -> catalog.requireConfiguredLocation(FACTORY, "LC01"))
                .isInstanceOf(BusinessException.class);
        // ⚠️ 但 null 仍然放行 —— 没配位置不代表不能存在「未分配」的批次
        assertThat(catalog.requireConfiguredLocation(FACTORY, null)).isNull();
    }

    @Test
    @DisplayName("配置结构不对时当成没配, 而不是当成什么都合法")
    void malformedConfigFailsClosed() {
        FactoryFeatureConfig config = new FactoryFeatureConfig();
        config.setConfig(Map.of("locations", "这不是数组"));
        when(repository.findByFactoryIdAndModuleIdAndDeletedAtIsNull(eq(FACTORY), any()))
                .thenReturn(Optional.of(config));
        assertThat(catalog.configuredCodes(FACTORY)).isEmpty();
        assertThatThrownBy(() -> catalog.requireConfiguredLocation(FACTORY, "LC01"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("阳性对照: 夹具真的装进去了 7 个编码")
    void fixtureActuallyLoads() {
        // 没有这一条, 上面那些「被拒」可能只是因为配置根本没读出来。
        assertThat(catalog.configuredCodes(FACTORY)).containsExactlyInAnyOrderElementsOf(REAL_CODES);
    }
}
