'use strict';

// One function per scenario. Each throws on the first failed expectation; the orchestrator
// catches it, prints the diagnostics attached to the error, and moves on.
//
// Every player gets their own username: trusted sessions are keyed by UUID, and in offline
// mode the UUID is derived from the name, so distinct names are independent identities. That
// keeps a verification in one scenario from silently letting a later scenario skip the gate,
// and sidesteps the per-player code cooldown. The trusted-rejoin scenario reuses a name on
// purpose — that is the behaviour under test.

const { join, attemptJoin, waitFor, delay } = require('./lib/client');

const PROMPT = 'A single-use code was sent to Discord';
const CODE_IN_CONSOLE = /\[EasyGuard\] Verification code for (\S+) is: ([A-Z0-9]+) \(valid (\d+) minute\(s\)\)/;

/**
 * Attaches the player's context to a failure so it can be diagnosed without a re-run. The
 * server log is appended centrally by the orchestrator, for every failure.
 */
function fail(message, { player } = {}) {
  const error = new Error(message);
  if (player) error.diagnostics = player.diagnostics();
  return error;
}

function expect(condition, message, context) {
  if (!condition) throw fail(message, context);
}

async function awaitCode(webhook, username, timeout = 15000) {
  return waitFor(() => webhook.codeFor(username), {
    timeout,
    describe: `a code for ${username} to reach the webhook`,
  });
}

/** A player who joins, gets a code, is frozen, verifies, and is released. */
async function happyPath({ server, webhook, connect }) {
  const player = await join('EGHappy', connect);
  try {
    const code = await awaitCode(webhook, 'EGHappy');
    await player.awaitChat(PROMPT);

    const held = await player.walkForward();
    expect(!held.moved, `EGHappy moved while unverified: ${JSON.stringify(held)}`, { player, server });

    player.say(code);
    await player.awaitChat('Verified. Welcome,');

    // The join announcement is suppressed until the player is actually in.
    expect(
      player.saw('EGHappy joined the game'),
      'the join message was never broadcast after verifying',
      { player, server },
    );

    const freed = await player.walkForward();
    expect(freed.moved, `EGHappy still could not move after verifying: ${JSON.stringify(freed)}`, {
      player,
      server,
    });
  } finally {
    await player.quit();
  }
}

/** Codes are upper-cased before comparison, so a lower-case paste must work. */
async function lowercaseCode({ server, webhook, connect }) {
  const player = await join('EGCase', connect);
  try {
    const code = await awaitCode(webhook, 'EGCase');
    player.say(code.toLowerCase());
    await player.awaitChat('Verified. Welcome,');
  } finally {
    await player.quit();
  }
}

/**
 * Wrong codes are counted down and then kicked, the attempt is never broadcast to chat, and
 * commands other than /verify are refused. The counter is read after the decrement, so with
 * max_attempts 3 the player sees two warnings and the third wrong code is the kick.
 */
async function wrongCodes({ server, webhook, connect }) {
  const player = await join('EGWrong', connect);
  await awaitCode(webhook, 'EGWrong');
  await player.awaitChat(PROMPT);

  player.say('WRONGCODE');
  await player.awaitChat('2 attempt(s) left.');

  expect(!player.saw('<EGWrong>'), 'a wrong code was broadcast to public chat', { player, server });

  player.say('/help');
  await player.awaitChat('Verify first: paste the code from Discord in chat.');

  player.say('ALSOWRONG');
  await player.awaitChat('1 attempt(s) left.');

  player.say('STILLWRONG');
  const reason = await player.awaitDisconnect(20000);
  expect(
    reason.includes('Too many wrong codes.'),
    `expected an out-of-attempts kick, got: ${reason.replace(/\n/g, ' / ')}`,
    { player, server },
  );
}

/** A verified player reconnecting inside the trust window needs no second code. */
async function trustedRejoin({ server, webhook, connect }) {
  const before = webhook.countFor('EGHappy');
  expect(before > 0, 'the happy-path scenario must run before the trusted-rejoin scenario', {
    server,
  });

  const player = await join('EGHappy', connect);
  try {
    // Nothing to await: this proves the absence of a second code and of a prompt.
    await delay(3000);
    expect(
      webhook.countFor('EGHappy') === before,
      `a trusted rejoin issued a new code (${before} -> ${webhook.countFor('EGHappy')})`,
      { player, server },
    );
    expect(!player.saw(PROMPT), 'a trusted rejoin was asked to verify again', { player, server });

    const walk = await player.walkForward();
    expect(walk.moved, 'a trusted player could not move', { player, server });
  } finally {
    await player.quit();
  }
}

