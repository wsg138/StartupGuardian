# StartupGuardian

StartupGuardian protects a Paper or Leaf server when configured critical plugins are missing or disabled after a full startup. It enables the whitelist, alerts Discord staff, and can request a controlled restart. Persisted incident state prevents a permanently broken dependency from causing an endless restart loop.

## Important limitation

StartupGuardian cannot protect against **StartupGuardian itself** failing to load or enable. Use a server- or panel-level watchdog for that case.

## Installation

1. Build with Java 21 and Maven 3.9+: `mvn clean verify`.
2. Copy `target/StartupGuardian.jar` to the server's `plugins` folder.
3. Start once, then edit `plugins/StartupGuardian/config.yml`.
4. Restart the server normally.

The final check runs after `ServerLoadEvent` on full startup only. It intentionally does not automatically enforce on `/reload`.

Set `required-plugins` to plugin names reported by `/plugins` or declared in each dependency's `plugin.yml`. Matching is case-insensitive. Jar file names are not used. An empty required-plugin list is rejected instead of silently reporting a healthy server.

## Failure and restart behavior

On a failed startup, StartupGuardian:

1. records or updates `plugins/StartupGuardian/active-incident.json`;
2. enables the whitelist when configured;
3. optionally removes online players;
4. sends the initial Discord incident alert and configured reminders;
5. schedules an automatic restart only when the persisted restart limit permits it.

`restart-loop-protection.maximum-automatic-restarts` is a hard per-incident cap. It does not reset after a time window. The old `attempt-window-minutes` setting was removed because it never affected behavior and implied unsafe automatic re-arming.

A manual `/startupguardian check --enforce` always reprocesses the current failure and sends a fresh incident notification. It does not schedule a duplicate restart while one is already pending, and it cannot exceed the persisted automatic restart cap.

`/startupguardian reset confirm` clears the active incident and cancels any pending restart. It deliberately leaves the current whitelist state unchanged.

## Incident persistence and recovery

The active marker records:

- incident ID;
- first and latest detection timestamps;
- failed plugin states;
- original whitelist state;
- whether StartupGuardian enabled the whitelist;
- automatic restart count;
- whether loop protection stopped further restarts.

Writes use a temporary file and an atomic replacement where supported. Incident JSON is structurally validated when loaded. Malformed or incomplete markers are moved to a timestamped backup, and automatic restart is suppressed until reset or a healthy recovery.

Incident state is cached after the first read. Login bypass checks therefore do not repeatedly read and parse the marker from disk.

On a healthy check after an incident, the marker is cleared, a recovery webhook is sent, a pending restart is cancelled, and the previous whitelist state is restored only when StartupGuardian enabled it for that incident.

## Critical-mode access bypass

By default, no player bypasses emergency whitelist restrictions. You can allow access with:

- a UUID in `critical-mode-bypass.player-uuids`;
- the configured permission, normally `startupguardian.bypass`;
- operator status when `critical-mode-bypass.allow-ops` is enabled.

Explicit UUID and permission bypasses work for operators even when automatic operator bypass is disabled.

## Discord setup

Create a Discord channel webhook and place its URL in `discord.webhook-url`. Numeric role and user IDs may be listed under `staff-mentions`. The payload uses explicit `allowed_mentions`, so only configured IDs may be pinged. The webhook URL is never written to logs.

Repeated alerts are scheduled without blocking the HTTP executor. Their delays are absolute multiples of `delay-between-alerts-milliseconds`, so a 1.5-second delay produces alerts at approximately 0, 1.5, and 3 seconds.

Discord content is capped at the platform's 2,000-character limit. Network and HTTP failures are logged without interrupting whitelist or restart protection.

## Commands

All commands require `startupguardian.admin`; console is always permitted.

- `/startupguardian status` — show dependency, incident, restart, whitelist, and webhook status.
- `/startupguardian check` — inspect dependency health without enforcing.
- `/startupguardian check --enforce` — apply protection and send a fresh incident alert.
- `/startupguardian reload` — validate and apply configuration; invalid reloads keep the previous settings.
- `/startupguardian reset confirm` — clear the incident and cancel a pending restart.
- `/startupguardian testdiscord` — send a harmless webhook test.

## Development and validation

`mvn clean verify` runs:

- unit tests;
- Checkstyle;
- PMD and duplicate-code detection;
- SpotBugs;
- JaCoCo XML and HTML coverage generation;
- shaded plugin packaging.

GitHub Actions runs the same verification on every pull request and push to `main`, then uploads the plugin jar and JaCoCo report as workflow artifacts.

Codacy supports repository configuration files for Checkstyle, PMD, and SpotBugs. Activate the repository configuration-file option for those tools in Codacy so `checkstyle.xml`, `ruleset.xml`, and any future SpotBugs configuration are used. To publish coverage to Codacy, add a `CODACY_PROJECT_TOKEN` repository secret and a coverage-reporter step that uploads `target/site/jacoco/jacoco.xml`.
