package com.cretas.aims.service.mobile;

import com.cretas.aims.entity.*;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.entity.enums.Status;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.*;
import com.cretas.aims.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebLoginSessionContractTest {
    protected JdbcTemplate jdbc;
    protected UserRepository users;
    protected PlatformAdminRepository admins;
    protected FactoryRepository factories;
    protected JwtUtil jwt;
    protected WebLoginSessionService service;
    protected User user;
    protected Factory factory;
    protected PlatformAdmin admin;

    protected JdbcTemplate createJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:websession" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    @BeforeEach void setup() {
        jdbc = createJdbc();
        new ResourceDatabasePopulator(new ClassPathResource("db/flyway/V20261103_71__persistent_web_login_sessions.sql")).execute(jdbc.getDataSource());
        users = mock(UserRepository.class); admins = mock(PlatformAdminRepository.class); factories = mock(FactoryRepository.class);
        jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", "web-session-test-only-signing-key-not-production-123456789");
        ReflectionTestUtils.setField(jwt, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(jwt, "refreshExpiration", 3_600_000L);
        user = new User(); user.setId(7L); user.setFactoryId("F006"); user.setUsername("test_buyer");
        user.setRoleCode("procurement_manager"); user.setIsActive(true); user.setPasswordHash("fixture-password-hash");
        factory = new Factory(); factory.setId("F006"); factory.setIsActive(true);
        admin = new PlatformAdmin(); admin.setId(7L); admin.setUsername("test_platform"); admin.setStatus(Status.active);
        admin.setPlatformRole(PlatformRole.super_admin); admin.setPasswordHash("platform-fixture-hash");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(admins.findById(7L)).thenReturn(Optional.of(admin));
        when(factories.findById("F006")).thenReturn(Optional.of(factory));
        service = newService();
    }

    protected WebLoginSessionService newService() { return new WebLoginSessionService(jdbc, users, admins, factories, jwt); }
    protected String login() { return service.issue(jwt.generateToken(7L, "F006", user.getUsername(), user.getRoleCode())); }

    @Test void oldCreationTimeAndNewServiceInstanceDoNotExpireLogin() {
        String secret = login();
        jdbc.update("UPDATE web_login_sessions SET created_at=TIMESTAMP '2000-01-01 00:00:00'");
        String token = newService().resolve(secret);
        assertThat(jwt.validateToken(token)).isTrue();
        assertThat(jwt.getFactoryIdFromToken(token)).isEqualTo("F006");
        assertThat(jwt.getExpirationDateFromToken(token)).isNotNull();
        assertThat(jwt.isExplicitPlatformToken(token)).isFalse();
    }

    @Test void storesOnlyHashAndUsesUnpredictableDistinctCredentials() {
        String a = login(), b = login();
        assertThat(a).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(b);
        assertThat(jdbc.queryForList("SELECT session_hash FROM web_login_sessions", String.class))
                .containsExactlyInAnyOrder(WebLoginSessionService.hash(a), WebLoginSessionService.hash(b)).doesNotContain(a, b);
        assertThat(jdbc.queryForList("SELECT password_fingerprint FROM web_login_sessions", String.class))
                .doesNotContain(user.getPasswordHash());
    }

    @Test void revokeSurvivesNewServiceInstanceAndDoesNotRevokeOtherBrowser() {
        String a = login(), b = login(); service.revoke(a);
        assertThatThrownBy(() -> newService().resolve(a)).isInstanceOf(BusinessException.class);
        assertThat(jwt.validateToken(newService().resolve(b))).isTrue();
    }

    @Test void disabledUserIsRejected() {
        String secret = login(); user.setIsActive(false);
        assertThatThrownBy(() -> service.resolve(secret)).isInstanceOf(BusinessException.class);
    }

    @Test void deletedUserIsRejected() {
        String secret = login(); when(users.findById(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve(secret)).isInstanceOf(BusinessException.class);
    }

    @Test void closedFactoryIsRejected() {
        String secret = login(); factory.setIsActive(false);
        assertThatThrownBy(() -> service.resolve(secret)).isInstanceOf(BusinessException.class);
    }

    @Test void passwordChangeInvalidatesExistingSession() {
        String secret = login(); user.setPasswordHash("changed-password-hash");
        assertThatThrownBy(() -> service.resolve(secret)).isInstanceOf(BusinessException.class);
    }

    @Test void factoryMoveDoesNotCarryOldBrowserIntoNewTenant() {
        String secret = login(); user.setFactoryId("F007");
        Factory other = new Factory(); other.setId("F007"); other.setIsActive(true);
        when(factories.findById("F007")).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.resolve(secret)).isInstanceOf(BusinessException.class);
    }

    @Test void roleIsRehydratedInsteadOfKeepingOldPrivileges() {
        String secret = login(); user.setRoleCode("viewer");
        assertThat(jwt.getRoleFromToken(service.resolve(secret))).isEqualTo("viewer");
    }

    @Test void platformAndFactoryIdsCannotCollide() {
        String secret = service.issue(jwt.generatePlatformToken(7L, admin.getUsername(), "super_admin"));
        clearInvocations(users);
        String access = service.resolve(secret);
        assertThat(jwt.isExplicitPlatformToken(access)).isTrue();
        assertThat(jwt.getFactoryIdFromToken(access)).isEqualTo("PLATFORM");
        verifyNoInteractions(users);
        admin.setStatus(Status.inactive);
        assertThatThrownBy(() -> service.resolve(secret)).isInstanceOf(BusinessException.class);
    }

    @Test void platformPasswordChangeInvalidatesSession() {
        String secret = service.issue(jwt.generatePlatformToken(7L, admin.getUsername(), "super_admin"));
        admin.setPasswordHash("new-platform-hash");
        assertThatThrownBy(() -> service.resolve(secret)).isInstanceOf(BusinessException.class);
    }

    @Test void unknownOrMalformedOrNullCredentialsFailClosed() {
        assertThatThrownBy(() -> service.resolve("a".repeat(43))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.resolve("' OR TRUE --")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.resolve(null)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_login_sessions", Long.class)).isZero();
    }

    @Test void expiredOrForeignLoginCannotMintPersistentSession() {
        String wrongTenant = jwt.generateToken(7L, "F007", user.getUsername(), user.getRoleCode());
        assertThatThrownBy(() -> service.issue(wrongTenant)).isInstanceOf(BusinessException.class);
        ReflectionTestUtils.setField(jwt, "expiration", -60_000L);
        String expired = jwt.generateToken(7L, "F006", user.getUsername(), user.getRoleCode());
        assertThatThrownBy(() -> service.issue(expired)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_login_sessions", Long.class)).isZero();
    }
}
