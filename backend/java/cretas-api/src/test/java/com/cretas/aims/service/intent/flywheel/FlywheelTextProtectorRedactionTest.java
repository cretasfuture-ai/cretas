package com.cretas.aims.service.intent.flywheel;

import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.entity.intent.IntentFlywheelTurn.SecrecyMode;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.repository.intent.FlywheelMasterDataRepository;
import com.cretas.aims.repository.learning.FactoryNameDirectoryRepository;
import com.cretas.aims.service.secrecy.FactorySecrecyConfig;
import com.cretas.aims.service.secrecy.SecrecyItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 飞轮导出文本的脱敏口径 (Steve 2026-11-14 更正), 逐条:
 * 客户名 (含称呼) → 客户A/B 且同会话一致; 产品名 / 数量默认不脱、工厂可开; 价格 / 工艺参数保密厂脱; 普通业务说法不动;
 * 只按本厂主数据确定性匹配, 名单外的称呼不换但计数; 保密关的厂同一句原样 (阳性对照)。
 */
class FlywheelTextProtectorRedactionTest {

    static final String SECRET = "F-SECRET";
    static final String PLAIN = "F-PLAIN";
    static final String SENTENCE = "给老张家下五十斤卤鸭腿，按特价走";

    FlywheelMasterDataRepository master;
    FactoryNameDirectoryRepository people;
    FactoryFeatureConfigRepository features;
    FlywheelTextProtector protector;

    @BeforeEach
    void setUp() {
        master = mock(FlywheelMasterDataRepository.class);
        people = mock(FactoryNameDirectoryRepository.class);
        features = mock(FactoryFeatureConfigRepository.class);
        for (String f : List.of(SECRET, PLAIN)) {
            when(master.findCustomerNames(f)).thenReturn(List.of("老张家卤味店", "李记餐饮有限公司"));
            when(master.findSupplierNames(f)).thenReturn(List.of("鲜禽供应站"));
            when(master.findProductNames(f)).thenReturn(List.of("卤鸭腿", "酱牛肉"));
            when(people.findPeopleNames(f)).thenReturn(List.of("王小明"));
        }
        when(features.findByFactoryIdAndModuleIdAndDeletedAtIsNull(anyString(), eq(FlywheelTextProtector.OPTIONS_MODULE)))
                .thenReturn(Optional.empty());
        protector = new FlywheelTextProtector(master, people, features, key());
        FactorySecrecyConfig secrecy = mock(FactorySecrecyConfig.class);
        when(secrecy.isOn(eq(SECRET), any(SecrecyItem.class))).thenAnswer(inv -> inv.getArgument(1) == SecrecyItem.EGRESS_REDACTION);
        when(secrecy.isOn(eq(PLAIN), any(SecrecyItem.class))).thenReturn(false);
        protector.setFactorySecrecyConfig(secrecy);
    }

