package com.cretas.aims.service.consent;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 隐私条款第六条: 工艺保密产品的数据不用于训练。训练样本不带产品编号, 所以按名称判断:
 * 原话 / 问答里出现该厂任一保密产品的名称, 整条不进训练样本。名称少于 2 个字的不参与匹配
 * (避免单字误伤)。与 Python {@code smartbi/services/process_secret_names.py} 同一规则。
 */
@Component
public class ProcessSecretSampleFilter {

    private static final Logger log = LoggerFactory.getLogger(ProcessSecretSampleFilter.class);

    static final int MIN_NAME_LENGTH = 2;

    private final ProcessSecretAccessService accessService;
    private final ProductTypeRepository productTypeRepository;

    public ProcessSecretSampleFilter(ProcessSecretAccessService accessService,
                                     ProductTypeRepository productTypeRepository) {
        this.accessService = accessService;
        this.productTypeRepository = productTypeRepository;
    }

    /** 文本是否提到本厂某个工艺保密产品。 */
    public boolean mentionsProtectedProduct(String factoryId, String... texts) {
        return mentionsAny(protectedProductNames(factoryId), texts);
    }

    /** 收训练样本前的保密判断结果。只有 {@link #CLEAR} 才能存。 */
    public enum SampleVerdict {
        /** 没提到本厂保密产品 */
        CLEAR,
        /** 提到了本厂保密产品, 不存 */
        PROTECTED,
        /** 名单查不出来 (查库失败等), 判断不了 —— 这一条不存, 也不影响调用方的主流程 */
        UNVERIFIED
    }

    static final String METRIC_SAMPLE_UNVERIFIED = "cretas.training_sample.secrecy_unverified";

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /**
     * 收训练样本 (意图识别样本、低置信样本) 时用: 名单查库失败只丢掉这一条样本, 不抛给调用方 ——
     * 识别请求照常返回 (2026-10-29: 名单查失败曾让用户的意图识别直接报错)。
     *
     * <p>不在调用方事务里查: 查库出错时仓库层会把所在事务标成只能回滚, 调用方即使接住异常,
     * 提交时也会抛 {@code UnexpectedRollbackException}, 结果还是传进识别流程。挂起外层事务后,
     * 出错只回滚名单查询自己的那个事务。
     *
     * <p>判断不了的样本没有「待复核」状态可放 (训练样本表没有状态列, 低置信样本表的状态里没有待复判),
     * 所以不存, 记一条 WARN (工厂 + 原因, 不带原话) 和计数 {@value #METRIC_SAMPLE_UNVERIFIED}。
     *
     * @param kind 样本类别, 进日志和计数标签 (如 {@code intent} / {@code active_learning})
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SampleVerdict checkTrainingSample(String kind, String factoryId, String... texts) {
        try {
            return mentionsProtectedProduct(factoryId, texts) ? SampleVerdict.PROTECTED : SampleVerdict.CLEAR;
        } catch (RuntimeException e) {
            log.warn("训练样本保密判断失败, 本条不存: kind={}, factory={}, reason={}",
                    kind, factoryId, e.getClass().getSimpleName());
            if (meterRegistry != null) {
                meterRegistry.counter(METRIC_SAMPLE_UNVERIFIED, "kind", kind == null ? "unknown" : kind).increment();
            }
            return SampleVerdict.UNVERIFIED;
        }
    }

    /**
     * 文本是否提到<b>任何一家</b>工厂的工艺保密产品。写全局 (所有工厂共享) 的说法 / 知识前用:
     * 全局内容对每家工厂可见, 只查写入方自己那家不够 (Steve 2026-10-29: 保密内容不出本厂)。
     * 查库出错照样抛, 调用方据此不写全局。一批写入 (种子、批量学习) 先取一次 {@link #allFactoriesProtectedNames()}
     * 再用 {@link #mentionsAny} 逐条比对, 不要逐条查库。
     */
    public boolean mentionsAnyFactoryProtectedProduct(String... texts) {
        return mentionsAny(allFactoriesProtectedNames(), texts);
    }

    /** 所有工厂的工艺保密产品名 (并集; 一批写入取一次)。查库出错照样抛。 */
    public Set<String> allFactoriesProtectedNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String factoryId : accessService.factoriesWithProtectedProducts()) {
            names.addAll(protectedProductNames(factoryId));
        }
        return names;
    }

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
