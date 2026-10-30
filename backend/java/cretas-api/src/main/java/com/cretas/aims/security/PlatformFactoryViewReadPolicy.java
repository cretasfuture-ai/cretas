package com.cretas.aims.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.regex.Pattern;

/** Positively reviewed read handlers only; a GET method alone does not prove read-only. */
public final class PlatformFactoryViewReadPolicy {
    public static final String REQUEST_ATTRIBUTE = PlatformFactoryViewReadPolicy.class.getName() + ".active";
    private static final List<Pattern> READ_PATHS = load();

    private PlatformFactoryViewReadPolicy() {}

    public static boolean isView(jakarta.servlet.http.HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(REQUEST_ATTRIBUTE))
                && AuthenticatedActor.current(request).map(AuthenticatedActor::isPlatformAdmin).orElse(false);
    }

    public static boolean isCurrentView() {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        return attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
                && isView(servlet.getRequest());
    }

    public static boolean allows(String requestUri, String factoryId) {
        String prefix = "/api/mobile/" + factoryId;
        if (!requestUri.startsWith(prefix + "/")) return false;
        String suffix = requestUri.substring(prefix.length());
        return READ_PATHS.stream().anyMatch(pattern -> pattern.matcher(suffix).matches());
    }

    private static List<Pattern> load() {
        try (InputStream input = PlatformFactoryViewReadPolicy.class.getResourceAsStream("/platform/factory-view-read-paths.json")) {
            if (input == null) throw new IllegalStateException("Missing platform factory view read policy");
            List<String> paths = new ObjectMapper().readValue(input, new TypeReference<>() {});
            return paths.stream().map(Pattern::compile).toList();
        } catch (IOException error) {
            throw new UncheckedIOException("Invalid platform factory view read policy", error);
        }
    }
}
