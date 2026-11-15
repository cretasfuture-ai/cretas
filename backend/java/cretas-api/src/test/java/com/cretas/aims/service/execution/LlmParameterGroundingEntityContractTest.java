package com.cretas.aims.service.execution;

import com.cretas.aims.repository.FactoryEntityDirectoryRepository;
import com.cretas.aims.service.entity.FactoryEntityResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 填参数接上实体解析 (LlmParameterGrounding 规则 3): LLM 抽出的客户 / 产品 / 员工名到本厂主数据<b>全名</b>精确匹配。
 * 解析规则本身 (两层、候选、停用、工厂范围) 在真实 JPA 上由 {@code FactoryEntityResolverRepositoryQueryValidationTest}
 * 钉住; 这里用桩仓储给定本厂主数据, 量的是「填参数用解析结果做了什么」: 只有全名唯一命中才换名, 候选 ⛔ 不换参数,
 * 写操作不解析到停用对象, 新建类工具不因主数据里没有而丢参数, 原话比对与解析同一份规范化 (NFKC)。
 */
@DisplayName("填参数: 实体名到本厂主数据落地")
class LlmParameterGroundingEntityContractTest {

    static final String F = "F-GROUND";
    static final Map<String, Object> SCHEMA = Map.of("properties", Map.of(
            "customerName", Map.of("type", "string"),
            "productName", Map.of("type", "string"),
            "employeeName", Map.of("type", "string")));

    private final FactoryEntityDirectoryRepository directory = mock(FactoryEntityDirectoryRepository.class);
    private final FactoryEntityResolver resolver = new FactoryEntityResolver(directory, null);

    {
        when(directory.findCustomerRows(anyString())).thenReturn(List.of());
        when(directory.findCustomerRows(F)).thenReturn(List.of(
                new Object[]{"c1", "老张家卤味店", "CU001", null, null, null, true},
                new Object[]{"c2", "华联超市", "CU002", "中山路", null, null, true},
                new Object[]{"c3", "华联超市", "CU003", "解放路", null, null, true},
                new Object[]{"c4", "旭日商行", "CU004", null, null, null, false},
                new Object[]{"c5", "老张家熟食店", "CU005", null, null, null, true},
                new Object[]{"c6", "新世纪超市", "CU006", null, null, null, true}));
        when(directory.findProductRows(F)).thenReturn(List.<Object[]>of(
                new Object[]{"p1", "卤鸭腿", "P-1", "500g/袋", null, true}));
        when(directory.findActiveEmployeeRows(F)).thenReturn(List.<Object[]>of(
                new Object[]{11L, "张三", "加工一车间", null, "011", null}));
    }

    private LlmParameterGrounding.EntityScope read(String factoryId) {
        return LlmParameterGrounding.EntityScope.of(resolver, factoryId, "customer_crm_status_query", true);
    }

    private LlmParameterGrounding.Result ground(String key, String value, String userInput,
                                                LlmParameterGrounding.EntityScope scope) {
        return LlmParameterGrounding.ground(Map.of(key, value), Map.of(), userInput, SCHEMA, scope);
    }

    @Test
    @DisplayName("全名唯一命中: 收下并换成主数据里的写法 (「老张家 卤味店」→ 老张家卤味店, 熟食店不混进来; 「张 三」→ 张三)")
    void uniqueFullNameIsCanonicalised() {
        assertThat(ground("customerName", "老张家 卤味店", "查一下老张家 卤味店的客户状态", read(F)).accepted())
                .containsEntry("customerName", "老张家卤味店");
        assertThat(ground("employeeName", "张 三", "张 三今天打卡了吗", read(F)).accepted())
                .containsEntry("employeeName", "张三");
    }

    @Test
    @DisplayName("候选不替换参数: 「老张家」「新世纪集团」「华联」只是称呼核心 / 包含候选 → 原样收用户说的, 不换成某家")
    void suggestionsNeverReplaceTheParameter() {
        assertThat(ground("customerName", "老张家", "查一下老张家的客户状态", read(F)).accepted())
                .containsEntry("customerName", "老张家");
        assertThat(ground("customerName", "新世纪集团", "新世纪集团什么状态", read(F)).accepted())
                .containsEntry("customerName", "新世纪集团");
        assertThat(ground("customerName", "华联", "华联什么状态", read(F)).accepted())
                .containsEntry("customerName", "华联");
    }

    @Test
    @DisplayName("多个全名命中: 原样收, 交给工具按口径规则 8 全部列出 (⛔ 不只取第一个)")
    void multipleMatchesKeepTheSaidName() {
        LlmParameterGrounding.Result r = ground("customerName", "华联超市", "华联超市什么状态", read(F));
        assertThat(r.accepted()).containsEntry("customerName", "华联超市");
        assertThat(r.dropped()).isEmpty();
    }

