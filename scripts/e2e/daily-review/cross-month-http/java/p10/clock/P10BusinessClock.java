package p10.clock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Request-scoped business date used only by the P10 acceptance agent.
 *
 * This class deliberately lives outside com.cretas so the agent cannot
 * substitute its own clock reads and recurse.  A request without a context
 * always uses the JVM clock.
 */
public final class P10BusinessClock {
    public static final String ACCEPTANCE_PROPERTY = "p10.acceptance";
    public static final String CLOCK_FILE_PROPERTY = "p10.clock.file";
    public static final String PHASE_HEADER = "X-P10-Clock-Phase";
    public static final String DATE_HEADER = "X-P10-Business-Date";
    public static final String ISOLATION_HEADER = "X-P10-Clock-Isolated";
    public static final String PAID_PHASE = "MARK_PAID";
    public static final String REVERSAL_PHASE = "REQUEST_REVERSAL";
    private static final Set<LocalDate> ALLOWED_DATES = Set.of(
            LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 1));
    private static final boolean P31 = "P31".equals(System.getProperty("p10.profile"));
    private static final String FACTORY_URI_PREFIX = P31 ? "/api/mobile/FOOD_3101_001/" : "/api/mobile/VISION-ENTRY-A/";
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static volatile Path clockFile;
    private static volatile boolean acceptanceEnabled;

    private P10BusinessClock() {
    }

    public static synchronized void initializeFromSystemProperties() {
        String acceptance = System.getProperty(ACCEPTANCE_PROPERTY);
        if (!"true".equals(acceptance)) {
            throw new IllegalStateException("P10_CLOCK_REQUIRES_ACCEPTANCE_FLAG");
        }
        String profile=System.getProperty("p10.profile", "P10");
        if (!profile.equals("P10") && !profile.equals("P31")) throw new IllegalStateException("ACCEPTANCE_PROFILE_NOT_ALLOWED");
        String configured = System.getProperty(CLOCK_FILE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("P10_CLOCK_FILE_REQUIRED");
        }
        Path candidate = validateOwnerBoundPath(configured);
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("P10_CLOCK_FILE_NOT_FOUND");
        }
        readDate(candidate);
        clockFile = candidate;
        acceptanceEnabled = true;
    }

    public static boolean isAcceptanceEnabled() {
        return acceptanceEnabled;
    }

    public static LocalDate localDateNow() {
        Context context = CURRENT.get();
        return context == null ? LocalDate.now() : context.businessDate();
    }

    public static LocalDateTime localDateTimeNow() {
        Context context = CURRENT.get();
        return context == null
                ? LocalDateTime.now()
                : LocalDateTime.of(context.businessDate(), LocalTime.now());
    }

    public static Context readContext(String phase) {
        if (!acceptanceEnabled) {
            throw new IllegalStateException("P10_CLOCK_NOT_ACTIVE");
        }
        LocalDate date = readDate(clockFile);
        String normalizedPhase = normalizePhase(phase, date);
        return new Context(date, normalizedPhase);
    }

    public static Context enter(Context context) {
        Objects.requireNonNull(context, "context");
        Context previous = CURRENT.get();
        CURRENT.set(context);
        return previous;
    }

    public static void restore(Context previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public static <T> T withContext(Context context, Supplier<T> action) {
        Context previous = enter(context);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    public static boolean appliesToFactoryUri(String uri) {
        return uri != null && uri.startsWith(FACTORY_URI_PREFIX);
    }

    public static Path configuredClockFile() {
        return clockFile;
    }

    public static boolean hasActiveContext() {
        return CURRENT.get() != null;
    }

    private static String normalizePhase(String phase, LocalDate date) {
        if (phase == null || phase.isBlank()) {
            return switch (date.toString()) {
                case "2026-10-31", "2026-11-30" -> PAID_PHASE;
                case "2026-11-01", "2026-12-01" -> REVERSAL_PHASE;
                case "2026-10-18" -> {
                    if(!P31) throw new IllegalArgumentException("P10_CLOCK_DATE_NOT_ALLOWED");
                    yield PAID_PHASE;
                }
                default -> throw new IllegalArgumentException("P10_CLOCK_DATE_NOT_ALLOWED");
            };
        }
        String normalized = phase.trim().toUpperCase(Locale.ROOT);
        if (!PAID_PHASE.equals(normalized) && !REVERSAL_PHASE.equals(normalized)) {
            throw new IllegalArgumentException("P10_CLOCK_PHASE_INVALID");
        }
        return normalized;
    }

    private static LocalDate readDate(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String value = lines.stream().map(String::trim).filter(line -> !line.isEmpty()).reduce((a, b) -> {
                throw new IllegalArgumentException("P10_CLOCK_FILE_MULTIPLE_VALUES");
            }).orElseThrow(() -> new IllegalArgumentException("P10_CLOCK_DATE_REQUIRED"));
            try {
                LocalDate date = LocalDate.parse(value);
                if (!ALLOWED_DATES.contains(date) && !(P31 && date.equals(LocalDate.of(2026,10,18)))) {
                    throw new IllegalArgumentException("P10_CLOCK_DATE_NOT_ALLOWED");
                }
                return date;
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("P10_CLOCK_DATE_INVALID", ex);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("P10_CLOCK_FILE_UNREADABLE", ex);
        }
    }

    private static Path validateOwnerBoundPath(String configured) {
        Path ownerRoot = Path.of(System.getProperty("user.home"), "Documents",
                "cretas-connect-20261015", P31 ? "factory-data-p31" : "factory-data-p10").toAbsolutePath().normalize();
        Path raw = Path.of(configured);
        if (!raw.isAbsolute()) {
            throw new IllegalStateException("P10_CLOCK_FILE_MUST_BE_ABSOLUTE");
        }
        Path candidate = raw.toAbsolutePath().normalize();
        if (!candidate.startsWith(ownerRoot)) {
            throw new IllegalStateException("P10_CLOCK_FILE_OUTSIDE_OWNER_ROOT");
        }
        try {
            Path realRoot = ownerRoot.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)) {
                throw new IllegalStateException("P10_CLOCK_FILE_SYMLINK_OUTSIDE_OWNER_ROOT");
            }
            return realCandidate;
        } catch (IOException ex) {
            throw new IllegalStateException("P10_CLOCK_FILE_PATH_UNRESOLVED", ex);
        }
    }

    public record Context(LocalDate businessDate, String phase) {
        public Context {
            Objects.requireNonNull(businessDate, "businessDate");
            Objects.requireNonNull(phase, "phase");
        }
    }
}
