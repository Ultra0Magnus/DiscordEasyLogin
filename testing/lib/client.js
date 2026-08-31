'use strict';

// A scripted Minecraft player. Everything a scenario needs to observe: what the server
// said, whether the player could move, and why they were disconnected.

const mineflayer = require('mineflayer');

const VERSION = process.env.EASYGUARD_TEST_MC_VERSION || '26.1.2';

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Polls until the predicate returns something truthy. Used instead of fixed sleeps so the
 * suite is not slower than it has to be on a fast machine, nor flaky on a slow one.
 */
async function waitFor(predicate, { timeout = 15000, interval = 100, describe = 'condition' } = {}) {
  const deadline = Date.now() + timeout;
  for (;;) {
    const value = await predicate();
    if (value) return value;
    if (Date.now() > deadline) {
      throw new Error(`timed out after ${timeout}ms waiting for ${describe}`);
    }
    await delay(interval);
  }
}

/**
 * Disconnect reasons arrive as chat components, and the shape varies: a plain string, JSON,
 * or — for kicks in the play phase on modern versions — NBT-style `{ type, value }` wrappers
 * around each field. Handle all of them rather than silently producing an empty reason.
 */
function flatten(reason) {
  if (reason == null) return '';
  if (typeof reason === 'string') {
    const trimmed = reason.trim();
    if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return reason;
    try {
      return flatten(JSON.parse(trimmed));
    } catch {
      return reason;
    }
  }
  if (typeof reason !== 'object') return String(reason);
  if (Array.isArray(reason)) return reason.map(flatten).join('');

  // NBT wrapper: the payload is under `value`, with no component fields of its own.
  if (reason.value !== undefined && reason.text === undefined && reason.translate === undefined) {
    return flatten(reason.value);
  }

  let text = flatten(reason.text ?? reason.translate ?? '');
  if (reason.with) text += flatten(reason.with);
  if (reason.extra) text += flatten(reason.extra);
  return text;
}

class Player {
  constructor(username, { host, port }) {
    this.username = username;
    this.chat = [];
    this.disconnect = null;
    this.rawDisconnect = null;
    this.ended = false;
    this.error = null;
    this.spawned = false;
    this.bot = mineflayer.createBot({
      host,
      port,
      username,
      version: VERSION,
      auth: 'offline',
      hideErrors: true,
    });
    this.bot.on('messagestr', (message) => this.chat.push(message));
    this.bot.once('spawn', () => {
      this.spawned = true;
    });
    this.bot.on('kicked', (reason) => {
      this.record(reason);
    });
    this.bot.on('end', (reason) => {
      this.record(reason);
    });
    this.bot.on('error', (err) => {
      this.error = this.error ?? err.message;
    });
  }

  /**
   * Remembers a disconnect. The raw payload is kept too: if a future protocol change breaks
   * the decoder, a scenario should fail with something inspectable rather than hang.
   */
  record(reason) {
    this.ended = true;
    this.rawDisconnect = this.rawDisconnect ?? reason;
    const text = flatten(reason);
    if (text && !this.disconnect) this.disconnect = text;
  }

  /** Resolves once the server has either let us in or thrown us out. */
  settled() {
    return waitFor(() => this.spawned || this.ended || this.error, {
      timeout: 30000,
      describe: `${this.username} to spawn or be disconnected`,
    });
  }

  saw(fragment) {
    return this.chat.some((message) => message.includes(fragment));
  }

  /** Waits with the player's own context attached, so a timeout is diagnosable. */
  async waitWithContext(predicate, options) {
    try {
      return await waitFor(predicate, options);
    } catch (err) {
      err.diagnostics = this.diagnostics();
      throw err;
    }
  }

  awaitChat(fragment, timeout = 15000) {
    return this.waitWithContext(() => this.chat.find((message) => message.includes(fragment)), {
      timeout,
      describe: `${this.username} to be told "${fragment}"`,
    });
  }

  async awaitDisconnect(timeout = 45000) {
    await this.waitWithContext(() => this.ended, {
      timeout,
      describe: `${this.username} to be disconnected`,
    });
    return this.disconnect || '(disconnected with no decodable reason)';
  }

  say(message) {
    this.bot.chat(message);
  }

  blockPosition() {
    const position = this.bot.entity.position;
    return {
      x: Math.floor(position.x),
      y: Math.floor(position.y),
      z: Math.floor(position.z),
    };
  }

  /**
   * Walks forward and reports which block the player ended up in. The gate deliberately
   * allows movement *within* a block and only teleports back on a block boundary, so the
   * block coordinate — not a distance threshold — is what says whether the freeze held.
   *
   * The fixed walk duration is unavoidable here: this measures the absence of movement,
   * and there is no event to await for something that must not happen.
   */
  async walkForward(ms = 2000) {
    const before = this.blockPosition();
    this.bot.setControlState('forward', true);
    await delay(ms);
    this.bot.setControlState('forward', false);
    await delay(400);
    const after = this.blockPosition();
    return {
      before,
      after,
      moved: before.x !== after.x || before.z !== after.z,
    };
  }

  quit() {
    this.bot.quit();
    return delay(500);
  }

  /** Everything worth printing when an assertion about this player fails. */
  diagnostics() {
    const lines = [`chat seen by ${this.username}:`];
    lines.push(...(this.chat.length ? this.chat.map((m) => `    ${m}`) : ['    (nothing)']));
    if (this.disconnect) lines.push(`  disconnect reason: ${this.disconnect.replace(/\n/g, ' / ')}`);
    else if (this.ended) lines.push(`  disconnected, raw reason: ${JSON.stringify(this.rawDisconnect)}`);
    if (this.error) lines.push(`  client error: ${this.error}`);
    return lines.join('\n');
  }
}

/** Connects and requires that the player actually gets in. */
async function join(username, options) {
  const player = new Player(username, options);
  await player.settled();
  if (!player.spawned) {
    const why = player.disconnect || player.error || 'unknown reason';
    throw new Error(`${username} could not join: ${why.replace(/\n/g, ' / ')}`);
  }
  return player;
}

/** Connects without requiring success — for the scenarios where a deny is the point. */
async function attemptJoin(username, options) {
  const player = new Player(username, options);
  await player.settled();
  return player;
}

module.exports = { Player, join, attemptJoin, waitFor, delay, flatten };
