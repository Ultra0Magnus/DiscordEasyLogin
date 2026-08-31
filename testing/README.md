# EasyGuard tests

Two suites, both against a throwaway Paper 26.1.2 server that they create, configure and
delete. Both point the plugin at a **local mock webhook**, so nothing touches a real server or
the real Discord.

| Suite | What it does |
|---|---|
| `npm run e2e` | Exercises the gate: does it work, and does it fail closed? 9 scenarios |
| `npm run adversarial` | Tries to *defeat* the gate: bypasses, stolen codes, floods, and interactions with other plugins. 11 scenarios |

## Run them

```sh
cd testing
npm install
npm run e2e
npm run adversarial
```

First run downloads Paper (~50 MB, checksum-verified) into `testing/.cache/`. The whole suite
takes about four minutes, most of it server startups and the 30-second verification timeout.

Requires **Java 25** (Paper 26.1+ will not run on anything older) and a built plugin — run
`JAVA_HOME=/path/to/jdk-25 mvn -B package` in the repo root first.

## What `npm run e2e` proves

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

## What `npm run adversarial` proves

Nine attacks that must fail:

| Attack | Result |
|---|---|
| Namespaced and built-in commands while unverified (`/minecraft:me`, `/bukkit:help`, `/list`, `/msg`, `/say`, `/easyguard:easyguard status`) | all refused, none produce output |
| Player B submits the code issued to player A | refused, and A's code is **not** consumed |
| A code is reused after its session quit | refused; the rejoin gets a fresh one |
| Rejoining after an out-of-attempts kick | fresh code, attempts reset, old code dead — a kick launders nothing |
| The same correct code pasted twice | verifies once, records exactly one trusted session |
| A code submitted after `code.ttl_seconds` | refused with an expiry kick |
| More joins than `code.max_per_minute` | rate-limit kick, and the webhook stops receiving posts at the cap |
| A non-whitelisted stranger connects | denied by the vanilla whitelist, and **zero** codes posted — strangers scanning your tunnel cannot make your channel ring |
| Brute force | out of scope by construction: codes are bound to the submitting player, 3 attempts, 8 characters from 31 symbols |

Plus two **characterised** scenarios, which assert what the plugin actually does rather than
what it ought to do, so a behaviour change fails loudly instead of passing silently.

### Findings this suite records

These are printed as `NOTE:` lines at the end of every run. None is a way past the gate; all
three are worth knowing:

1. **`code.cooldown_seconds` does not survive a reconnect.** `GateService.onQuit` calls
   `VerificationManager.cancel`, which does `lastIssued.remove(player)` — quitting clears your
   own cooldown. One whitelisted account can loop reconnects and post up to
   `code.max_per_minute` codes per minute (default 20) instead of the 6/min a 10-second
   cooldown implies. Makes your Discord channel noisy; does not let anyone in.
2. **A teleport moves a held player.** A `PlayerMoveEvent` handler does not receive
   `PlayerTeleportEvent` in Bukkit, so the freeze does not cover teleports. Another plugin
   (Multiverse spawn handling) or an admin `/tp` can relocate an invisible, unverified player,
   and their own next step yanks them back to the original anchor. They still cannot act or
   verify.
3. **A `MONITOR`-priority chat listener in another plugin can read what an unverified player
   types.** EasyGuard cancels the message at `LOWEST` so it never reaches public chat, but
   cancelling does not hide the event from later listeners. A chat-bridge plugin (DiscordSRV
   and friends) that ignores the cancelled flag could forward a verification code. Verified
   with `leak-probe/`, a test-only plugin built on demand — the suite sends a canary string,
   never a real code.

## Options

| Environment variable | Default | Purpose |
|---|---|---|
| `EASYGUARD_JAVA` | `$JAVA_HOME/bin/java`, then `java` | Which Java to run the server with. Must be 25+ |
| `EASYGUARD_TEST_PORT` | `25599` | Minecraft port. Deliberately not 25565, so a real server on the default port is left alone |
| `EASYGUARD_WEBHOOK_PORT` | `8099` | Mock webhook port |
| `EASYGUARD_PAPER_JAR` | *(downloaded)* | Use a Paper jar you already have instead of downloading |
| `EASYGUARD_ONLY` | *(all)* | Run only scenarios whose name contains this text, e.g. `EASYGUARD_ONLY=lockdown` |
| `EASYGUARD_EXTRA_PLUGINS` | *(none)* | A directory of plugin jars (or one jar) to install alongside EasyGuard — see below |
| `--keep` | off | Keep the scratch server after a passing run (it is always kept after a failure) |

## Testing beside your real plugins

Both suites run on a clean server by default, so neither one tells you how EasyGuard behaves
next to *your* plugin set — which is where the interesting failures live. Copy your plugin jars
somewhere and point the suites at them:

```sh
EASYGUARD_EXTRA_PLUGINS=~/my-server-plugins npm run adversarial
```

Their configs will be defaults, so this catches event-priority and startup interactions rather
than anything config-specific. Worth doing once whenever you add a plugin that touches chat,
joins, or teleports.

## When something fails

The run exits non-zero and prints, for each failed scenario: the expectation that failed, every
chat message that player saw, the disconnect reason if there was one, and the last 30 lines of
the server log. The scratch server is left in `testing/.server/` with its full log in `logs/`.

Re-run just the failure with `EASYGUARD_ONLY`.

## Layout

```
e2e.js            does the gate work, and does it fail closed?
adversarial.js    can the gate be beaten?
scenarios.js      the e2e scenarios, one function each
lib/harness.js    shared: prerequisites, plugin config, scenario loop, result table
lib/server.js     Paper lifecycle: download, config, spawn, console, log tail, guaranteed kill
lib/client.js     scripted player: chat buffer, predicate waits, movement probe
lib/webhook.js    mock Discord webhook; stopping it is how the outage is simulated
leak-probe/       test-only plugin that watches chat like a Discord bridge would. Never deploy it
```

Both suites wait on **conditions**, not fixed sleeps — a chat line, a webhook payload, a log
line, a disconnect — so they are neither slower than necessary on a fast machine nor flaky on a
slow one. The few places that do use a fixed delay are proving a *negative* (a player did not
move, no second code was sent, a command produced no output), where there is no event to await;
they are commented as such.

## Not covered here

- **Simple Voice Chat while unverified.** mineflayer does not implement the voice protocol, so
  this cannot be automated — and it matters: an unverified player is *invisible*
  (`hide_from_others`) but voice runs on its own channel, so they may be able to talk to and
  listen in on nearby players. Check it by hand: join with a second account, do not verify, and
  have someone on voice see whether they can hear you and you them.
- **Your plugins' real configs.** `EASYGUARD_EXTRA_PLUGINS` installs the jars with defaults,
  which catches event-priority and startup interactions but not config-driven behaviour.
- **`easyguard.bypass`** — needs op or a permissions plugin to mean anything; it is a single
  permission check in `GateService`.
- **Real Discord delivery.** Deliberately: a test that posts to your channel is a test you
  stop running. `DiscordWebhookNotifierTest` covers the HTTP behaviour, including that a 5xx,
  a timeout and a dead socket all report failure — which is what fail-closed depends on.
