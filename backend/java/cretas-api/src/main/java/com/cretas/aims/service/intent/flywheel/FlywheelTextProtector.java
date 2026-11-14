package com.cretas.aims.service.intent.flywheel;

import com.cretas.aims.entity.intent.IntentFlywheelTurn.SecrecyMode;
import com.cretas.aims.repository.learning.FactoryNameDirectoryRepository;
import com.cretas.aims.service.consent.LlmEgressRedactor;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.consent.TrainingSampleRules;
import com.cretas.aims.service.secrecy.FactorySecrecyConfig;
import com.cretas.aims.service.secrecy.SecrecyItem;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 飞轮记录的文本按工厂保密开关处理 (Steve 2026-11-14: 关 → 原样进; 开 → 先脱敏、原文加密存, 也进)。
 *
 * <p><b>保密厂</b> (a 工艺保密 / c 出外脱敏 / d 训练过滤 / e 客户侧保管 任一项开着; 读不到配置或工厂不明一律当开着):
 * 复用出外脱敏 (c) 的 {@link LlmEgressRedactor} —— 本厂人员姓名 / 客户名称 (名单) 换代号, 号码 / 证件换代号, 认出的
 * 「岗位 + 人名」、公司名换代号, 本厂登记了保密策略的全部产品词 (训练名单, 不看 a 开不开) 换代号; 之后再用训练样本的
 * {@link TrainingSampleRules#maskNumbers} 把其余数值换成 {@code <数值>} (工艺数值不出厂)。原文不落明文列;
 * 配了密钥 ({@code CRETAS_FLYWHEEL_RAW_KEY}, base64 的 32 字节) 才以 AES-GCM 密文留一份, 没配就不留。
 * 名单查不出来 / 脱敏器拒绝 → {@link SecrecyMode#TEXT_DROPPED}: 原文与导出文本都不留。</p>
 *
 * <p><b>保密全关的厂</b>: 原文照存; 导出文本只把本厂人名 / 客户名与号码换代号 (隐私, Steve 2026-11-04: 训练、学习、
 * 蒸馏样本里一律剔除, 不归任何保密开关管), 工资 / 考勤词带数值时数值也抹掉; 保密产品名与工艺数值原样保留。</p>
 *
 * <p>⛔ 不判别的开关, 也不在这里另写一套脱敏规则: 规则全部来自 {@link LlmEgressRedactor} 与 {@link TrainingSampleRules}。</p>
 */
@Slf4j
@Component
public class FlywheelTextProtector {

    /** 哪些项开着就按保密厂处理。f (审计) 与 b (只在 a 开时起作用) 不影响文本。 */
    static final List<SecrecyItem> TEXT_ITEMS = List.of(
            SecrecyItem.PROCESS_SECRET, SecrecyItem.EGRESS_REDACTION,
            SecrecyItem.TRAINING_FILTER, SecrecyItem.CUSTOMER_HELD);

    static final String CIPHER_PREFIX = "v1:";

    private final ObjectMapper objectMapper;
    private final ProcessSecretSampleFilter sampleFilter;
    private final FactoryNameDirectoryRepository directory;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    private FactorySecrecyConfig secrecy = FactorySecrecyConfig.allOn();

    @Autowired
    public FlywheelTextProtector(ObjectMapper objectMapper,
                                 ProcessSecretSampleFilter sampleFilter,
                                 FactoryNameDirectoryRepository directory,
                                 @Value("${cretas.ai.flywheel.raw-key:${CRETAS_FLYWHEEL_RAW_KEY:}}") String base64Key) {
        this.objectMapper = objectMapper;
        this.sampleFilter = sampleFilter;
        this.directory = directory;
        this.key = parseKey(base64Key);
    }

    @Autowired(required = false)
    public void setFactorySecrecyConfig(FactorySecrecyConfig secrecy) {
        if (secrecy != null) {
            this.secrecy = secrecy;
        }
    }

    /** 处理结果。原文字段只在 {@link SecrecyMode#PLAIN} 时非空。 */
    public record Protected(SecrecyMode mode, String rawInput, String exportInput, String inputCipher,
                            List<String> exportCandidateNames, String rawNotKeptReason) {
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
     * @param candidateNames 候选意图的展示名 (保密厂一起脱敏, 与原话共用一套代号)
     */
    public Protected protect(String factoryId, String input, List<String> candidateNames) {
        List<String> names = candidateNames == null ? List.of() : candidateNames;
        if (!secretFactory(factoryId)) {
            return plain(factoryId, input, names);
        }
        String cipher = null;
        String notKept = null;
        if (input != null && !input.isEmpty()) {
            if (key == null) {
                notKept = "no_key";
            } else {
                try {
                    cipher = encrypt(input);
                } catch (RuntimeException e) {
                    notKept = "encrypt_failed";
                    log.warn("[flywheel] 原话加密失败, 不留原文: factory={}, reason={}", factoryId, e.getClass().getSimpleName());
                }
            }
        }
        try {
            List<String> texts = new ArrayList<>();
            texts.add(input == null ? "" : input);
            texts.addAll(names);
            LlmEgressRedactor.Result r = new LlmEgressRedactor(objectMapper)
                    .redactTexts(texts, parties(factoryId), protectedTerms(factoryId), true);
            if (r.refused()) {
                return new Protected(SecrecyMode.TEXT_DROPPED, null, null, cipher, List.of(), notKept);
            }
            String exportInput = input == null ? null : TrainingSampleRules.maskNumbers(r.text(0));
            List<String> exportNames = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                exportNames.add(TrainingSampleRules.maskNumbers(r.text(i + 1)));
            }
            return new Protected(SecrecyMode.REDACTED, null, exportInput, cipher, exportNames, notKept);
        } catch (RuntimeException e) {
            log.warn("[flywheel] 保密厂脱敏做不了, 不留文本: factory={}, reason={}", factoryId, e.getClass().getSimpleName());
            return new Protected(SecrecyMode.TEXT_DROPPED, null, null, cipher, List.of(), notKept);
        }
    }

    private Protected plain(String factoryId, String input, List<String> names) {
        String export = null;
        if (input != null) {
            try {
                LlmEgressRedactor.Result r = new LlmEgressRedactor(objectMapper)
                        .redactTexts(List.of(input), parties(factoryId), Set.of(), false);
                if (!r.refused()) {
                    export = r.text(0);
                    if (TrainingSampleRules.utteranceHasPayValue(export)) {
                        export = TrainingSampleRules.maskNumbers(export);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("[flywheel] 人名名单查不出, 本条不出导出文本: factory={}, reason={}",
                        factoryId, e.getClass().getSimpleName());
            }
        }
        return new Protected(SecrecyMode.PLAIN, input, export, null, names, null);
    }

    /** 本厂人员 / 客户名单 (精确名字, 整串换代号)。查库出错照样抛 (调用方据此不出文本)。 */
    private Map<String, LlmEgressRedactor.Party> parties(String factoryId) {
        Map<String, LlmEgressRedactor.Party> out = new LinkedHashMap<>();
        if (directory == null || factoryId == null || factoryId.isBlank()) {
            return out;
        }
        for (String n : directory.findCustomerNames(factoryId)) {
            if (n != null && n.trim().length() >= 2) out.put(n.trim(), LlmEgressRedactor.Party.CUSTOMER);
        }
        for (String n : directory.findPeopleNames(factoryId)) {
            if (n != null && n.trim().length() >= 2) out.putIfAbsent(n.trim(), LlmEgressRedactor.Party.PERSON);
        }
        return out;
    }

    /** 本厂登记了保密策略的全部产品词 (训练名单, 不看 a) ∪ 生效中的保密产品名 (出外脱敏名单)。 */
    private Set<String> protectedTerms(String factoryId) {
        Set<String> out = new LinkedHashSet<>();
        if (sampleFilter == null) {
            return out;
        }
        out.addAll(sampleFilter.trainingLists(factoryId).terms());
        out.addAll(sampleFilter.protectedProductNames(factoryId));
        return out;
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
