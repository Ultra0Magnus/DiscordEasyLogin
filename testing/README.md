# EasyGuard end-to-end tests

Boots a throwaway Paper 26.1.2 server, installs the plugin you just built, points it at a
**local mock webhook**, and drives real Minecraft clients through every path the gate has —
including the ones that lock the server down — then cleans up after itself.

Nothing here touches a real server or the real Discord.

## Run it

```sh
cd testing
npm install
npm run e2e
```

First run downloads Paper (~50 MB, checksum-verified) into `testing/.cache/`. The whole suite
takes about four minutes, most of it server startups and the 30-second verification timeout.

Requires **Java 25** (Paper 26.1+ will not run on anything older) and a built plugin — run
`JAVA_HOME=/path/to/jdk-25 mvn -B package` in the repo root first.

## What it proves

| Scenario | What it proves |
|---|---|
| happy path | A player gets a code, cannot move until they answer, is released when they do, and only then is announced to the server |
| lower-case code | Codes are case-insensitive, so pasting from Discord works however it arrives |
| wrong codes | Attempts count down, a wrong code is **never broadcast to public chat**, other commands are refused, and the third wrong code kicks |
| trusted rejoin | A verified player reconnecting inside the trust window gets no second code — a lag-out will not spam your Discord channel |
| verification timeout | An unanswered join cannot occupy the server indefinitely |
| Discord unreachable | The player is **held rather than waved through**, the code is written to the server console, and that console code still verifies. This is the escape hatch you would actually use during a Discord outage |
| broken config on reload | A bad config blocks the door instead of silently leaving it open, and fixing the config reopens it **without a restart** |
| broken config at startup | The worst case — the plugin cannot configure itself at boot, and every login is denied rather than the server running unprotected |
| recovery | A fixed config plus a restart restores normal joins: **you can always get back in** |

## Options

| Environment variable | Default | Purpose |
|---|---|---|
| `EASYGUARD_JAVA` | `$JAVA_HOME/bin/java`, then `java` | Which Java to run the server with. Must be 25+ |
| `EASYGUARD_TEST_PORT` | `25599` | Minecraft port. Deliberately not 25565, so a real server on the default port is left alone |
| `EASYGUARD_WEBHOOK_PORT` | `8099` | Mock webhook port |
| `EASYGUARD_PAPER_JAR` | *(downloaded)* | Use a Paper jar you already have instead of downloading |
| `EASYGUARD_ONLY` | *(all)* | Run only scenarios whose name contains this text, e.g. `EASYGUARD_ONLY=lockdown` |
| `--keep` | off | Keep `testing/.server/` after a passing run (it is always kept after a failure) |

## When something fails

The run exits non-zero and prints, for each failed scenario: the expectation that failed, every
chat message that player saw, the disconnect reason if there was one, and the last 30 lines of
the server log. The scratch server is left in `testing/.server/` with its full log in `logs/`.

Re-run just the failure with `EASYGUARD_ONLY`.

## Layout

```
e2e.js            prerequisites, server setup, scenario loop, result table
scenarios.js      one function per scenario
lib/server.js     Paper lifecycle: download, config, spawn, console, log tail, guaranteed kill
lib/client.js     scripted player: chat buffer, predicate waits, movement probe
lib/webhook.js    mock Discord webhook; stopping it is how the outage is simulated
```

The suite waits on **conditions**, not fixed sleeps — a chat line, a webhook payload, a log
line, a disconnect — so it is neither slower than necessary on a fast machine nor flaky on a
slow one. The three places that do use a fixed delay are proving a *negative* (a player did not
move, no second code was sent, a message was not broadcast), where there is no event to await;
they are commented as such.

## Not covered here

- **Rate limiting** (`code.cooldown_seconds`, `code.max_per_minute`) — driving 20+ joins a
  minute is slow and noisy for logic that `VerificationManagerTest` already covers against the
  clock. The suite turns rate limiting off so it cannot interfere.
- **`easyguard.bypass`** — needs op or a permissions plugin to mean anything; it is a single
  permission check in `GateService`.
- **Real Discord delivery.** Deliberately: a test that posts to your channel is a test you
  stop running. `DiscordWebhookNotifierTest` covers the HTTP behaviour, including that a 5xx,
  a timeout and a dead socket all report failure — which is what fail-closed depends on.
