# Enthusia SMP Deployment

`README.md` is the authoritative technical description of StartupGuardian. This file records how that framework is currently configured on Enthusia SMP so future server/wiki tooling does not have to infer production policy from the repository defaults.

## Current critical-plugin set

The latest Enthusia server snapshot requires all of the following plugins to be present and enabled at full startup:

- FastAsyncWorldEdit
- WorldGuard
- LuckPerms
- PolarLoader
- EnthusiaCurrency
- Multiverse-Core
- Tebex (`tebex` plugin name)
- CoreProtect
- Nexo
- LiteBans
- MaceGuard

StartupGuardian's own failure cannot be detected by StartupGuardian; that still requires an external panel/server watchdog as described in the main README.

## Current emergency behavior

After the full server load event, StartupGuardian waits **20 ticks** before its dependency check. If a required plugin is missing or disabled and emergency enforcement succeeds:

- the whitelist is enabled,
- ordinary online players are kicked,
- operators are not automatically kicked by the emergency-kick setting,
- a controlled restart is scheduled after **8 seconds**,
- the primary console command is `restart`,
- the fallback command is `stop`,
- the previous whitelist state is restored after healthy recovery when StartupGuardian was the component that enabled it.

The player-facing maintenance reason currently says that the server entered emergency maintenance because a critical plugin failed to load.

## Restart-loop protection

Automatic restart-loop protection is enabled and the current hard cap is **1 automatic restart per incident**.

The current configuration still contains `attempt-window-minutes: 15`, but the implementation/README documents the restart count as a hard per-incident cap rather than a rolling-window reset. Future documentation should follow the implementation rather than implying that another automatic restart becomes available after 15 minutes.

## Discord alerting

Discord incident alerting is enabled. The live webhook URL and staff IDs are operational secrets and must never be copied into public documentation.

Current behavior/configuration:

- three repeated alerts are configured,
- repeated alerts are 15 seconds apart,
- webhook username: `Startup Guardian`,
- only explicitly configured mention IDs may be pinged by the webhook implementation.

## Emergency access

Operators do **not** automatically bypass critical-mode whitelist restrictions (`allow-ops: false`). Access is instead granted by an explicit trusted UUID list and/or the `startupguardian.bypass` permission.

The actual trusted UUID list is intentionally omitted here. Use the private/live server configuration for operational access control; it is not player/wiki information.

## Public-documentation guidance

StartupGuardian is server safety infrastructure, not a normal player feature. A public wiki only needs a short statement such as:

> Enthusia uses an automated startup safety system that places the server into emergency maintenance if critical infrastructure fails to load correctly, preventing players from entering a partially broken server.

Do not publish the exact critical-plugin list, staff mention IDs, trusted UUIDs, webhook details, or restart commands unless there is a specific operational reason to do so.