**A second login factor for your Minecraft server, delivered through your own Discord channel.**

When someone joins, they are frozen in place and a single-use code is posted to a private Discord
channel you control. They paste the code in chat, and they're in. No code, no game.

A whitelist proves an account *name* is allowed. It doesn't prove the person behind it is you or
your friends: a stolen or shared account walks straight in. DiscordEasyLogin adds a check on
something an attacker almost certainly can't reach — your private Discord channel.

- **No bot, no token, no account linking.** It posts through an ordinary Discord webhook.
- **Fails closed.** A bad config, a failed start, or Discord being down never lets anyone in
  unchecked. During an outage the code goes to your server console, so you can still get in.
- **Hard to get around.** A waiting player can't move, chat, read chat, use commands or items,
  open inventories, or be seen. Codes are random, single-use, expire, and allow three attempts.
- **Doesn't nag.** A verified player isn't asked again for 24 hours (configurable).
- **Tested on real servers.** Every change goes through an end-to-end suite and a 15-attack
  adversarial suite, run against real Paper servers on every supported version.

## Requirements

- **Paper** 26.1.2 or 26.2 (Spigot is not supported; Folia is not supported)
- **Java 25**
- An **online-mode** server (normal Mojang authentication). This plugin is a second factor on top
  of real accounts, not a login system for offline-mode servers.
- A Discord channel you can add a webhook to

## Setup

1. In Discord: channel → **Edit Channel → Integrations → Webhooks → New Webhook → Copy Webhook
   URL**. Restrict who can view that channel — anyone who can read it can use the codes in it.
2. Drop the jar into `plugins/` and start the server once.
3. Put the URL in `plugins/DiscordEasyLogin/config.yml` under `discord.webhook_url` (or in the
   `DISCORDEASYLOGIN_WEBHOOK` environment variable), then run `/discordeasylogin reload`.

Until a webhook is configured, **every login is refused** — on purpose: a security plugin that
isn't set up must not leave the server open. `/discordeasylogin status` shows what it is doing.

## What it sends, and where

DiscordEasyLogin talks to exactly one place: **the Discord webhook you configure**. For each join
that needs verifying it posts the player's **name**, **UUID**, the **code** and its expiry.

It does **not** send the player's IP address unless you add `<ip>` to the message template
yourself. It sends nothing to the plugin's author, has no telemetry, no analytics, and no update
checker.

## Commands and permissions

| Command | Who | What it does |
|---|---|---|
| `/verify <code>` | any player | Submit a code (pasting it into chat works too) |
| `/discordeasylogin status` | `discordeasylogin.admin` | Lockdown state, pending codes, trusted players |
| `/discordeasylogin reload` | `discordeasylogin.admin` | Reload the config |
| `/discordeasylogin code <player>` | console only | Show a waiting player's code — your way in if Discord is down |
| `/discordeasylogin trust clear <player\|all>` | `discordeasylogin.admin` | Make players verify again |
| `/discordeasylogin lockdown <on\|off>` | `discordeasylogin.admin` | Refuse all logins / resume |

`/dlogin` is a short alias. `discordeasylogin.bypass` skips verification entirely and is granted
to nobody by default — giving it to yourself defeats the plugin.

## Good to know

- **Anyone who can read the Discord channel can use any code in it.** Lock the channel down.
- **Ping yourself on every join request** with `discord.ping_user_ids`, so a login attempt on a
  stolen account reaches your phone. Nothing else in a message can ever ping anyone.
- **Upgrading from EasyGuard?** That was this plugin's former name. Remove the old jar; your
  settings and trusted players are copied over automatically on the first start.
- Not related to the *EasyLogin* or *DiscordLogin* projects.

Open source under the MIT licence.
