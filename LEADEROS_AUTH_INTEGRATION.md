# LeaderOS-Auth Compatibility Guide (Private Wiki)

This document explains, in detail, how this plugin integrates with **LeaderOS-Auth** so that core logic runs **only after successful authentication**.

It is written as a reusable implementation guide for other plugin developers.

## 1. Goal

When a player joins, do **not** execute sensitive logic immediately (e.g., alt-account linking, account/IP persistence, punish triggers, identity-based checks).

Instead:

1. Detect whether LeaderOS-Auth is installed and enabled.
2. If installed, wait until the player is authenticated by LeaderOS-Auth.
3. Run the plugin’s main join logic only after auth is confirmed.

This prevents false data pollution from unauthenticated players (password guesses, account probing, etc.).

## 2. Important Clarification: We Do Not Listen to a Custom Auth Event

In this implementation, we do **not** subscribe to a dedicated `AuthSuccessEvent`.

Why:

- We avoid hard compile-time dependency on LeaderOS-Auth APIs/events.
- We keep compatibility resilient even if API/event classes move or change.
- The integration works as long as LeaderOS-Auth exposes a stable runtime auth state check.

So the strategy is:

- Hook into `PlayerJoinEvent`.
- Poll LeaderOS auth state until it becomes authenticated.

## 3. High-Level Architecture

### 3.1 Soft Dependency

In `plugin.yml`, add:

```yml
softdepend: [LeaderOS-Auth]
```

This ensures better load order without making LeaderOS-Auth mandatory.

### 3.2 Join Gate

At join:

- If LeaderOS-Auth is not available: run normal logic immediately.
- If available and player is not authenticated: start a repeating check task.
- When authenticated: cancel the task and run main logic once.

### 3.3 Duplicate Protection

Use a `Set<UUID>` to make sure main logic executes only once per session, even if multiple paths try to trigger it.

### 3.4 Cleanup on Quit

On `PlayerQuitEvent`, cancel pending polling task(s) and clear tracking sets/maps for that UUID.

## 4. Step-by-Step Flow

## Step 1: Receive Join Event

`onPlayerJoin(PlayerJoinEvent event)` is the entry point.

Pseudo-flow:

```java
Player player = event.getPlayer();

if (shouldWaitForLeaderosAuth(player)) {
    scheduleAuthenticatedProcessing(player);
    return;
}

processPlayer(player);
```

Meaning:

- If auth is still pending, defer all sensitive work.
- Otherwise execute immediately.

## Step 2: Decide Whether Waiting Is Required

`shouldWaitForLeaderosAuth(player)` does two checks:

1. Is `LeaderOS-Auth` plugin present and enabled?
2. Is the player currently unauthenticated according to LeaderOS-Auth?

If both are true, return `true` and delay processing.

## Step 3: Read LeaderOS Auth State via Reflection

Instead of importing LeaderOS classes directly, use reflection:

```java
Class<?> clazz = Class.forName("net.leaderos.auth.bukkit.Bukkit");
Object instance = clazz.getMethod("getInstance").invoke(null);
Object result = clazz.getMethod("isAuthenticated", Player.class).invoke(instance, player);
boolean authenticated = (result instanceof Boolean) && (Boolean) result;
```

Behavior:

- `true` -> player is authenticated.
- `false` -> player still needs login/register/TFA completion.

Fallback behavior on reflection failure:

- Log once.
- Return `true` (fail-open to avoid permanently blocking logic if integration breaks).

You can change to fail-closed if your security model requires strict auth confirmation.

## Step 4: Poll Until Auth Is Complete

If not authenticated at join, schedule a repeating Bukkit task:

- Initial delay: `10L` (~0.5s)
- Period: `20L` (~1s)

Each tick:

1. Resolve player by UUID.
2. If offline/null: cancel and cleanup.
3. If authenticated: cancel task, run `processPlayer(player)`.

## Step 5: Execute Main Logic Exactly Once

`processPlayer(player)` begins with a dedup check:

```java
if (!processedPlayers.add(playerId)) {
    return;
}
```

This ensures:

- Join path + polling path cannot execute main logic twice.
- Re-entrant calls do not duplicate DB writes/notifications.

## Step 6: Cleanup on Quit

In `onPlayerQuit`:

1. Remove UUID from `processedPlayers`.
2. Cancel and remove UUID’s polling task from `authWaitTasks`.

This avoids orphan tasks, memory growth, and stale session states.

## 5. Data Structures Used

- `Map<UUID, BukkitTask> authWaitTasks`
  - Tracks active polling task per player.
  - Prevents duplicate timers and enables cancellation.

- `Set<UUID> processedPlayers`
  - Guards “run once per join session” behavior.

- `boolean authReflectionFailureLogged`
  - Prevents console spam if reflection fails repeatedly.

## 6. Why Polling Works Well Here

Pros:

- No compile-time coupling.
- No mandatory API/event package from the auth plugin.
- Easy to retrofit into existing plugins with minimal architecture changes.

Cons:

- Slight delay granularity (depends on poll interval).
- A tiny ongoing scheduler cost while waiting for auth.

For most lobby/auth workflows, `1s` polling is acceptable and low overhead.

## 7. Recommended Integration Template for Other Plugins

If you want to make another plugin LeaderOS-Auth compatible, apply this sequence:

1. Add `softdepend: [LeaderOS-Auth]`.
2. Wrap sensitive join logic behind an auth gate.
3. Check auth status through LeaderOS runtime API (reflection if you want loose coupling).
4. If unauthenticated, queue periodic checks.
5. On first authenticated state, run core logic once.
6. Add quit cleanup for tasks and state.
7. Add dedup guard to avoid double execution.

## 8. Source Anchors in This Project

Implementation points:

- `src/main/resources/plugin.yml`
  - `softdepend: [LeaderOS-Auth]`

- `src/main/java/com/bobcat00/altdetector/Listeners.java`
  - `onPlayerJoin(...)`
  - `shouldWaitForLeaderosAuth(...)`
  - `scheduleAuthenticatedProcessing(...)`
  - `isLeaderosAuthenticated(...)`
  - `processPlayer(...)`
  - `onPlayerQuit(...)`

## 9. Optional Event-Driven Alternative

If LeaderOS-Auth exposes a stable, documented public event such as `AuthSuccessEvent`, you can replace polling with direct event handling:

1. Listen to auth success event.
2. Run guarded main logic in that handler.
3. Keep dedup + quit cleanup anyway.

Event-driven is cleaner when API stability is guaranteed. Polling is safer when compatibility and loose coupling are priority.

## 10. Private File Note

This file lives in `.local/` and is ignored by git (`.gitignore` contains `.local/`), so it is not intended to be committed to GitHub.
