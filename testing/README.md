# DiscordEasyLogin tests

Two suites, both against a throwaway Paper server (26.1.2 unless you pick another) that they create, configure and
delete. Both point the plugin at a **local mock webhook**, so nothing touches a real server or
the real Discord.

| Suite | What it does |
|---|---|
| `npm run e2e` | Exercises the gate: does it work, and does it fail closed? Also upgrading from the EasyGuard name. 12 scenarios |
| `npm run adversarial` | Tries to *defeat* the gate: bypasses, stolen codes, floods, item use, races, and leaks to other plugins. 15 scenarios |

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
| reload mid-verification | A config reload keeps the code that is already sitting in Discord valid, instead of stranding the player until they time out |
| manual lockdown | `/discordeasylogin lockdown on` survives a reload, says why it is denying logins, and a typo like `lockdown of` cannot switch it off |
| broken config on reload | A bad config blocks the door instead of silently leaving it open, and fixing the config reopens it **without a restart** |
| broken config at startup | The worst case — the plugin cannot configure itself at boot, and every login is denied rather than the server running unprotected. **`/discordeasylogin lockdown off` does not change that**: it once did, and the next player got in with no code |
| recovery | A fixed config plus a restart restores normal joins: **you can always get back in** |

## What `npm run adversarial` proves

Fifteen attacks, every one of which must fail:

| Attack | Result |
|---|---|
| More joins than `code.max_per_minute` | rate-limit kick, and the webhook stops receiving posts at the cap |
| Namespaced and built-in commands while unverified (`/minecraft:me`, `/bukkit:help`, `/list`, `/msg`, `/say`, `/discordeasylogin:discordeasylogin status`) | all refused, none produce output |
| Player B submits the code issued to player A | refused, and A's code is **not** consumed |
| A code is reused after its session quit | refused; the rejoin gets a fresh one |
| Rejoining after an out-of-attempts kick | fresh code, attempts reset, old code dead — a kick launders nothing |
| The same correct code pasted twice | verifies once, one trusted session, and the second copy never reaches public chat |
| A code submitted after `code.ttl_seconds` | refused with an expiry kick |
| Reconnecting inside `code.cooldown_seconds` | rate-limit kick and no new code — one account cannot flood your channel |
| A non-whitelisted stranger connects | denied by the vanilla whitelist, and **zero** codes posted — strangers scanning your tunnel cannot make your channel ring |
| `/tp` of a held player from the console | the player stays put, and the freeze still works afterwards |
| Using items while held: throwing a snowball, drinking milk | nothing is used; milk would have cleared the blindness |
| Another player chats while someone is held | the held player never receives it |
| A player joins *after* someone was held | cannot see them, not even in the tab list |
| Discord fails slowly, the account quits and rejoins in between | the late failure for the dead session leaves the new one held; the new one's own failure refuses it |
| Another plugin listens to chat — at `MONITOR` on the modern event, at `NORMAL` on the legacy one | it sees an empty, cancelled message, never what the held player typed |

Brute force is out of scope by construction: a code is bound to the player it was issued to,
with 3 attempts at 8 characters from 31 symbols.

### Findings these tests guard

Each of these was found in a security review, reproduced by one of the attacks above against the
code of the time, and then fixed. The test stays to keep it fixed.

1. **Item use while held.** Bukkit creates an air click's `PlayerInteractEvent` already
   cancelled, so a handler with `ignoreCancelled = true` never saw it: bows, potions, ender pearls
   and milk all worked for an unverified player.
2. **A slow Discord failure freed the next login.** The failure handler asked only "is this
   account held?" — true for the *new* session after a quick reconnect — and dropped that
   session's hold. With `log_code_to_console_on_failure: false`, that was a way in. Holds now
   carry a per-session token.
3. **Legacy chat listeners saw codes.** When any plugin listens to the deprecated chat event,
   Paper fires it first, uncancelled. DiscordEasyLogin now guards that event too, but only once some
   plugin listens to it, so it never switches a server onto the legacy chat path by itself.
4. **Teleports moved held players**, because `PlayerTeleportEvent` has its own handler list.
5. **Quitting reset the per-player cooldown**, so a reconnect loop could post a code each time.
6. **Held players received public chat, and players who joined later could see them.**

`leak-probe/` is a test-only plugin, built on demand, that stands in for a chat bridge. The suite
sends a canary string to it, never a real code.

## Options

