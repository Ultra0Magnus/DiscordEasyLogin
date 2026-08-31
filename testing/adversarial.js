'use strict';

// Adversarial suite: this one tries to *defeat* the gate rather than exercise it.
//
// Usage:  npm run adversarial        (add -- --keep to retain the scratch server)
//
// Every scenario is an attack that must fail, except the last two, which characterise
// behaviour that is neither clearly right nor clearly wrong: they report what they observed
// and assert it, so a future change that alters it fails loudly.
//
// Set EASYGUARD_EXTRA_PLUGINS=/path/to/jars to run this beside your real plugin set — that is
// the gap no clean-server suite can close.

const fs = require('fs');
const path = require('path');

const { MockWebhook } = require('./lib/webhook');
const { PaperServer, resolveJava, ensurePaperJar, requireFreePort, MC_VERSION } = require('./lib/server');
const {
  ROOT,
  TESTING,
  findPluginJar,
  requireDependencies,
  pluginConfig,
  registerEmergencyStop,
  runScenarios,
  report,
} = require('./lib/harness');
const { join, attemptJoin, waitFor, delay } = require('./lib/client');

const SERVER_DIR = path.join(TESTING, '.server-adversarial');
const CACHE_DIR = path.join(TESTING, '.cache');
const PROBE_DIR = path.join(TESTING, 'leak-probe');

const MC_PORT = Number(process.env.EASYGUARD_TEST_PORT || 25599);
const WEBHOOK_PORT = Number(process.env.EASYGUARD_WEBHOOK_PORT || 8099);
const KEEP = process.argv.includes('--keep');
const ONLY = process.env.EASYGUARD_ONLY || '';

const PROMPT = 'A single-use code was sent to Discord';
const BLOCKED = 'Verify first: paste the code from Discord in chat.';
const SESSION_LINE = /^[0-9a-f-]{36}=/;

function fail(message, { player } = {}) {
  const error = new Error(message);
  if (player) error.diagnostics = player.diagnostics();
  return error;
}

function expect(condition, message, context) {
  if (!condition) throw fail(message, context);
}

function awaitCode(webhook, username, timeout = 15000) {
  return waitFor(() => webhook.codeFor(username), {
    timeout,
    describe: `a code for ${username} to reach the webhook`,
  });
}

/** Waits for a *new* code for a player who already had one (a rejoin). */
async function awaitNewCode(webhook, username, previousCount, timeout = 15000) {
  await waitFor(() => webhook.countFor(username) > previousCount, {
    timeout,
    describe: `a second code for ${username}`,
  });
  return webhook.codeFor(username);
}

function trustedSessionCount(server) {
  const file = path.join(server.dir, 'plugins', 'EasyGuard', 'sessions.properties');
  if (!fs.existsSync(file)) return 0;
  return fs.readFileSync(file, 'utf8').split('\n').filter((line) => SESSION_LINE.test(line)).length;
}

/**
 * The obvious bypass: the command block matches on the label, so try the namespaced forms and
 * anything else a server has lying around. Nothing may produce output.
 */
async function commandBypass({ server, webhook, connect }) {
  const attempts = [
    '/minecraft:me sneaking past',
    '/bukkit:help',
    '/help',
    '/list',
    '/msg EGCmd hello',
    '/say hello',
    '/easyguard status',
    '/easyguard:easyguard status',
  ];

  const player = await join('EGCmd', connect);
  try {
    await awaitCode(webhook, 'EGCmd');
    await player.awaitChat(PROMPT);

    for (const command of attempts) {
      const before = player.chat.length;
      player.say(command);
      // Proving a negative: there is no event to await for output that must not arrive.
      await delay(900);
      const produced = player.chat.slice(before).filter((line) => !line.includes(BLOCKED));
      expect(
        produced.length === 0,
        `"${command}" produced output while unverified: ${JSON.stringify(produced)}`,
        { player, server },
      );
    }

    expect(player.saw(BLOCKED), 'no command was refused out loud', { player, server });
  } finally {
    await player.quit();
  }
}

