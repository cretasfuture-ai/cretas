package p10.clock;

import p10.acceptance.CrossMonthHttpApplication;

import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Arrays;

/** Small no-network check for the fail-closed file and request context contract. */
public final class P10ClockSelfCheck {
    private P10ClockSelfCheck() {
    }

    public static void main(String[] args) {
        P10BusinessClock.initializeFromSystemProperties();
        String phase = Arrays.stream(args)
                .filter(arg -> !arg.startsWith("--"))
                .findFirst()
                .orElse(null);
        P10BusinessClock.Context context = P10BusinessClock.readContext(phase);
        if (!Files.isRegularFile(P10BusinessClock.configuredClockFile())
                || context.businessDate() == null
                || !LocalDate.parse(context.businessDate().toString()).equals(context.businessDate())) {
            throw new IllegalStateException("P10_CLOCK_SELF_CHECK_FAILED");
        }
        LocalDate inside = P10BusinessClock.withContext(context, P10BusinessClock::localDateNow);
        if (!inside.equals(context.businessDate())) {
            throw new IllegalStateException("P10_CLOCK_CONTEXT_SELF_CHECK_FAILED");
        }
        if (P10BusinessClock.hasActiveContext()) {
            throw new IllegalStateException("P10_CLOCK_CONTEXT_NOT_RESTORED");
        }
        if (Arrays.asList(args).contains("--guard-check")) {
            String[] valid = {
                    "--server.address=127.0.0.1", "--server.port=10062",
                    "--spring.datasource.url=jdbc:postgresql://127.0.0.1:55447/p10_refund_http_20261016?stringtype=unspecified",
                    "--smartbi.postgres.url=jdbc:postgresql://127.0.0.1:55447/p10_refund_http_20261016?stringtype=unspecified",
                    "--spring.jpa.hibernate.ddl-auto=none", "--spring.flyway.enabled=false",
                    "--spring.sql.init.mode=never"
            };
            CrossMonthHttpApplication.validateLaunchArguments(valid);
            expectGuardFailure(valid, "--spring.datasource.url=jdbc:postgresql://127.0.0.1:55447/cretas_db");
            expectGuardFailure(valid, "--server.port=10010");
            expectGuardFailure(java.util.Arrays.copyOf(valid, valid.length - 1));
            String[] duplicate = Arrays.copyOf(valid, valid.length + 1);
            duplicate[duplicate.length - 1] = valid[0];
            expectGuardFailure(duplicate);
        }
        System.out.println("P10_CLOCK_SELF_CHECK=PASS date=" + context.businessDate()
                + " phase=" + context.phase());
    }

    private static void expectGuardFailure(String[] valid, String replacement) {
        String[] candidate = valid.clone();
        if (replacement.startsWith("--spring.datasource.url")) {
            candidate[2] = replacement;
        } else if (replacement.startsWith("--server.port")) {
            candidate[1] = replacement;
        }
        try {
            CrossMonthHttpApplication.validateLaunchArguments(candidate);
            throw new IllegalStateException("P10_CLOCK_GUARD_NEGATIVE_NOT_REJECTED");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void expectGuardFailure(String[] candidate) {
        try {
            CrossMonthHttpApplication.validateLaunchArguments(candidate);
            throw new IllegalStateException("P10_CLOCK_GUARD_MISSING_NOT_REJECTED");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
