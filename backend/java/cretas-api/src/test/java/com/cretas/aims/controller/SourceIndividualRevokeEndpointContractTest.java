package com.cretas.aims.controller;

import com.cretas.aims.dto.source.CreateSourceIndividualRequest;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.source.SourceIndividualDetailService;
import com.cretas.aims.service.source.SourceIndividualService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #1844 复审 M-D: DELETE / POST revoke 这两个<b>HTTP 入口</b>的契约。
 *
 * <p>守的是「入口接在 revoke 上」: 原因走请求体并在 controller 层被 @Valid 校验(缺/空/超长 400),
 * 合法时撤销人取自 token 的用户、撤销人与时间真落库(真 JPA 读回列值)。
 * 哪天有人把 DELETE 改回直接软删, 这里的落库断言会红。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class SourceIndividualRevokeEndpointContractTest {

    private static final String FACTORY = "F-SI-HTTP";
    private static final String BASE = "/api/mobile/" + FACTORY + "/source-individuals/";

    @Autowired TestEntityManager em;
    @Autowired SourceIndividualRepository repository;
    @Autowired MaterialBatchRepository materialBatchRepository;
    private SourceIndividualService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = new SourceIndividualService(repository);
        ReflectionTestUtils.setField(service, "materialBatchRepository", materialBatchRepository);
        MobileService mobileService = mock(MobileService.class);
        when(mobileService.getUserFromToken(anyString())).thenReturn(UserDTO.builder().id(42L).build());
        SourceIndividualController controller = new SourceIndividualController(
                service, mock(SourceIndividualDetailService.class), mobileService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String newIndividual(String code) {
        String id = service.create(FACTORY, CreateSourceIndividualRequest.builder().individualCode(code).build())
                .getId();
        em.flush();
        return id;
    }

    private static MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer test-token").contentType(MediaType.APPLICATION_JSON);
    }

    /** 直接读库列 —— 不经实体(@Where 会把已撤销行过滤掉)。 */
    private Object[] revokeColumns(String id) {
        em.flush();
        em.clear();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.getEntityManager().createNativeQuery(
                "SELECT revoke_reason, revoked_by, revoked_at, deleted_at FROM source_individuals WHERE id = ?1")
                .setParameter(1, id).getResultList();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    @DisplayName("DELETE 不带请求体 / 空原因 / 超 255 字 → 400, 档案未被撤销")
    void deleteWithoutValidReasonIs400() throws Exception {
        String id = newIndividual("H-1");

        mvc.perform(withAuth(delete(BASE + id))).andExpect(status().isBadRequest());
        mvc.perform(withAuth(delete(BASE + id)).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(withAuth(delete(BASE + id)).content("{\"reason\":\"   \"}")).andExpect(status().isBadRequest());
        mvc.perform(withAuth(delete(BASE + id)).content("{\"reason\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());

        Object[] cols = revokeColumns(id);
        assertThat(cols[0]).isNull();
        assertThat(cols[1]).isNull();
        assertThat(cols[3]).as("deleted_at").isNull();
    }

    @Test
    @DisplayName("DELETE 带原因 → 200, revoke_reason / revoked_by(=token 用户) / revoked_at / deleted_at 落库")
    void deleteWithReasonPersistsRevokedByAndAt() throws Exception {
        String id = newIndividual("H-2");

        mvc.perform(withAuth(delete(BASE + id)).content("{\"reason\":\"录入错误\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.revokeReason").value("录入错误"))
                .andExpect(jsonPath("$.data.revokedBy").value("42"));

        Object[] cols = revokeColumns(id);
        assertThat(cols[0]).isEqualTo("录入错误");
        assertThat(cols[1]).isEqualTo("42");
        assertThat(cols[2]).as("revoked_at").isNotNull();
        assertThat(cols[3]).as("deleted_at").isNotNull();
    }

    @Test
    @DisplayName("POST /{id}/revoke 同一契约: 缺原因 400; 带原因落库撤销人")
    void postRevokeSameContract() throws Exception {
        String id = newIndividual("H-3");

        mvc.perform(withAuth(post(BASE + id + "/revoke"))).andExpect(status().isBadRequest());
        assertThat(revokeColumns(id)[1]).isNull();

        mvc.perform(withAuth(post(BASE + id + "/revoke")).content("{\"reason\":\"重复建档\"}"))
                .andExpect(status().isOk());
        Object[] cols = revokeColumns(id);
        assertThat(cols[0]).isEqualTo("重复建档");
        assertThat(cols[1]).isEqualTo("42");
        assertThat(cols[2]).isNotNull();
    }
}
