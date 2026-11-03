package com.cretas.aims.gate;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps the reviewed production {@code @DataScope} surface explicit. */
class DataScopeServiceCoverageContractTest {

    private static final Path SERVICES = Paths.get("src/main/java/com/cretas/aims/service");
    private static final Pattern ANNOTATION = Pattern.compile("@DataScope\\s*\\(");

    private static final Map<String, String> EXPECTED_METHODS = expectedMethods();

    @Test
    void exactlyEighteenReviewedServiceMethodsAreDataScoped() throws IOException {
        Map<String, Integer> annotationCounts = new LinkedHashMap<>();

        try (var paths = Files.walk(SERVICES)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String source = stripComments(Files.readString(path, StandardCharsets.UTF_8));
                int count = countMatches(ANNOTATION, source);
                if (count > 0) {
                    annotationCounts.put(relative(path), count);
                }
            }
        }

        Set<String> expectedFiles = EXPECTED_METHODS.keySet().stream()
                .map(key -> key.substring(0, key.indexOf('#'))).collect(java.util.stream.Collectors.toSet());
        assertThat(annotationCounts)
                .as("The production @DataScope surface changed; review the new method and its role acceptance before updating this contract")
                .containsOnlyKeys(expectedFiles.toArray(String[]::new));
        Map<String, Integer> expectedCounts = new LinkedHashMap<>();
        EXPECTED_METHODS.keySet().forEach(key ->
                expectedCounts.merge(key.substring(0, key.indexOf('#')), 1, Integer::sum));
        assertThat(annotationCounts).isEqualTo(expectedCounts);
        assertThat(annotationCounts.values()).containsOnly(1, 2, 9);
        assertThat(annotationCounts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(18);

        EXPECTED_METHODS.forEach((methodKey, ignored) -> {
            try {
                String[] parts = methodKey.split("#", 2);
                String file = parts[0];
                String[] signature = parts[1].split("/", 2);
                String method = signature[0];
                String source = stripComments(Files.readString(SERVICES.resolve(file), StandardCharsets.UTF_8));
                assertThat(source)
                        .as("%s must keep @DataScope on %s", file, method)
                        .containsPattern("(?s)@DataScope\\s*\\([^)]*\\).{0,500}\\b" + method + "\\s*\\(");
                Class<?> implementation = Class.forName("com.cretas.aims.service."
                        + file.substring(0, file.length() - ".java".length()).replace('/', '.'));
                long annotatedMatches = java.util.Arrays.stream(implementation.getDeclaredMethods())
                        .filter(candidate -> candidate.getName().equals(method))
                        .filter(candidate -> signature.length == 1
                                || candidate.getParameterCount() == Integer.parseInt(signature[1]))
                        .filter(candidate -> candidate.isAnnotationPresent(com.cretas.aims.annotation.DataScope.class))
                        .count();
                assertThat(annotatedMatches).as("%s must identify exactly one annotated signature", methodKey)
                        .isEqualTo(1);
            } catch (IOException | ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static Map<String, String> expectedMethods() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("impl/CustomerServiceImpl.java#getCustomerList", "getCustomerList");
        expected.put("impl/MaterialBatchServiceImpl.java#getMaterialBatchList/2", "getMaterialBatchList");
        // Reviewed (ERP-S6-02): the filtered overload (status / material type / receipt dates) is what
        // material_batch_query calls directly, so it needs its own @DataScope rather than relying on the
        // 2-arg entry (a self-call skips the proxy). Same created_by chain: CUSTOM / empty chain -> empty page,
        // a SELF chain is applied inside the database query before paging, ALL leaves it unscoped.
        expected.put("impl/MaterialBatchServiceImpl.java#getMaterialBatchList/3", "getMaterialBatchList filtered");
        expected.put("impl/MaterialBatchServiceImpl.java#getLowStockWarnings", "getLowStockWarnings");
        expected.put("impl/MaterialBatchServiceImpl.java#getMaterialBatchesByStatus/2", "status");
        expected.put("impl/MaterialBatchServiceImpl.java#getMaterialBatchesByStatus/3", "status/warehouse");
        expected.put("impl/MaterialBatchServiceImpl.java#getMaterialBatchesByStatus/4", "status/product/warehouse");
        expected.put("impl/MaterialBatchServiceImpl.java#getWipBatches", "WIP");
        expected.put("impl/MaterialBatchServiceImpl.java#getExpiringBatches", "expiring");
        expected.put("impl/MaterialBatchServiceImpl.java#getExpiredBatches", "expired");
        expected.put("impl/QualityReturnOrderServiceImpl.java#list", "list");
        expected.put("inventory/impl/PurchaseServiceImpl.java#getPurchaseOrders", "getPurchaseOrders");
        // Reviewed (S6 2026-11-03): the status list behind purchase_order_list and the REST status endpoint. Same
        // created_by chain as getPurchaseOrders (SELF -> own, SELF_AND_BELOW / DEPT_AND_BELOW -> resolved chain,
        // empty -> own, otherwise unscoped). Before this the status path applied no DataScope.
        expected.put("inventory/impl/PurchaseServiceImpl.java#getPurchaseOrdersByStatus", "getPurchaseOrdersByStatus");
        expected.put("inventory/impl/ReturnOrderServiceImpl.java#getReturnOrders", "getReturnOrders");
        expected.put("inventory/impl/SalesServiceImpl.java#getSalesOrders", "getSalesOrders");
        // Reviewed (S6 2026-11-03): the status / order-date filtered list that order_list, order_query and the
        // REST status endpoint now share. Same created_by chain as getSalesOrders: SELF -> own, SELF_AND_BELOW /
        // DEPT_AND_BELOW -> resolved chain (empty -> own), ALL / CUSTOM / no context -> unscoped. Before this, the
        // status path (getSalesOrdersByStatus) applied no DataScope at all.
        expected.put("inventory/impl/SalesServiceImpl.java#getSalesOrdersFiltered", "getSalesOrdersFiltered");
        expected.put("listsummary/impl/ListSummaryServiceImpl.java#computeSummary", "computeSummary");
        // Reviewed (S6-09 task badge): the sales-order count mirrors SalesServiceImpl#getSalesOrders for
        // SELF / SELF_AND_BELOW / DEPT_AND_BELOW / ALL, and fails closed (UNKNOWN, never a factory-wide count)
        // when the caller has no scope context, no user id, or a scope it cannot narrow by (CUSTOM).
        expected.put("TaskBadgeService.java#countActionableSalesOrders", "countActionableSalesOrders");
        return expected;
    }

    private static int countMatches(Pattern pattern, String source) {
        int count = 0;
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String relative(Path path) {
        return SERVICES.relativize(path).toString().replace('\\', '/');
    }

    private static String stripComments(String source) {
        String noBlock = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return Pattern.compile("//.*$", Pattern.MULTILINE).matcher(noBlock).replaceAll("");
    }
}