    @Test
    @DisplayName("没有命中: LLM 自己编的 (本厂没有、原话也没有) 丢掉; 原话比对走 NFKC (全角「ＡＢＣ」= abc)")
    void noMatchOnlyKeepsWhatTheUserSaidUnderSameNormalisation() {
        LlmParameterGrounding.Result invented = ground("customerName", "华联超市旗舰店", "查一下客户状态", read(F));
        assertThat(invented.accepted()).doesNotContainKey("customerName");
        assertThat(invented.dropped()).containsKey("customerName");

        assertThat(ground("customerName", "abc食品", "查一下ＡＢＣ食品的状态", read(F)).accepted())
                .containsEntry("customerName", "abc食品");
        assertThat(ground("customerName", "xyz食品", "查一下ＡＢＣ食品的状态", read(F)).dropped())
                .as("阴性对照: 原话里没有的名字照样丢").containsKey("customerName");
    }

    @Test
    @DisplayName("写操作不解析到停用客户: 读查询认得旭日商行, 写操作不认 (原话里也没有 → 丢)")
    void writeDoesNotResolveToInactive() {
        LlmParameterGrounding.Result readResult = ground("customerName", "旭日商行", "改一下那个客户的电话", read(F));
        assertThat(readResult.accepted()).as("阳性对照: 读查询解析得到停用客户").containsEntry("customerName", "旭日商行");

        LlmParameterGrounding.Result write = ground("customerName", "旭日商行", "改一下那个客户的电话",
                LlmParameterGrounding.EntityScope.of(resolver, F, "customer_update", false));
        assertThat(write.accepted()).doesNotContainKey("customerName");
        assertThat(write.dropped()).containsKey("customerName");
        assertThat(LlmParameterGrounding.EntityScope.of(resolver, F).purpose())
                .as("不知道读写时按写操作").isEqualTo(FactoryEntityResolver.Purpose.WRITE);
    }

    @Test
    @DisplayName("新建类工具: customer_create 的新名字「新世纪集团」不被换成新世纪超市; product_create 的新名字原话没逐字出现也不丢")
    void creatingToolsKeepTheNewName() {
        LlmParameterGrounding.Result customer = ground("customerName", "新世纪集团", "帮我建个客户叫新世纪集团",
                LlmParameterGrounding.EntityScope.of(resolver, F, "customer_create", false));
        assertThat(customer.accepted()).containsEntry("customerName", "新世纪集团");

        String said = "新建产品：香辣鸭脖（500g）";
        LlmParameterGrounding.Result created = ground("productName", "香辣鸭脖500g", said,
                LlmParameterGrounding.EntityScope.of(resolver, F, "product_create", false));
        System.out.println("[grounding] product_create accepted=" + created.accepted() + " dropped=" + created.dropped());
        assertThat(created.accepted()).containsEntry("productName", "香辣鸭脖500g");
        assertThat(created.dropped()).isEmpty();

        LlmParameterGrounding.Result update = ground("productName", "香辣鸭脖500g", said,
                LlmParameterGrounding.EntityScope.of(resolver, F, "product_update", false));
        assertThat(update.dropped()).as("阴性对照: 非新建工具对本厂没有的产品名照常校验").containsKey("productName");
    }

    @Test
    @DisplayName("跨厂: 别的工厂里没有老张家卤味店 → 不换成 F 厂的名字; 原话里没有就丢")
    void otherFactoryDoesNotSeeThisFactorysMasterData() {
        assertThat(ground("customerName", "老张家 卤味店", "查一下老张家 卤味店的客户状态", read("F-OTHER")).accepted())
                .containsEntry("customerName", "老张家 卤味店");
        assertThat(ground("customerName", "老张家卤味店", "查一下客户状态", read("F-OTHER")).dropped())
                .containsKey("customerName");
    }

    @Test
    @DisplayName("没给范围 (旧调用) 不做实体名这一条: 同一个编出来的名字, 有范围丢、没范围收; 查库出错时原样收")
    void withoutScopeOrOnLookupFailureBehaviourIsUnchanged() {
        LlmParameterGrounding.Result legacy = LlmParameterGrounding.ground(
                Map.of("customerName", "华联超市旗舰店"), Map.of(), "查一下客户状态", SCHEMA);
        assertThat(legacy.accepted()).containsEntry("customerName", "华联超市旗舰店");
        assertThat(ground("customerName", "华联超市旗舰店", "查一下客户状态", read(F)).dropped())
                .as("对照: 带范围时同一个值被丢, 说明上面收下是因为没范围").containsKey("customerName");
        assertThat(LlmParameterGrounding.EntityScope.of(resolver, " ")).isNull();

        FactoryEntityDirectoryRepository broken = mock(FactoryEntityDirectoryRepository.class);
        when(broken.findCustomerRows(anyString())).thenThrow(new IllegalStateException("db down"));
        LlmParameterGrounding.Result r = LlmParameterGrounding.ground(
                Map.of("customerName", "老张家"), Map.of(), "查一下客户状态", SCHEMA,
                LlmParameterGrounding.EntityScope.of(new FactoryEntityResolver(broken, null), F));
        assertThat(r.accepted()).containsEntry("customerName", "老张家");
    }
}