/** A code belongs to one player. Stealing it must fail, and must not burn the victim's code. */
async function crossPlayerCode({ server, webhook, connect }) {
  const victim = await join('EGVictim', connect);
  const thief = await join('EGThief', connect);
  try {
    const victimCode = await awaitCode(webhook, 'EGVictim');
    const thiefCode = await awaitCode(webhook, 'EGThief');
    expect(victimCode !== thiefCode, 'two players were issued the same code', { server });

    thief.say(victimCode);
    await thief.awaitChat('Wrong code.');

    // The victim's code must still work: the thief's attempt may not consume it.
    victim.say(victimCode);
    await victim.awaitChat('Verified. Welcome,');
  } finally {
    await thief.quit();
    await victim.quit();
  }
}

/** A code must not outlive the session it was minted for. */
async function codeDiesOnQuit({ server, webhook, connect }) {
  const first = await join('EGQuit', connect);
  const oldCode = await awaitCode(webhook, 'EGQuit');
  const seen = webhook.countFor('EGQuit');
  await first.quit();

  const second = await join('EGQuit', connect);
  try {
    const newCode = await awaitNewCode(webhook, 'EGQuit', seen);
    expect(newCode !== oldCode, 'the same code was issued twice', { player: second, server });

    second.say(oldCode);
    await second.awaitChat('Wrong code.');

    second.say(newCode);
    await second.awaitChat('Verified. Welcome,');
  } finally {
    await second.quit();
  }
}

/** Being kicked for wrong codes must not launder anything: fresh code, fresh attempts. */
async function kickThenRejoin({ server, webhook, connect }) {
  const burner = await join('EGBurn', connect);
  const oldCode = await awaitCode(webhook, 'EGBurn');
  const seen = webhook.countFor('EGBurn');

  burner.say('NOPE1111');
  await burner.awaitChat('2 attempt(s) left.');
  burner.say('NOPE2222');
  await burner.awaitChat('1 attempt(s) left.');
  burner.say('NOPE3333');
  const kick = await burner.awaitDisconnect(20000);
  expect(kick.includes('Too many wrong codes.'), `expected a kick, got: ${kick}`, {
    player: burner,
    server,
  });

  const rejoined = await join('EGBurn', connect);
  try {
    const newCode = await awaitNewCode(webhook, 'EGBurn', seen);

    // A full three attempts again, i.e. the counter reset rather than carrying over.
    rejoined.say('NOPE4444');
    await rejoined.awaitChat('2 attempt(s) left.');

    // And the code from the kicked session is dead.
    rejoined.say(oldCode);
    await rejoined.awaitChat('1 attempt(s) left.');

    rejoined.say(newCode);
    await rejoined.awaitChat('Verified. Welcome,');
  } finally {
    await rejoined.quit();
  }
}

/** A double-paste must not verify twice or record two trusted sessions. */
async function doubleSubmit({ server, webhook, connect }) {
  const before = trustedSessionCount(server);
  const player = await join('EGDouble', connect);
  try {
    const code = await awaitCode(webhook, 'EGDouble');

    player.say(code);
    player.say(code);

    await player.awaitChat('Verified. Welcome,');
    await player.awaitChat('You have nothing to verify.');

    const after = await waitFor(
      () => {
        const count = trustedSessionCount(server);
        return count > before ? count : null;
      },
      { timeout: 10000, describe: 'the trusted session to be persisted' },
    );
    expect(
      after === before + 1,
      `expected one new trusted session, got ${after - before}`,
      { player, server },
    );
  } finally {
    await player.quit();
  }
}

/** Expiry is a security control; this covers the wiring, not the clock. */
async function expiredCode({ server, webhook, connect, applyConfig }) {
  // 30s is the plugin's validated floor for a code TTL. The verification timeout is pushed out
  // so the player is not kicked for idling before the code has a chance to expire.
  await applyConfig({ ttlSeconds: 30, verificationTimeout: 120 });
  try {
    const player = await join('EGExpire', connect);
    const code = await awaitCode(webhook, 'EGExpire');

    await delay(34000);
    player.say(code);

    const reason = await player.awaitDisconnect(20000);
    expect(
      reason.includes('That code expired.'),
      `expected an expiry kick, got: ${reason.replace(/\n/g, ' / ')}`,
      { player, server },
    );
  } finally {
    await applyConfig();
  }
}

