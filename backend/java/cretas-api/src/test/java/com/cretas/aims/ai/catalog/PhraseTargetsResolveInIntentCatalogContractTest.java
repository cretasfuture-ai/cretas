package com.cretas.aims.ai.catalog;

import com.cretas.aims.config.IntentKnowledgeBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 短语表里每个意图码必须在当前意图目录里解析得到 (BP 问题 47 复测: 短语快捷指向 PRODUCT_SALES_RANKING,
 * 该码在 V20260821_06 里已被软删、目录里只剩 PRODUCT_PRODUCT_SALES_RANKING, 短语快捷按码取配置取不到就静默退回
 * 决策层, #1942 的测试只断言「短语命中的字符串」所以全绿但生产没接上)。
 *
 * <p>真相源 = factory-intent-catalog.json (V20261104_51 起的当前目录, 含别名), 与运行时
 * {@link FactoryIntentCatalog#canonicalCode} 同一份。读到 0 条目录一律红 (不把「读不到」当「全解析」)。
 *
 * <p>存量: 下面 基线文件是写这条闸时已经解析不到的码 (历史包袱, 棘轮只许减少、不许新增);
 * 新增 / 改出来的死码一律红。
 */
class PhraseTargetsResolveInIntentCatalogContractTest {

    private static final String BASELINE = "/ai/phrase-targets-unresolved-baseline.txt";

    /** 写这条闸时就解析不到的短语目标码 (存量, 不等于「库里确认没有」: 目录只是当前规范目录)。只许删, 不许加。 */
    static Set<String> knownUnresolved() {
        try (var in = PhraseTargetsResolveInIntentCatalogContractTest.class.getResourceAsStream(BASELINE)) {
            if (in == null) throw new IllegalStateException("基线文件不存在: " + BASELINE);
            Set<String> out = new TreeSet<>();
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
            }
            return out;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Set<String>> unresolvedTargets() {
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        Map<String, Set<String>> bad = new TreeMap<>();
        for (Map<String, String> table : java.util.List.of(kb.getPhraseToIntentMapping(), kb.getCommonPhraseMapping())) {
            for (Map.Entry<String, String> e : table.entrySet()) {
                String code = e.getValue();
                if (FactoryIntentCatalog.isRetiredIntent(code)) continue;      // 已退役的码有专门的退役闸
                if (!FactoryIntentCatalog.canonicalCodes().contains(FactoryIntentCatalog.canonicalCode(code))) {
                    bad.computeIfAbsent(code, k -> new TreeSet<>()).add(e.getKey());
                }
            }
        }
        return bad;
    }

    @Test
    @DisplayName("自检: 目录真的读到了, 短语表真的有内容")
    void instrumentIsAlive() {
        assertThat(FactoryIntentCatalog.canonicalCodes()).as("目录读到 0 条 = 仪器坏了").hasSizeGreaterThan(100);
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        assertThat(kb.getPhraseToIntentMapping()).isNotEmpty();
    }

    @Test
    @DisplayName("短语表的目标码都在当前意图目录里 (存量棘轮外不许新增死码)")
    void everyPhraseTargetResolvesInCurrentCatalog() {
        Map<String, Set<String>> bad = unresolvedTargets();
        String regen = System.getProperty("regenPhraseBaseline");
        if (regen != null) {   // 只在人工重生基线时用: -DregenPhraseBaseline=<输出路径>
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(regen), String.join("\n", bad.keySet()) + "\n");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            return;
        }
        Set<String> known = knownUnresolved();
        assertThat(known).as("基线读到 0 条 = 仪器坏了").isNotEmpty();
        Map<String, Set<String>> fresh = new TreeMap<>(bad);
        fresh.keySet().removeAll(known);
        assertThat(fresh)
                .as("这些短语指向的意图码在 factory-intent-catalog.json 里解析不到 (运行时按码取配置会取空, 短语快捷静默失效)。"
                        + "改成目录里的码或别名, 不要加进基线文件")
                .isEmpty();
        assertThat(known)
                .as("基线里有已经能解析 / 已不在短语表的码, 请从基线文件删掉 (棘轮只减不增)")
                .allSatisfy(code -> assertThat(bad).containsKey(code));
    }

    @Test
    @DisplayName("47 的死码: 增量归因短语指向的码必须解析得到")
    void growthAttributionPhrasesPointAtALiveIntent() {
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        for (String q : new String[] {
                "10 月比 9 月涨了 8.2%，主要是哪个客户、哪个产品带来的",
                "10月比9月涨了8.2%，主要是哪个客户、哪个产品带来的",
                "10 月比 9 月涨了 8.2%，主要是哪个客户、哪个产品带来的？"}) {
            var hit = kb.matchPhraseHit(q.toLowerCase().trim(), "FACTORY");
            assertThat(hit).as(q).isPresent();
            assertThat(com.cretas.aims.service.execution.PhraseShortcutEligibility.isUnconditionedSimple(
                    q, hit.get().getKey(), hit.get().getValue())).as("短语快捷必须放行原句: " + q).isTrue();
            String code = hit.get().getValue();
            assertThat(code).as("增量归因必须指向产品销售排行的现行码").isEqualTo("PRODUCT_PRODUCT_SALES_RANKING");
            assertThat(FactoryIntentCatalog.canonicalCodes()).as("原句 [" + q + "] 命中的码 " + code + " 解析不到").contains(FactoryIntentCatalog.canonicalCode(code));
        }
    }
}
