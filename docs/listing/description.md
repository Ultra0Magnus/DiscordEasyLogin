**A second login step for your Minecraft server, using your own Discord channel.**

When a player joins, they're frozen and a one-time code is posted to a private Discord channel you
control. They type the code in chat and they're in. No code, no game.

A whitelist checks the account name. It can't tell if a stolen or shared account is being used.
DiscordEasyLogin adds a check that an attacker can't easily pass: access to your Discord channel.

## Features

- **No bot needed.** It posts through a normal Discord webhook. No token, no account linking.
- **Safe by default.** If the config is broken or the plugin fails to start, nobody gets in. If
  Discord is down, the code is printed to the server console instead.
- **Hard to bypass.** A waiting player can't move, chat, use commands or items, or be seen. Codes
  are random, single-use, expire after 5 minutes and allow 3 tries.
- **Not annoying.** Once verified, a player isn't asked again for 24 hours (configurable).

## Requirements

- Paper 26.1.2 or 26.2 (not Spigot or Folia)
- Java 25
- An online-mode server. This is an extra check on top of real accounts, not a login plugin for
  offline-mode servers.

## Setup

1. In Discord, open your channel settings: Integrations > Webhooks > New Webhook > Copy Webhook URL.
   Make sure only you (or people you trust) can see that channel.
2. Put the jar in `plugins/` and start the server once.
3. Paste the URL into `plugins/DiscordEasyLogin/config.yml` under `discord.webhook_url`, then run
   `/discordeasylogin reload`.

Until the webhook is set, every login is refused. That's on purpose.

## What it sends

Only to your Discord webhook: the player's name, UUID, the code and when it expires. No IP address
(unless you add it to the message yourself), no telemetry, no update checker.

## Commands

- `/verify <code>` - enter your code (or just paste it in chat)
- `/discordeasylogin status` - see what the plugin is doing
- `/discordeasylogin reload` - reload the config
- `/discordeasylogin code <player>` - console only: show a player's code if Discord is down
- `/discordeasylogin trust clear <player|all>` - make players verify again
- `/discordeasylogin lockdown <on|off>` - block all logins / allow them again

Short alias: `/dlogin`. Admin commands need `discordeasylogin.admin`.

## Good to know

- Anyone who can read the Discord channel can use the codes in it, so keep it private.
- Add your Discord user ID to `discord.ping_user_ids` to get pinged on every join request.
- Upgrading from EasyGuard (the old name)? Remove the old jar. Your settings and trusted players
  are copied over automatically.

Open source, MIT licence.