/** The server-wide cap is what stops your Discord channel being used as a firehose. */
async function floodTheChannel({ server, webhook, connect, applyConfig }) {
  await applyConfig({ maxPerMinute: 3, cooldownSeconds: 0 });
  const players = [];
  try {
    const before = webhook.payloads.length;

    for (const name of ['EGFlood1', 'EGFlood2', 'EGFlood3']) {
      const player = await join(name, connect);
      players.push(player);
      await awaitCode(webhook, name);
    }
    expect(
      webhook.payloads.length === before + 3,
      `expected exactly 3 codes under the cap, got ${webhook.payloads.length - before}`,
      { server },
    );

    const blocked = await attemptJoin('EGFlood4', connect);
    players.push(blocked);
    const reason = await blocked.awaitDisconnect(20000);
    expect(
      reason.includes('Too many verification requests.'),
      `expected a rate-limit kick past the cap, got: ${reason.replace(/\n/g, ' / ')}`,
      { player: blocked, server },
    );
    expect(
      webhook.payloads.length === before + 3,
      'a code was posted to the webhook after the cap was reached',
      { server },
    );
  } finally {
    for (const player of players) await player.quit().catch(() => {});
    await applyConfig();
  }
}

/**
 * FINDING, characterised: the per-player cooldown does NOT survive a reconnect.
 *
 * `GateService.onQuit` calls `VerificationManager.cancel`, which does `lastIssued.remove(player)`
 * — so quitting erases your own cooldown. One account can reconnect in a loop and mint a fresh
 * code every time; only the server-wide `code.max_per_minute` cap limits the damage.
 *
 * This test pins the behaviour as it actually is. If the cooldown is ever made to survive a
 * reconnect, this fails and should be rewritten to assert the kick instead.
 */
async function reconnectCooldown({ server, webhook, connect, applyConfig, notes }) {
  await applyConfig({ cooldownSeconds: 10, maxPerMinute: 500 });
  try {
    const first = await join('EGCool', connect);
    await awaitCode(webhook, 'EGCool');
    const seen = webhook.countFor('EGCool');
    await first.quit();

    const again = await attemptJoin('EGCool', connect);
    let outcome;
    try {
      const code = await awaitNewCode(webhook, 'EGCool', seen, 12000);
      outcome = `a fresh code (${code.length} chars) was issued straight away`;
    } catch {
      outcome = 'no new code was issued';
    }

    const rateLimited = (again.disconnect || '').includes('Too many verification requests.');
    notes.push(
      rateLimited
        ? 'The per-player cooldown survives a reconnect and kicked the rejoining player.'
        : 'FINDING: code.cooldown_seconds does NOT survive a reconnect — quitting clears it '
          + `(GateService.onQuit -> VerificationManager.cancel -> lastIssued.remove), so ${outcome}. `
          + 'One whitelisted account can loop reconnects and post up to code.max_per_minute codes '
          + 'to your Discord channel every minute (default 20) instead of the 6/min the 10s '
          + 'cooldown implies. Not a way past the gate; it makes your channel noisy.',
    );

    expect(
      !rateLimited,
      'the per-player cooldown now survives a reconnect — behaviour improved; rewrite this '
        + 'scenario to assert the rate-limit kick',
      { player: again, server },
    );
    await again.quit().catch(() => {});
  } finally {
    await applyConfig();
  }
}

/**
 * The property that keeps strangers scanning your tunnel from making your Discord channel
 * ring: the vanilla whitelist is checked before EasyGuard ever issues a code.
 */
async function whitelistRunsFirst({ server, webhook, connect }) {
  const before = webhook.payloads.length;
  await server.console('whitelist on', /hitelist/);
  try {
    const stranger = await attemptJoin('EGStranger', connect);
    expect(!stranger.spawned, 'a non-whitelisted player got onto the server', {
      player: stranger,
      server,
    });
    expect(
      webhook.payloads.length === before,
      `a stranger caused ${webhook.payloads.length - before} webhook post(s) — strangers can ring your channel`,
      { player: stranger, server },
    );
  } finally {
    await server.console('whitelist off', /hitelist/);
  }
}

