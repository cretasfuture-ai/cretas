import org.flywaydb.core.Flyway;

/** Tiny dependency-only runner used by platform-assembly-migrations.ps1. */
public final class PlatformAssemblyFlywayRunner {
    public static void main(String[] args) {
        if (args.length != 5) throw new IllegalArgumentException("url user password locations baselineVersion");
        Flyway flyway = Flyway.configure()
                .dataSource(args[0], args[1], args[2])
                .locations(args[3])
                .baselineOnMigrate(true)
                .baselineVersion(args[4])
                .baselineDescription("cretas-prod-schema-baseline")
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load();
        flyway.baseline();
        flyway.migrate();
    }
}
