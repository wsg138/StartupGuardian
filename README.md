# StartupGuardian

StartupGuardian protects a Paper or Leaf server when configured critical plugins are missing or disabled after a full startup. It enables the whitelist, alerts Discord staff, and can request one controlled restart. If the same incident remains after that restart, it keeps the server in maintenance mode rather than looping restarts.

## Important limitation

StartupGuardian cannot protect against **StartupGuardian itself** failing to load or enable. A server/panel-level watchdog is needed for that case.

## Installation

1. Build with Java 21: `mvn clean package`.
2. Copy `target/StartupGuardian.jar` to the server's `plugins` folder.
3. Start once, then edit `plugins/StartupGuardian/config.yml`.
4. Restart the server normally. The plugin runs its final check after `ServerLoadEvent` on full startup only; it intentionally does not enforce on `/reload`.

Set `required-plugins` to exact plugin names (matching is case-insensitive). The default 20-tick grace period gives other plugins time to finish enabling.

## Discord setup

Create a channel webhook in Discord and place its URL in `discord.webhook-url`. Put numeric Discord role and/or user IDs under `staff-mentions`. Webhook requests use explicit `allowed_mentions`, so only those listed users and roles may be pinged. The URL is never written to plugin logs. `/startupguardian testdiscord` tests delivery without changing the server.

## Restart setup

The default command is `restart`, dispatched by the console after the configured delay. Your host or restart plugin must support that command. If dispatching it fails, StartupGuardian dispatches `stop`; configure the hosting panel or watchdog to bring the process back online after a stop. The plugin never calls `System.exit()`.

## Incident and recovery behavior

The persistent marker is `plugins/StartupGuardian/active-incident.json`. It records the incident ID, timestamps, failed plugin states, original whitelist state, and restart count. Writes use a temporary file followed by an atomic move where supported. A malformed marker is retained as a timestamped backup rather than discarded.

With the defaults, the first failed startup schedules one restart. A second failed startup keeps the whitelist enabled and sends alerts but does not restart. Restarting is not automatically re-armed merely after the 15-minute attempt window; it requires a healthy startup or `/startupguardian reset confirm`. This avoids a continuing cycle from a permanently broken plugin.

On a healthy startup after an incident, the marker is cleared, a single recovery webhook is sent, and the prior whitelist state is restored only when this plugin enabled it for the incident. A whitelist that was already enabled is never disabled by recovery.

## Critical-mode access bypass

By default, no player bypasses emergency whitelist restrictions. Add trusted player UUIDs to `critical-mode-bypass.player-uuids`, or grant the configured `startupguardian.bypass` permission through a permissions plugin. These bypasses work only while an active StartupGuardian incident exists and whitelist mode is enabled. Operators do not bypass automatically; set `critical-mode-bypass.allow-ops: true` only if every operator should be permitted to join in critical mode.

## Commands

All commands require `startupguardian.admin`; console is always permitted.

- `/startupguardian status` — health, incident, whitelist, restart, and webhook status.
- `/startupguardian check` — report health only.
- `/startupguardian check --enforce` — apply emergency handling when unhealthy.
- `/startupguardian reload` — reload and validate configuration without clearing an incident.
- `/startupguardian reset confirm` — clear the marker and re-arm restarts; does not change whitelist state.
- `/startupguardian testdiscord` — send a harmless webhook test.

## Example output and payload

```text
[StartupGuardian] All 2 required plugins are enabled.
========== STARTUPGUARDIAN CRITICAL FAILURE ==========
Required plugin WorldGuard: disabled (detected as WorldGuard)
Incident: 8b2... | restart attempts: 1 | restart scheduled: true
Whitelist enabled: true | marker: plugins/StartupGuardian/active-incident.json
```

```json
{
  "content": "<@&123456> **CRITICAL SERVER STARTUP FAILURE**\nIncident: `8b2...`\nFailures: `WorldGuard` (disabled)\nAutomatic restart attempts: 1\nWhitelist enabled: true\nRestart scheduled: true",
  "allowed_mentions": {"roles": ["123456"], "users": [], "parse": []}
}
```

## Validation

The project includes unit tests for configuration bounds, incident serialization/corruption handling, persisted restart counts, and loop stopping. Bukkit operations run on the server thread; webhook HTTP uses a dedicated asynchronous executor with connection and request timeouts. Webhook errors are logged without interrupting whitelist or restart protection.