/**
 * Characterising, not judging. Held players are returned to their anchor by a PlayerMoveEvent
 * handler, and PlayerTeleportEvent extends PlayerMoveEvent — so a teleport from the console or
 * from another plugin (Multiverse's join handling, an admin rescuing someone) may be overridden.
 * Either answer is worth knowing; this pins whichever one is true.
 */
async function teleportWhileHeld({ server, webhook, connect, notes }) {
  const player = await join('EGTeleport', connect);
  try {
    await awaitCode(webhook, 'EGTeleport');
    await player.awaitChat(PROMPT);
    const anchor = player.blockPosition();

    await server.console('tp EGTeleport 120 -60 120', /Teleported|No player was found/);
    await delay(2500);

    const afterTeleport = player.blockPosition();
    const distance = Math.hypot(afterTeleport.x - anchor.x, afterTeleport.z - anchor.z);
    const moved = distance > 5;

    // If the teleport landed, the anchor is now stale — so check what the player's own next step
    // does: being yanked back across 100+ blocks is a different problem from staying put.
    const walk = await player.walkForward();
    const backAtAnchor = Math.hypot(walk.after.x - anchor.x, walk.after.z - anchor.z) < 5;

    notes.push(
      moved
        ? `FINDING: a console or plugin teleport MOVES a held, unverified player (${distance.toFixed(0)} `
          + 'blocks). A PlayerMoveEvent handler does not receive PlayerTeleportEvent in Bukkit, so '
          + 'the freeze does not cover teleports. The gate still holds — they cannot act or verify '
          + `— but another plugin can relocate an invisible, unverified player, and their own next `
          + `step ${backAtAnchor ? 'yanks them back to the original anchor' : 'leaves them where they were put'}. `
          + 'Relevant with Multiverse spawn handling on your server.'
        : 'A held player is snapped back to their anchor even by a console/plugin teleport, so '
          + 'Multiverse spawn handling and admin /tp on an unverified player are overridden.',
    );

    // Whatever the movement outcome, the security property must hold: still held, still unverified.
    expect(!player.saw('Verified. Welcome,'), 'a teleport verified the player', { player, server });
    player.say('STILLWRONG');
    await player.awaitChat('attempt(s) left.');

    // Pinned so a future change to the freeze logic surfaces here rather than silently.
    expect(
      moved,
      'the freeze now overrides teleports too — behaviour changed; re-read the note above and '
        + 'update this expectation',
      { player, server },
    );
  } finally {
    await player.quit();
  }
}

/**
 * Does an unverified player's typed code reach other plugins? EasyGuard cancels the chat event
 * at LOWEST, but cancelling does not stop later listeners from seeing it. LeakProbe stands in
 * for a Discord chat bridge, listening at MONITOR without ignoring cancelled events.
 *
 * A canary string is used rather than a real code, so nothing secret is written to the log.
 */
async function chatLeakToOtherPlugins({ server, webhook, connect, notes }) {
  const canary = 'LEAKCANARY7788';
  const player = await join('EGLeak', connect);
  try {
    await awaitCode(webhook, 'EGLeak');
    await player.awaitChat(PROMPT);

    const from = server.mark();
    player.say(canary);
    await player.awaitChat('attempt(s) left.');
    await delay(1500);

    const leak = server.matchLog(new RegExp(`LEAKED\\[cancelled=(\\w+)\\] EGLeak: (${canary})`), from);

    notes.push(
      leak
        ? 'A MONITOR-priority chat listener in another plugin CAN read what an unverified player '
          + `types (observed cancelled=${leak[1]}). EasyGuard keeps codes out of public chat, but a `
          + 'chat-bridge plugin that ignores the cancelled flag could forward one. You run no chat '
          + 'bridge today — worth remembering before adding DiscordSRV or similar.'
        : 'A MONITOR-priority chat listener in another plugin could NOT read what an unverified '
          + 'player types: the message does not reach other plugins at all.',
    );

    expect(
      leak !== null,
      'the chat event no longer reaches MONITOR listeners — behaviour changed for the better; '
        + 'update this expectation',
      { player, server },
    );
  } finally {
    await player.quit();
  }
}

