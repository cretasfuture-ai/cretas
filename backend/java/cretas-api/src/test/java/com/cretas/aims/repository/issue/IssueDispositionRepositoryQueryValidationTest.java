package com.cretas.aims.repository.issue;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.issue.IssueDisposition;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.foodsafety.ColdChainAlertRepository;
import com.cretas.aims.repository.shortage.DailyReconcileRunRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.issue.IssueDispositionService;
import com.cretas.aims.service.issue.IssueDispositionService.SourceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 迁移 P4「发现的问题」的处置在真实 JPA 映射上跑 (H2; 设了 CRETAS_WORKFLOW_PG_URL 时在 PG 上跑)。
 *
 * <p>阴性: 关闭不选结论 / 结论不在原型清单 / 说明不足 2 字 ⇒ 拒绝且什么都没存;
 * 别厂的来源 id ⇒ 404; 没有处理权限 ⇒ 403。阳性: 选了结论能保存, 清掉持久化上下文后能回读。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository", includeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {IssueDispositionRepository.class, ShortageCaseRepository.class,
                ColdChainAlertRepository.class, DailyReconcileRunRepository.class}))
@Import(IssueDispositionService.class)
class IssueDispositionRepositoryQueryValidationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            String bound = url.contains("stringtype=") ? url : url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
            registry.add("spring.datasource.url", () -> bound);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    private static final String F1 = "F-ISSUE-1";
    private static final String F2 = "F-ISSUE-2";
    private static final ActorRef ME = new ActorRef(AuthenticatedActorType.FACTORY_USER, 71L);
    private static final ActorRef OTHER = new ActorRef(AuthenticatedActorType.FACTORY_USER, 72L);

    @Autowired TestEntityManager em;
    @Autowired IssueDispositionRepository repository;
    @Autowired ShortageCaseRepository cases;
    @Autowired IssueDispositionService service;
    @MockBean UserRepository users;
    @MockBean PermissionService permissions;

    private String riskId;

    @BeforeEach
    void seed() {
        when(users.findById(any())).thenAnswer(i -> Optional.of(new User()));
        when(permissions.hasAnyPermission(any(User.class), any(String[].class))).thenReturn(true);
        riskId = newCase(F1, ShortageCase.SourceType.DELIVERY_RISK, "SO-1").getId();
    }

    private ShortageCase newCase(String factoryId, ShortageCase.SourceType type, String source) {
        ShortageCase c = new ShortageCase();
        c.setFactoryId(factoryId);
        c.setSourceType(type);
        c.setSourceId(source);
        c.setStage(ShortageCase.Stage.DETECTED);
        c.setOwnerRole("production_manager");
        c.setInputFingerprint("fp");
        c.setLastRefreshedAt(LocalDateTime.of(2026, 11, 14, 8, 0));
        c.setLastRefreshReason("TEST");
        return cases.saveAndFlush(c);
    }

    private SourceRef risk() {
        return new SourceRef("DELIVERY_RISK", riskId, "交付风险 · SO-1", "/production/delivery-risks");
    }

    @Test
    void conclusionListIsThePrototypeListVerbatim() {
        assertThat(Arrays.stream(IssueDisposition.Conclusion.values()).map(IssueDisposition.Conclusion::label))
                .containsExactly("已处理", "误报", "接受偏差");
    }

    @Test
    void closingWithoutAConclusionIsRejectedAndNothingIsStored() {
        assertThatThrownBy(() -> service.close(F1, ME, risk(), null, "已经和客户确认"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("先选结论");
        assertThatThrownBy(() -> service.close(F1, ME, risk(), "  ", "已经和客户确认"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("先选结论");
        // 中文标签不是结论码; 清单外的码也不行
        assertThatThrownBy(() -> service.close(F1, ME, risk(), "已处理", "已经和客户确认"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("结论只能是");
        assertThatThrownBy(() -> service.close(F1, ME, risk(), "IGNORED", "已经和客户确认"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("结论只能是");
        // 选了结论但没写一句
        assertThatThrownBy(() -> service.close(F1, ME, risk(), "HANDLED", " 好 "))
                .isInstanceOf(BusinessException.class).hasMessageContaining("写一句说明");
        em.flush();
        em.clear();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void closingWithAConclusionIsSavedAndReadsBack() {
        service.close(F1, ME, risk(), "ACCEPTED_DEVIATION", "  客户同意晚两天交  ");
        em.flush();
        em.clear();

        List<IssueDisposition> rows = service.listActive(F1);
        assertThat(rows).hasSize(1);
        IssueDisposition d = rows.get(0);
        assertThat(d.getStatus()).isEqualTo(IssueDisposition.Status.CLOSED);
        assertThat(d.getConclusion()).isEqualTo(IssueDisposition.Conclusion.ACCEPTED_DEVIATION);
        assertThat(d.getConclusion().label()).isEqualTo("接受偏差");
        assertThat(d.getReason()).isEqualTo("客户同意晚两天交");
        assertThat(d.getSourceId()).isEqualTo(riskId);
        assertThat(d.getActorId()).isEqualTo(71L);
        // 别厂看不到
        assertThat(service.listActive(F2)).isEmpty();
    }

    @Test
    void aSourceFromAnotherFactoryIsNotFound() {
        String foreign = newCase(F2, ShortageCase.SourceType.DELIVERY_RISK, "SO-9").getId();
        assertThatThrownBy(() -> service.close(F1, ME,
                new SourceRef("DELIVERY_RISK", foreign, "x", null), "HANDLED", "处理好了"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("本厂没有");
        // 类型对不上 (缺料实例冒充交付风险) 也不行
        assertThatThrownBy(() -> service.close(F1, ME,
                new SourceRef("SHORTAGE_CASE", riskId, "x", null), "HANDLED", "处理好了"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("本厂没有");
        em.flush();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void withoutHandlingPermissionTheCloseIsForbidden() {
        when(permissions.hasAnyPermission(any(User.class), eq("production:read_write"), eq("sales:read_write")))
                .thenReturn(false);
        assertThatThrownBy(() -> service.close(F1, ME, risk(), "HANDLED", "处理好了"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有处理这类问题的权限");
    }

    @Test
    void convertingLandsInTheActorsTodoAndReopenClearsIt() {
        service.convertToTodo(F1, ME, risk());
        em.flush();
        em.clear();
        assertThat(service.assignedTo(F1, 71L)).extracting(IssueDisposition::getTitle).containsExactly("交付风险 · SO-1");
        assertThat(service.assignedTo(F1, 72L)).isEmpty();
        assertThat(service.assignedTo(F2, 71L)).isEmpty();

        // 已转待办的可以关闭 (结论照样必选), 关闭后不再是待办
        service.close(F1, OTHER, risk(), "HANDLED", "已经排进明天");
        em.flush();
        em.clear();
        assertThat(service.assignedTo(F1, 71L)).isEmpty();
        assertThatThrownBy(() -> service.convertToTodo(F1, ME, risk()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("先恢复");

        service.reopen(F1, ME, risk());
        em.flush();
        em.clear();
        IssueDisposition d = repository.findByFactoryIdAndSourceTypeAndSourceId(F1,
                IssueDisposition.SourceType.DELIVERY_RISK, riskId).orElseThrow();
        assertThat(d.getStatus()).isEqualTo(IssueDisposition.Status.OPEN);
        assertThat(d.getConclusion()).isNull();
        assertThat(d.getReason()).isNull();
        assertThat(service.listActive(F1)).isEmpty();
    }
}
