package com.cretas.aims.controller;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.SavedViewRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.view.SavedViewService;
import com.cretas.aims.service.view.ViewDefinitionValidator;
import com.cretas.aims.service.view.ViewEntityCatalog;
import com.cretas.aims.service.view.ViewQueryEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S6-01: 保存的视图在真实 controller 映射 + 真实服务 + 真实 JPA 查询下, 按「当前用户」的权限重算。
 * 权限判定是 DB 驱动的 PermissionService, 这里按用户名给每个人一份固定的权限集合。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories(basePackageClasses = SavedViewRepository.class,
        includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {SavedViewRepository.class, UserRepository.class}))
@Import({ViewQueryEngine.class, ViewEntityCatalog.class, ViewDefinitionValidator.class})
class SavedViewControllerTest {

    @Autowired EntityManager em;
    @Autowired SavedViewRepository views;
    @Autowired UserRepository users;
    @Autowired ViewQueryEngine engine;
    @Autowired ViewEntityCatalog catalog;
    @Autowired ViewDefinitionValidator validator;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private Long finance;   // sales + finance
    private Long sales;     // sales only (no finance)
    private Long nobody;    // no permission at all
    private Long foreign;   // other factory, has everything

    private static final Map<String, Set<String>> PERMS = Map.of(
            "v-finance", Set.of("sales:read", "finance:read"),
            "v-sales", Set.of("sales:read"),
            "v-nobody", Set.of(),
            "v-foreign", Set.of("sales:read", "finance:read"));

    @BeforeEach
    void setUp() {
        for (String id : new String[]{"F1", "F2"}) {
            Factory f = new Factory();
            f.setId(id);
            f.setName(id);
            em.persist(f);
        }
        finance = user("v-finance", "F1");
        sales = user("v-sales", "F1");
        nobody = user("v-nobody", "F1");
        foreign = user("v-foreign", "F2");

        customer("C1", "F1", "Alpha", true, "5000.00");
        customer("C2", "F1", "Beta", true, "100.00");
        customer("C3", "F1", "Gamma", false, "9000.00");
        customer("C4", "F2", "Delta-other-factory", true, "7777.00");
        em.flush();
        em.clear();

        // Mockito 把 varargs 展开进 getArguments(): [user, p1, p2, ...]
        PermissionService perms = mock(PermissionService.class, inv -> {
            if (!"hasAnyPermission".equals(inv.getMethod().getName())) return null;
            Object[] args = inv.getArguments();
            Set<String> have = PERMS.getOrDefault(((User) args[0]).getUsername(), Set.of());
            return Arrays.stream(args, 1, args.length).anyMatch(p -> have.contains((String) p));
        });
        SavedViewService service = new SavedViewService(views, catalog, validator, engine, users, perms, json);
        mvc = MockMvcBuilders.standaloneSetup(new SavedViewController(service)).build();
    }

    private Long user(String name, String factory) {
        User u = new User();
        u.setFactoryId(factory);
        u.setUsername(name);
        u.setPasswordHash("unused");
        u.setIsActive(true);
        em.persist(u);
        em.flush();
        return u.getId();
    }

    private void customer(String id, String factory, String name, boolean active, String credit) {
        Customer c = new Customer();
        c.setId(id);
        c.setFactoryId(factory);
        c.setCode(id);
        c.setCustomerCode(id);
        c.setName(name);
        c.setIsActive(active);
        c.setCreditLimit(new BigDecimal(credit));
        c.setCreatedBy(finance == null ? 1L : finance);
        em.persist(c);
    }

