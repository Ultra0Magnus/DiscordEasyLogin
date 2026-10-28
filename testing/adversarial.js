'use strict';

// Adversarial suite: this one tries to *defeat* the gate rather than exercise it.
//
// Usage:  npm run adversarial        (add -- --keep to retain the scratch server)
//
// Every scenario is an attack that must fail. Several started life as findings that passed only
// because the plugin was wrong (teleports, item use, chat leaks, the reconnect cooldown, a late
// Discord failure freeing the next session); they now assert the fixed behaviour.
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

/** A double-paste must not verify twice, record two trusted sessions, or leak the code to chat. */
async function doubleSubmit({ server, webhook, connect }) {
  const before = trustedSessionCount(server);
  const player = await join('EGDouble', connect);
  try {
    const code = await awaitCode(webhook, 'EGDouble');

    player.say(code);
    player.say(code);

    await player.awaitChat('Verified. Welcome,');
    // The second copy is either refused ("nothing to verify") or, if it lands after the release,
    // silently dropped. What it must never do is appear in public chat: a race once put it there.
    await delay(1500);
    expect(
      !player.saw(`<EGDouble> ${code}`) && !player.chat.some((line) => line.includes(code)),
      'the second paste of the code was broadcast to public chat',
      { player, server },
    );

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
 * A reconnect loop must not mint codes. Quitting used to clear the per-player cooldown, so one
 * account could post a fresh code to your channel on every reconnect.
 */
async function reconnectCooldown({ server, webhook, connect, applyConfig }) {
  await applyConfig({ cooldownSeconds: 10, maxPerMinute: 500 });
  try {
    const first = await join('EGCool', connect);
    await awaitCode(webhook, 'EGCool');
    const seen = webhook.countFor('EGCool');
    await first.quit();

    const again = await attemptJoin('EGCool', connect);
    const reason = await again.awaitDisconnect(20000);
    expect(
      reason.includes('Too many verification requests.'),
      `expected a cooldown kick on an immediate rejoin, got: ${reason.replace(/\n/g, ' / ')}`,
      { player: again, server },
    );
    expect(
      webhook.countFor('EGCool') === seen,
      'the immediate rejoin still posted a fresh code to the webhook',
      { player: again, server },
    );
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
 * A teleport from the console or another plugin (Multiverse, an admin /tp) must not move an
 * unverified player. Bukkit delivers teleports to PlayerTeleportEvent handlers only, so the
 * PlayerMoveEvent freeze alone never saw them.
 */
async function teleportWhileHeld({ server, webhook, connect }) {
  const player = await join('EGTeleport', connect);
  try {
    await awaitCode(webhook, 'EGTeleport');
    await player.awaitChat(PROMPT);
    const anchor = player.blockPosition();

    await server.console('tp EGTeleport 120 -60 120', /Teleported|No player was found/);
    // Proving a negative: there is no event for a teleport that must not happen.
    await delay(2500);

    const now = player.blockPosition();
    const distance = Math.hypot(now.x - anchor.x, now.z - anchor.z);
    expect(distance < 5, `a console teleport moved a held player ${distance.toFixed(0)} blocks`, {
      player,
      server,
    });

    // Blocking teleports must not break the freeze, which itself sends the player back to the
    // anchor by teleport: an earlier version of this fix let held players walk away.
    const walk = await player.walkForward();
    expect(!walk.moved, `a held player could walk after the teleport attempt: ${JSON.stringify(walk)}`, {
      player,
      server,
    });

    player.say('STILLWRONG');
    await player.awaitChat('attempt(s) left.');
  } finally {
    await player.quit();
  }
}

/**
 * An unverified player must not be able to *use* anything. Bukkit creates an air click's
 * PlayerInteractEvent already cancelled, so a handler with ignoreCancelled = true never saw it:
 * bows, potions, ender pearls and milk (which clears the blindness) all worked while held.
 */
async function itemUseWhileHeld({ server, webhook, connect }) {
  const player = await join('EGItems', connect);
  try {
    await awaitCode(webhook, 'EGItems');
    await player.awaitChat(PROMPT);
    const { bot } = player;
    const snowball = bot.registry.itemsByName.snowball.id;
    const milk = bot.registry.itemsByName.milk_bucket.id;

    await server.console('give EGItems snowball 16', /Gave|No player was found/);
    await server.console('give EGItems milk_bucket 1', /Gave|No player was found/);
    await waitFor(() => bot.inventory.count(snowball) === 16 && bot.inventory.count(milk) === 1, {
      timeout: 10000,
      describe: 'the items to arrive in the held player\'s inventory',
    });

    // Throw at the sky: a right-click on air, the case Bukkit pre-cancels.
    await bot.look(0, Math.PI / 2, true);
    await bot.equip(snowball, 'hand');
    bot.activateItem();
    await delay(1500);
    bot.deactivateItem();
    expect(
      bot.inventory.count(snowball) === 16,
      `a held player threw a snowball (${16 - bot.inventory.count(snowball)} used)`,
      { player, server },
    );

    // Drinking milk would remove the blindness that hides the base.
    await bot.equip(milk, 'hand');
    bot.activateItem();
    await delay(2500);
    bot.deactivateItem();
    await delay(500);
    expect(
      bot.inventory.count(milk) === 1,
      'a held player drank milk, which clears the blindness',
      { player, server },
    );
  } finally {
    await player.quit();
  }
}

/** Unverified players don't get to read the server's chat while they wait. */
async function chatNotReceivedWhileHeld({ server, webhook, connect }) {
  const talker = await join('EGTalker', connect);
  let listener;
  try {
    talker.say(await awaitCode(webhook, 'EGTalker'));
    await talker.awaitChat('Verified. Welcome,');

    listener = await join('EGEavesdrop', connect);
    await awaitCode(webhook, 'EGEavesdrop');
    await listener.awaitChat(PROMPT);

    const secret = 'base-coords-are-1234-5678';
    talker.say(secret);
    // Positive control first: the message really was sent and delivered to someone.
    await talker.awaitChat(secret);
    await delay(1000);
    expect(!listener.saw(secret), 'a held, unverified player received public chat', {
      player: listener,
      server,
    });
  } finally {
    if (listener) await listener.quit();
    await talker.quit();
  }
}

/** Hiding must cover players who join *after* someone was held, not just those already online. */
async function lateJoinerCannotSeeHeld({ server, webhook, connect }) {
  const held = await join('EGHidden', connect);
  let watcher;
  try {
    await awaitCode(webhook, 'EGHidden');
    await held.awaitChat(PROMPT);

    watcher = await join('EGWatcher', connect);
    watcher.say(await awaitCode(webhook, 'EGWatcher'));
    await watcher.awaitChat('Verified. Welcome,');
    // Positive control: the watcher's own tab-list entry proves player info is arriving at all.
    await waitFor(() => watcher.bot.players.EGWatcher, {
      timeout: 10000,
      describe: 'the watcher to receive the player list',
    });
    await delay(1000);

    expect(
      !watcher.bot.players.EGHidden,
      'a player who joined later can see the held, "invisible" player in the tab list',
      { player: watcher, server },
    );
  } finally {
    if (watcher) await watcher.quit();
    await held.quit();
  }
}

/**
 * A slow Discord failure for a dead session must not release the next one. Before sessions were
 * tracked, the failure callback only asked "is this UUID held?" — true for the *new* session — and
 * then dropped that session's hold, leaving it online and free.
 */
async function lateFailureCannotReleaseNextSession({ server, webhook, connect, applyConfig }) {
  // Discord hangs past the plugin's timeout; with the console fallback off, a failure kicks.
  await applyConfig({ logCodeToConsole: false, discordTimeout: 6 });
  webhook.delayMs = 10000;
  let second;
  try {
    const first = await join('EGRace', connect);
    await first.quit();
    second = await join('EGRace', connect);

    // Both deliveries time out. The first session's late failure must leave the second alone,
    // and the second's own failure must kick it — not leave it free.
    const reason = await second.awaitDisconnect(25000);
    expect(
      reason.includes('Verification is unavailable'),
      `expected the second session to be refused when its own delivery failed, got: ${reason.replace(/\n/g, ' / ')}`,
      { player: second, server },
    );
  } finally {
    webhook.delayMs = 0;
    if (second) await second.quit().catch(() => {});
    await applyConfig();
  }
}

/**
 * Does an unverified player's typed text reach other plugins? LeakProbe stands in for a chat
 * bridge on both the modern event (at MONITOR) and the deprecated one (at LOWEST, the earliest any
 * plugin can look). A canary is used rather than a real code, so nothing secret reaches the log.
 */
async function chatLeakToOtherPlugins({ server, webhook, connect }) {
  const canary = 'LEAKCANARY7788';
  const player = await join('EGLeak', connect);
  try {
    await awaitCode(webhook, 'EGLeak');
    await player.awaitChat(PROMPT);

    const from = server.mark();
    player.say(canary);
    await player.awaitChat('attempt(s) left.');
    await delay(1500);

    const modern = server.matchLog(new RegExp(`LEAKED\\[cancelled=\\w+\\] EGLeak: .*${canary}`), from);
    const legacy = server.matchLog(new RegExp(`LEGACY\\[cancelled=\\w+\\] EGLeak: .*${canary}`), from);
    expect(!modern, `a MONITOR chat listener read the held player's text: ${modern?.[0]}`, { player, server });
    expect(!legacy, `a legacy chat listener read the held player's text: ${legacy?.[0]}`, { player, server });

    // Counted as exactly one attempt: blanking the text for others must not blank it for the gate,
    // and capturing it from both chat events must not submit it twice.
    expect(player.saw('2 attempt(s) left.'), 'the canary was not processed as a code attempt', {
      player,
      server,
    });
    expect(!player.saw('1 attempt(s) left.'), 'one message was counted as two attempts', {
      player,
      server,
    });
  } finally {
    await player.quit();
  }
}

const SCENARIOS = [
  // First: the per-minute cap counts every code issued in the last minute, and a config reload
  // deliberately does not reset it, so this needs a server that has issued no codes yet.
  { name: 'the channel cap stops a webhook flood', run: floodTheChannel },
  { name: 'namespaced and built-in commands are all refused', run: commandBypass },
  { name: "another player's code cannot be used or burned", run: crossPlayerCode },
  { name: 'a code dies with the session that minted it', run: codeDiesOnQuit },
  { name: 'a kick grants no new code and no extra attempts', run: kickThenRejoin },
  { name: 'a double-paste verifies once and trusts once', run: doubleSubmit },
  { name: 'an expired code is refused', run: expiredCode },
  { name: 'a reconnect inside the cooldown gets no new code', run: reconnectCooldown },
  { name: 'the whitelist is checked before any code is issued', run: whitelistRunsFirst },
  { name: 'a teleport cannot move a held player', run: teleportWhileHeld },
  { name: 'a held player cannot use items (snowball, milk)', run: itemUseWhileHeld },
  { name: 'a held player does not receive public chat', run: chatNotReceivedWhileHeld },
  { name: 'a later joiner cannot see a held player', run: lateJoinerCannotSeeHeld },
  { name: "a late Discord failure cannot free the next session", run: lateFailureCannotReleaseNextSession },
  { name: "other plugins cannot read a held player's chat", run: chatLeakToOtherPlugins },
];

/** The leak scenario needs a second plugin; build it with the Maven that built EasyGuard. */
function buildLeakProbe(javaBin) {
  const { spawnSync } = require('child_process');
  const jar = path.join(PROBE_DIR, 'target', 'LeakProbe.jar');
  // Always rebuild, so a changed probe is never tested from a stale jar; Maven skips it if unchanged.
  // The probe targets Java 25 like the plugin, so give Maven the same JDK the server runs on.
  const env = { ...process.env };
  const binary = path.isAbsolute(javaBin)
    ? javaBin
    : spawnSync('sh', ['-c', `command -v ${javaBin}`], { encoding: 'utf8' }).stdout.trim();
  if (binary) {
    const javaHome = path.dirname(path.dirname(fs.realpathSync(binary)));
    if (fs.existsSync(path.join(javaHome, 'bin', 'javac'))) env.JAVA_HOME = javaHome;
  }
  const build = spawnSync('mvn', ['-B', '-q', 'package'], { cwd: PROBE_DIR, encoding: 'utf8', env });
  if (build.status !== 0 || !fs.existsSync(jar)) {
    console.log(`probe    build failed:\n${(build.stdout || '') + (build.stderr || '')}`.trimEnd());
    return null;
  }
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

  const probeJar = buildLeakProbe(javaBin);
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
      // The server goes with the connection details so a player can read why it was dropped.
      connect: { host: '127.0.0.1', port: MC_PORT, server },
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