| Environment variable | Default | Purpose |
|---|---|---|
| `DISCORDEASYLOGIN_JAVA` | `$JAVA_HOME/bin/java`, then `java` | Which Java to run the server with. Must be 25+ |
| `DISCORDEASYLOGIN_TEST_PORT` | `25599` | Minecraft port. Deliberately not 25565, so a real server on the default port is left alone |
| `DISCORDEASYLOGIN_WEBHOOK_PORT` | `8099` | Mock webhook port |
| `DISCORDEASYLOGIN_PAPER_JAR` | *(downloaded)* | Use a Paper jar you already have instead of downloading |
| `DISCORDEASYLOGIN_ONLY` | *(all)* | Run only scenarios whose name contains this text, e.g. `DISCORDEASYLOGIN_ONLY=lockdown` |
| `DISCORDEASYLOGIN_EXTRA_PLUGINS` | *(none)* | A directory of plugin jars (or one jar) to install alongside DiscordEasyLogin — see below |
| `DISCORDEASYLOGIN_TEST_MC_VERSION` | `26.1.2` | Paper version to test on, e.g. `26.2`. See below |
| `DISCORDEASYLOGIN_TEST_CLIENT_VERSION` | `26.1.2` | Protocol the test clients speak. Only change it once mineflayer supports a newer one |
| `--keep` | off | Keep the scratch server after a passing run (it is always kept after a failure) |

## Testing other Paper versions

The test clients (mineflayer) only speak 26.1.2. To test on a newer Paper, set
`DISCORDEASYLOGIN_TEST_MC_VERSION`: the suites then also install
[ViaVersion](https://modrinth.com/plugin/viaversion) and
[ViaBackwards](https://modrinth.com/plugin/viabackwards) on the test server — the latest releases
for that version, downloaded from Modrinth and checked against their SHA-512 — so the old clients
can join. They are only ever installed on the throwaway server. CI runs both suites on 26.1.2 and
26.2.

**26.3 can't be tested this way yet.** With ViaVersion/ViaBackwards 5.12.2, a 26.1.2 client on a
26.3 server is kicked with `Invalid move player packet received` a second or two after joining —
with no DiscordEasyLogin installed at all. The scenarios that don't need a player to stay online
(lockdowns, broken configs, the upgrade) pass on 26.3; the rest can't run until Via or mineflayer
catches up. Until then, 26.3 isn't listed as supported.

```sh
DISCORDEASYLOGIN_TEST_MC_VERSION=26.2 npm run e2e
```

## Testing beside your real plugins

Both suites run on a clean server by default, so neither one tells you how DiscordEasyLogin behaves
next to *your* plugin set — which is where the interesting failures live. Copy your plugin jars
somewhere and point the suites at them:

```sh
DISCORDEASYLOGIN_EXTRA_PLUGINS=~/my-server-plugins npm run adversarial
```

Their configs will be defaults, so this catches event-priority and startup interactions rather
than anything config-specific. Worth doing once whenever you add a plugin that touches chat,
joins, or teleports.

## When something fails

The run exits non-zero and prints, for each failed scenario: the expectation that failed, every
chat message that player saw, the disconnect reason if there was one, and the last 30 lines of
the server log. The scratch server is left in `testing/.server/` with its full log in `logs/`.

Re-run just the failure with `DISCORDEASYLOGIN_ONLY`.

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
- **Your plugins' real configs.** `DISCORDEASYLOGIN_EXTRA_PLUGINS` installs the jars with defaults,
  which catches event-priority and startup interactions but not config-driven behaviour.
- **`discordeasylogin.bypass`** — needs op or a permissions plugin to mean anything; it is a single
  permission check in `GateService`. Note that a permissions plugin granting `*` grants it too.
- **A legacy chat listener at `LOWEST`, registered before DiscordEasyLogin's guard.** Same priority means
  registration order decides, so it would still see the text first. No realistic bridge listens
  that early; the probe checks `NORMAL`.
- **Armour stands, fishing rods, mounts and respawn blindness.** Guarded in code, but each would
  need a world fixture (a stand, a horse, a death) the suite does not build. The item-use attack
  covers the underlying bypass these share.
- **Real Discord delivery.** Deliberately: a test that posts to your channel is a test you
  stop running. `DiscordWebhookNotifierTest` covers the HTTP behaviour, including that a 5xx,
  a timeout and a dead socket all report failure — which is what fail-closed depends on.
