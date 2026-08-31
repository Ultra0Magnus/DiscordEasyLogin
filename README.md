# EasyGuard

A second factor for your Minecraft server. When someone joins, they are frozen in place and a
single-use code is posted to a Discord channel you control. They paste that code in chat to play.
No code, no game.

Built for **Paper 26.1.2** (requires **Java 25**).

## Why

A whitelist proves an *account name* is allowed. It does not prove the person behind it is you or
your friends — a compromised, shared, or spoofed account walks straight in, and a public tunnel
address (playit.gg and friends) means anyone can knock. EasyGuard adds a check on something an
attacker almost certainly cannot reach: your private Discord channel.

Keep the whitelist on. EasyGuard is the second layer, not a replacement.

## How it looks

1. Player connects. They spawn but cannot move, break anything, use their inventory, or chat.
2. A message lands in your Discord channel:

   ```
   **Join request**
   Player: `Steve`
   UUID: `f84c6a79-...`
   IP: `203.0.113.7`
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

Drop `EasyGuard-0.0.1.jar` into `plugins/` and start the server once. It will refuse to run until a
webhook URL is set (that is deliberate — see [Fail-closed](#fail-closed)).

### 3. Point it at your webhook

Either set an environment variable before starting the server (preferred — keeps the secret out of
config backups):

```sh
export EASYGUARD_DISCORD_WEBHOOK="https://discord.com/api/webhooks/…"
```

…or edit `plugins/EasyGuard/config.yml`:

```yaml
discord:
  webhook_url: "https://discord.com/api/webhooks/…"
```

Then `/easyguard reload` (or restart). `/easyguard status` shows what it is doing.

## Fail-closed

"Impossible to connect without a code" only holds if the gate cannot quietly stop gating, so:

| Situation | Behaviour |
|---|---|
| Discord unreachable | Player stays frozen. The code is written to the **server console**, so you can still get yourself in during an outage. Set `fallback.log_code_to_console_on_failure: false` to kick instead. |
| Bad config / plugin fails to start | The server enters **lockdown**: every login is denied with an explanatory kick screen. A broken EasyGuard blocks the door rather than vanishing and leaving the server open. |
| Webhook URL typo pointing elsewhere | Rejected at load. Only `https://discord.com/api/webhooks/…` is accepted unless you explicitly set `discord.allow_non_discord_host: true`. |
| Join flood | Per-player cooldown (10s) plus a server-wide ceiling (20 codes/minute) protect your channel. Codes are only issued *after* the vanilla whitelist and ban checks, so strangers never reach Discord. |
| Plugin disabled while someone is unverified | Unverified players are kicked rather than silently released. |
| `enabled: false` | The gate is off and anyone whitelisted can join with no code. Logged loudly as a warning at every startup. |

## Configuration

Full documented defaults live in [`src/main/resources/config.yml`](src/main/resources/config.yml).
The values worth knowing:

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch for the gate. |
| `discord.webhook_url` | *empty* | Overridden by `$EASYGUARD_DISCORD_WEBHOOK`. |
| `discord.mention_prefix` | *empty* | e.g. `<@your-user-id>` to tag yourself. Pings are suppressed by default. |
| `code.length` | `8` | Drawn from `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (no `O/0/I/1/L` lookalikes). |
| `code.ttl_seconds` | `300` | How long a code stays valid. |
| `code.max_attempts` | `3` | Wrong guesses before a kick. |
| `verification.timeout_seconds` | `120` | How long a player may sit unverified. |
| `verification.apply_blindness` | `true` | Stops an unverified client from scouting your base. |
| `verification.hide_from_others` | `true` | Unverified players are invisible and absent from the tab list. |
| `trust.duration_hours` | `24` | `0` requires a code on every single join. |

All player-facing text is [MiniMessage](https://docs.advntr.dev/minimessage/format.html) and
editable under `messages:`.

## Commands and permissions

| Command | Who | Purpose |
|---|---|---|
| `/verify <code>` | any player | Submit a code. Pasting the code into chat works too. |
| `/easyguard status` | `easyguard.admin` | Current state: lockdown, pending codes, trusted sessions. |
| `/easyguard reload` | `easyguard.admin` | Re-read config. A rejected config locks the server down. |
| `/easyguard code <player>` | **console only** | Print a waiting player's live code. The documented escape hatch for a Discord outage. |
| `/easyguard trust clear <player\|all>` | `easyguard.admin` | Force re-verification. |
| `/easyguard lockdown <on\|off>` | `easyguard.admin` | Deny all logins / resume. |

| Permission | Default | Notes |
|---|---|---|
| `easyguard.admin` | op | Administration commands. |
| `easyguard.bypass` | **nobody** | Skips verification entirely. Granting this to yourself defeats the gate — the 24h trust window is the convenience mechanism instead. |

## Limits

Worth being clear about what this does and does not buy you:

- **The code authenticates channel access, not a person.** Anyone who can read the Discord channel
  can use any code in it. Lock the channel's permissions down. (A per-player Discord mapping with
  DMs would need a bot; this build deliberately uses a webhook so there is no token to leak.)
- **Anyone with filesystem or console access to the host can remove the jar.** The gate protects the
  front door, not the server box.
- **Unverified players are technically online.** They occupy a player slot while frozen, blinded and
  hidden. The verification timeout bounds how long.
- **Trusted sessions are keyed by UUID, not IP.** Behind a tunnel like playit.gg the server usually
  sees the tunnel's address rather than the player's, so IP binding would be meaningless or would
  lock everyone out.
- **Folia is not supported.** The plugin uses the standard Bukkit scheduler.

## Building

```sh
JAVA_HOME=/path/to/jdk-25 mvn package   # -> target/EasyGuard-0.0.1.jar
```

## Testing

Because this plugin fails *closed*, the failure that matters is "nobody can log in" — so test
it against a server you do not care about before you point it at one you do.

```sh
cd testing && npm install && npm run e2e
```

That boots a throwaway Paper 26.1.2 server, installs the jar you just built, points it at a
local mock webhook (never the real Discord), and drives real Minecraft clients through nine
scenarios — including the ones that lock the server down — then cleans up. About four minutes.

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
  breaks on reload or at startup
- **a fixed config lets you back in** — by reload, without a restart, or by restart

See [`testing/README.md`](testing/README.md) for options, and for what it deliberately does not
cover. `mvn test` runs the unit tests underneath it (codes, attempt/expiry handling, session
persistence, webhook delivery against a local mock HTTP server).
