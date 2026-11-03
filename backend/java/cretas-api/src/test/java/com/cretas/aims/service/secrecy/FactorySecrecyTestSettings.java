package com.cretas.aims.service.secrecy;

import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 给整套 Spring 容器的测试用: 显式把某工厂的保密配置设成要守的那一档。
 *
 * <p>⚠️ 容器里的 {@link FactorySecrecyConfig} 是真实例: 没有配置行 = 每一项都关 (新默认)。以前靠「有保密策略就生效」
 * 证明遮挡的测试, 不显式打开对应的项就会变成在守「关」的行为。配置在自己的只读事务里读 (REQUIRES_NEW),
 * 看不到 {@code @Transactional} 测试里未提交的行 —— 所以这里另开事务提交, 关闭时同样另开事务删掉, 并清缓存。</p>
 */
public final class FactorySecrecyTestSettings implements AutoCloseable {

    private final TransactionTemplate tx;
    private final FactorySecrecySettingRepository repository;
    private final FactorySecrecyConfig config;
    private final String factoryId;

    private FactorySecrecyTestSettings(PlatformTransactionManager transactionManager,
                                       FactorySecrecySettingRepository repository, FactorySecrecyConfig config,
                                       String factoryId) {
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.repository = repository;
        this.config = config;
        this.factoryId = factoryId;
    }

    /** 提交 {@code values} (null = 跟随, 只给 AI_SECRECY_AUDIT), 返回的句柄关闭时删掉这些行。 */
    public static FactorySecrecyTestSettings set(PlatformTransactionManager transactionManager,
                                                 FactorySecrecySettingRepository repository,
                                                 FactorySecrecyConfig config, String factoryId,
                                                 Map<SecrecyItem, Boolean> values) {
        FactorySecrecyTestSettings handle = new FactorySecrecyTestSettings(transactionManager, repository, config, factoryId);
        handle.tx.executeWithoutResult(status -> values.forEach((item, enabled) -> repository.saveAndFlush(
                FactorySecrecySetting.builder().factoryId(factoryId).itemCode(item.name()).enabled(enabled)
                        .updatedAt(LocalDateTime.now()).updatedBy(1L).build())));
        config.invalidate(factoryId);
        return handle;
    }

    @Override
    public void close() {
        tx.executeWithoutResult(status -> {
            List<FactorySecrecySetting> rows = repository.findByFactoryId(factoryId);
            repository.deleteAll(rows);
            repository.flush();
        });
        config.invalidate(factoryId);
    }
}
