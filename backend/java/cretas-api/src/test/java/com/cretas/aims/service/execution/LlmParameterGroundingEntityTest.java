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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 填参数接上实体解析 (LlmParameterGrounding 规则 3): LLM 抽出的客户 / 员工名到本厂主数据精确匹配。
 * 解析规则本身 (规范化、工厂范围) 在真实 JPA 上由 {@code FactoryEntityResolverRepositoryQueryValidationTest} 钉住;
 * 这里用桩仓储给定本厂主数据, 量的是「填参数用解析结果做了什么」。
 */
@DisplayName("填参数: 实体名到本厂主数据落地")
class LlmParameterGroundingEntityTest {

    static final String F = "F-GROUND";
    static final Map<String, Object> SCHEMA = Map.of("properties", Map.of(
            "customerName", Map.of("type", "string"),
            "employeeName", Map.of("type", "string")));

    private final FactoryEntityDirectoryRepository directory = mock(FactoryEntityDirectoryRepository.class);
    private final FactoryEntityResolver resolver = new FactoryEntityResolver(directory, null);

    {
        when(directory.findCustomerRows(anyString())).thenReturn(List.of());
        when(directory.findCustomerRows(F)).thenReturn(List.of(
                new Object[]{"c1", "老张家卤味店", "CU001", null, null, null},
                new Object[]{"c2", "华联超市", "CU002", "中山路", null, null},
                new Object[]{"c3", "华联超市", "CU003", "解放路", null, null}));
        when(directory.findActiveEmployeeRows(F)).thenReturn(List.<Object[]>of(
                new Object[]{11L, "张三", "加工一车间", null, "011", null}));
    }

    private LlmParameterGrounding.Result ground(String key, String value, String userInput, String factoryId) {
        return LlmParameterGrounding.ground(Map.of(key, value), Map.of(), userInput, SCHEMA,
                LlmParameterGrounding.EntityScope.of(resolver, factoryId));
    }

    @Test
    @DisplayName("唯一命中: 收下并换成主数据里的名字 (「老张家」→ 老张家卤味店; 「张 三」→ 张三)")
    void uniqueMatchIsCanonicalised() {
        assertThat(ground("customerName", "老张家", "查一下老张家的客户状态", F).accepted())
                .containsEntry("customerName", "老张家卤味店");
        assertThat(ground("employeeName", "张 三", "张 三今天打卡了吗", F).accepted())
                .containsEntry("employeeName", "张三");
    }

    @Test
    @DisplayName("多个命中: 原样收, 交给工具按口径规则 8 全部列出 (⛔ 不只取第一个)")
    void multipleMatchesKeepTheSaidName() {
        LlmParameterGrounding.Result r = ground("customerName", "华联超市", "华联超市什么状态", F);
        assertThat(r.accepted()).containsEntry("customerName", "华联超市");
        assertThat(r.dropped()).isEmpty();
    }

    @Test
    @DisplayName("没有命中: 用户说过的原样收; LLM 自己编的 (本厂没有、原话也没有) 丢掉; 「华联」不按包含算成华联超市")
    void noMatchOnlyKeepsWhatTheUserSaid() {
        assertThat(ground("customerName", "华联", "华联什么状态", F).accepted())
                .containsEntry("customerName", "华联");
        LlmParameterGrounding.Result invented = ground("customerName", "华联超市旗舰店", "查一下客户状态", F);
        assertThat(invented.accepted()).doesNotContainKey("customerName");
        assertThat(invented.dropped()).containsKey("customerName");
    }

    @Test
    @DisplayName("跨厂: 别的工厂里没有老张家 → 不换成 F 厂的名字; 原话里没有就丢")
    void otherFactoryDoesNotSeeThisFactorysMasterData() {
        assertThat(ground("customerName", "老张家", "查一下老张家的客户状态", "F-OTHER").accepted())
                .containsEntry("customerName", "老张家");
        assertThat(ground("customerName", "老张家卤味店", "查一下客户状态", "F-OTHER").dropped())
                .containsKey("customerName");
    }

    @Test
    @DisplayName("没给范围 (旧调用) 与以前一样不查主数据; 查库出错时原样收, 不因此丢参数")
    void withoutScopeOrOnLookupFailureBehaviourIsUnchanged() {
        LlmParameterGrounding.Result legacy = LlmParameterGrounding.ground(
                Map.of("customerName", "华联超市旗舰店"), Map.of(), "查一下客户状态", SCHEMA);
        assertThat(legacy.accepted()).containsEntry("customerName", "华联超市旗舰店");
        verify(directory, never()).findCustomerRows("F-NONE");

        FactoryEntityDirectoryRepository broken = mock(FactoryEntityDirectoryRepository.class);
        when(broken.findCustomerRows(anyString())).thenThrow(new IllegalStateException("db down"));
        LlmParameterGrounding.Result r = LlmParameterGrounding.ground(
                Map.of("customerName", "老张家"), Map.of(), "查一下客户状态", SCHEMA,
                LlmParameterGrounding.EntityScope.of(new FactoryEntityResolver(broken, null), F));
        assertThat(r.accepted()).containsEntry("customerName", "老张家");
    }
}