    static String key() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) i;
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void secretFactoryDefaultReplacesTheCustomerAppellationOnly() {
        FlywheelTextProtector.Protected p = protector.protect(SECRET, "s1", SENTENCE);

        assertThat(p.mode()).isEqualTo(SecrecyMode.REDACTED);
        assertThat(p.exportInput()).isEqualTo("给客户A下五十斤卤鸭腿，按特价走");
        assertThat(p.rawInput()).isNull();
        assertThat(protector.decrypt(p.inputCipher())).isEqualTo(SENTENCE);
        assertThat(p.stats()).containsEntry("customer", 1).doesNotContainKey("product").doesNotContainKey("quantity");
    }

    @Test
    void secretFactoryWithProductSwitchAlsoReplacesTheProduct() {
        options(Map.of(FlywheelTextProtector.OPT_PRODUCT, true));

        FlywheelTextProtector.Protected p = protector.protect(SECRET, "s1", SENTENCE);

        assertThat(p.exportInput()).isEqualTo("给客户A下五十斤产品A，按特价走");
    }

    @Test
    void secretFactoryWithQuantitySwitchMasksQuantities() {
        options(Map.of(FlywheelTextProtector.OPT_QUANTITY, "true"));

        assertThat(protector.protect(SECRET, "s1", SENTENCE).exportInput()).isEqualTo("给客户A下<数量>斤卤鸭腿，按特价走");
    }

    @Test
    void plainFactoryKeepsTheSameSentenceAsIs() {
        FlywheelTextProtector.Protected p = protector.protect(PLAIN, "s1", SENTENCE);

        assertThat(p.mode()).isEqualTo(SecrecyMode.PLAIN);
        assertThat(p.rawInput()).isEqualTo(SENTENCE);
        assertThat(p.exportInput()).isEqualTo(SENTENCE);
        assertThat(p.inputCipher()).isNull();
    }

    @Test
    void sameCustomerKeepsItsLetterAcrossTheSessionAndANewCustomerGetsTheNext() {
        assertThat(protector.protect(SECRET, "s9", "老张家要退货").exportInput()).isEqualTo("客户A要退货");
        assertThat(protector.protect(SECRET, "s9", "李记餐饮和老张家卤味店都催单").exportInput())
                .isEqualTo("客户B和客户A都催单");
        // 另一个会话重新编号
        assertThat(protector.protect(SECRET, "s10", "李记餐饮有限公司催单").exportInput()).isEqualTo("客户A催单");
    }

    @Test
    void pricesAndProcessParametersAreMaskedButOrdinaryBusinessWordsStay() {
        String out = protector.protect(SECRET, "s2",
                "老样子，加急，卤鸭腿单价18.5，杀菌温度121度，油温180℃，二十块一斤").exportInput();

        assertThat(out).isEqualTo("老样子，加急，卤鸭腿单价<价格>，杀菌温度<参数>度，油温<参数>℃，<价格>块一斤");
    }

    @Test
    void supplierAndStaffNamesAreReplacedAndPhoneMasked() {
        String out = protector.protect(SECRET, "s3", "让王小明找鲜禽供应站，电话13812345678").exportInput();

        assertThat(out).isEqualTo("让人员A找供应商A，电话<电话>");
    }

    @Test
    void nameLikeWordsOutsideTheMasterDataAreNotReplacedButCounted() {
        FlywheelTextProtector.Protected p = protector.protect(SECRET, "s4", "老刘家要二十箱");

        assertThat(p.exportInput()).isEqualTo("老刘家要二十箱");
        assertThat(p.stats()).containsEntry("unmatchedNameLike", 1);
    }

    @Test
    void masterDataLookupFailureDropsTextInsteadOfExportingHalfRedacted() {
        when(master.findCustomerNames(SECRET)).thenThrow(new IllegalStateException("db down"));

        FlywheelTextProtector.Protected p = protector.protect(SECRET, "s5", SENTENCE);

        assertThat(p.mode()).isEqualTo(SecrecyMode.TEXT_DROPPED);
        assertThat(p.exportInput()).isNull();
        assertThat(p.rawInput()).isNull();
    }

    @Test
    void optionReadFailureTreatsBothSwitchesAsOn() {
        when(features.findByFactoryIdAndModuleIdAndDeletedAtIsNull(SECRET, FlywheelTextProtector.OPTIONS_MODULE))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(protector.protect(SECRET, "s6", SENTENCE).exportInput()).isEqualTo("给客户A下<数量>斤产品A，按特价走");
    }

    @Test
    void withoutAKeyTheRawTextIsNotKeptAnywhere() {
        FlywheelTextProtector noKey = new FlywheelTextProtector(master, people, features, "");
        FactorySecrecyConfig secrecy = mock(FactorySecrecyConfig.class);
        when(secrecy.isOn(eq(SECRET), any(SecrecyItem.class))).thenReturn(true);
        noKey.setFactorySecrecyConfig(secrecy);

        FlywheelTextProtector.Protected p = noKey.protect(SECRET, "s7", SENTENCE);

        assertThat(p.inputCipher()).isNull();
        assertThat(p.rawInput()).isNull();
        assertThat(p.rawNotKeptReason()).isEqualTo("no_key");
        assertThat(p.exportInput()).isEqualTo("给客户A下五十斤卤鸭腿，按特价走");
    }

    @Test
    void shortCoreOfACustomerNameIsNotUsedAsAName() {
        when(master.findCustomerNames(SECRET)).thenReturn(List.of("上海超市"));

        assertThat(protector.protect(SECRET, "s8", "上海那边要货，上海超市也要").exportInput())
                .isEqualTo("上海那边要货，客户A也要");
    }

    private void options(Map<String, Object> config) {
        FactoryFeatureConfig row = new FactoryFeatureConfig();
        row.setFactoryId(SECRET);
        row.setModuleId(FlywheelTextProtector.OPTIONS_MODULE);
        row.setEnabled(true);
        row.setConfig(new HashMap<>(config));
        when(features.findByFactoryIdAndModuleIdAndDeletedAtIsNull(SECRET, FlywheelTextProtector.OPTIONS_MODULE))
                .thenReturn(Optional.of(row));
    }
}
