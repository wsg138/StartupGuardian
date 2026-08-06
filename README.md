# StartupGuardian

StartupGuardian protects a Paper or Leaf server when configured critical plugins are missing or disabled after a full startup. It can persist an incident, enable the whitelist, remove online players, notify Discord staff, and request a controlled restart. Persistent incident state prevents a permanently broken dependency from creating an endless restart loop.

## Important limitation

StartupGuardian cannot protect against **StartupGuardian itself** failing to load or enable. Use a server- or panel-level watchdog for that case.

## Installation

1. Build with Java 21 and Maven 3.9 or newer: `mvn clean verify`.
2. Copy `target/StartupGuardian.jar` to the server's `plugins` folder.
3. Start once, then edit `plugins/StartupGuardian/config.yml`.
4. Restart the server normally.

The final dependency check runs after `ServerLoadEvent` on a full startup. It does not automatically enforce after `/reload`.

Set `required-plugins` to plugin names reported by `/plugins` or declared in each dependency's `plugin.yml`. Matching is case-insensitive, duplicate names are removed case-insensitively, and an empty list is rejected. Jar file names are not used.

## Failure transaction and restart behavior

On a failed enforced check, StartupGuardian uses this order:

1. load the existing incident or create a new one;
2. evaluate the restart policy and update the persisted restart count;
3. save `plugins/StartupGuardian/active-incident.json` successfully;
4. enable the whitelist and optionally kick players;
5. queue Discord alerts;
6. schedule a restart when the persisted policy permits it.

Whitelist changes, player kicks, and new restart scheduling are skipped when the marker cannot be saved. A separate persistence-failure alert is queued when Discord is configured. An already pending restart is not cancelled by a later failed incident update. If protection or restart scheduling fails after persistence, the marker remains available for staff access bypasses and later recovery.

`restart-loop-protection.maximum-automatic-restarts` is a hard per-incident cap. It does not reset after a time window. A repeated `/startupguardian check --enforce` sends a new incident notification, but it does not create a duplicate pending restart or exceed the persisted cap.

The configured restart command is dispatched by the console. If it returns failure, StartupGuardian dispatches the configured fallback command. The plugin does not call `System.exit()`.

## Incident marker schema and recovery

New markers use schema version `1` and contain:

- incident ID;
- first and latest detection timestamps;
- failed plugin states;
- the previous whitelist state;
- whether StartupGuardian enabled the whitelist;
- the automatic restart count;
- whether restart-loop protection stopped further restarts.

StartupGuardian validates the JSON object, every required property, property types, timestamps, failure entries, and the schema version before deserializing it. Complete markers written by StartupGuardian `1.0.0` remain readable through the legacy schema path. Missing primitive properties are rejected rather than being accepted as Gson defaults. Unsupported future versions, malformed JSON, and incomplete markers are quarantined to a timestamped `active-incident.corrupt-*.json` file when possible.

An untrusted marker suppresses automatic restart behavior. If quarantine itself fails, the invalid marker is left in place and the in-process corruption state still suppresses automatic restarts.

On a healthy check after an incident, StartupGuardian first clears the marker. Only after that succeeds does it cancel a pending restart, restore the whitelist when StartupGuardian originally enabled it, and queue the recovery alert. A whitelist that was already enabled before the incident is not disabled.

`/startupguardian reset confirm` also clears the marker first. A successful reset then cancels a pending restart and leaves the whitelist unchanged. If marker deletion fails, reset reports failure and leaves the pending restart unchanged.

## Critical-mode access bypass

By default, no player bypasses emergency whitelist restrictions. Access can be allowed by:

- a UUID in `critical-mode-bypass.player-uuids`;
- the configured permission, normally `startupguardian.bypass`;
- operator status when `critical-mode-bypass.allow-ops` is enabled.

Explicit UUID and permission bypasses still work for operators when automatic operator bypass is disabled. Bypasses apply only while the whitelist is enabled and a trusted active incident exists.

## Discord behavior

Create a Discord channel webhook and place its URL in `discord.webhook-url`. Numeric role and user IDs may be listed under `staff-mentions`. Payloads always include explicit `allowed_mentions` arrays and an empty `parse` list, so only configured IDs may be mentioned. Content is truncated to Discord's 2,000-character limit. The webhook URL is not logged.

All HTTP delivery and rate-limit retries run outside the server thread. HTTP 429 responses may schedule one bounded retry from `Retry-After`; there is no unlimited retry loop. Non-2xx responses and network failures are logged without changing server protection state.

On shutdown, delayed reminders are cancelled. Already-started webhook delivery receives a short bounded shutdown period before remaining work is interrupted, so future reminders do not outlive the plugin while an initial alert has a limited chance to finish.

`/startupguardian testdiscord` reports one of three actual outcomes:

- test queued;
- Discord is not configured;
- the webhook client is already closed.

## Commands

All commands require `startupguardian.admin`; console is always permitted.

- `/startupguardian status` — show dependency, incident, restart, whitelist, and webhook status.
- `/startupguardian check` — inspect dependency health without enforcing.
- `/startupguardian check --enforce` — persist and apply emergency handling when unhealthy.
- `/startupguardian reload` — validate and apply configuration; invalid reloads keep the previous settings.
- `/startupguardian reset confirm` — clear the incident, then cancel a pending restart; whitelist unchanged.
- `/startupguardian testdiscord` — report whether a harmless webhook test was actually queued.

## Development and validation

Run the complete local gate with:

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

That command runs unit tests, Checkstyle, PMD, CPD, SpotBugs, JaCoCo report generation, and shaded plugin packaging.

Inspect the shaded plugin with:

```bash
jar tf target/StartupGuardian.jar | tee target/jar-contents.txt
grep -q '^plugin.yml$' target/jar-contents.txt
grep -q '^config.yml$' target/jar-contents.txt
grep -q '^dev/p2wn/startupguardian/' target/jar-contents.txt
grep -q '^dev/p2wn/startupguardian/lib/gson/' target/jar-contents.txt
! grep -Eq '^(org/bukkit|io/papermc/paper)/' target/jar-contents.txt
```

GitHub Actions runs once for pull-request commits, for pushes to `main`, and on manual dispatch. A concurrency group cancels an older run for the same pull request or branch. Third-party actions are pinned to immutable commit SHAs with readable release tags in comments. Successful runs upload the shaded plugin, its entry listing, and the JaCoCo report.

The workflow already contains the Codacy coverage-reporter step. To publish coverage, configure only the `CODACY_PROJECT_TOKEN` repository secret; do not add a second reporter step. Codacy's repository configuration-file option should use `checkstyle.xml` and `ruleset.xml` for matching local analysis behavior.
