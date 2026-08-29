package com.cretas.aims.service.canvas.impl;

import com.cretas.aims.entity.canvas.FactoryThreshold;
import com.cretas.aims.entity.canvas.ThresholdCategory;
import com.cretas.aims.entity.canvas.ThresholdValueType;
import com.cretas.aims.repository.canvas.FactoryThresholdRepository;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钉住「<b>闸门类</b>阈值不吃 5 分钟缓存」。
 *
 * <h2>它修的缺陷</h2>
 * 解析器的本地 Caffeine 缓存 TTL 是 5 分钟，写入后只失效<b>本节点</b>
 * （类注释自己写着「跨节点失效暂未实现」）。对预警红黄线无所谓；
 * 但 {@code sales.delivery.over_ship_rate} 是一道<b>会 409 拦货</b>的闸：
 * 多实例部署时管理员在 A 节点把上限调宽了，B 节点最长 5 分钟内仍按旧值拦货。
 *
 * <p>⚠️ 现场看到的是「<b>我刚调宽了怎么还是发不了</b>」，而这条路径上
 * <b>每一层都不报错</b>：接口 200、审计里有那次修改、日志里那次拦截也"合法"。
 *
 * <h2>⚠️ 仪器: 为什么必须注入 Ticker</h2>
 * 「两档 TTL」是一个<b>与时间有关</b>的行为。不推进时钟就只能断言
 * 「有两个不同的 Cache 对象」——那守的是实现细节，不是行为，
 * 而且把 TTL 从 10 秒改成 5 分钟它照样绿。
 * 所以这里注入一个可手动推进的 {@link Ticker}，让每条断言都跑在
 * <b>「时钟走了 N 秒之后」</b>这个真实条件上，且不用 sleep。
 *
 * <h2>每条正向断言都配了阴性对照</h2>
 * <ul>
 *   <li>{@link #gatingKeyReReadsAfterShortTtl()} ←→ {@link #nonGatingKeyStillCachedAtSameInstant()}
 *       —— <b>同一个 ticker、同一次推进</b>。若少了阴性那条，
 *       「把两档 TTL 都改短」也会绿，而那不是本次要的东西。</li>
 *   <li>{@link #gatingKeyIsStillCachedWithinItsTtl()} —— 证明它是<b>短 TTL</b> 而不是
 *       「干脆不缓存」；后者会把 {@code ABSENT_SENTINEL} 那层保护一起去掉。</li>
 *   <li>{@link #registryIsNotEmpty()} —— 阳性对照。若登记表为空，
 *       本类里所有「闸门 key」其实都走了普通那一档，全部断言集体失去意义。</li>
 * </ul>
 */
@DisplayName("闸门类阈值的缓存 TTL")
@ExtendWith(MockitoExtension.class)
class ThresholdResolverGatingCacheTest {

    /** 闸门类 —— 读到旧值会 409 把货挡在门口。 */
    private static final String GATING_KEY = ThresholdKeys.SALES_DELIVERY_OVER_SHIP_RATE;
    /** 预警类 —— 读到旧值只是看板晚一会儿变色。 */
    private static final String ADVISORY_KEY = ThresholdKeys.INVENTORY_TURNOVER_RED;

    private static final String FACTORY = "LIUSHANMEN";
    private static final String GLOBAL = FactoryThreshold.GLOBAL_FALLBACK_FACTORY_ID;

    @Mock
    private FactoryThresholdRepository repository;

    private MutableTicker ticker;
    private ThresholdResolverServiceImpl resolver;

    /** 可手动推进的时钟 —— Caffeine 的 {@link Ticker} 读的是纳秒。 */
    private static final class MutableTicker implements Ticker {
        private long nanos = 0L;

        @Override
        public long read() {
            return nanos;
        }

        void advanceSeconds(long seconds) {
            nanos += TimeUnit.SECONDS.toNanos(seconds);
        }
    }

    @BeforeEach
    void setUp() {
        ticker = new MutableTicker();
        resolver = new ThresholdResolverServiceImpl(repository);
        // ⚠️ 用 seam 换时钟, ⛔ 不是加第二个构造器 —— 后者会让 Mockito 的 @InjectMocks
        //    挑到它并塞 null Ticker, 把【别的类】的测试整片打红 (2026-08-29 实测)。
        resolver.useTickerForTest(ticker);
    }

    private static FactoryThreshold row(String factoryId, String key, String value) {
        FactoryThreshold t = new FactoryThreshold();
        t.setFactoryId(factoryId);
        t.setThresholdKey(key);
        t.setCategory(ThresholdCategory.SALES);
        t.setValueType(ThresholdValueType.DECIMAL);
        t.setThresholdValue(value);
        t.setDefaultValue(value);
        t.setEnabled(true);
        return t;
    }

    // ── 阳性对照: 仪器活着吗 ─────────────────────────────────────────────

    @Test
    @DisplayName("阳性对照: 登记表非空且这两个 key 真的分属两档 —— 否则下面的对照是同一条路跑两遍")
    void registryIsNotEmpty() {
        assertThat(ThresholdKeys.GATING_KEYS)
                .as("登记表为空 ⇒ 本类里每一个「闸门 key」其实都走普通那一档, 所有断言集体作废")
                .isNotEmpty();
        assertThat(ThresholdKeys.isGating(GATING_KEY))
                .as("超发上限是会 409 拦货的闸, 必须在登记表里")
                .isTrue();
        assertThat(ThresholdKeys.isGating(ADVISORY_KEY))
                .as("⛔ 阴性: 预警红线不该被登记成闸门 —— 否则「两档」退化成「一档」")
                .isFalse();
        assertThat(ThresholdKeys.isGating(null))
                .as("null 按非闸门处理, 不许 NPE")
                .isFalse();
    }

    // ── 缺陷本体 + 它的阴性对照 (同一个时钟, 同一次推进) ────────────────

    @Test
    @DisplayName("🔴 闸门 key: 管理员改宽了上限, 别的节点在短 TTL 之后必须读到新值 (原实现要等 5 分钟)")
    void gatingKeyReReadsAfterShortTtl() {
        // 库里先是 0.10, 管理员在【另一个节点】改成 0.30 —— 本节点没有收到任何失效通知。
        when(repository.findByFactoryIdAndThresholdKey(FACTORY, GATING_KEY))
                .thenReturn(Optional.of(row(FACTORY, GATING_KEY, "0.10")))
                .thenReturn(Optional.of(row(FACTORY, GATING_KEY, "0.30")));

        BigDecimal before = resolver.getBigDecimal(FACTORY, GATING_KEY, null);
        ticker.advanceSeconds(ThresholdResolverServiceImpl.GATING_CACHE_TTL_SECONDS + 1);
        BigDecimal after = resolver.getBigDecimal(FACTORY, GATING_KEY, null);

        // 「两次调用确实分别发生」的证据: 仓储被真的问了两次, 不是同一个读数印了两遍。
        verify(repository, times(2)).findByFactoryIdAndThresholdKey(FACTORY, GATING_KEY);
        assertThat(before).isEqualByComparingTo("0.10");
        assertThat(after)
                .as("仍读到 0.10 ⇒ 管理员刚调宽的上限在这个节点上还要再拦 5 分钟的货")
                .isEqualByComparingTo("0.30");
    }

    @Test
    @DisplayName("⛔ 阴性对照: 同一时刻、同一个时钟, 预警 key 仍然命中缓存 —— 差异必须来自 key 的分档, 不是「时钟走了」")
    void nonGatingKeyStillCachedAtSameInstant() {
        when(repository.findByFactoryIdAndThresholdKey(FACTORY, ADVISORY_KEY))
                .thenReturn(Optional.of(row(FACTORY, ADVISORY_KEY, "6")))
                .thenReturn(Optional.of(row(FACTORY, ADVISORY_KEY, "9")));

        BigDecimal before = resolver.getBigDecimal(FACTORY, ADVISORY_KEY, null);
        ticker.advanceSeconds(ThresholdResolverServiceImpl.GATING_CACHE_TTL_SECONDS + 1);
        BigDecimal after = resolver.getBigDecimal(FACTORY, ADVISORY_KEY, null);

        verify(repository, times(1)).findByFactoryIdAndThresholdKey(FACTORY, ADVISORY_KEY);
        assertThat(before).isEqualByComparingTo("6");
        assertThat(after)
                .as("⛔ 若这里也变成 9, 说明改动把【所有】key 的 TTL 都缩短了 —— 那是另一件事, 代价是 DB 负载")
                .isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("⛔ 阴性对照: 闸门 key 在自己的 TTL 【以内】仍然命中缓存 —— 它是短 TTL, 不是「不走缓存」")
    void gatingKeyIsStillCachedWithinItsTtl() {
        when(repository.findByFactoryIdAndThresholdKey(FACTORY, GATING_KEY))
                .thenReturn(Optional.of(row(FACTORY, GATING_KEY, "0.10")))
                .thenReturn(Optional.of(row(FACTORY, GATING_KEY, "0.30")));

        resolver.getBigDecimal(FACTORY, GATING_KEY, null);
        ticker.advanceSeconds(ThresholdResolverServiceImpl.GATING_CACHE_TTL_SECONDS - 1);
        BigDecimal within = resolver.getBigDecimal(FACTORY, GATING_KEY, null);

        verify(repository, times(1)).findByFactoryIdAndThresholdKey(FACTORY, GATING_KEY);
        assertThat(within)
                .as("完全不缓存也会让上面那条正向断言变绿 —— 这一条把两者分开; "
                        + "不缓存等于把 ABSENT_SENTINEL 那层保护也去掉")
                .isEqualByComparingTo("0.10");
    }

    @Test
    @DisplayName("闸门 key 走全局 '*' 回落时同样是短 TTL (分档按 key, ⛔ 不是按 factoryId)")
    void gatingKeyIsShortTtlOnTheGlobalFallbackRowToo() {
        when(repository.findByFactoryIdAndThresholdKey(FACTORY, GATING_KEY))
                .thenReturn(Optional.empty());
        when(repository.findByFactoryIdAndThresholdKey(GLOBAL, GATING_KEY))
                .thenReturn(Optional.of(row(GLOBAL, GATING_KEY, "0.10")))
                .thenReturn(Optional.of(row(GLOBAL, GATING_KEY, "0.30")));

        BigDecimal before = resolver.getBigDecimal(FACTORY, GATING_KEY, null);
        ticker.advanceSeconds(ThresholdResolverServiceImpl.GATING_CACHE_TTL_SECONDS + 1);
        BigDecimal after = resolver.getBigDecimal(FACTORY, GATING_KEY, null);

        assertThat(before).isEqualByComparingTo("0.10");
        assertThat(after)
                .as("工厂没有 override 时闸值来自全局行 —— 那一行若还按 5 分钟缓存, 修了等于没修")
                .isEqualByComparingTo("0.30");
        verify(repository, times(2)).findByFactoryIdAndThresholdKey(GLOBAL, GATING_KEY);
    }

    // ── 失效仍然覆盖两档 ────────────────────────────────────────────────

    @Test
    @DisplayName("invalidate 对闸门 key 立即生效 (写入节点不必等 TTL)")
    void invalidateStillWorksForGatingKey() {
        when(repository.findByFactoryIdAndThresholdKey(FACTORY, GATING_KEY))
                .thenReturn(Optional.of(row(FACTORY, GATING_KEY, "0.10")))
                .thenReturn(Optional.of(row(FACTORY, GATING_KEY, "0.30")));

        resolver.getBigDecimal(FACTORY, GATING_KEY, null);
        resolver.invalidate(FACTORY, GATING_KEY);   // controller 写入后调的就是它

        assertThat(resolver.getBigDecimal(FACTORY, GATING_KEY, null)).isEqualByComparingTo("0.30");
        verify(repository, times(2)).findByFactoryIdAndThresholdKey(FACTORY, GATING_KEY);
    }

    @Test
    @DisplayName("invalidateFactory / invalidateAll 覆盖【两档】—— 漏一档会留下失效不掉的僵尸条目")
    void bulkInvalidationCoversBothTiers() {
        when(repository.findByFactoryIdAndThresholdKey(FACTORY, GATING_KEY))
                .thenReturn(Optional.of(row(FACTORY, GATING_KEY, "0.10")))
                .thenReturn(Optional.of(row(FACTORY, GATING_KEY, "0.30")));
        when(repository.findByFactoryIdAndThresholdKey(FACTORY, ADVISORY_KEY))
                .thenReturn(Optional.of(row(FACTORY, ADVISORY_KEY, "6")))
                .thenReturn(Optional.of(row(FACTORY, ADVISORY_KEY, "9")));

        resolver.getBigDecimal(FACTORY, GATING_KEY, null);
        resolver.getBigDecimal(FACTORY, ADVISORY_KEY, null);
        resolver.invalidateFactory(FACTORY);

        assertThat(resolver.getBigDecimal(FACTORY, GATING_KEY, null))
                .as("闸门那一档没被 invalidateFactory 扫到")
                .isEqualByComparingTo("0.30");
        assertThat(resolver.getBigDecimal(FACTORY, ADVISORY_KEY, null))
                .as("普通那一档没被 invalidateFactory 扫到")
                .isEqualByComparingTo("9");
    }
}
