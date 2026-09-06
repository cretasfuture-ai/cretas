package com.cretas.aims.service;

import com.cretas.aims.mapper.CustomerMapper;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.service.impl.CustomerServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * owner 裁定 (2026-09-06): 「客户名单只有 销售、财务、工厂管理员、运营 能看。」
 *
 * <p>{@code GET /customers/overall-statistics} 被判为<b>档 C(故意不关)</b>，理由是
 * 「一个客户身份都不吐，全是聚合」。⚠️ 这个理由<b>从签名上读不出来</b> ——
 * 它返回 {@code Map<String, Object>}，那个签名装得下任何东西，包括一个
 * {@code topCustomers: [...]}。判档靠的是<b>读实现</b>，而读来的结论会随实现漂。</p>
 *
 * <h3>本测试把那个结论钉成可执行的</h3>
 * <p>它真的调一次 {@link CustomerServiceImpl#getOverallCustomerStatistics}(仓库层打桩)，
 * 断言 key 集合<b>恰好</b>是那 8 个聚合 key。谁加了第 9 个 key，这里当场红 ——
 * ⛔ 那时的正确动作是<b>回去重新判档</b>(含客户名/ id / top-N ⇒ 改判档 A、挂裁定四码)，
 * 而不是把新 key 补进下面的集合。</p>
 *
 * <p>🔴 为什么值得单独一条: 三个「维度 -&gt; 计数」的嵌套表 (type/industry/rating) 也在这里
 * <b>真的跑了一遍</b>并被断言只含维度与计数 —— 那三张表是最容易被人「顺手加个客户名方便看」的地方。</p>
 *
 * @see com.cretas.aims.controller.CustomerControllerRemainingReadPathsPermissionTest 12 条已关路径的门
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("/customers/overall-statistics 判档 C 的依据: key 集合恰好 8 个聚合 key, 无客户身份")
class CustomerOverallStatisticsKeysContractTest {

    private static final String FACTORY = "F006";

    /**
     * 判档 C 的<b>全部依据</b>。⛔ 加 key 之前请先回答: 新 key 里有没有客户名 / 客户 id /
     * top-N 客户？有 ⇒ 本端点改判档 A，挂
     * {@code sales:read_write|sales:read|finance:read_write|finance:read}。
     */
    private static final Set<String> EXPECTED_KEYS = Set.of(
            "totalCustomers",            // long   客户总数
            "activeCustomers",           // long   活跃客户数
            "averageRating",             // Double 平均评级
            "totalOutstandingBalance",   // BigDecimal 全厂总欠款 (不分客户)
            "totalCreditLimit",          // BigDecimal 全厂总信用额度
            "typeDistribution",          // Map<String,Long>  类型 -> 计数
            "industryDistribution",      // Map<String,Long>  行业 -> 计数
            "ratingDistribution");       // Map<Integer,Long> 评级 -> 计数

    @Mock private CustomerRepository customerRepository;
    @Mock private CustomerMapper customerMapper;
    @Mock private ExcelUtil excelUtil;

    private CustomerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CustomerServiceImpl(customerRepository, customerMapper, excelUtil);
    }

    /**
     * 打桩里<b>故意塞了会暴露身份的值</b>作为阳性对照的一半:
     * 类型/行业维度用真实业务字面量，评级用真实分档。如果实现某天把客户名塞进维度键，
     * 下面 {@code keysAreExactlyTheEightAggregates} 不一定看得见 —— 所以另有一条
     * {@code nestedDistributionsCarryNoCustomerIdentity} 逐层检查嵌套表的键类型。
     */
    private void stubRepository() {
        when(customerRepository.countByFactoryId(FACTORY)).thenReturn(17L);
        when(customerRepository.countByFactoryIdAndIsActive(FACTORY, true)).thenReturn(15L);
        when(customerRepository.calculateAverageRating(FACTORY)).thenReturn(3.5);
        when(customerRepository.calculateTotalOutstandingBalance(FACTORY))
                .thenReturn(new BigDecimal("-2000.00"));
        when(customerRepository.calculateTotalCreditLimit(FACTORY))
                .thenReturn(new BigDecimal("500000.00"));
        when(customerRepository.countByType(FACTORY))
                .thenReturn(List.of(new Object[] {"DEALER", 9L}, new Object[] {null, 2L}));
        when(customerRepository.countByIndustry(FACTORY))
                .thenReturn(List.of(new Object[] {"餐饮", 7L}, new Object[] {null, 1L}));
        when(customerRepository.getCustomerRatingDistribution(FACTORY))
                .thenReturn(List.of(new Object[] {Integer.valueOf(5), 4L},
                        new Object[] {null, 3L}));
    }

    @Test
    @DisplayName("恰好 8 个 key, 一个不多 —— 多出来的那个请先回答「它含不含客户身份」")
    void keysAreExactlyTheEightAggregates() {
        stubRepository();

        Map<String, Object> stats = service.getOverallCustomerStatistics(FACTORY);

        assertThat(stats.keySet())
                .as("档 C 的依据是「一个客户身份都不吐」。多出一个 key ⇒ 先重新判档, "
                        + "⛔ 不要直接把它加进 EXPECTED_KEYS")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_KEYS);
    }

    /**
     * 阴性对照的实质那半: 逐个 key 检查<b>值里认不出任何一个具体客户</b>。
     *
     * <p>标量三项必须是数字; 三张分布表的<b>键</b>必须是维度 (类型串 / 行业串 / 评级整数),
     * <b>值</b>必须是计数。任何一层出现「客户 id 形状的键」或「客户名列表」都会在这里红。</p>
     */
    @Test
    @DisplayName("嵌套分布表只有「维度 -> 计数」, 键不是客户 id、值不是客户名列表")
    void nestedDistributionsCarryNoCustomerIdentity() {
        stubRepository();

        Map<String, Object> stats = service.getOverallCustomerStatistics(FACTORY);

        assertThat(stats.get("totalCustomers")).isInstanceOf(Number.class);
        assertThat(stats.get("activeCustomers")).isInstanceOf(Number.class);
        assertThat(stats.get("averageRating")).isInstanceOf(Number.class);
        assertThat(stats.get("totalOutstandingBalance")).isInstanceOf(BigDecimal.class);
        assertThat(stats.get("totalCreditLimit")).isInstanceOf(BigDecimal.class);

        @SuppressWarnings("unchecked")
        Map<String, Long> type = (Map<String, Long>) stats.get("typeDistribution");
        assertThat(type)
                .as("类型分布的键是客户类型枚举名或字面量「未分类」—— ⛔ 不是客户名")
                .containsOnlyKeys("DEALER", "未分类");
        assertThat(type.values()).allSatisfy(v -> assertThat(v).isInstanceOf(Long.class));

        @SuppressWarnings("unchecked")
        Map<String, Long> industry = (Map<String, Long>) stats.get("industryDistribution");
        assertThat(industry)
                .as("行业分布的键是行业名或字面量「未分类」—— ⛔ 不是客户名")
                .containsOnlyKeys("餐饮", "未分类");
        assertThat(industry.values()).allSatisfy(v -> assertThat(v).isInstanceOf(Long.class));

        @SuppressWarnings("unchecked")
        Map<Integer, Long> rating = (Map<Integer, Long>) stats.get("ratingDistribution");
        assertThat(rating.keySet())
                .as("评级分布的键是 0..5 的整数 —— 结构上就装不下客户身份")
                .allSatisfy(k -> assertThat(k).isBetween(0, 5));
        assertThat(rating.values()).allSatisfy(v -> assertThat(v).isInstanceOf(Long.class));
    }
}
