package p10.acceptance;

import com.cretas.aims.CretasBackendApplication;
import org.springframework.boot.SpringApplication;
import p10.clock.P10ClockSpringConfiguration;

/** Private launcher that boots the full application with the P10 filter. */
public final class CrossMonthHttpApplication {
    private static final String ACCEPTANCE_PROPERTY = "p10.acceptance";
    private static final String ADDRESS = "127.0.0.1";
    private static final String PORT = "10062";
    private static final String JDBC_URL =
            "jdbc:postgresql://127.0.0.1:55447/p10_refund_http_20261016?stringtype=unspecified";

    private CrossMonthHttpApplication() {
    }

    public static void main(String[] args) {
        validateLaunchArguments(args);
        SpringApplication app = new SpringApplication(CretasBackendApplication.class,
                P10ClockSpringConfiguration.class);
        app.run(args);
    }

    public static void validateLaunchArguments(String[] args) {
        if (!"true".equals(System.getProperty(ACCEPTANCE_PROPERTY))) {
            throw new IllegalStateException("P10_BOOTSTRAP_REQUIRES_ACCEPTANCE_FLAG");
        }
        requireExactly(args, "server.address", ADDRESS);
        String profile=System.getProperty("p10.profile","P10");
        if (!profile.equals("P10") && !profile.equals("P31")) throw new IllegalStateException("ACCEPTANCE_PROFILE_NOT_ALLOWED");
        boolean p31=profile.equals("P31");
        String database=p31?"jdbc:postgresql://127.0.0.1:55447/p31_finance_closeout_20261018?stringtype=unspecified":JDBC_URL;
        requireExactly(args, "server.port", p31?"10068":PORT);
        requireExactly(args, "spring.datasource.url", database);
        requireExactly(args, "smartbi.postgres.url", database);
        requireExactly(args, "spring.jpa.hibernate.ddl-auto", "none");
        requireExactly(args, "spring.flyway.enabled", "false");
        requireExactly(args, "spring.sql.init.mode", "never");
    }

    private static void requireExactly(String[] args, String key, String expected) {
        int count = 0;
        String actual = null;
        String prefix = "--" + key + "=";
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                count++;
                actual = arg.substring(prefix.length());
            }
        }
        if (count != 1 || !expected.equals(actual)) {
            throw new IllegalArgumentException("P10_BOOTSTRAP_GUARD_INVALID_" + key);
        }
    }
}
