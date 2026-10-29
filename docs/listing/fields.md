# Marketplace listing: form fields

The project description for both sites is [`description.md`](description.md): paste it as is.
Everything below is what each site's creation form asks for.

Upload `DiscordEasyLogin-<version>.jar` from the GitHub release, not a local build, so the file
people download matches the SHA-256 in the release notes.

## Before the first upload

- [ ] **Make the GitHub repository public**, or remove the source links below. Both sites link to
      it, and `plugin.yml` names it as the plugin's website; while it's private, that link is a
      404 for everyone but you.
- [ ] Icon: [`icon.png`](icon.png) (512x512, drawn from [`icon.svg`](icon.svg)). Both sites accept it.
- [ ] Optional but worth it: two or three screenshots - the in-game prompt, a Discord join
      request, `/discordeasylogin status`.

Only list versions the test suites pass on. 26.3 is left out for now: the plugin starts on it,
but the tests can't fully run there yet (see `testing/README.md`). Add it once CI covers it.

## Hangar - https://hangar.papermc.io

| Field | Value |
|---|---|
| Name | DiscordEasyLogin |
| Category | Admin Tools |
| Summary | A second login factor: players enter a one-time code posted to your private Discord channel. |
| Platform | **Paper** only. Hangar requires every platform you tick to load the plugin; Velocity and Waterfall can't. |
| Platform versions | 26.1.2, 26.2 |
| License | MIT |
| Source | https://github.com/Ultra0Magnus/DiscordEasyLogin (once public) |
| Issues | https://github.com/Ultra0Magnus/DiscordEasyLogin/issues (once public) |
| Dependencies | none |

Hangar's guidelines that matter here, all met: the platforms ticked can load it; it doesn't grant
or revoke anything for users the author picks; and it is not made for offline-mode servers (the
description says so explicitly, because "Login" in a plugin name usually means an offline-mode
password plugin, which Hangar does not allow).

## Modrinth - https://modrinth.com

| Field | Value |
|---|---|
| Project type | Plugin |
| Name | DiscordEasyLogin |
| URL slug | `discordeasylogin` |
| Summary (plain text, no formatting) | A second login factor: players enter a one-time code posted to your private Discord channel. |
| Categories | Management, Utility |
| Loaders | **Paper** only (untested on Purpur, Spigot and Folia - don't tick them) |
| Game versions | 26.1.2, 26.2 |
| Environment | Server-side only |
| License | MIT |
| Source | https://github.com/Ultra0Magnus/DiscordEasyLogin (once public) |
| Issues | https://github.com/Ultra0Magnus/DiscordEasyLogin/issues (once public) |

**Content disclosure - required.** Modrinth's rules forbid software that uploads data to a remote
server "without clear disclosure". DiscordEasyLogin posts the joining player's name, UUID and code
to the Discord webhook the server owner configures; the "What it sends, and where" section of the
description is that disclosure. Keep it accurate if the default message ever changes.

Modrinth also asks you to disclose when a project *depends on generative AI to function*. This one
doesn't.

## SpigotMC, CurseForge - not recommended

The plugin uses Paper-only APIs and cannot run on Spigot. SpigotMC requires premium resources to
support Spigot, and free Paper-only ones can be removed if reported. CurseForge's Bukkit section
has the same mismatch.
