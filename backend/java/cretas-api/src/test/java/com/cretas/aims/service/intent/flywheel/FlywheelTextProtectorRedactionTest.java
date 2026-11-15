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
            when(master.findIngredientNames(f)).thenReturn(List.of("八角", "桂皮", "盐"));
        }
        when(features.findByFactoryIdAndModuleIdAndDeletedAtIsNull(anyString(), eq(FlywheelTextProtector.OPTIONS_MODULE)))
                .thenReturn(Optional.empty());
        protector = new FlywheelTextProtector(new com.cretas.aims.service.entity.FactoryEntityResolver(null, master), people, features, key());
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
    void pricesAndProcessValuesBecomeTypedPlaceholdersButOrdinaryBusinessWordsStay() {
        String out = protector.protect(SECRET, "s2",
                "老样子，加急，卤鸭腿单价18.5，杀菌温度121度，油温180℃，二十块一斤").exportInput();

        assertThat(out).isEqualTo("老样子，加急，卤鸭腿单价<价格1>，杀菌温度<温度1>，油温<温度2>，<价格2>一斤");
    }

    /** 阳性 (保密厂: 原值不在导出里, 说法骨架还在) + 阳性对照 (保密关的厂原样)。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "卤水里八角放5克、桂皮3克|卤水里<配料1>放<用量1>、<配料2><用量2>|5克,3克,八角,桂皮|recipe",
            "鸭腿炸到180就捞出来|鸭腿炸到<温度>就捞出来|180|recipe",
            "先卤45分钟再焖两个小时|先卤<时长1>再焖<时长2>|45,两个小时|recipe",
            "盐3%|<配料><比例>|3%,盐|recipe",
            "盐放百分之三|<配料>放<比例>|百分之三|recipe",
            "一斤12|一斤<价格>|12|price",
            "12一斤|<价格>一斤|12|price",
            "每斤12，明天开始|每斤<价格>，明天开始|12|price",
            "价格调到12|价格调到<价格>|12|price",
            "这批按15卖不行，卖16|这批按<价格1>卖不行，卖<价格2>|15,16|price",
            "15/斤|<价格>/斤|15|price",
            "¥ 12 一份|<价格> 一份|12|price",
            "糖度12，中心温度75|糖度<参数>，中心温度<温度>|75|recipe"
    })
    void secretFactoryMasksRecipeProcessAndPriceValuesAndKeepsThePhrasing(String said, String expected, String gone,
                                                                          String kind) {
        String out = protector.protect(SECRET, "v-" + said.hashCode(), said).exportInput();

        assertThat(out).isEqualTo(expected);
        for (String raw : gone.split(",")) {
            assertThat(out).doesNotContain(raw);
        }
        // 保密关的厂: 配方 / 工艺参数也脱 (Steve 11-14: 所有工厂), 价格按保密开关原样 (阳性对照)
        assertThat(protector.protect(PLAIN, "v-" + said.hashCode(), said).exportInput())
                .isEqualTo("recipe".equals(kind) ? expected : said);
    }

    /** 保密关的厂: 配方 / 工艺参数被脱掉, 客户名、价格、普通说法原样。 */
    @Test
    void plainFactoryStillMasksRecipeAndProcessValuesButKeepsCustomerAndOrdinaryWords() {
        String said = "老张家那批卤水八角放5克，炸到180，按特价走，下五十斤卤鸭腿，一斤12";

        FlywheelTextProtector.Protected p = protector.protect(PLAIN, "p1", said);

        assertThat(p.mode()).isEqualTo(SecrecyMode.PLAIN);
        assertThat(p.exportInput()).isEqualTo("老张家那批卤水<配料>放<用量>，炸到<温度>，按特价走，下五十斤卤鸭腿，一斤12");
        // 高1: 原文列同样不存配方 / 工艺参数原值 (任何工厂、任何列), 只换值留说法
        assertThat(p.rawInput()).isEqualTo("老张家那批卤水<配料>放<用量>，炸到<温度>，按特价走，下五十斤卤鸭腿，一斤12");
        assertThat(p.rawInput()).doesNotContain("5克").doesNotContain("180");
        // 阴性对照: 保密关的厂, 客户名与价格在原文列 / 导出列都原样
        assertThat(p.rawInput()).contains("老张家").contains("一斤12");
        assertThat(p.exportInput()).contains("老张家").contains("一斤12");
    }

    /** 高1: 保密关的厂原文列也抹手机 / 证件号 (隐私底线), 但人员姓名只在导出列换。 */
    @Test
    void plainFactoryRawColumnHasPrivacyMaskedButKeepsStaffNameForReview() {
        String said = "让王小明找鲜禽供应站，电话13812345678，身份证110101199001011234";

        FlywheelTextProtector.Protected p = protector.protect(PLAIN, "p2", said);

        assertThat(p.rawInput()).isEqualTo("让王小明找鲜禽供应站，电话<电话>，身份证<证件号>");
        assertThat(p.exportInput()).isEqualTo("让人员A找鲜禽供应站，电话<电话>，身份证<证件号>");
    }

    /** 高1: 保密厂的密文解开后同样没有配方 / 工艺参数原值与手机号; 客户名仍在密文里 (密文本来就是给人工复核看原话的)。 */
    @Test
    void secretFactoryCipherHoldsTheBaselineNotTheRecipeValues() {
        String said = "老张家那批卤水八角放5克，炸到180，电话13812345678，一斤12";

        FlywheelTextProtector.Protected p = protector.protect(SECRET, "c1", said);

        assertThat(p.rawInput()).isNull();
        String decrypted = protector.decrypt(p.inputCipher());
        assertThat(decrypted).doesNotContain("5克").doesNotContain("180").doesNotContain("13812345678");
        assertThat(decrypted).isEqualTo("老张家那批卤水<配料>放<用量>，炸到<温度>，电话<电话>，一斤12");
        assertThat(p.exportInput()).isEqualTo("客户A那批卤水<配料>放<用量>，炸到<温度>，电话<电话>，一斤<价格>");
    }

    /** 高2: 原料名单查不出 → 保密关的厂原文与导出都不留 (不留没脱配方的文本)。 */
    @Test
    void plainFactoryIngredientLookupFailureKeepsNeitherRawNorExport() {
        when(master.findIngredientNames(PLAIN)).thenThrow(new IllegalStateException("db down"));

        FlywheelTextProtector.Protected p = protector.protect(PLAIN, "e1", "让王小明把卤水八角放5克，炸到180");

        assertThat(p.exportInput()).isNull();
        assertThat(p.rawInput()).isNull();
        assertThat(p.inputCipher()).isNull();
        assertThat(p.mode()).isEqualTo(SecrecyMode.TEXT_DROPPED);
    }

    /** 高2 阴性对照: 名单查得出时同一句照常留 (说明上面那条是出错路径收住, 不是一律不留)。 */
    @Test
    void plainFactoryWithIngredientsAvailableKeepsBothColumns() {
        FlywheelTextProtector.Protected p = protector.protect(PLAIN, "e2", "让王小明把卤水八角放5克，炸到180");

        assertThat(p.mode()).isEqualTo(SecrecyMode.PLAIN);
        assertThat(p.exportInput()).isEqualTo("让人员A把卤水<配料>放<用量>，炸到<温度>");
        assertThat(p.rawInput()).isEqualTo("让王小明把卤水<配料>放<用量>，炸到<温度>");
    }

    /** 高2: 保密厂原料名单查不出 → 密文也不留 (密文里不能是没脱配方的原话)。 */
    @Test
    void secretFactoryIngredientLookupFailureKeepsNoCipher() {
        when(master.findIngredientNames(SECRET)).thenThrow(new IllegalStateException("db down"));

        FlywheelTextProtector.Protected p = protector.protect(SECRET, "e3", "卤水八角放5克，炸到180");

        assertThat(p.mode()).isEqualTo(SecrecyMode.TEXT_DROPPED);
        assertThat(p.inputCipher()).isNull();
        assertThat(p.exportInput()).isNull();
        assertThat(p.rawInput()).isNull();
    }

    /** 阴性: 普通数量 / 产品名 / 业务说法在保密厂默认不动。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "下五十斤卤鸭腿", "八角到货了", "再来两个人帮忙", "第3车间先停", "老样子，加急", "特价下三箱", "等会儿再说"})
    void ordinaryQuantitiesAndBusinessPhrasesAreNotTouched(String said) {
        assertThat(protector.protect(SECRET, "n-" + said.hashCode(), said).exportInput()).isEqualTo(said);
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
        FlywheelTextProtector noKey = new FlywheelTextProtector(new com.cretas.aims.service.entity.FactoryEntityResolver(null, master), people, features, "");
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

    /**
     * 三审 中1: 原料表叫「食盐 / 白砂糖」, 话里说「盐 / 白糖」, 小句也没有配方类词 —— 配方专用单位照样抹, 所有列一起
     * (原文列、导出列; 保密厂另看密文)。
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "加盐5克|加盐<用量>|5克",
            "白糖30克|白糖<用量>|30克",
            "生抽一勺|生抽<用量>|一勺",
            "老抽10ml再说|老抽<用量>再说|10ml",
            "鸭腿先抹盐，过会儿再加两片姜|鸭腿先抹盐，过会儿再加<用量>姜|两片"
    })
    void recipeOnlyUnitsAreMaskedEvenWhenTheIngredientIsNamedDifferentlyInTheMasterData(String said, String expected,
                                                                                         String gone) {
        when(master.findIngredientNames(PLAIN)).thenReturn(List.of("食盐", "白砂糖"));
        when(master.findIngredientNames(SECRET)).thenReturn(List.of("食盐", "白砂糖"));

        FlywheelTextProtector.Protected plain = protector.protect(PLAIN, "u-" + said.hashCode(), said);
        assertThat(plain.rawInput()).isEqualTo(expected).doesNotContain(gone);
        assertThat(plain.exportInput()).isEqualTo(expected).doesNotContain(gone);

        FlywheelTextProtector.Protected secret = protector.protect(SECRET, "u-" + said.hashCode(), said);
        assertThat(secret.exportInput()).doesNotContain(gone);
        assertThat(protector.decrypt(secret.inputCipher())).isEqualTo(expected);
    }

    /** 三审 中2 阴性: 业务话里的斤 / 公斤不当配方抹 (原料名、「加」都在也不算); 阳性对照见上「加盐5克」。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "入库鸭腿200公斤", "领料鸭腿50斤", "订单再加20斤", "八角到货100斤", "采购盐300公斤", "盐出库两斤", "配料入库200公斤", "领料八角5片"})
    void businessQuantitiesStayAsIsEvenWithIngredientNamesOrAdd(String said) {
        when(master.findIngredientNames(PLAIN)).thenReturn(List.of("鸭腿", "八角", "盐"));

        FlywheelTextProtector.Protected p = protector.protect(PLAIN, "b-" + said.hashCode(), said);

        assertThat(p.rawInput()).isEqualTo(said);
        assertThat(p.exportInput()).isEqualTo(said);
    }

    /** 三审 中2 阳性对照: 业务单位在有配方类词的小句里仍算用量; 「放 / 加」隔两三个字也算配方语境。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "卤水配料放2斤|卤水配料放<用量>",
            "八角放了个5片|<配料>放了个<用量>"
    })
    void businessUnitsCountWithRecipeWordsAndAddWithinFourChars(String said, String expected) {
        assertThat(protector.protect(PLAIN, "r-" + said.hashCode(), said).rawInput()).isEqualTo(expected);
    }

    /**
     * 终审 高1: 业务单位 (斤 / 升) 在原料名或「放 / 加」语境里仍是配方用量, 四列 (保密关的厂原文 / 导出, 保密厂导出 / 密文)
     * 都不留原值; 终审 低5: 「一起」的「一」不是数。
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "加白砂糖2斤|加<配料><用量>|2斤",
            "白砂糖放2斤|<配料>放<用量>|2斤",
            "每锅白砂糖2斤|每锅<配料><用量>|2斤",
            "白砂糖2斤食盐1斤一起下锅|<配料1><用量1><配料2><用量2>一起下锅|2斤,1斤",
            "这锅加水20升|这锅加<配料><用量>|20升",
            "腌料比例3:1|腌料比例<比例>|3:1",
            "配比3:1|配比<比例>|3:1",
            "白砂糖和食盐3：1|<配料1>和<配料2><比例>|3：1",
            // 终审复跑: 「克」不排除「拉」; g 只看数值前紧邻 4 字, 不看整句
            "盐3克拉匀|盐<用量>拉匀|3克",
            "放盐3克拉面汤里|放盐<用量>拉面汤里|3克",
            "手机上记一下味精2g|手机上记一下味精<用量>|2g",
            // 终审复跑二: g 后面紧跟「拉」「卡」也是用量
            "放盐3g拉匀|放盐<用量>拉匀|3g",
            "味精2g拉面里|味精<用量>拉面里|2g",
            "盐3g卡住了|盐<用量>卡住了|3g"
    })
    void businessUnitsInRecipeContextAndColonRatiosAreMaskedInEveryColumn(String said, String expected, String gone) {
        List<String> ingredients = List.of("白砂糖", "食盐", "水");
        when(master.findIngredientNames(PLAIN)).thenReturn(ingredients);
        when(master.findIngredientNames(SECRET)).thenReturn(ingredients);
        String session = "f-" + said.hashCode();

        FlywheelTextProtector.Protected plain = protector.protect(PLAIN, session, said);
        FlywheelTextProtector.Protected secret = protector.protect(SECRET, session, said);
        String[] columns = {plain.rawInput(), plain.exportInput(), secret.exportInput(), protector.decrypt(secret.inputCipher())};
        for (String column : columns) {
            assertThat(column).isEqualTo(expected);
            for (String raw : gone.split(",")) {
                assertThat(column).doesNotContain(raw);
            }
        }
    }

    /** 终审阴性对照: 业务话、非配方的冒号、g / 克不是单位的说法都原样。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "订单再加20斤", "入库鸭腿200公斤", "比分3:1", "时间10:30", "用2g网络", "手机是4g的", "这个和那个一样"})
    void finalReviewNegativesStayAsIs(String said) {
        List<String> ingredients = List.of("白砂糖", "食盐", "水", "鸭腿");
        when(master.findIngredientNames(PLAIN)).thenReturn(ingredients);
        when(master.findIngredientNames(SECRET)).thenReturn(ingredients);

        FlywheelTextProtector.Protected plain = protector.protect(PLAIN, "fn-" + said.hashCode(), said);
        assertThat(plain.rawInput()).isEqualTo(said);
        assertThat(plain.exportInput()).isEqualTo(said);
        assertThat(protector.protect(SECRET, "fn-" + said.hashCode(), said).exportInput()).isEqualTo(said);
    }

    /** 终审 低4: 15 位只在第 7-12 位是合法 YYMMDD 且不以 20 开头时算身份证。 */
    @Test
    void fifteenDigitNumbersAreIdCardsOnlyWithAValidBirthDate() {
        assertThat(FlywheelTextProtector.looksLikeOldIdCard("110101900101123")).isTrue();
        assertThat(FlywheelTextProtector.looksLikeOldIdCard("202611140012345")).isFalse();   // 日期型编号
        assertThat(FlywheelTextProtector.looksLikeOldIdCard("110101901301123")).isFalse();   // 13 月
        assertThat(FlywheelTextProtector.looksLikeOldIdCard("110101900132123")).isFalse();   // 32 日
        assertThat(protector.protect(PLAIN, "id15", "批次202611140012345").rawInput()).isEqualTo("批次202611140012345");
    }

    /** 三审 低3: 号码各种写法 + 15 位旧身份证。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "电话8613812345678|电话<电话>",
            "电话+86 13812345678|电话<电话>",
            "电话+8613812345678|电话<电话>",
            "电话008613812345678|电话<电话>",
            "电话138-1234-5678|电话<电话>",
            "电话138 1234 5678|电话<电话>",
            "电话+86 138-1234-5678|电话<电话>",
            "身份证110101900101123|身份证<证件号>",
            "身份证11010119900101123X|身份证<证件号>"
    })
    void phoneAndIdFormatsAreMasked(String said, String expected) {
        assertThat(protector.protect(PLAIN, "ph-" + said.hashCode(), said).rawInput()).isEqualTo(expected);
        assertThat(protector.protect(SECRET, "ph-" + said.hashCode(), said).exportInput()).isEqualTo(expected);
    }

    /** 低3 阴性: 普通编号 / 数量不当号码抹。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"查一下1830号任务", "批次20261114001", "下五十斤卤鸭腿"})
    void ordinaryNumbersAreNotTakenForPhones(String said) {
        assertThat(protector.protect(PLAIN, "on-" + said.hashCode(), said).rawInput()).isEqualTo(said);
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
