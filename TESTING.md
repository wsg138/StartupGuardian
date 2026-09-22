# Testing StartupGuardian

StartupGuardian's handwritten regression tests live in this repository under `src/test/java/dev/p2wn/startupguardian/`. Sentinel can exercise the built plugin in a compatibility environment, but these unit/service tests remain owned by StartupGuardian and run in its normal Maven CI.

## Automated coverage

The established suite covers startup/recovery orchestration, bypass behavior, incident state and corruption ownership, restart policy, settings loading, and webhook behavior.

The test-hardening additions add direct coverage for:

- `PluginHealthTest` — trimming/validation of configured and detected names, health-state semantics, missing/disabled states, and null/blank rejection;
- `SettingsValidationTest` — defensive copies, root/nested numeric validation, command/message trimming, Discord configured semantics, and bypass/Discord collection immutability;
- `FullFeatureCoverageContractTest` — a guard tying the major deterministic feature families to concrete regression files so coverage cannot silently disappear.

## Run locally

Run the full repository quality gate:

```bash
mvn -B -ntp clean verify
```

Run only tests:

```bash
mvn -B -ntp test
```

Run one class:

```bash
mvn -B -ntp -Dtest=SettingsValidationTest test
```

Surefire reports are under `target/surefire-reports/`. Checkstyle, PMD/CPD and SpotBugs are also part of the normal `verify` path; test code is intentionally subject to the repository's static-analysis rules.

## CI and reviewing results

`.github/workflows/ci.yml` is the canonical repository CI. For every test-hardening change:

1. verify the run belongs to the current PR head;
2. confirm JUnit actually executed rather than merely compiling;
3. treat Checkstyle/PMD/CPD/SpotBugs failures as real quality gates, including failures in tests;
4. use the Surefire class/method name for focused reproduction;
5. do not suppress an analyzer, weaken an assertion, or loosen production safety behavior merely to make the branch green.

If a new test exposes a product defect, keep the regression case and fix the product in the branch/work package that owns that production path.

## Maintenance rule

When a major deterministic feature family changes, update or add real behavioral tests in the same product PR. Add the feature to `FullFeatureCoverageContractTest` only after concrete regression evidence exists. If an existing family is intentionally removed, update both its tests and the inventory together.

## Runtime boundaries

The automated suite does not by itself prove a complete production Paper/Leaf startup. Separate runtime validation remains appropriate for:

- actual plugin enable/disable ordering on the production plugin stack;
- real whitelist/player kick behavior and Bukkit permissions;
- restart/fallback command execution by the live server process;
- filesystem corruption and process-restart behavior outside the JVM;
- real Discord delivery/network conditions and external rate limits;
- post-restart recovery across an actual server lifecycle.

Use Sentinel and/or controlled staging/real-server acceptance for those boundaries. Do not mock external runtime behavior and call it equivalent production evidence.