const SCENARIOS = [
  { name: 'namespaced and built-in commands are all refused', run: commandBypass },
  { name: "another player's code cannot be used or burned", run: crossPlayerCode },
  { name: 'a code dies with the session that minted it', run: codeDiesOnQuit },
  { name: 'a kick grants no new code and no extra attempts', run: kickThenRejoin },
  { name: 'a double-paste verifies once and trusts once', run: doubleSubmit },
  { name: 'an expired code is refused', run: expiredCode },
  { name: 'the channel cap stops a webhook flood', run: floodTheChannel },
  { name: 'a reconnect loop evades the per-player cooldown (characterised)', run: reconnectCooldown },
  { name: 'the whitelist is checked before any code is issued', run: whitelistRunsFirst },
  { name: 'teleporting a held player (characterised)', run: teleportWhileHeld },
  { name: 'chat leaking to other plugins (characterised)', run: chatLeakToOtherPlugins },
];

/** The leak scenario needs a second plugin; build it with the Maven that built EasyGuard. */
function buildLeakProbe() {
  const { spawnSync } = require('child_process');
  const jar = path.join(PROBE_DIR, 'target', 'LeakProbe.jar');
  if (fs.existsSync(jar)) return jar;
  const build = spawnSync('mvn', ['-B', '-q', 'package'], { cwd: PROBE_DIR, encoding: 'utf8' });
  if (build.status !== 0 || !fs.existsSync(jar)) return null;
  return jar;
}

async function main() {
  requireDependencies();
  const pluginJar = findPluginJar();
  const javaBin = resolveJava();
  await requireFreePort(MC_PORT, 'Minecraft', 'EASYGUARD_TEST_PORT');
  await requireFreePort(WEBHOOK_PORT, 'Mock webhook', 'EASYGUARD_WEBHOOK_PORT');

  console.log(`plugin   ${path.relative(ROOT, pluginJar)}`);
  console.log(`java     ${javaBin}`);
  console.log(`paper    ${MC_VERSION} (downloading if not cached)`);
  const paperJar = await ensurePaperJar(CACHE_DIR);

  fs.rmSync(SERVER_DIR, { recursive: true, force: true });
  const server = new PaperServer({ dir: SERVER_DIR, paperJar, javaBin, port: MC_PORT });
  server.writeBaseFiles();
  server.installPlugin(pluginJar);

  const probeJar = buildLeakProbe();
  let scenarios = SCENARIOS;
  if (probeJar) {
    server.installPlugin(probeJar);
    console.log(`probe    ${path.relative(ROOT, probeJar)}`);
  } else {
    scenarios = SCENARIOS.filter((scenario) => scenario.run !== chatLeakToOtherPlugins);
    console.log('probe    could not be built — the chat-leak scenario will be skipped');
  }

  const extra = server.installExtraPlugins(process.env.EASYGUARD_EXTRA_PLUGINS);
  if (extra.length) console.log(`extra    ${extra.join(', ')}`);

  const base = { webhookPort: WEBHOOK_PORT };
  server.writePluginConfig(pluginConfig(base));

  const webhook = new MockWebhook(WEBHOOK_PORT);
  registerEmergencyStop(server, webhook);

  const notes = [];
  let results = [];
  try {
    await webhook.start();
    console.log(`webhook  http://127.0.0.1:${WEBHOOK_PORT}/hook (mock)\n`);
    console.log('starting the test server, this takes a moment...');
    await server.start();
    console.log(`server   up on 127.0.0.1:${MC_PORT}\n`);

    const applyConfig = async (overrides = {}) => {
      server.writePluginConfig(pluginConfig({ ...base, ...overrides }));
      await server.console('easyguard reload', /EasyGuard reloaded\./);
    };

    results = await runScenarios(scenarios, {
      server,
      webhook,
      notes,
      applyConfig,
      connect: { host: '127.0.0.1', port: MC_PORT },
    }, { only: ONLY });
  } finally {
    await server.stop().catch(() => server.kill());
    await webhook.stop().catch(() => {});
  }

  const passed = report(results, { notes });
  if (!passed || KEEP) {
    console.log(`\nserver files kept for inspection: ${path.relative(ROOT, SERVER_DIR)}`);
  } else {
    fs.rmSync(SERVER_DIR, { recursive: true, force: true });
  }
  process.exit(passed ? 0 : 1);
}

main().catch((err) => {
  console.error(`\n${err.message}`);
  process.exit(2);
});
