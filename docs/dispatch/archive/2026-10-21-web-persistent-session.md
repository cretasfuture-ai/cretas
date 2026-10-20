# WEB-PERSISTENT-SESSION-20261021

Owner: side/01a1545a-f3dd-7433-96cd-4c943ab8109b. Base: 1b9f712ecf275fe7f784e58174f90e8e1b9c68bf.
User explicitly requested removing session timeout and merge/deploy on 2026-10-21.

Scope: new WebLoginSessionService and its contract/actual-PostgreSQL tests;
V20261103_71 web_login_sessions migration; CookieAuthHelper, MobileController,
WebLoginSessionFilter persistent-session wiring plus associated tests;
JwtUtil bounded Web-session claim and JwtAuthInterceptor's durable bound-access
revocation validation (no competing scope found in live main ACTIVE);
MobileAuthServiceImpl rejects bound Web access JWTs in the legacy refresh body,
without changing legacy mobile token issuance;
FactorySettingsController/SecuritySettings DTO and Web system settings removal;
Web login retires the unused rememberMe checkbox for an accurate persistence caption;
own dispatch/archive. No change to S1 services, gateways, QA CSV/browser/phone,
App token TTL, legacy JWT signing/expiry checks or existing role/tenant permission policy.

Design: opaque random Web cookie, SHA-256-only persisted credential, no server
fixed/idle expiry, explicit revoke, live principal/factory/password/role checks.
Browser cookie retention has browser-imposed limits and is renewed on access;
cleared cookies and private browsing cannot preserve authentication forever.
Normal mobile/demo authentication remains bounded. Existing Web sessions need a
new normal login to opt into the new persistent session. Do not bulk-revoke or
rewrite others' existing login sessions.

Acceptance: expiry-independent resolution, explicit logout/revoke, factory/user/
platform disable, password and identity changes, random/absent/revoked cookies,
credential hashing and browser cookie attributes, controller/interceptor wiring,
real PostgreSQL persistence across service instances, migration/FreshDB,
relevant final tests/typecheck/required CI, ordinary exact-head PR/manual merge,
official exact-main serial backend-first release and runtime readback.

Source prepared for PR review; source scope is released when this PR merges.
Current actual gates, source hashes and release disposition are retained in the
PR and private evidence, not inferred from this source checkpoint. No merge or
deployment is claimed by this document. Sole owner stays responsible through
validation, official release and own-session acceptance; no other QA ownership
is transferred.

Implementation: opaque 256-bit credential, hash-only positive allowlist, live
factory/platform/user/password rehydration, short-lived server-internal JWT
adaptation before existing interceptors/controllers. Original legacy tokens are
eligible for logout revocation only if actor namespace/id/factory match the
rehydrated principal. No-cookie mobile and demo login issuance remain unchanged.

Private logs/receipts: C:/Users/Steve/Documents/cretas-claude-handoff-20261021/
persistent-web-*. Initial target validation preceded the final mixed-header
identity guard; final source requires its own current-head checks. Typecheck
and existing Web auth-failure regression evidence are separately recorded.

The production profile's legacy cookie.secure=false was independently found.
New persistent cookies therefore force Secure=true without changing legacy
App/access/refresh cookie configuration. The actual Web login controller test
first failed under the old flag (1 failure, 0 errors); HTTPS-only enforcement
requires final rebuilt artifacts and new-head CI. Initial cf7 candidate build
74/74 and local 39/39 (13 actual PG) remain historical, not final-build proof.

Compatibility closeout includes normal validate/refresh endpoints and explicit
real-to-demo switching. All Web-issued access JWTs are bounded and bound to the
durable session hash, including initial JSON/cookie credentials; Web login does
not expose its legacy refresh credential. Cookie-less bound JWT access must
still pass durable revocation/live identity checks, cannot borrow another actor
or browser, and cannot mint refresh authority. Cookie and bearer-only logout
both revoke the durable browser session. Legacy mobile/demo JWT generation is
unchanged. Any new final source supersedes earlier candidate build receipts.

Sealed local target run: 59 PASS/0skip/fail/error, including 20 cases on actual
disposable PG17; same real service, JWT and interceptor test methods exercise
durable access revocation, actor collision, public voice authentication and
legacy-refresh-body escape refusal. The temporary cluster is task-owned only.
Final official package/CI/release receipts must still bind the final commit;
the Web caption-only change does not change the validated Java source bytes.
