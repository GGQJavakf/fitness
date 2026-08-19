# Implementation plan

- [x] 1. Establish failing acceptance tests and update superseded product rules
  - Add the 45-minute matrix, AI-underfill, route-parity, lifecycle-bypass, and release-profile tests.
  - Update PRD/detailed design and mark the former count-agnostic clauses superseded.
  - _Requirements: R1, R2, R5, R8_

- [x] 2. Implement authoritative session composition
  - Add versioned duration bands and complete duration estimation.
  - Compose both AI and fallback plans to four or five exercises for 45-minute sessions.
  - Return explicit insufficiency diagnostics when safe candidates are unavailable.
  - _Requirements: R1, R2_

- [x] 3. Harden AI activation, data projection, and error classification
  - Keep online `PLAN_GENERATION` fail-closed for the current release and use deterministic
    onboarding generation.
  - Add approval/consent gates and an allowlisted prompt DTO.
  - Preserve configuration/contract failures and limit ordinary fallback to transient failures.
  - Verify provenance in persistence and UI.
  - _Requirements: R2, R3_

- [x] 4. Repair workout lifecycle, local concurrency, and queue convergence
  - Enforce the recovery warning again at the server start boundary and require an atomic,
    short-lived confirmation token before creating the session.
  - Restrict generic status writes and add completion/outbox coverage.
  - Implement start-or-resume, revision-safe serialization, conflict/rejection convergence, and
    corrupt-draft recovery.
  - Implement append-only set void semantics.
  - _Requirements: R4-R6_

- [x] 5. Repair API truth and durable idempotency
  - Add OpenAPI/Spring bidirectional route parity and response-contract checks.
  - Normalize header/binding/internal error envelopes.
  - Replay progression decisions by idempotency key and narrow unsupported exercise actions.
  - Add truthful training-day fields without breaking compatibility.
  - _Requirements: R5_

- [x] 6. Bound persistence, operational state, and caches
  - Replace history N+1 reads with projection/batch queries and paginate recommendations.
  - Bound and age candidate caches with a controllable clock.
  - Move privacy/auth operational state to shared atomic persistence.
  - Purge all user-scoped local data on revocation/logout/deletion/account switch.
  - _Requirements: R6, R7_

- [x] 7. Replace false-positive tests and create one release-quality entry
  - Replace critical source-string UI checks with rendered interaction tests.
  - Add consumer/runtime contract fixtures, SQL-count budgets, coverage thresholds, and packaged
    profile smoke checks.
  - Add one repository `verify` entry and CI definition.
  - _Requirement: R8_

- [x] 8. Complete full regression, independent review, and completion audit
  - Run backend verify, frontend API drift/typecheck/tests/build, release preflight, architecture
    scans, migration verification, and diff hygiene.
  - Run independent backend, frontend, security/privacy, and completion-evidence reviews; fix all
    material findings and rerun affected checks.
  - Local code, contract, build, coverage, and independent reviews are complete. By product
    decision, patch/minor differences inside MySQL 8 do not block validation: MySQL 8.0.44 is the
    pinned reference environment and the external-version gate accepts the MySQL 8 family.
  - Frontend packaged dependencies, the complete Taro build toolchain, and backend Maven runtime
    dependencies now have fail-closed audit gates. Both npm audits reject findings from low
    severity upward and currently report zero known vulnerabilities; the current OSV scan reports
    no known match across the 49 Maven runtime coordinates. Webpack is fixed at 5.109.2 together
    with webpackbar 7.0.0; a standard empty-cache `npm ci` (without `--force`), the complete
    frontend suite, and the real WeChat production build pass despite Taro 4.2.1's stale peer
    metadata.
  - The latest clean local regression has 410 backend Surefire tests with zero failures/errors and
    12 database-environment skips, plus one packaged-smoke Failsafe test that is explicitly skipped
    without an eligible database. JaCoCo reports 76.19% line and 59.90% branch coverage. The
    frontend release suite has 62 files / 415 tests, API drift, typecheck, both dependency audits,
    and the production WeChat build green. OpenAPI/Spring parity is 17/17, and the 40-case
    HOME/GYM × frequency × goal matrix produces only ready 45-minute days with four or five
    exercises. These green local checks did not replace the zero-skip database gate described
    below.
  - An explicitly approved disposable MySQL 8.0.44 schema completed V011→V024 migration/upgrade
    tests 24/24 and packaged staging smoke 1/1 with zero skips over encrypted TLS. Independent
    review then rejected that endpoint as release evidence because its auto-generated certificate
    has no hostname/IP identity and fails `VERIFY_IDENTITY`; the harness now refuses that downgrade.
    The MySQL version gate is satisfied. The harness now also supports an explicit pinned-CA path:
    remote access and pinned-CA mode must both be opted into, the DBA/platform-provided CA must live
    in a local PKCS12/JKS trust store, system-trust fallback is disabled, secrets are supplied only
    through environment/secret files, and both direct migration and packaged-jar connections assert
    a non-empty `Ssl_cipher`. Remote URLs are restricted to one explicit IP literal and port, and
    packaged smoke must consume a same-run, one-time marker bound to the JDBC target, catalog,
    MySQL server UUID, and exact V001-V024 Flyway history. JDBC topology and usernames no longer
    travel through Maven system properties or test reports. A network-captured leaf certificate was
    correctly rejected as an invalid trust anchor. The verification owner subsequently made an
    explicit product decision to accept encrypted-only direct access for this disposable database.
    A separate, mutually exclusive `allow-unverified-tls` mode was therefore added without weakening
    the default verified modes. On MySQL 8.0.44, the final clean run completed 410 Surefire tests and
    one packaged-smoke Failsafe test with zero failures, errors, or skips; V001-V024, legacy V011
    upgrade, Flyway validation/idempotency, JDBC behavior, staging profile, health/DataSource/Flyway,
    authentication, consumer HTTP contract, and non-empty `Ssl_cipher` all passed. JaCoCo reported
    88.17% line and 64.23% branch coverage, and a post-run scan found no JDBC host, password value,
    or password-file name in Surefire/Failsafe reports or the persisted smoke log. This accepted mode
    proves compatibility and encryption but intentionally does not claim remote server identity.
  - _Requirements: R1-R8_

- [x] 9. Enforce one server-authoritative active workout per user
  - Serialize start decisions across client keys and instances, preserve pristine create replay,
    and return effective server facts for in-progress or paused recovery.
  - Return a typed terminal replay result that clears the durable key without auto-starting a new
    workout; rebuild local state only from runtime-validated recovery responses.
  - Cover different-key races, same-key response loss, paused activation, voided-set filtering,
    terminal replay, corrupt start intent, and general-warmup restoration.
  - _Requirements: R4, R5_
