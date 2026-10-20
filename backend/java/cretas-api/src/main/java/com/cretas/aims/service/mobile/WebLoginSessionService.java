package com.cretas.aims.service.mobile;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.utils.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/** Durable positive allowlist: elapsed time alone never invalidates a Web login. */
@Service
@RequiredArgsConstructor
public class WebLoginSessionService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final PlatformAdminRepository admins;
    private final FactoryRepository factories;
    private final JwtUtil jwt;
    private final SecureRandom random = new SecureRandom();

    private record Principal(String factory, String password, String accessToken) {}
    private record Stored(String actor, Long id, String factory, String password) {}

    @Transactional
    public String issue(String authenticatedAccessToken) {
        if (!jwt.validateToken(authenticatedAccessToken)) throw unauthorized();
        String actor = jwt.isExplicitPlatformToken(authenticatedAccessToken) ? "PLATFORM_ADMIN" : "FACTORY_USER";
        Long id = jwt.getUserIdFromToken(authenticatedAccessToken);
        Principal principal = principal(actor, id);
        if (!Objects.equals(jwt.getFactoryIdFromToken(authenticatedAccessToken), principal.factory())) throw unauthorized();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO web_login_sessions(session_hash,actor_type,user_id,factory_id,password_fingerprint) VALUES (?,?,?,?,?)",
                hash(secret), actor, id, principal.factory(), hash(principal.password()));
        return secret;
    }

    /** Rehydrate current identity before the unchanged JWT and permission chain. */
    @Transactional(readOnly = true)
    public String resolve(String secret) {
        if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}")) throw unauthorized();
        String sessionHash = hash(secret);
        return jwt.bindWebSession(checkedPrincipal(sessionHash).accessToken(), sessionHash);
    }

    /** Access JWTs stay bounded and cannot outlive explicit Web-session revocation. */
    @Transactional(readOnly = true)
    public void assertBoundAccessToken(String token) {
        if (!jwt.validateToken(token) || jwt.getExpirationDateFromToken(token) == null) throw unauthorized();
        Principal current = checkedPrincipal(jwt.getWebSessionHash(token));
        String expected = current.accessToken();
        if (!Objects.equals(jwt.getUserIdFromToken(token), jwt.getUserIdFromToken(expected))
                || !Objects.equals(jwt.getFactoryIdFromToken(token), jwt.getFactoryIdFromToken(expected))
                || !Objects.equals(jwt.getRoleFromToken(token), jwt.getRoleFromToken(expected))
                || jwt.isExplicitPlatformToken(token) != jwt.isExplicitPlatformToken(expected)) throw unauthorized();
    }

    @Transactional
    public void revokeBoundAccessToken(String token) {
        if (jwt.getWebSessionHash(token) == null) return; // Legacy mobile authentication is unchanged.
        assertBoundAccessToken(token);
        jdbc.update("UPDATE web_login_sessions SET revoked=TRUE WHERE session_hash=?", jwt.getWebSessionHash(token));
    }

    private Principal checkedPrincipal(String sessionHash) {
        if (sessionHash == null || !sessionHash.matches("[a-f0-9]{64}")) throw unauthorized();
        var rows = jdbc.query("SELECT actor_type,user_id,factory_id,password_fingerprint FROM web_login_sessions WHERE session_hash=? AND revoked=FALSE",
                (rs, row) -> new Stored(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)), sessionHash);
        if (rows.size() != 1) throw unauthorized();
        Stored stored = rows.get(0);
        Principal current = principal(stored.actor(), stored.id());
        if (!Objects.equals(stored.factory(), current.factory())
                || !MessageDigest.isEqual(stored.password().getBytes(StandardCharsets.US_ASCII),
                        hash(current.password()).getBytes(StandardCharsets.US_ASCII))) throw unauthorized();
        return current;
    }

    @Transactional
    public void revoke(String secret) {
        if (secret != null && secret.matches("[A-Za-z0-9_-]{43}"))
            jdbc.update("UPDATE web_login_sessions SET revoked=TRUE WHERE session_hash=?", hash(secret));
    }

    private Principal principal(String actor, Long id) {
        if (id == null) throw unauthorized();
        if ("PLATFORM_ADMIN".equals(actor)) {
            var admin = admins.findById(id).orElseThrow(WebLoginSessionService::unauthorized);
            if (!admin.isActive() || admin.getPlatformRole() == null || admin.getPasswordHash() == null) throw unauthorized();
            return new Principal("PLATFORM", admin.getPasswordHash(),
                    jwt.generatePlatformToken(id, admin.getUsername(), admin.getPlatformRole().name().toLowerCase(java.util.Locale.ROOT)));
        }
        if (!"FACTORY_USER".equals(actor)) throw unauthorized();
        var user = users.findById(id).orElseThrow(WebLoginSessionService::unauthorized);
        if (!Boolean.TRUE.equals(user.getIsActive()) || user.getFactoryId() == null || user.getPasswordHash() == null) throw unauthorized();
        var factory = factories.findById(user.getFactoryId()).orElseThrow(WebLoginSessionService::unauthorized);
        if (!Boolean.TRUE.equals(factory.getIsActive())) throw unauthorized();
        String role = user.getRoleCode() == null ? "viewer" : user.getRoleCode();
        return new Principal(user.getFactoryId(), user.getPasswordHash(),
                jwt.generateToken(id, user.getFactoryId(), user.getUsername(), role));
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static BusinessException unauthorized() {
        return new BusinessException(401, "登录已撤销或账号信息已变更，请重新登录");
    }
}
