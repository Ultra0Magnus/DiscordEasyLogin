# DiscordEasyLogin

A second factor for your Minecraft server. When someone joins, they are frozen in place and a
single-use code is posted to a Discord channel you control. They paste that code in chat to play.
No code, no game.

Built for **Paper 26.1.2 and 26.2** (requires **Java 25**). Formerly called EasyGuard —
see [Upgrading from EasyGuard](#upgrading-from-easyguard).

## Why

A whitelist proves an *account name* is allowed. It does not prove the person behind it is you or
your friends — a compromised, shared, or spoofed account walks straight in, and a public tunnel
address (playit.gg and friends) means anyone can knock. DiscordEasyLogin adds a check on something an
attacker almost certainly cannot reach: your private Discord channel.

Keep the whitelist on. DiscordEasyLogin is the second layer, not a replacement.

## How it looks

1. Player connects. They spawn but cannot move, break anything, use their inventory, or chat.
2. A message lands in your Discord channel:

   ```
   **Join request**
   Player: `Steve`
   UUID: `f84c6a79-...`
   Code: **75M8RPHK**
   Expires in 5 minute(s).
   ```

3. They paste `75M8RPHK` into chat (or run `/verify 75M8RPHK`). They are released and the normal
   "Steve joined the game" message appears.
4. For the next 24 hours that player can reconnect without a new code, so a lag-out does not spam
   your channel. After that, they verify again.

Three wrong codes, or 120 seconds of silence, and they are kicked.

## Setup

### 1. Create the Discord webhook

In Discord: **your channel → Edit Channel → Integrations → Webhooks → New Webhook → Copy Webhook
URL**.

Lock that channel down (`View Channel` for you and trusted members only). Anyone who can read the
channel can read every code — see [Limits](#limits).

### 2. Install

Drop `DiscordEasyLogin-0.1.0.jar` into `plugins/` and start the server once. It will refuse to run until a
webhook URL is set (that is deliberate — see [Fail-closed](#fail-closed)).

### 3. Point it at your webhook

Either set an environment variable before starting the server (preferred — keeps the secret out of
config backups):

```sh
export DISCORDEASYLOGIN_WEBHOOK="https://discord.com/api/webhooks/…"
```

…or edit `plugins/DiscordEasyLogin/config.yml`:

```yaml
discord:
  webhook_url: "https://discord.com/api/webhooks/…"
```

Then `/discordeasylogin reload` (or restart). `/discordeasylogin status` shows what it is doing.

## Fail-closed

"Impossible to connect without a code" only holds if the gate cannot quietly stop gating, so:

| Situation | Behaviour |
|---|---|
| Discord unreachable | Player stays frozen. The code is written to the **server console**, so you can still get yourself in during an outage. Set `fallback.log_code_to_console_on_failure: false` to kick instead. |
| Bad config / plugin fails to start | The server enters **lockdown**: every login is denied with an explanatory kick screen. A broken DiscordEasyLogin blocks the door rather than vanishing and leaving the server open. `/discordeasylogin lockdown off` cannot lift it — only a config that loads can. |
| Unexpected error while checking a joining player | The player is kicked with "verification unavailable" and the error is logged. An exception never lets someone in unverified. |
| Discord answers slowly, then fails | Only the session that asked is affected. A late failure can never act on a newer login by the same account. |
| Webhook URL typo pointing elsewhere | Rejected at load. Only `https://discord.com/api/webhooks/…` is accepted unless you explicitly set `discord.allow_non_discord_host: true`. |
| Join flood | Per-player cooldown (10s, kept across reconnects) plus a server-wide ceiling (20 codes/minute) protect your channel. Codes are only issued *after* the vanilla whitelist and ban checks, so strangers never reach Discord. |
| Webhook URL malformed | The error says what is wrong and where, but never repeats the URL: it contains the webhook's secret token, and logs get shared. |
| Plugin disabled while someone is unverified | Unverified players are kicked rather than silently released. |
| `enabled: false` | The gate is off and anyone whitelisted can join with no code. Logged loudly as a warning at every startup. |

## Configuration

Full documented defaults live in [`src/main/resources/config.yml`](src/main/resources/config.yml).
The values worth knowing:

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch for the gate. |
| `discord.webhook_url` | *empty* | Overridden by `$DISCORDEASYLOGIN_WEBHOOK` (the old `$EASYGUARD_DISCORD_WEBHOOK` still works). |
| `discord.ping_user_ids` | *empty* | Discord user IDs to ping on every join request, so a login attempt on a compromised account reaches your phone. The only mentions that can ever ping — `@everyone`, `@here` and roles never do. |
| `code.length` | `8` | Drawn from `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (no `O/0/I/1/L` lookalikes). Minimum 6. |
| `code.ttl_seconds` | `300` | How long a code stays valid. |
| `code.max_attempts` | `3` | Wrong guesses before a kick. |
| `verification.timeout_seconds` | `120` | How long a player may sit unverified. |
| `verification.apply_blindness` | `true` | Makes it harder for an unverified player to look around. Not a guarantee — see [Limits](#limits). |
| `verification.hide_from_others` | `true` | Unverified players are invisible and absent from the tab list. |
| `trust.duration_hours` | `24` | `0` requires a code on every single join. |

All player-facing text is [MiniMessage](https://docs.advntr.dev/minimessage/format.html) and
editable under `messages:`.

## Commands and permissions

| Command | Who | Purpose |
|---|---|---|
| `/verify <code>` | any player | Submit a code. Pasting the code into chat works too. |
| `/discordeasylogin status` | `discordeasylogin.admin` | Current state: lockdown (and why), pending codes, trusted sessions. |
| `/discordeasylogin reload` | `discordeasylogin.admin` | Re-read config. A rejected config locks the server down. Codes already sent and a manual lockdown both survive it. |
| `/discordeasylogin code <player>` | **console only** | Print a waiting player's live code. The documented escape hatch for a Discord outage. |
| `/discordeasylogin trust clear <player\|all>` | `discordeasylogin.admin` | Force re-verification. |
| `/discordeasylogin lockdown <on\|off>` | `discordeasylogin.admin` | Deny all logins / resume. Survives `reload`; does not survive a restart. Anything but exactly `on` or `off` is refused. |

| Permission | Default | Notes |
|---|---|---|
| `discordeasylogin.admin` | op | Administration commands. |
| `discordeasylogin.bypass` | **nobody** | Skips verification entirely. Granting this to yourself defeats the gate — the 24h trust window is the convenience mechanism instead. |

`/dlogin` is a short alias for `/discordeasylogin`.

## Upgrading from EasyGuard

This plugin was called EasyGuard until 0.1.0. To upgrade:

1. Put `DiscordEasyLogin-0.1.0.jar` in `plugins/` and **remove the old `EasyGuard-*.jar`**. If both
   are installed, players get asked for two codes, and the new plugin warns about it at startup.
2. Start the server. On the first start, `plugins/EasyGuard/config.yml` and your trusted players
   (`sessions.properties`) are **copied** to `plugins/DiscordEasyLogin/`. The old folder is left
   alone so you can go back; delete it once everything works.

What changed, apart from the name:

| Before | Now |
|---|---|
| `/easyguard`, `/eg` | `/discordeasylogin`, `/dlogin` |
| `easyguard.admin`, `easyguard.bypass` | `discordeasylogin.admin`, `discordeasylogin.bypass` |
| `EASYGUARD_DISCORD_WEBHOOK` | `DISCORDEASYLOGIN_WEBHOOK` — the old variable still works |
| IP address in every Discord message | Off by default for new installs; opt in by adding `<ip>` to `discord_template` |

A copied config keeps its own message texts, so it still says "EasyGuard" in-game and still sends
the IP. Delete the `messages:` section from `config.yml` to get the new defaults.

## Limits

Worth being clear about what this does and does not buy you:

- **The code authenticates channel access, not a person.** Anyone who can read the Discord channel
  can use any code in it. Lock the channel's permissions down. (A per-player Discord mapping with
  DMs would need a bot; this build deliberately uses a webhook so there is no token to leak.)
- **Anyone with filesystem or console access to the host can remove the jar.** The gate protects the
  front door, not the server box.
- **Unverified players are technically online.** They occupy a player slot while frozen, blinded and
  hidden. The verification timeout bounds how long.
- **Being in the world leaks some information.** A held player cannot act, chat, read chat, use
  items, move or be seen — but the server still sends them the chunks around where they logged
  out. Blindness hides that from a normal client, not from a modified one. Fully closing this
  needs players to verify before they enter the world at all, which is a larger redesign.
- **Voice chat is not covered.** Simple Voice Chat and similar plugins carry voice on their own
  channel. Whether a held player can talk or listen depends on that plugin; test it with a second
  account before relying on it.
- **Other plugins can still see chat at `LOWEST` on the legacy chat event.** DiscordEasyLogin blanks an
  unverified player's message before every chat priority a bridge realistically uses, on both the
  modern and the legacy chat event. A plugin listening to the deprecated event at `LOWEST` itself
  and registered before DiscordEasyLogin would still run first.
- **Trusted sessions are keyed by UUID, not IP.** Behind a tunnel like playit.gg the server usually
  sees the tunnel's address rather than the player's, so IP binding would be meaningless or would
  lock everyone out.
- **Folia is not supported.** The plugin uses the standard Bukkit scheduler.

## Building

```sh
JAVA_HOME=/path/to/jdk-25 mvn package   # -> target/DiscordEasyLogin-0.1.0.jar
```

## Testing

Because this plugin fails *closed*, the failure that matters is "nobody can log in" — so test
it against a server you do not care about before you point it at one you do.

```sh
cd testing && npm install && npm run e2e
```

That boots a throwaway Paper 26.1.2 server, installs the jar you just built, points it at a
local mock webhook (never the real Discord), and drives real Minecraft clients through twelve
scenarios — including the ones that lock the server down — then cleans up. About four minutes.
Set `DISCORDEASYLOGIN_TEST_MC_VERSION=26.2` to test on 26.2; CI runs every supported version on
every push.

It verifies, against a live server rather than by assertion:

- a code reaches the webhook on join, and the player is frozen until they answer it
- a lower-case paste of the code works
- wrong codes count down, are never broadcast to public chat, and kick on the third
- other commands are refused while unverified
- a verified player reconnecting inside the trust window gets no second code
- an unanswered join is kicked on timeout
- **Discord unreachable → the player is held, the code goes to the server console, and that
  console code still verifies** — the escape hatch you would use during an outage
- **a broken config denies every login** rather than leaving the server unprotected, whether it
  breaks on reload or at startup — and `/discordeasylogin lockdown off` cannot lift it
- a reload mid-verification keeps the code that is already in Discord valid
- a manual lockdown survives a reload, and a typo cannot switch it off
- **a fixed config lets you back in** — by reload, without a restart, or by restart

A second suite, `npm run adversarial`, tries to *defeat* the gate rather than exercise it, with
fifteen attacks: namespaced command bypasses, using another player's code, reusing a dead one,
rejoining after a kick, double-pastes, expired codes, webhook floods, reconnect loops, strangers
off the whitelist, teleporting a held player, using items while held (bows, potions, pearls,
milk), reading chat while held, seeing a held player after joining later, a slow Discord failure
freeing the next login, and other plugins reading what a held player types.

Several of those started as findings in a security review and failed against the code of the
time; each now guards its fix. See [`testing/README.md`](testing/README.md) for the list, for
`DISCORDEASYLOGIN_EXTRA_PLUGINS` (running the suites beside your own plugin set), and for what
deliberately is not covered — notably voice chat, which no scripted client can test.

`mvn test` runs the unit tests underneath both suites (codes, attempt/expiry handling, cooldowns,
config validation, session persistence, webhook delivery and redaction against a local mock HTTP
server).

Every push runs all three in CI (`.github/workflows/ci.yml`).

## Releasing

Set the version in `pom.xml`, commit, then push a matching tag:

```sh
git tag v0.1.0 && git push origin v0.1.0
```

`.github/workflows/release.yml` checks the tag matches `pom.xml`, runs the tests, and publishes a
GitHub Release with the jar and its SHA-256 attached.