/** An unanswered join cannot sit on the server forever. */
async function verificationTimeout({ server, webhook, connect }) {
  const player = await join('EGTimeout', connect);
  await awaitCode(webhook, 'EGTimeout');

  const reason = await player.awaitDisconnect(60000);
  expect(
    reason.includes('Verification timed out.'),
    `expected a timeout kick, got: ${reason.replace(/\n/g, ' / ')}`,
    { player, server },
  );
}

/**
 * The escape hatch that matters most: with Discord unreachable the player is held rather than
 * waved through, the code goes to the server console, and that console code still works.
 */
async function discordUnreachable({ server, webhook, connect }) {
  await webhook.stop();
  const from = server.mark();
  let player;
  try {
    player = await join('EGDown', connect);
    await player.awaitChat('Discord could not be reached.');

    const logged = await server.awaitLog(CODE_IN_CONSOLE, {
      from,
      timeout: 15000,
      describe: 'the verification code to be written to the server console',
    });
    const [, name, code] = logged.match(CODE_IN_CONSOLE);
    expect(name === 'EGDown', `console code was logged for ${name}, not EGDown`, { player, server });

    const held = await player.walkForward();
    expect(!held.moved, 'EGDown was released even though Discord was unreachable', {
      player,
      server,
    });

    player.say(code);
    await player.awaitChat('Verified. Welcome,');
  } finally {
    if (player) await player.quit();
    await webhook.start();
  }
}

/** A broken config must block the door, and a fixed one must reopen it without a restart. */
async function reloadLockdown({ server, connect, config }) {
  server.writePluginConfig(config.broken);
  try {
    await server.console('easyguard reload', /Reload failed, server is now locked down/);

    const denied = await attemptJoin('EGLockReload', connect);
    expect(!denied.spawned, 'a player joined a locked-down server', { player: denied, server });
    expect(
      (denied.disconnect || '').includes('This server is locked down.'),
      `unexpected lockdown reason: ${(denied.disconnect || '(none)').replace(/\n/g, ' / ')}`,
      { player: denied, server },
    );
  } finally {
    server.writePluginConfig(config.good);
    await server.console('easyguard reload', /EasyGuard reloaded\./);
  }

  const player = await join('EGUnlocked', connect);
  await player.quit();
}

/**
 * The worst case: the plugin cannot configure itself at boot. Paper would normally disable a
 * failing plugin and leave the server wide open, so this asserts the opposite — and that the
 * deny message is the "no config ever loaded" variant, which is a different string.
 */
async function startupLockdown({ server, connect, config }) {
  await server.restartWith(config.broken, { expectLockdown: true });

  expect(
    server.matchLog(/EasyGuard could not start: /) !== null,
    'the startup failure was not logged',
    { server },
  );
  expect(
    server.matchLog(/The server is now LOCKED DOWN - all logins are denied until this is fixed\./) !== null,
    'the lockdown warning was not logged',
    { server },
  );

  const denied = await attemptJoin('EGLockBoot', connect);
  expect(!denied.spawned, 'a player joined a server whose gate failed to start', {
    player: denied,
    server,
  });
  expect(
    (denied.disconnect || '').includes('This server is locked down: EasyGuard could not start.'),
    `unexpected startup lockdown reason: ${(denied.disconnect || '(none)').replace(/\n/g, ' / ')}`,
    { player: denied, server },
  );
}

/** And the answer to "can I get back in": fix the config, restart, play. */
async function recovery({ server, webhook, connect, config }) {
  await server.restartWith(config.good);

  const player = await join('EGRecovered', connect);
  try {
    const code = await awaitCode(webhook, 'EGRecovered');
    player.say(code);
    await player.awaitChat('Verified. Welcome,');
  } finally {
    await player.quit();
  }
}

// Order matters: the restart-based scenarios come last, and trustedRejoin depends on
// happyPath having verified the same username.
module.exports = [
  { name: 'happy path: code, freeze, verify, release', run: happyPath },
  { name: 'lower-case code is accepted', run: lowercaseCode },
  { name: 'wrong codes: counted down, never broadcast, then kicked', run: wrongCodes },
  { name: 'trusted rejoin issues no second code', run: trustedRejoin },
  { name: 'unanswered join is kicked on timeout', run: verificationTimeout },
  { name: 'Discord unreachable: held, code to console, console code works', run: discordUnreachable },
  { name: 'broken config on reload locks the door, a fix reopens it', run: reloadLockdown },
  { name: 'broken config at startup denies every login', run: startupLockdown },
  { name: 'recovery: fixed config restores normal joins', run: recovery },
];
