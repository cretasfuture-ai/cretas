package com.cretas.aims.dto.platform;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.List;

public final class PlatformFactoryMenuDTO {
    private PlatformFactoryMenuDTO() {}

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Config {
        private String factoryId;
        private String factoryName;
        private List<Module> modules;
    }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Module {
        private String moduleId;
        private String moduleName;
        private boolean visible;
        private boolean configured;
        private List<String> disabledMenuPaths;
    }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Update {
        @NotNull private Boolean visible;
        private List<String> disabledMenuPaths;
    }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ViewContext {
        private String factoryId;
        private String factoryName;
        private String factoryType;
        private String accessToken;
        private long expiresIn;
        private boolean readOnly;
    }
}
