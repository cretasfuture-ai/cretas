package com.cretas.aims.service.consent;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.learning.FactoryNameDirectoryRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 隐私条款第六条: 工艺保密产品的数据不用于训练; Steve 2026-10-24: 具体人名 / 客户名不进训练样本。
 *
 * <p>三套名单, 用途分开:
 * <ul>
 *   <li><b>本厂训练样本 / 学习</b> ({@link #checkTrainingSample}, {@link #trainingLists}; 按样本所属工厂的 d):
 *       本厂「名单」= 保密产品的全名 / 编码 / ID ({@link ProcessSecretTerms}: 纯数字名与品类词不收,
 *       编码按边界匹配) + 本厂人员姓名 + 本厂客户名称 (精确匹配)。与 Python
 *       {@code smartbi/services/process_secret_names.py} 同一规则 (Steve 2026-10-29 选 A)。</li>
 *   <li><b>写给所有工厂的共享内容</b> —— 平台级意图与晋升 / 回滚、全局人工说法 / 种子、共享短语表 / 全局知识, 全部用
 *       <b>同一份</b>并集 ({@link #checkSharedContent}, {@link #allFactoriesPolicyTermsForSharedContent},
 *       {@link #allFactoriesPolicyTermsForSharedContentOrNull}): 所有工厂全部策略产品 (含已软删除的) 的全名 / 编码 / ID /
 *       专属别名, 品类词不收; <b>不看任何保密开关 (a–f)</b> —— 跨厂保护不受开关影响
 *       (第四 / 五轮复审)。按 d 剔工厂的全厂并集已没有调用方, 删掉了 (d 只按样本所属工厂判, 见 {@link #trainingFilterOff})。</li>
 *   <li><b>外发脱敏</b> ({@link #protectedProductNames}, {@link #mentionsAny}): 本厂生效中的保密产品名。</li>
 * </ul>
 */
@Component
public class ProcessSecretSampleFilter {

    private static final Logger log = LoggerFactory.getLogger(ProcessSecretSampleFilter.class);

    static final int MIN_NAME_LENGTH = 2;

    private final ProcessSecretAccessService accessService;
    private final ProductTypeRepository productTypeRepository;
    private final FactoryNameDirectoryRepository directoryRepository;

    public ProcessSecretSampleFilter(ProcessSecretAccessService accessService,
                                     ProductTypeRepository productTypeRepository,
                                     FactoryNameDirectoryRepository directoryRepository) {
        this.accessService = accessService;
        this.productTypeRepository = productTypeRepository;
        this.directoryRepository = directoryRepository;
    }

    /**
     * 文本是否提到本厂某个工艺保密产品 (训练 / 学习用)。只看该厂「训练数据过滤」(d) (Steve 2026-11-03 Q1=A):
     * d 关 → 一律 false; d 开 → 按库里登记了保密策略的全部产品名判, 与工艺保密 (a) 开不开无关。
     * 训练授权是另一套, 由调用方照常判。
     */
    public boolean mentionsProtectedProduct(String factoryId, String... texts) {
        if (trainingFilterOff(factoryId)) return false;
        return mentionsAny(trainingProductNames(factoryId), texts);
    }

    /**
     * 训练 / 学习 / 写全局内容用的保密产品名: 库里登记了保密策略的全部产品 (生效中的 ∪ 因 a / e 关着而不生效的),
     * 不看 a。出外脱敏用的 {@link #protectedProductNames} 仍按生效中的保密产品。
     */
    public Set<String> trainingProductNames(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            return Set.of();
        }
        Set<String> ids = trainingPolicyProductIds(factoryId);
        if (ids.isEmpty()) {
            return Set.of();
        }
        Set<String> key = Set.copyOf(ids);
        return nameCache.get(new NameKey(factoryId, key), k -> loadNames(factoryId, key));
    }

    /** 单测直接 new 时当开启; Spring 下由 setter 注入 (没有这个 bean 的切片测试保持全开)。 */
    private com.cretas.aims.service.secrecy.FactorySecrecyConfig secrecy =
            com.cretas.aims.service.secrecy.FactorySecrecyConfig.allOn();

    @Autowired(required = false)
    public void setFactorySecrecyConfig(com.cretas.aims.service.secrecy.FactorySecrecyConfig secrecy) {
        this.secrecy = secrecy;
    }

    /**
     * 该厂「训练数据过滤」(d) 是否<b>关着</b> (按样本所属的工厂; 工厂不明 / 读不到 → 开着 → false)。关着时不按保密产品名
     * 和「工艺词 + 数值」剔除该厂的训练样本; <b>人名 / 客户名照样剔除</b> (隐私, 不归任何开关管, Steve 2026-11-04);
     * 训练授权 (同意 / 撤回) 不受影响。
     * 问「关着吗」而不是「开着吗」: 被 mock 的默认 false 落在过滤照常生效那一边。
     */
    public boolean trainingFilterOff(String factoryId) {
        return !secrecy.isOn(factoryId, com.cretas.aims.service.secrecy.SecrecyItem.TRAINING_FILTER);
    }

    /**
     * 训练过滤 (d) 用的保密产品: 库里登记了保密策略的全部产品 = 生效中的 ({@code protectedProductTypeIds}) ∪ 因 a / e
     * 关着而不生效的 ({@code switchedOffPolicyProductTypeIds}) —— 不看 a (Steve 2026-11-03 Q1=A)。
     */
    Set<String> trainingPolicyProductIds(String factoryId) {
        Set<String> ids = new LinkedHashSet<>(accessService.protectedProductTypeIds(factoryId));
        ids.addAll(accessService.switchedOffPolicyProductTypeIds(factoryId));
        return ids;
    }

    /** 平台改了保密配置后清名单缓存 (名单本身不看开关, 清掉只是不留旧状态窗口; 训练名单缓存一并清)。 */
    public void invalidateNameCache() {
        nameCache.invalidateAll();
        listsCache.invalidateAll();
    }

    /** 收训练样本前的判断结果。只有 {@link #CLEAR} 才能直接存。 */
    public enum SampleVerdict {
        /** 没提到本厂保密词 / 人员 / 客户 */
        CLEAR,
        /** 提到了, 不存 */
        PROTECTED,
        /**
         * 本厂名单这次查不出来, 按<b>上次成功的名单</b>判下来没问题 —— 不直接存, 挂起待重判
         * (调用方交给 {@code TrainingSampleHoldStore}); 也不影响调用方的主流程
         */
        HELD,
        /** 名单查不出来、也没有上次成功的名单, 判断不了 —— 这一条不存, 也不影响调用方的主流程 */
        UNVERIFIED
    }

    /** 一家工厂的训练样本名单 (都已折叠)。 */
    public record TrainingLists(Set<String> terms, Set<String> directory) {
        public static final TrainingLists EMPTY = new TrainingLists(Set.of(), Set.of());

    }

    static final String METRIC_SAMPLE_UNVERIFIED = "cretas.training_sample.secrecy_unverified";
    static final String METRIC_SAMPLE_HELD = "cretas.training_sample.secrecy_held";

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /**
     * 收训练样本 (意图识别样本、低置信样本) 和学习原话前用: 名单查库失败不抛给调用方 ——
     * 识别请求照常返回 (2026-10-29: 名单查失败曾让用户的意图识别直接报错)。
     *
     * <p>不在调用方事务里查: 查库出错时仓库层会把所在事务标成只能回滚, 调用方即使接住异常,
     * 提交时也会抛 {@code UnexpectedRollbackException}, 结果还是传进识别流程。挂起外层事务后,
     * 出错只回滚名单查询自己的那个事务。代价: 名单查询看不到调用方事务里尚未提交的写入 (例如同一事务里刚设的保密产品),
     * 收样本这个用途可以接受。收样本的调用方应在开事务<b>之前</b>调它 (否则缓存未命中时一次收样本占两个连接)。
     *
     * <p>名单查不出来时按上次成功的名单判 ({@link SampleVerdict#HELD}: 挂起待重判); 连上次成功的名单都没有
     * ({@link SampleVerdict#UNVERIFIED}) 才丢。两种都记一条 WARN (工厂 + 原因, 不带原话) 和计数。
     *
     * @param kind 样本类别, 进日志和计数标签 (如 {@code intent} / {@code active_learning})
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SampleVerdict checkTrainingSample(String kind, String factoryId, String... texts) {
        if (factoryId == null || factoryId.isBlank()) {
            return SampleVerdict.UNVERIFIED;     // 没有工厂就没有可比的名单 (不用所有工厂的并集)
        }
        // 「训练数据过滤」(d; 按样本所属的工厂, 开关读不到按开着) 只管<b>保密产品词</b>。人名 / 客户名是隐私不是保密:
        // 训练、学习、蒸馏样本里一律剔除, 任何开关都不管 (Steve 2026-11-04) —— 下面查人员 / 客户那一半不许跟着 d 走。
        // 名单查不出来时两者同样: 按上次成功的名单判, 否则挂起 / 不存, 绝不直接存。训练授权由调用方照常判
        boolean withTerms = !trainingFilterOff(factoryId);
        try {
            TrainingLists lists = trainingLists(factoryId);
            return names(lists, withTerms, texts) ? SampleVerdict.PROTECTED : SampleVerdict.CLEAR;
        } catch (RuntimeException e) {
            TrainingLists last = lastGoodLists.get(factoryId);
            SampleVerdict verdict = last == null ? SampleVerdict.UNVERIFIED
                    : names(last, withTerms, texts) ? SampleVerdict.PROTECTED : SampleVerdict.HELD;
            log.warn("训练样本名单查询失败, {}: kind={}, factory={}, reason={}",
                    verdict == SampleVerdict.HELD ? "按上次成功的名单判过, 挂起待重判"
                            : verdict == SampleVerdict.PROTECTED ? "上次成功的名单里有, 不存" : "没有上次成功的名单, 本条不存",
                    kind, factoryId, e.getClass().getSimpleName());
            if (meterRegistry != null && verdict != SampleVerdict.PROTECTED) {
                meterRegistry.counter(verdict == SampleVerdict.HELD ? METRIC_SAMPLE_HELD : METRIC_SAMPLE_UNVERIFIED,
                        "kind", kind == null ? "unknown" : kind).increment();
            }
            return verdict;
        }
    }

    private static boolean names(TrainingLists lists, boolean withTerms, String... texts) {
        return (withTerms && ProcessSecretTerms.mentionsAny(lists.terms(), texts))
                || ProcessSecretTerms.mentionsAny(lists.directory(), texts);
    }

    /**
     * 写<b>共享内容</b> (所有工厂都用得到的全局人工说法 / 种子、共享短语表、全局知识) 前用: 内容提到任何一家工厂的
     * 保密词 → {@link SampleVerdict#PROTECTED}; 查不出来 → {@link SampleVerdict#UNVERIFIED} (不写)。
     * <b>不看任何保密开关</b> (某厂关了 d 甚至全关, 它的保密词照样不能进共享内容; 第五轮复审)。
     * 与 {@link #checkTrainingSample} 一样挂起调用方事务, 查库失败不把调用方事务标成只能回滚。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SampleVerdict checkSharedContent(String kind, String... texts) {
        try {
            return ProcessSecretTerms.mentionsAny(allFactoriesPolicyTermsForSharedContent(), texts)
                    ? SampleVerdict.PROTECTED : SampleVerdict.CLEAR;
        } catch (RuntimeException e) {
            log.warn("全局写入前的保密词查询失败, 不写: kind={}, reason={}", kind, e.getClass().getSimpleName());
            if (meterRegistry != null) {
                meterRegistry.counter(METRIC_SAMPLE_UNVERIFIED, "kind", kind == null ? "unknown" : kind).increment();
            }
            return SampleVerdict.UNVERIFIED;
        }
    }

    /**
     * 一批共享写入 (种子) 先取一次所有工厂的保密词 (不看开关); 查库失败返回 null (调用方改逐条判), 不抛。
     * 挂起调用方事务, 查库失败不把调用方事务标成只能回滚。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Set<String> allFactoriesPolicyTermsForSharedContentOrNull() {
        try {
            return Set.copyOf(allFactoriesPolicyTermsForSharedContent());
        } catch (RuntimeException e) {
            log.warn("全厂训练保密词查询失败: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * <b>唯一一份</b>共享内容并集 (第六轮: 平台级意图与全局说法 / 短语表 / 知识用同一份): 所有工厂的保密词 —— 全名 / 编码 /
     * ID / 专属别名, 与训练同一套词规则 (纯数字名、品类词不收: 泛泛的词不因保密被拦) —— 每次都算, <b>不看任何保密开关</b>:
     * 登记了保密策略的每一家工厂 ({@code factoriesWithPolicyProducts}), 每家的全部策略产品 (含已软删除的)。
     * 只取保密词, 不查人员 / 客户。查库出错照样抛。
     */
    public Set<String> allFactoriesPolicyTermsForSharedContent() {
        Set<String> terms = new LinkedHashSet<>();
        for (String factoryId : accessService.factoriesWithPolicyProducts()) {
            terms.addAll(policyTerms(factoryId, trainingPolicyProductIds(factoryId)));
        }
        return terms;
    }

    /**
     * 本厂训练样本名单 (保密词 + 人员 + 客户)。保密产品取库里登记了保密策略的全部产品 (与 a / e 开不开无关, 只看 d,
     * 由调用方 / {@link #check} 判 d)。按「工厂 + 当前保密产品集合」缓存 10 秒, 每次成功都记为
     * 「上次成功的名单」。查库失败照样抛 (不缓存)。
     */
    public TrainingLists trainingLists(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            return TrainingLists.EMPTY;
        }
        Set<String> protectedIds = trainingPolicyProductIds(factoryId);     // 不看 a / e (Q1=A)
        TrainingLists lists = listsCache.get(new NameKey(factoryId, Set.copyOf(protectedIds)),
                key -> loadTrainingLists(factoryId, protectedIds));
        lastGoodLists.put(factoryId, lists);
        return lists;
    }

    private TrainingLists loadTrainingLists(String factoryId, Set<String> protectedIds) {
        List<String> names = new ArrayList<>(directoryRepository.findPeopleNames(factoryId));
        names.addAll(directoryRepository.findCustomerNames(factoryId));
        return new TrainingLists(policyTerms(factoryId, protectedIds), ProcessSecretTerms.directoryNames(names));
    }

    /**
     * 一家工厂的保密词 (全名 / 编码 / ID / 专属别名; {@link ProcessSecretTerms} 的词规则)。训练名单与共享内容并集共用这一份。
     * 原生查询: 含已软删除的保密产品 (复审 N2; findAllById 会被 @Where 过滤掉), 与 Python 同一口径。
     */
    private Set<String> policyTerms(String factoryId, Set<String> protectedIds) {
        List<ProcessSecretTerms.Product> products = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        if (!protectedIds.isEmpty()) {
            for (Object[] r : directoryRepository.findProtectedProductRows(factoryId, protectedIds)) {
                products.add(new ProcessSecretTerms.Product(str(r[0]), str(r[1]), str(r[2]), str(r[3]), str(r[4])));
            }
            // 专属别名: 保密负责人在保密设置里登记 (Steve 2026-11-03 Q1)
            for (String raw : directoryRepository.findPolicyAliases(factoryId)) {
                aliases.addAll(ProcessSecretTerms.policyAliases(raw));
            }
        }
        return ProcessSecretTerms.secretTerms(products, aliases);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private final Cache<NameKey, TrainingLists> listsCache = Caffeine.newBuilder()
            .expireAfterWrite(NAME_CACHE_TTL)
            .maximumSize(1_000)
            .build();

    /** 每家工厂上次成功查到的名单 (名单查询失败时兜底; 只在内存里, 进程重启后要先成功查一次)。 */
    private final Map<String, TrainingLists> lastGoodLists = new ConcurrentHashMap<>();

    /**
     * 名单里任一名字出现在任一文本里。字母不分大小写 (「SecretX」与「secretx」算同一个名字 —— 说法学习会把原话转小写),
     * 汉字照原样比; Python {@code smartbi.services.process_secret_names.mentions_any} 同一规则。
     */
    public static boolean mentionsAny(Collection<String> names, String... texts) {
        if (names == null || names.isEmpty() || texts == null) {
            return false;
        }
        for (String text : texts) {
            if (text == null || text.isEmpty()) {
                continue;
            }
            String folded = text.toLowerCase(Locale.ROOT);
            for (String name : names) {
                if (name != null && !name.isEmpty() && folded.contains(name.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 本厂工艺保密产品名 (只含本厂、长度够的名字; 已 trim)。 */
    public Set<String> protectedProductNames(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            return Set.of();
        }
        Set<String> protectedIds = accessService.protectedProductTypeIds(factoryId);
        if (protectedIds.isEmpty()) {
            return Set.of();
        }
        // 一条消息要过好几处外发出口, 每处都要这份名单: 按「工厂 + 当前保密产品集合」缓存 10 秒。
        // 保密产品集合来自 accessService (改保密设置时立即失效), 集合一变键就变 —— 新设保密立即生效;
        // 只有「已保密产品改名」最多 10 秒后才认新名字。查库失败不缓存, 照样抛给调用方 (外发据此拒绝)。
        return nameCache.get(new NameKey(factoryId, Set.copyOf(protectedIds)), key -> loadNames(factoryId, protectedIds));
    }

    static final Duration NAME_CACHE_TTL = Duration.ofSeconds(10);

    private record NameKey(String factoryId, Set<String> protectedIds) {
    }

    private final Cache<NameKey, Set<String>> nameCache = Caffeine.newBuilder()
            .expireAfterWrite(NAME_CACHE_TTL)
            .maximumSize(1_000)
            .build();

    private Set<String> loadNames(String factoryId, Set<String> protectedIds) {
        Set<String> names = new LinkedHashSet<>();
        List<ProductType> products = productTypeRepository.findAllById(protectedIds);
        for (ProductType product : products) {
            String name = product.getName();
            if (!factoryId.equals(product.getFactoryId()) || name == null || name.strip().length() < MIN_NAME_LENGTH) {
                continue;
            }
            names.add(name.strip()); // 全角空格也去掉 (与 Python process_secret_sql.usable_names 一致)
        }
        return Set.copyOf(names);
    }
}
