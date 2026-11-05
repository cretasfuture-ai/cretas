package com.cretas.aims.blueprint.interview;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** web-admin 的模块下拉必须与后端目录逐字一致：后端不认的 id 只会 400。 */
class InterviewModuleCatalogParityContractTest {

    @Test
    void frontendModuleDropdownMatchesTheBackendCatalog() throws Exception {
        Path file = Path.of("../../../web-admin/src/views/platform/factory-onboarding/interviewModuleCatalog.ts");
        assertThat(file).as("前端模块目录文件").exists();
        Matcher matcher = Pattern.compile("moduleId: '([a-z_]+)'").matcher(Files.readString(file));
        List<String> frontend = new ArrayList<>();
        while (matcher.find()) frontend.add(matcher.group(1));
        List<String> backend = InterviewModuleConfigCatalog.all().stream()
                .map(InterviewModuleConfigCatalog.Entry::moduleId).toList();
        assertThat(backend).as("后端目录非空且moduleId唯一").isNotEmpty().doesNotHaveDuplicates();
        assertThat(backend).contains("quality", "trace", "system").doesNotContain("restaurant");
        assertThat(frontend).as("前端moduleId唯一且拒退休餐饮").doesNotHaveDuplicates().doesNotContain("restaurant");
        assertThat(frontend).containsExactlyElementsOf(backend);
    }
}
