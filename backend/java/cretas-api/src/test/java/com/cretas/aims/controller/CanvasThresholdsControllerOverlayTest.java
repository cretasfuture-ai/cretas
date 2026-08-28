package com.cretas.aims.controller;

import com.cretas.aims.entity.canvas.FactoryThreshold;
import com.cretas.aims.entity.canvas.ThresholdCategory;
import com.cretas.aims.entity.canvas.ThresholdValueType;
import com.cretas.aims.repository.canvas.FactoryThresholdRepository;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 钉住「工厂配置页看到的是<b>有效配置</b>，不是『这张表里 factory_id 等于我的那些行』」。
 *
 * <h2>它修的缺陷（2026-08-29 生产实测）</h2>
 * 列表接口原来严格按 {@code factory_id} 过滤，于是全局 {@code '*'} 行在任何工厂页面上都看不见：
 *
 * <table border="1">
 *   <tr><th>factory_id</th><th>行数</th><th>后果</th></tr>
 *   <tr><td>{@code '*'}</td><td>1 (sales.delivery.over_ship_rate)</td>
 *       <td>🔴 <b>所有工厂都配不了它</b> —— 一个专为「让它可配」而建的页面恰恰配不了它</td></tr>
 *   <tr><td>{@code F006}</td><td>23</td><td>只有这一家看得到东西</td></tr>
 *   <tr><td>{@code LIUSHANMEN}(真实客户)</td><td><b>0</b></td>
 *       <td>🔴 管理员看到 15 个<b>空分类</b>, 而解析器一直在用全局默认值跑</td></tr>
 *   <tr><td>{@code MOCK_REST}</td><td><b>0</b></td><td>同上</td></tr>
 * </table>
 *
 * <p>⚠️ 症状是「界面上什么都没有」，而这在每一项判据上都长得像「这个工厂没有配置」——
 * 它<b>不报错</b>，接口返回 200，列表返回空数组。
 *
 * <h2>⛔ 为什么不能靠补 seed 解决</h2>
 * 给已知工厂补 per-factory 行只治标：<b>下一个新建的工厂照样全空</b>。
 * 而且那会制造 {@code N × M} 份重复数据，改全局默认值再也推不下去。
 *
 * <h2>每条正向断言都配了阴性对照</h2>
 * 尤其是 {@link #globalPathItselfIsNotOverlaid()} —— 若少了它，
 * 叠加逻辑可能在管理全局行时把自己叠给自己而没人发现。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CanvasThresholdsControllerOverlayTest {

    @Mock FactoryThresholdRepository repository;
    @Mock ThresholdResolverService resolver;

    CanvasThresholdsController controller;

    private static final String FACTORY = "LIUSHANMEN";
    private static final String GLOBAL = FactoryThreshold.GLOBAL_FALLBACK_FACTORY_ID;

    @BeforeEach
    void setUp() {
        controller = new CanvasThresholdsController(repository, resolver);
    }

    private FactoryThreshold row(String factoryId, String key, String value, ThresholdCategory cat) {
        FactoryThreshold t = FactoryThreshold.builder()
                .factoryId(factoryId).thresholdKey(key).category(cat)
                .valueType(ThresholdValueType.DECIMAL)
                .thresholdValue(value).defaultValue(value).enabled(true).build();
        t.setId(UUID.randomUUID());
        return t;
    }

    private void stub(String factoryId, ThresholdCategory cat, List<FactoryThreshold> rows) {
        when(repository.findByFactoryIdAndCategoryOrderByThresholdKey(eq(factoryId), eq(cat)))
                .thenReturn(rows);
        when(repository.findByFactoryIdOrderByCategoryAscThresholdKeyAsc(eq(factoryId)))
                .thenReturn(rows);
    }

    private List<FactoryThreshold> listSales(String factoryId) {
        return controller.list(factoryId, "SALES").getData();
    }

    // ── 缺陷本体 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 工厂一行都没有时, 仍要看到全局默认值 (原实现返回空数组)")
    void factoryWithNoRowsStillSeesGlobalDefaults() {
        stub(FACTORY, ThresholdCategory.SALES, List.of());
        stub(GLOBAL, ThresholdCategory.SALES,
                List.of(row(GLOBAL, "sales.delivery.over_ship_rate", "0.10", ThresholdCategory.SALES)));

        List<FactoryThreshold> got = listSales(FACTORY);

        assertThat(got).as("空数组 = 管理员看到一个空页面, 而解析器正在用 0.10 跑").hasSize(1);
        assertThat(got.get(0).getThresholdKey()).isEqualTo("sales.delivery.over_ship_rate");
        assertThat(got.get(0).getInherited())
                .as("必须标出来 —— 否则用户分不清这是本厂设的还是全局共用的")
                .isTrue();
    }

    @Test
    @DisplayName("工厂有自己的 override 时, 用它, 且【不】标继承")
    void ownRowWins() {
        stub(FACTORY, ThresholdCategory.SALES,
                List.of(row(FACTORY, "sales.delivery.over_ship_rate", "0.25", ThresholdCategory.SALES)));
        stub(GLOBAL, ThresholdCategory.SALES,
                List.of(row(GLOBAL, "sales.delivery.over_ship_rate", "0.10", ThresholdCategory.SALES)));

        List<FactoryThreshold> got = listSales(FACTORY);

        assertThat(got).as("⛔ 同一个 key 不能同时显示两行 —— 用户会不知道哪条在生效").hasSize(1);
        assertThat(got.get(0).getThresholdValue()).isEqualTo("0.25");
        assertThat(got.get(0).getInherited()).isNull();
    }

    @Test
    @DisplayName("部分覆盖: 自己有 A, 全局有 A+B → A 用自己的, B 继承")
    void partialOverrideMerges() {
        stub(FACTORY, ThresholdCategory.SALES,
                List.of(row(FACTORY, "sales.a", "1", ThresholdCategory.SALES)));
        stub(GLOBAL, ThresholdCategory.SALES, List.of(
                row(GLOBAL, "sales.a", "9", ThresholdCategory.SALES),
                row(GLOBAL, "sales.b", "9", ThresholdCategory.SALES)));

        List<FactoryThreshold> got = listSales(FACTORY);

        assertThat(got).hasSize(2);
        FactoryThreshold a = got.stream().filter(t -> t.getThresholdKey().equals("sales.a")).findFirst().orElseThrow();
        FactoryThreshold b = got.stream().filter(t -> t.getThresholdKey().equals("sales.b")).findFirst().orElseThrow();
        assertThat(a.getThresholdValue()).isEqualTo("1");
        assertThat(a.getInherited()).isNull();
        assertThat(b.getInherited()).isTrue();
    }

    // ── 阴性对照 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 阴性对照: 直接管理全局路径('*')时不叠加 —— 否则自己叠自己")
    void globalPathItselfIsNotOverlaid() {
        stub(GLOBAL, ThresholdCategory.SALES,
                List.of(row(GLOBAL, "sales.delivery.over_ship_rate", "0.10", ThresholdCategory.SALES)));

        List<FactoryThreshold> got = listSales(GLOBAL);

        assertThat(got).hasSize(1);
        assertThat(got.get(0).getInherited())
                .as("管理全局行时它就是本体, 不是继承来的")
                .isNull();
    }

    @Test
    @DisplayName("阴性对照: 全局也没有这一类时, 返回空 —— ⛔ 不许凭空造行")
    void nothingAnywhereReturnsEmpty() {
        stub(FACTORY, ThresholdCategory.SALES, List.of());
        stub(GLOBAL, ThresholdCategory.SALES, List.of());

        assertThat(listSales(FACTORY))
                .as("没有就是没有 —— 叠加不能变成「总能返回点什么」")
                .isEmpty();
    }

    @Test
    @DisplayName("不带 category 的全量查询同样叠加 (两条路径不能只修一条)")
    void unfilteredListAlsoOverlays() {
        when(repository.findByFactoryIdOrderByCategoryAscThresholdKeyAsc(eq(FACTORY)))
                .thenReturn(List.of());
        when(repository.findByFactoryIdOrderByCategoryAscThresholdKeyAsc(eq(GLOBAL)))
                .thenReturn(List.of(row(GLOBAL, "sales.delivery.over_ship_rate", "0.10",
                        ThresholdCategory.SALES)));

        List<FactoryThreshold> got = controller.list(FACTORY, null).getData();

        assertThat(got).as("⛔ 只修带 category 的那条路径 = 又一个「只改一处」的漂移").hasSize(1);
        assertThat(got.get(0).getInherited()).isTrue();
    }
}
