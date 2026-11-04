package com.cretas.aims.ai.tool.gateway;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovedToolSensitiveLoggingContractTest {

    @Test
    void approvedRestrictedToolsDoNotLogRawParametersOrUserProvidedNamesAndReasons() throws Exception {
        String userDisable = logLines(Path.of(
                "src/main/java/com/cretas/aims/ai/tool/impl/user/UserDisableTool.java"));

        assertThat(userDisable)
                .doesNotContain("参数: {}")
                .doesNotContain("username={}, userId={}")
                .doesNotContain("原因: {}")
                .doesNotContain("targetUserId, reason");
    }

    private static String logLines(Path source) throws Exception {
        return String.join("\n", Files.readAllLines(source).stream()
                .filter(line -> line.contains("log."))
                .toList());
    }
}
