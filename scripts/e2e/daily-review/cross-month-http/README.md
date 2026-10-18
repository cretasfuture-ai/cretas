# P10 cross-month HTTP clock support

This directory contains test-only support for the real Cretas application and
the private P10 PostgreSQL clone. It does not change product sources, entities,
repositories, or runtime configuration.

## JVM contract

The Java agent is `p10.clock.P10ClockAgent` and its jar manifest contains
`Premain-Class: p10.clock.P10ClockAgent`. A run must provide all of these
properties before the application starts:

```text
-javaagent:<absolute path to p10-clock-agent.jar>
-Dp10.acceptance=true
-Dp10.clock.file=<absolute path to one-line ISO date file>
```

The date file must be a regular file below the current Windows owner's
`Documents/cretas-connect-20261015/factory-data-p10` directory. It contains
exactly one nonblank line, for example `2026-10-31`. A missing property,
outside path, symlink outside the owner directory, missing file, multiple
values, or malformed date aborts the agent before Spring starts.

The private bootstrap class is `p10.acceptance.CrossMonthHttpApplication`. It
starts the ordinary `com.cretas.aims.CretasBackendApplication` together with
`p10.clock.P10ClockSpringConfiguration`; it is a full Spring Boot application
bootstrap, not a mock controller. The configuration has a no-argument constructor and registers
`P10BusinessDateFilter` only for `/api/mobile/VISION-ENTRY-A/*`. The agent and
filter are otherwise constructor-free; no product bean needs a new property.

## Request contract

Every request on the isolated factory path may send:

```text
X-P10-Clock-Phase: MARK_PAID
```

or:

```text
X-P10-Clock-Phase: REQUEST_REVERSAL
```

When the phase header is missing or blank, the filter deterministically derives
`MARK_PAID` for `2026-10-31`/`2026-11-30` and `REQUEST_REVERSAL` for
`2026-11-01`/`2026-12-01`. An explicitly supplied unknown phase remains a
failure. The date file is restricted to exactly those four dates and is
validated both at agent startup and before each request. The filter reads the
private date file before entering the application. An explicitly supplied unknown phase,
unreadable file, or invalid date returns HTTP 400 and does not invoke the
business chain. Valid requests receive
`X-P10-Business-Date: <ISO date>` and `X-P10-Clock-Isolated: true`. JWT and
authentication requests use the real JVM clock because the filter and the
agent's substitutions are restricted to the isolated factory path and exact
target classes. `LocalTime.now()` remains real; only zero-argument
`LocalDate.now()` and `LocalDateTime.now()` calls are substituted.

The accepted target classes are exactly:

```text
com.cretas.aims.service.inventory.ReturnRefundService
com.cretas.aims.service.voucher.impl.VoucherServiceImpl
com.cretas.aims.service.finance.impl.ArApServiceImpl
com.cretas.aims.service.finance.impl.AccountingPeriodServiceImpl
com.cretas.aims.entity.BaseEntity
com.cretas.aims.entity.finance.AccountingPeriod
org.springframework.data.auditing.CurrentDateTimeProvider
```

The agent prints one `P10_CLOCK_TRANSFORM` line per transformation and a
`P10_CLOCK_TRANSFORM_ERROR` line for any ByteBuddy error. It prints
`P10_CLOCK_AGENT_READY targets=7` after installing the transformations.

`CrossMonthHttpApplication` rejects startup before creating Spring when any
required guard is missing, duplicated, or has another value. It requires
`p10.acceptance=true` and exactly these arguments, protecting the original
database regardless of environment defaults:

```text
--server.address=127.0.0.1
--server.port=10062
--spring.datasource.url=jdbc:postgresql://127.0.0.1:55447/p10_refund_http_20261016?stringtype=unspecified
--smartbi.postgres.url=jdbc:postgresql://127.0.0.1:55447/p10_refund_http_20261016?stringtype=unspecified
--spring.jpa.hibernate.ddl-auto=none
--spring.flyway.enabled=false
--spring.sql.init.mode=never
```

## Date scenarios

The HTTP runner changes the private date file between requests to exercise
`2026-10-31 -> 2026-11-01` and `2026-11-30 -> 2026-12-01`. The request phase
may be omitted because the filter derives it from the allowed date; no REST
time endpoint is provided.

## Build and self-check

The helper is compiled with Zulu 21, ByteBuddy 1.14.19, Spring Boot 3.2.12,
the existing platform-entry classpath, and the application target classes.
Build output belongs outside Maven `target`, preferably
`C:\Users\Steve\Documents\cretas-connect-20261015\factory-data-p10\clock-classes`,
with the agent jar at
`C:\Users\Steve\Documents\cretas-connect-20261015\factory-data-p10\p10-clock-agent.jar`.
The helper-only self-check is:

```text
java -cp <p10-clock-agent.jar> -Dp10.acceptance=true \
  -Dp10.clock.file=<owner-bound ISO date file> p10.clock.P10ClockSelfCheck
```

The phase argument is optional in this helper, which exercises the same blank
header default as the filter. Appending `--guard-check` to the self-check arguments also checks wrong database,
wrong port, and missing guard negatives, and verifies the ThreadLocal context is
restored. It must print `P10_CLOCK_SELF_CHECK=PASS`. Running without either required
property must fail closed with a `P10_CLOCK_*` error before any HTTP listener
starts.

For an offline real-target transformation check without Spring, HTTP, or a
database, run `p10.clock.P10TargetLoadingSelfCheck` with the same agent and the
full platform-entry/application classpath plus `clock-classes`:

```text
java -javaagent:<p10-clock-agent.jar> \
  -cp <platform-entry-classpath>;<app-target-classes>;<clock-classes> \
  -Dp10.acceptance=true -Dp10.clock.file=<owner-bound ISO date file> \
  p10.clock.P10TargetLoadingSelfCheck
```

It must print
`P10_TARGET_LOAD_SELF_CHECK=PASS targets=7`; this also verifies that the
auditing provider receives the effective date and the ThreadLocal is restored.

## Full HTTP acceptance

The coordinator first creates and records a private clone named
`p10_refund_http_20261016` from the protected local sample database. The
launcher refuses to overwrite occupied ports or reuse a different database.
Set `P10_ACCEPTANCE_DIR` to the owner-bound private directory above, then run
`python runtime.py start` and `python acceptance.py NON_PRODUCTION_ONLY`.
Both commands require the existing private runtime arguments and account file;
neither credentials nor database snapshots belong in this directory.

The HTTP runner reuses `BusinessFlow` persistence and the existing refund,
independent approval, payment, voucher posting, reversal and report endpoints.
It covers a posted October refund reversed in November, and an unposted
November refund cancelled in December. It checks original-row preservation,
monthly statements, cash reports, exact bank/AR entries, repeated approval,
self-approval and factory isolation. Business mutations use HTTP only;
PostgreSQL access in the runner is read-only. The test clock changes dates only
in the private service and does not change the host clock or authentication.
