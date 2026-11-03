package com.cretas.aims.service.secrecy;

import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FactorySecrecyConfig#isOn}: 没有行 = 默认 (关; f 跟随 a), f 三态, 工厂不明 / 读库失败一律开 (偏向保密),
 * 10 秒缓存、改了立即失效、失败不缓存。
 */
class FactorySecrecyConfigContractTest {

    static final String F = "F-SEC-1";

    private final FactorySecrecySettingRepository repository = mock(FactorySecrecySettingRepository.class);
    private final List<FactorySecrecySetting> rows = new ArrayList<>();
    private final FactorySecrecyConfig config = new FactorySecrecyConfig(repository, (org.springframework.transaction.PlatformTransactionManager) null);

    FactorySecrecyConfigContractTest() {
        when(repository.findByFactoryId(anyString())).thenAnswer(inv -> List.copyOf(rows));
    }

    private void set(SecrecyItem item, Boolean enabled) {
        rows.removeIf(r -> r.getItemCode().equals(item.name()));
        rows.add(FactorySecrecySetting.builder().factoryId(F).itemCode(item.name()).enabled(enabled).updatedBy(1L).build());
        config.invalidate(F);
    }

    @Test
    void noRowMeansEveryItemIsOffAndTheAuditFollowsProcessSecret() {
        for (SecrecyItem item : SecrecyItem.values()) {
            assertThat(config.isOn(F, item)).as(item.name()).isFalse();
        }
        set(SecrecyItem.PROCESS_SECRET, true);
        assertThat(config.isOn(F, SecrecyItem.PROCESS_SECRET)).isTrue();
        assertThat(config.isOn(F, SecrecyItem.AI_SECRECY_AUDIT)).as("f 跟随 a").isTrue();
        assertThat(config.isOn(F, SecrecyItem.PLATFORM_RECIPE_BLOCK)).as("别的项不跟").isFalse();
    }

    @Test
    void theAuditItemIsTriState() {
        set(SecrecyItem.PROCESS_SECRET, false);
        set(SecrecyItem.AI_SECRECY_AUDIT, true);
        assertThat(config.isOn(F, SecrecyItem.AI_SECRECY_AUDIT)).as("单独开").isTrue();
        set(SecrecyItem.PROCESS_SECRET, true);
        set(SecrecyItem.AI_SECRECY_AUDIT, false);
        assertThat(config.isOn(F, SecrecyItem.AI_SECRECY_AUDIT)).as("单独关").isFalse();
        set(SecrecyItem.AI_SECRECY_AUDIT, null);
        assertThat(config.isOn(F, SecrecyItem.AI_SECRECY_AUDIT)).as("跟随 (a 开)").isTrue();
        set(SecrecyItem.PROCESS_SECRET, false);
        assertThat(config.isOn(F, SecrecyItem.AI_SECRECY_AUDIT)).as("跟随 (a 关)").isFalse();
    }

    @Test
    void unknownFactoryIsTreatedAsOn() {
        for (String factory : new String[]{null, "", "  ", "PLATFORM", "platform"}) {
            for (SecrecyItem item : SecrecyItem.values()) {
                assertThat(config.isOn(factory, item)).as(factory + "/" + item).isTrue();
            }
        }
        assertThat(config.effectiveByCode("PLATFORM").values()).containsOnly(true);
    }

    @Test
    void aFailedReadIsTreatedAsOnAndIsNotCached() {
        when(repository.findByFactoryId(anyString())).thenThrow(new DataAccessResourceFailureException("db down"));
        for (SecrecyItem item : SecrecyItem.values()) {
            assertThat(config.isOn(F, item)).as(item.name()).isTrue();
        }
        assertThat(config.effectiveByCode(F).values()).containsOnly(true);
        // 恢复后立即读到真值 (失败没进缓存)
        org.mockito.Mockito.doAnswer(inv -> List.copyOf(rows)).when(repository).findByFactoryId(anyString());
        assertThat(config.isOn(F, SecrecyItem.PROCESS_SECRET)).isFalse();
    }

    @Test
    void readsAreCachedPerFactoryAndAWriteInvalidatesThem() {
        assertThat(config.isOn(F, SecrecyItem.EGRESS_REDACTION)).isFalse();
        assertThat(config.isOn(F, SecrecyItem.TRAINING_FILTER)).isFalse();
        verify(repository, times(1)).findByFactoryId(F);
        // 库里变了但没清缓存: 10 秒内仍是旧值
        rows.add(FactorySecrecySetting.builder().factoryId(F).itemCode("EGRESS_REDACTION").enabled(true).updatedBy(1L).build());
        assertThat(config.isOn(F, SecrecyItem.EGRESS_REDACTION)).isFalse();
        config.invalidate(F);
        assertThat(config.isOn(F, SecrecyItem.EGRESS_REDACTION)).isTrue();
        verify(repository, times(2)).findByFactoryId(F);
    }

    @Test
    void allOnIsOnForEveryFactoryAndItem() {
        for (SecrecyItem item : SecrecyItem.values()) {
            assertThat(FactorySecrecyConfig.allOn().isOn(F, item)).isTrue();
        }
    }
}
