package com.cretas.aims.service.intent.flywheel;

import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.entity.intent.IntentFlywheelTurn.SecrecyMode;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.repository.learning.FactoryNameDirectoryRepository;
import com.cretas.aims.service.consent.TrainingSampleRules;
import com.cretas.aims.service.entity.EntityKind;
import com.cretas.aims.service.entity.FactoryEntityResolver;
import com.cretas.aims.service.secrecy.FactorySecrecyConfig;
import com.cretas.aims.service.secrecy.SecrecyItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 飞轮记录的文本按工厂保密开关处理 (Steve 2026-11-14: 保密关 → 原样进; 保密开 → 先脱敏、原文只加密存, 照样进飞轮,
 * 不整厂排除)。
 *
 * <h3>保密厂 (a 工艺保密 / c 出外脱敏 / d 训练过滤 / e 客户侧保管 任一项开着; 读不到配置或工厂不明一律当开着)</h3>
 * 导出文本 ({@code user_input_export}) 的口径 (Steve 2026-11-14 更正):
 * <ul>
 *   <li><b>客户名</b> (含「老张家」这类称呼) → 「客户A / 客户B」, 同一会话同一客户编号一致; 供应商名 → 「供应商A」
 *       (同为交易对手身份, 与客户同口径);</li>
 *   <li><b>具体价格数字</b> (数字后跟 元 / 块 / 毛 / 角, 前面是 ¥, 或紧挨「价」字) → {@code <价格>};
 *       <b>配方 / 工艺参数</b> (参数词后面的数值, 或带 ℃) → {@code <参数>};</li>
 *   <li><b>产品名、数量 / 重量</b>: 默认不脱; 工厂可单独开 ({@link #OPTIONS_MODULE}, 见下), 开了产品名 → 「产品A」,
 *       数量 / 重量 (数字后跟斤、公斤、箱 …) → {@code <数量>};</li>
 *   <li>普通业务说法 (特价、加急、老样子) 不动: 规则只认名单里的名字和贴着单位 / 参数词的数值, 不认词义。</li>
 * </ul>
 * 名字识别只靠<b>本厂主数据确定性匹配</b> (客户 / 供应商 / 产品表的名字, 含已停用的; 客户名另取去掉
 * 「店 / 有限公司 …」后缀、至少 3 个字的称呼核心, 「老张家卤味店」→「老张家」), ⛔ 不用语义 / embedding 猜。
 * 名单与称呼核心都来自实体解析公共能力 {@link FactoryEntityResolver} ({@code redactionNames} / {@code surfaceForms}),
 * 与填参数、同名列出同一份规范化规则 —— 这里 ⛔ 不另维护一份后缀表。
 * 像称呼但名单里没有的 (老X家 / X记 / X总 / …公司) <b>不替换, 但计数</b> ({@code unmatchedNameLike})。
 * 原文不落明文列; 配了密钥 ({@code CRETAS_FLYWHEEL_RAW_KEY}, base64 的 32 字节) 才以 AES-GCM 密文留一份, 没配就不留。
 * 密文里的「原文」也先过 {@link #rawBaseline} (配方 / 工艺参数值 + 个人隐私已抹)。
 * 名单查不出来 → {@link SecrecyMode#TEXT_DROPPED}: 原文、密文与导出文本都不留 (不拿不全的名单去脱敏)。
 *
 * <h3>底线 (所有工厂、所有列)</h3>
 * 配方 / 工艺参数原值 (配料用量、温度、时长、比例、参数) 与手机 / 证件 / 卡号 / 工资考勤数值在<b>任何工厂、任何列</b>
 * (原文列、密文、context_summary、导出列) 都不落库 (Steve 2026-11-14 + organizer 最严口径), 见 {@link #rawBaseline}。
 *
 * <h3>保密全关的厂</h3>
 * 原文列存「底线处理后的原文」; 导出文本 = 同一底线 + 本厂人员 / 联系人姓名 → 「人员A」 (Steve 2026-11-04: 人名、
 * 号码、工资考勤数值在训练样本里一律剔除, 不归任何保密开关管)。客户名、产品、数量、价格都不动。
 * 名单查不出 → {@link SecrecyMode#TEXT_DROPPED} (原文、导出都不留)。
 *
 * <h3>产品 / 数量开关放哪 (理由)</h3>
 * 放在现有按厂配置表 {@code factory_feature_config}, {@code module_id = AI_FLYWHEEL}, {@code config} 里
 * {@code redactProductNames} / {@code redactQuantities} (布尔, 缺省 false)。不扩 {@link SecrecyItem}: 那个枚举是平台
 * 保密页、feature-config 下发与前端生成文件的共同契约, 加一项要牵动前端重新生成; 这两个开关只影响飞轮导出, 不该进那套。
 * 读不到这一行 = 默认 (不脱); 读库出错 = 两项都按开着 (fail-closed)。只在保密厂起作用。
 */
@Slf4j
@Component
public class FlywheelTextProtector {

    /** 哪些项开着就按保密厂处理。f (审计) 与 b (只在 a 开时起作用) 不影响文本。 */
    static final List<SecrecyItem> TEXT_ITEMS = List.of(
            SecrecyItem.PROCESS_SECRET, SecrecyItem.EGRESS_REDACTION,
            SecrecyItem.TRAINING_FILTER, SecrecyItem.CUSTOMER_HELD);

    public static final String OPTIONS_MODULE = "AI_FLYWHEEL";
    public static final String OPT_PRODUCT = "redactProductNames";
    public static final String OPT_QUANTITY = "redactQuantities";

    static final String CIPHER_PREFIX = "v1:";

    /**
     * 配方 / 工艺参数 (配料、用量、比例、温度、时长、参数) 在<b>所有工厂</b>都脱敏, 不论保密开关 (Steve 2026-11-14 定);
     * 客户名、价格等其余内容仍按保密开关。
     */
    static final boolean RECIPE_IN_ALL_FACTORIES = true;

    enum Kind {
        CUSTOMER("客户"), SUPPLIER("供应商"), PRODUCT("产品"), PERSON("人员");
        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    static final String HAN = "\\u4e00-\\u9fa5";
    /** 像称呼 / 单位名但名单里没有: 不替换, 只计数。 */
    static final Pattern NAME_LIKE = Pattern.compile(
            "[老小阿][" + surnames() + "]家|(?<![" + HAN + "])[" + surnames() + "]记|(?<![" + HAN + "])["
                    + surnames() + "]总(?![量额数计成产体部结和监经])|[" + HAN + "]{2,10}(?:有限公司|公司|超市|商行|饭店|酒楼)");
    /** 手机号: 可带 86 / +86 / 0086 前缀, 中间 3-4-4 可隔空格或横杠 (「+86 138-1234-5678」)。 */
    static final Pattern PHONE = Pattern.compile(
            "(?<![0-9])(?:(?:\\+|00)?86[ -]?)?1[3-9][0-9](?:[ -]?[0-9]{4}){2}(?![0-9])");
    static final Pattern ID_CARD = Pattern.compile("(?<![0-9A-Za-z])[0-9]{17}[0-9Xx](?![0-9A-Za-z])");
    /** 15 位旧身份证 (在手机号之后抹: 「008613812345678」先当手机号)。 */
    static final Pattern ID_CARD_15 = Pattern.compile("(?<![0-9A-Za-z])[0-9]{15}(?![0-9A-Za-z])");
    static final Pattern BANK_CARD = Pattern.compile("(?<![0-9A-Za-z])[0-9]{16,19}(?![0-9A-Za-z])");

    private final FactoryEntityResolver entities;
    private final FactoryNameDirectoryRepository people;
    private final FactoryFeatureConfigRepository featureConfigs;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    private final FlywheelSessionAliases aliases = new FlywheelSessionAliases(20_000);
    private FactorySecrecyConfig secrecy = FactorySecrecyConfig.allOn();

    @Autowired
    public FlywheelTextProtector(FactoryEntityResolver entities,
                                 FactoryNameDirectoryRepository people,
                                 FactoryFeatureConfigRepository featureConfigs,
                                 @Value("${cretas.ai.flywheel.raw-key:${CRETAS_FLYWHEEL_RAW_KEY:}}") String base64Key) {
        this.entities = entities;
        this.people = people;
        this.featureConfigs = featureConfigs;
        this.key = parseKey(base64Key);
    }

    @Autowired(required = false)
    public void setFactorySecrecyConfig(FactorySecrecyConfig secrecy) {
        if (secrecy != null) {
            this.secrecy = secrecy;
        }
    }

    /**
     * 处理结果。原文字段只在 {@link SecrecyMode#PLAIN} 时非空, 且已过 {@link #rawBaseline}; {@code stats} 是各类替换 / 未匹配的次数
     * (customer / supplier / product / person / price / param / quantity / privacy / unmatchedNameLike)。
     */
    public record Protected(SecrecyMode mode, String rawInput, String exportInput, String inputCipher,
                            Map<String, Integer> stats, String rawNotKeptReason) {
    }

    /** 本厂按厂开关 (只在保密厂起作用)。 */
    public record Options(boolean redactProductNames, boolean redactQuantities) {
        static final Options DEFAULT = new Options(false, false);
        static final Options FAIL_CLOSED = new Options(true, true);
    }

    public boolean secretFactory(String factoryId) {
        for (SecrecyItem item : TEXT_ITEMS) {
            if (secrecy.isOn(factoryId, item)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasKey() {
        return key != null;
    }

    /**
     * @param sessionKey 同一会话的键 (工厂|用户|会话); 同键内同一名字的代号一致。null = 只在本句内一致。
     */
    public Protected protect(String factoryId, String sessionKey, String input) {
        Map<String, Integer> stats = new LinkedHashMap<>();
        if (!secretFactory(factoryId)) {
            return plain(factoryId, sessionKey, input, stats);
        }
        if (input == null || input.isEmpty()) {
            return new Protected(SecrecyMode.REDACTED, null, input, null, stats, null);
        }
        // 原料名单查不出 → 连「原文」都不知道该抹哪些配方值: 原文、密文、导出都不留 (fail-closed)
        List<String> ingredientNames;
        List<String> productNames;
        String rawBaseline;
        try {
            ingredientNames = ingredients(factoryId);
            productNames = products(factoryId);
            rawBaseline = rawBaseline(input, ingredientNames, productNames);
        } catch (RuntimeException e) {
            log.warn("[flywheel] 原料名单查不出, 不留文本: factory={}, reason={}", factoryId, e.getClass().getSimpleName());
            return new Protected(SecrecyMode.TEXT_DROPPED, null, null, null, stats, "lookup_failed");
        }
        String cipher = null;
        String notKept = null;
        if (key == null) {
            notKept = "no_key";
        } else {
            try {
                cipher = encrypt(rawBaseline);
            } catch (RuntimeException e) {
                notKept = "encrypt_failed";
                log.warn("[flywheel] 原话加密失败, 不留原文: factory={}, reason={}", factoryId, e.getClass().getSimpleName());
            }
        }
        try {
            Options options = options(factoryId);
            Map<String, Kind> dictionary = new LinkedHashMap<>();
            for (String n : entities.redactionNames(factoryId, EntityKind.CUSTOMER)) {
                addCustomer(dictionary, n, Kind.CUSTOMER);
            }
            for (String n : entities.redactionNames(factoryId, EntityKind.SUPPLIER)) {
                addCustomer(dictionary, n, Kind.SUPPLIER);
            }
            addPeople(dictionary, factoryId);
            if (options.redactProductNames()) {
                // 产品名单与上面「规格不当用量」用的是同一份 (只查一次解析器, 四份名单各一处)
                for (String n : productNames) {
                    add(dictionary, n, Kind.PRODUCT, 2);
                }
            }
            String out = replaceNames(input, dictionary, sessionKey, stats);
            out = maskPrivacy(out, stats);
            out = FlywheelValueMasker.mask(out, new FlywheelValueMasker.Scope(true, true, options.redactQuantities()),
                    ingredientNames, productNames, stats);
            countUnmatched(out, stats);
            return new Protected(SecrecyMode.REDACTED, null, out, cipher, stats, notKept);
        } catch (RuntimeException e) {
            log.warn("[flywheel] 保密厂名单查不出, 不留文本: factory={}, reason={}", factoryId, e.getClass().getSimpleName());
            // 密文也不留: 这条行不进任何读者 (ck_aift_dropped_no_export), 留一份「名单不全时的原文」没有用处
            return new Protected(SecrecyMode.TEXT_DROPPED, null, null, null, stats, "lookup_failed");
        }
    }

    /**
     * 任何工厂、任何列 (明文原文列、密文原文、context_summary) 落库前的底线: 配方 / 工艺参数值换占位符 (只换值留说法)
     * + 个人隐私抹除 (手机 / 证件 / 卡号 / 工资考勤数值)。保密开关只决定客户名、价格等其余项 (organizer 11-14 按最严口径裁定)。
     * 不在这里换人员姓名: 原文列给人工复核看「谁说了什么」, 姓名只在导出列换。
     */
    static String rawBaseline(String input, Collection<String> ingredientNames) {
        return rawBaseline(input, ingredientNames, List.of());
    }

    /** 同上; {@code productNames} 里的数 (产品规格 250g) 是品名的一部分, 不抹。 */
    static String rawBaseline(String input, Collection<String> ingredientNames, Collection<String> productNames) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        Map<String, Integer> ignored = new LinkedHashMap<>();
        String out = maskPrivacy(input, ignored);
        return FlywheelValueMasker.mask(out, new FlywheelValueMasker.Scope(false, RECIPE_IN_ALL_FACTORIES, false),
                ingredientNames, productNames, ignored);
    }

    private Protected plain(String factoryId, String sessionKey, String input, Map<String, Integer> stats) {
        if (input == null || input.isEmpty()) {
            return new Protected(SecrecyMode.PLAIN, input, input, null, stats, null);
        }
        // 全部算完才落到返回值上: 中途任一名单查不出, 原文与导出都不留 (不留半脱敏 / 未脱配方的文本)
        try {
            List<String> ingredientNames = ingredients(factoryId);
            List<String> productNames = products(factoryId);
            String raw = rawBaseline(input, ingredientNames, productNames);
            Map<String, Kind> dictionary = new LinkedHashMap<>();
            addPeople(dictionary, factoryId);
            String export = maskPrivacy(replaceNames(input, dictionary, sessionKey, stats), stats);
            export = FlywheelValueMasker.mask(export, new FlywheelValueMasker.Scope(false, RECIPE_IN_ALL_FACTORIES, false),
                    ingredientNames, productNames, stats);
            return new Protected(SecrecyMode.PLAIN, raw, export, null, stats, null);
        } catch (RuntimeException e) {
            log.warn("[flywheel] 名单查不出, 本条原文与导出文本都不留: factory={}, reason={}",
                    factoryId, e.getClass().getSimpleName());
            return new Protected(SecrecyMode.TEXT_DROPPED, null, null, null, stats, "lookup_failed");
        }
    }

    Options options(String factoryId) {
        if (featureConfigs == null) {
            return Options.DEFAULT;
        }
        try {
            Optional<FactoryFeatureConfig> row =
                    featureConfigs.findByFactoryIdAndModuleIdAndDeletedAtIsNull(factoryId, OPTIONS_MODULE);
            if (row.isEmpty() || !Boolean.TRUE.equals(row.get().getEnabled()) || row.get().getConfig() == null) {
                return Options.DEFAULT;
            }
            Map<String, Object> c = row.get().getConfig();
            return new Options(truthy(c.get(OPT_PRODUCT)), truthy(c.get(OPT_QUANTITY)));
        } catch (RuntimeException e) {
            log.warn("[flywheel] 读不到本厂飞轮脱敏开关, 产品名与数量都按开着: factory={}, reason={}",
                    factoryId, e.getClass().getSimpleName());
            return Options.FAIL_CLOSED;
        }
    }

    /** 本厂产品名 (含规格, 如「演示·酱卤牛肉250g」): 名字里的数不当用量抹。查不出照样抛 (调用方 fail-closed)。 */
    private List<String> products(String factoryId) {
        List<String> out = new ArrayList<>();
        for (String n : entities.redactionNames(factoryId, EntityKind.PRODUCT)) {
            if (n != null && !n.isBlank()) {
                out.add(n.trim());
            }
        }
        return out;
    }

    private List<String> ingredients(String factoryId) {
        List<String> out = new ArrayList<>();
        for (String n : entities.redactionNames(factoryId, EntityKind.MATERIAL)) {
            if (n != null && !n.isBlank()) {
                out.add(n.trim());
            }
        }
        return out;
    }

    private static boolean truthy(Object v) {
        return v instanceof Boolean b ? b : v != null && "true".equalsIgnoreCase(String.valueOf(v).trim());
    }

    private void addPeople(Map<String, Kind> dictionary, String factoryId) {
        if (people == null) {
            return;
        }
        for (String n : people.findPeopleNames(factoryId)) {
            add(dictionary, n, Kind.PERSON, 2);
        }
    }

    /** 全名 + 称呼核心 (由实体解析给出, 与解析同一份后缀规则)。 */
    private static void addCustomer(Map<String, Kind> dictionary, String name, Kind kind) {
        List<String> forms = FactoryEntityResolver.surfaceForms(
                kind == Kind.SUPPLIER ? EntityKind.SUPPLIER : EntityKind.CUSTOMER, name);
        for (int i = 0; i < forms.size(); i++) {
            add(dictionary, forms.get(i), kind, i == 0 ? 2 : FactoryEntityResolver.MIN_CORE);
        }
    }

    /** 先登记的类优先 (客户 > 供应商 > 人员 > 产品): 同一个串只归一类。 */
    private static void add(Map<String, Kind> dictionary, String name, Kind kind, int minLength) {
        if (name == null) {
            return;
        }
        String n = name.trim();
        if (n.length() >= minLength) {
            dictionary.putIfAbsent(n, kind);
        }
    }

    /** 从左到右, 每个位置取名单里最长的那个名字, 换成会话内一致的代号。 */
    String replaceNames(String text, Map<String, Kind> dictionary, String sessionKey, Map<String, Integer> stats) {
        if (dictionary.isEmpty()) {
            return text;
        }
        List<Map.Entry<String, Kind>> names = new ArrayList<>(dictionary.entrySet());
        names.sort(Comparator.comparingInt((Map.Entry<String, Kind> e) -> e.getKey().length()).reversed());
        FlywheelSessionAliases.Book book = aliases.book(sessionKey);
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            Map.Entry<String, Kind> hit = null;
            for (Map.Entry<String, Kind> e : names) {
                if (text.startsWith(e.getKey(), i)) {
                    hit = e;
                    break;
                }
            }
            if (hit == null) {
                out.append(text.charAt(i));
                i++;
                continue;
            }
            Kind kind = hit.getValue();
            // 同一客户的全名和称呼核心共用一个代号
            String identity = canonical(hit.getKey(), kind, dictionary);
            out.append(kind.label).append(book.alias(kind.name(), identity));
            bump(stats, kind.name().toLowerCase());
            i += hit.getKey().length();
        }
        return out.toString();
    }

    /** 称呼核心归到它的全名 (名单里以它开头、同类、最短的那个全名); 没有就是自己。 */
    private static String canonical(String name, Kind kind, Map<String, Kind> dictionary) {
        if (kind != Kind.CUSTOMER && kind != Kind.SUPPLIER) {
            return name;
        }
        String best = name;
        for (Map.Entry<String, Kind> e : dictionary.entrySet()) {
            String other = e.getKey();
            if (e.getValue() == kind && other.length() > name.length() && other.startsWith(name)) {
                String core = other;
                for (int round = 0; round < 2 && !core.equals(name); round++) {
                    core = FactoryEntityResolver.stripSuffix(core);
                }
                if (core.equals(name) && (best.equals(name) || other.length() < best.length())) {
                    best = other;
                }
            }
        }
        return best;
    }

    static String maskPrivacy(String text, Map<String, Integer> stats) {
        if (text == null) {
            return null;
        }
        String out = replaceCounting(ID_CARD, text, "<证件号>", stats);
        out = replaceCounting(BANK_CARD, out, "<卡号>", stats);
        out = replaceCounting(PHONE, out, "<电话>", stats);
        out = replaceOldIdCards(out, stats);
        if (TrainingSampleRules.utteranceHasPayValue(out)) {
            String masked = TrainingSampleRules.maskNumbers(out);
            if (!masked.equals(out)) {
                bump(stats, "privacy");
            }
            out = masked;
        }
        return out;
    }

    /**
     * 15 位旧身份证: 第 7-12 位必须是合法 YYMMDD (月 01-12、日 01-31), 且不以「20」开头
     * (「批次202611140012345」这类日期型编号不算)。
     */
    static boolean looksLikeOldIdCard(String digits) {
        if (digits.length() != 15 || digits.startsWith("20")) {
            return false;
        }
        int month = Integer.parseInt(digits.substring(8, 10));
        int day = Integer.parseInt(digits.substring(10, 12));
        return month >= 1 && month <= 12 && day >= 1 && day <= 31;
    }

    private static String replaceOldIdCards(String text, Map<String, Integer> stats) {
        Matcher m = ID_CARD_15.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (looksLikeOldIdCard(m.group())) {
                m.appendReplacement(sb, Matcher.quoteReplacement("<证件号>"));
                bump(stats, "privacy");
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceCounting(Pattern p, String text, String placeholder, Map<String, Integer> stats) {
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
            bump(stats, "privacy");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static void countUnmatched(String exported, Map<String, Integer> stats) {
        Matcher m = NAME_LIKE.matcher(exported);
        while (m.find()) {
            bump(stats, "unmatchedNameLike");
        }
    }

    private static void bump(Map<String, Integer> stats, String key) {
        stats.merge(key, 1, Integer::sum);
    }

    private static String surnames() {
        return "王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘蒋蔡余杜叶程苏"
                + "魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵"
                + "钱严覃武戴莫孔汤";
    }

    String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return CIPHER_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("encrypt", e);
        }
    }

    /** 只给人工复核 / 测试用: 密钥不对或被改过 → 抛。 */
    String decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(CIPHER_PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("decrypt", e);
        }
    }

    private static SecretKeySpec parseKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(base64.trim());
            if (raw.length != 32) {
                log.warn("[flywheel] CRETAS_FLYWHEEL_RAW_KEY 不是 32 字节, 保密厂原文不留");
                return null;
            }
            return new SecretKeySpec(raw, "AES");
        } catch (IllegalArgumentException e) {
            log.warn("[flywheel] CRETAS_FLYWHEEL_RAW_KEY 不是 base64, 保密厂原文不留");
            return null;
        }
    }
}