    private static RequestPostProcessor as(Long userId, String factoryId) {
        return req -> {
            req.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                    new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, factoryId, "x"));
            return req;
        };
    }

    private static final String DEF_WITH_CREDIT = """
            {"entity":"customers","columns":["code","name","creditLimit"],
             "filters":[{"field":"isActive","op":"eq","value":"true"}],
             "sort":{"field":"code","direction":"asc"}}""";

    private String create(Long actor, String factory, String name, String definition, boolean shared) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"shared\":" + shared + ",\"definition\":" + definition + "}";
        MvcResult r = mvc.perform(post("/api/mobile/{f}/saved-views", factory).with(as(actor, factory))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private JsonNode execute(Long actor, String factory, String id) throws Exception {
        MvcResult r = mvc.perform(get("/api/mobile/{f}/saved-views/{id}/execute", factory, id).with(as(actor, factory)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("data");
    }

    @Test
    void owner_with_finance_sees_credit_column_and_only_own_factory_rows() throws Exception {
        String id = create(finance, "F1", "活跃客户", DEF_WITH_CREDIT, true);
        JsonNode data = execute(finance, "F1", id);
        assertThat(data.get("rowCount").asInt()).isEqualTo(2);
        assertThat(data.get("hiddenColumns")).isEmpty();
        assertThat(data.at("/rows/0/creditLimit").decimalValue()).isEqualByComparingTo("5000.00");
        assertThat(data.get("rows").toString()).doesNotContain("Delta-other-factory");
    }

    @Test
    void same_view_opened_by_user_without_finance_shows_fewer_columns_never_more() throws Exception {
        String id = create(finance, "F1", "活跃客户", DEF_WITH_CREDIT, true);
        JsonNode data = execute(sales, "F1", id);
        assertThat(data.get("columns").toString()).isEqualTo("[\"code\",\"name\"]");
        assertThat(data.get("hiddenColumns").toString()).isEqualTo("[\"creditLimit\"]");
        assertThat(data.get("rows").toString()).doesNotContain("creditLimit").doesNotContain("5000");
        assertThat(data.get("rowCount").asInt()).isEqualTo(2);
    }

    @Test
    void user_without_entity_permission_gets_403_even_for_a_shared_view() throws Exception {
        String id = create(finance, "F1", "活跃客户", DEF_WITH_CREDIT, true);
        mvc.perform(get("/api/mobile/F1/saved-views/{id}/execute", id).with(as(nobody, "F1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void view_filtering_on_a_field_the_opener_cannot_see_is_refused_not_silently_widened() throws Exception {
        String def = """
                {"entity":"customers","columns":["code","name"],
                 "filters":[{"field":"creditLimit","op":"gt","value":"1000"}]}""";
        String id = create(finance, "F1", "大额客户", def, true);
        assertThat(execute(finance, "F1", id).get("rowCount").asInt()).isEqualTo(2); // C1 5000, C3 9000
        mvc.perform(get("/api/mobile/F1/saved-views/{id}/execute", id).with(as(sales, "F1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void execute_recomputes_from_current_source_state_every_time() throws Exception {
        String id = create(finance, "F1", "活跃客户", DEF_WITH_CREDIT, false);
        assertThat(execute(finance, "F1", id).get("rowCount").asInt()).isEqualTo(2);
        em.createQuery("UPDATE Customer c SET c.isActive = false WHERE c.id = 'C2'").executeUpdate();
        em.clear();
        JsonNode after = execute(finance, "F1", id);
        assertThat(after.get("rowCount").asInt()).isEqualTo(1);
        assertThat(after.at("/rows/0/code").asText()).isEqualTo("C1");
        assertThat(after.get("version").asInt()).isEqualTo(1); // 定义没变, 版本不变
    }

    @Test
    void another_factorys_view_is_404_and_unshared_views_are_404_for_colleagues() throws Exception {
        String id = create(finance, "F1", "私有", DEF_WITH_CREDIT, false);
        mvc.perform(get("/api/mobile/F2/saved-views/{id}", id).with(as(foreign, "F2")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/mobile/F2/saved-views/{id}/execute", id).with(as(foreign, "F2")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/mobile/F1/saved-views/{id}/execute", id).with(as(sales, "F1")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/mobile/F1/saved-views").with(as(sales, "F1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        // 路径工厂与 JWT 工厂不一致
        mvc.perform(get("/api/mobile/F2/saved-views").with(as(finance, "F1"))).andExpect(status().isForbidden());
    }

    @Test
    void definition_is_validated_against_the_whitelist() throws Exception {
        String[] bad = {
                "{\"entity\":\"users\",\"columns\":[\"username\"]}",
                "{\"entity\":\"customers\",\"columns\":[\"passwordHash\"]}",
                "{\"entity\":\"customers\",\"columns\":[\"code\"],\"filters\":[{\"field\":\"code\",\"op\":\"gt\",\"value\":\"1\"}]}",
                "{\"entity\":\"customers\",\"columns\":[\"code\"],\"filters\":[{\"field\":\"creditLimit\",\"op\":\"eq\",\"value\":\"abc\"}]}",
                "{\"entity\":\"customers\",\"columns\":[\"code\"],\"sort\":{\"field\":\"name; DROP TABLE x\"}}",
                "{\"entity\":\"customers\",\"columns\":[\"code\"],\"rawSql\":\"select 1\"}",
                "{\"entity\":\"customers\",\"columns\":[\"code\"],\"limit\":100000}",
                "{\"entity\":\"customers\",\"columns\":[]}"};
        for (String def : bad) {
            mvc.perform(post("/api/mobile/F1/saved-views").with(as(finance, "F1"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"x\",\"definition\":" + def + "}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(views.count()).isZero();
    }

    @Test
    void cannot_save_a_view_on_an_entity_the_user_cannot_read() throws Exception {
        mvc.perform(post("/api/mobile/F1/saved-views").with(as(nobody, "F1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"definition\":" + DEF_WITH_CREDIT + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void like_wildcards_in_filter_values_are_data_not_syntax() throws Exception {
        String def = """
                {"entity":"customers","columns":["code"],
                 "filters":[{"field":"name","op":"contains","value":"%"}]}""";
        String id = create(finance, "F1", "通配符", def, false);
        assertThat(execute(finance, "F1", id).get("rowCount").asInt()).isZero();
    }

    @Test
    void update_bumps_version_only_when_definition_changes_and_only_owner_may_edit() throws Exception {
        String id = create(finance, "F1", "v", DEF_WITH_CREDIT, true);
        String newDef = "{\"entity\":\"customers\",\"columns\":[\"code\"]}";
        mvc.perform(put("/api/mobile/F1/saved-views/{id}", id).with(as(finance, "F1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed\",\"version\":1,\"definition\":" + newDef + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(put("/api/mobile/F1/saved-views/{id}", id).with(as(finance, "F1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"stale\",\"version\":1}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/mobile/F1/saved-views/{id}", id).with(as(sales, "F1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"hijack\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/mobile/F1/saved-views/{id}", id).with(as(sales, "F1")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/mobile/F1/saved-views/{id}", id).with(as(finance, "F1")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/mobile/F1/saved-views/{id}", id).with(as(finance, "F1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void platform_actor_and_missing_identity_are_refused() throws Exception {
        mvc.perform(get("/api/mobile/F1/saved-views")
                        .with(req -> {
                            req.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(
                                    AuthenticatedActorType.PLATFORM_ADMIN, 1L, "F1", "super_admin"));
                            return req;
                        }))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/mobile/F1/saved-views")).andExpect(status().isUnauthorized());
    }

    @Test
    void options_mark_restricted_fields_unavailable_for_the_caller() throws Exception {
        mvc.perform(get("/api/mobile/F1/saved-views/options").with(as(sales, "F1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.key=='customers')].allowed").value(true))
                .andExpect(jsonPath("$.data[?(@.key=='suppliers')].allowed").value(false))
                .andExpect(jsonPath("$.data[?(@.key=='customers')].fields[?(@.name=='creditLimit')].allowed")
                        .value(false))
                .andExpect(jsonPath("$.data[?(@.key=='customers')].fields[?(@.name=='name')].allowed")
                        .value(true));
    }
}
