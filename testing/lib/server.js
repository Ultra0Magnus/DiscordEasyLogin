'use strict';

// Owns the throwaway Paper server: fetching it, configuring it, running it, talking to its
// console, and — most importantly — making sure it is dead when we are done. An orphaned
// server holding the port would poison every later run.

const { spawn, spawnSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const net = require('net');
const path = require('path');
const { Readable } = require('stream');
const { pipeline } = require('stream/promises');

const { waitFor, delay } = require('./client');

const MC_VERSION = process.env.DISCORDEASYLOGIN_TEST_MC_VERSION || '26.1.2';
const FILL_API = `https://fill.papermc.io/v3/projects/paper/versions/${MC_VERSION}/builds/latest`;
const MIN_JAVA = 25;
const USER_AGENT = 'Ultra0Magnus/discordeasylogin-tests (github.com/Ultra0Magnus/DiscordEasyLogin)';

const stripColor = (line) => line.replace(/\u001b\[[0-9;]*m/g, '');

/**
 * Paper 26.1 refuses to start on anything below Java 25, and the system default is often
 * older, so resolve it explicitly rather than letting the server fail with a stack trace.
 */
function resolveJava() {
  const candidates = [
    process.env.DISCORDEASYLOGIN_JAVA,
    process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, 'bin', 'java'),
    'java',
  ].filter(Boolean);

  const tried = [];
  for (const bin of candidates) {
    const probe = spawnSync(bin, ['-version'], { encoding: 'utf8' });
    if (probe.error) {
      tried.push(`${bin} — not runnable`);
      continue;
    }
    const major = Number(`${probe.stderr}${probe.stdout}`.match(/version "(\d+)/)?.[1]);
    tried.push(`${bin} — Java ${major || 'unknown'}`);
    if (major >= MIN_JAVA) return bin;
  }

  throw new Error(
    `Paper ${MC_VERSION} needs Java ${MIN_JAVA} or newer, and none was found.\n`
      + `  tried: ${tried.join('\n         ')}\n`
      + '  Fix: install a JDK 25 and set DISCORDEASYLOGIN_JAVA=/path/to/jdk-25/bin/java',
  );
}

function portFree(port) {
  return new Promise((resolve) => {
    const probe = net.createServer();
    probe.once('error', () => resolve(false));
    probe.once('listening', () => probe.close(() => resolve(true)));
    probe.listen(port, '127.0.0.1');
  });
}

async function requireFreePort(port, label, envVar) {
  if (!(await portFree(port))) {
    throw new Error(
      `${label} port ${port} is already in use.\n`
        + `  Fix: stop whatever is listening on it, or set ${envVar} to a free port.`,
    );
  }
}

function digest(file, algorithm) {
  return crypto.createHash(algorithm).update(fs.readFileSync(file)).digest('hex');
}

/** Downloads to the cache unless a copy with the right checksum is already there. */
async function downloadVerified(url, target, algorithm, expected, label) {
  if (fs.existsSync(target) && digest(target, algorithm) === expected) return target;
  const partial = `${target}.part`;
  const response = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!response.ok) throw new Error(`${label} download failed with HTTP ${response.status}`);
  await pipeline(Readable.fromWeb(response.body), fs.createWriteStream(partial));
  const actual = digest(partial, algorithm);
  if (actual !== expected) {
    fs.rmSync(partial, { force: true });
    throw new Error(`${label} download is corrupt: expected ${algorithm} ${expected}, got ${actual}`);
  }
  fs.renameSync(partial, target);
  return target;
}

/**
 * Downloads Paper, verifying the checksum the API hands us. The download URLs are
 * content-addressed and cannot be constructed by hand, so the metadata call is required.
 */
async function ensurePaperJar(cacheDir) {
  const override = process.env.DISCORDEASYLOGIN_PAPER_JAR;
  if (override) {
    if (!fs.existsSync(override)) {
      throw new Error(`DISCORDEASYLOGIN_PAPER_JAR points at ${override}, which does not exist.`);
    }
    return override;
  }

  fs.mkdirSync(cacheDir, { recursive: true });

  let download;
  try {
    const response = await fetch(FILL_API);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    download = (await response.json()).downloads['server:default'];
  } catch (err) {
    throw new Error(
      `Could not reach the Paper download API (${err.message}).\n`
        + '  Fix: check your network, or download paper yourself and set '
        + 'DISCORDEASYLOGIN_PAPER_JAR=/path/to/paper.jar',
    );
  }

  return downloadVerified(download.url, path.join(cacheDir, download.name), 'sha256',
    download.checksums.sha256, 'Paper');
}

/**
 * The scripted client only speaks the protocol versions its library knows, which trails new
 * Minecraft releases. To test the plugin on a newer server anyway, put ViaVersion and ViaBackwards
 * on the test server: they translate the older client's connection, while everything the plugin
 * does still runs on the newer server's real code. Fetched from Modrinth, release builds only, and
 * checked against the SHA-512 Modrinth publishes.
 */
async function ensureProtocolBridge(cacheDir) {
  fs.mkdirSync(cacheDir, { recursive: true });
  const jars = [];
  for (const project of ['viaversion', 'viabackwards']) {
    const query = `loaders=${encodeURIComponent('["paper"]')}&game_versions=${encodeURIComponent(`["${MC_VERSION}"]`)}`;
    let file;
    try {
      const response = await fetch(`https://api.modrinth.com/v2/project/${project}/version?${query}`,
        { headers: { 'User-Agent': USER_AGENT } });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const release = (await response.json()).find((version) => version.version_type === 'release');
      if (!release) throw new Error(`no release of ${project} supports Paper ${MC_VERSION} yet`);
      file = release.files.find((candidate) => candidate.primary) || release.files[0];
    } catch (err) {
      throw new Error(
        `Could not get ${project} for Paper ${MC_VERSION} (${err.message}).\n`
          + `  It is needed because the test client cannot speak ${MC_VERSION} directly.`,
      );
    }
    jars.push(await downloadVerified(file.url, path.join(cacheDir, file.filename), 'sha512',
      file.hashes.sha512, project));
  }
  return jars;
}

class PaperServer {
  constructor({ dir, paperJar, javaBin, port }) {
    this.dir = dir;
    this.paperJar = paperJar;
    this.javaBin = javaBin;
    this.port = port;
    this.log = [];
    this.proc = null;
    this.exited = null;
  }

  writeBaseFiles() {
    fs.mkdirSync(path.join(this.dir, 'plugins'), { recursive: true });
    fs.writeFileSync(path.join(this.dir, 'eula.txt'), 'eula=true\n');
    // offline-mode so scripted clients can log in; a flat world with no spawn protection so
    // the movement checks are not fighting terrain or the vanilla spawn radius.
    fs.writeFileSync(
      path.join(this.dir, 'server.properties'),
      [
        `server-port=${this.port}`,
        'online-mode=false',
        'white-list=false',
        'level-type=minecraft\\:flat',
        'spawn-protection=0',
        'max-players=20',
        'view-distance=4',
        'simulation-distance=4',
        'motd=DiscordEasyLogin end-to-end test',
        '',
      ].join('\n'),
    );
  }

  installPlugin(pluginJar) {
    fs.mkdirSync(path.join(this.dir, 'plugins'), { recursive: true });
    fs.copyFileSync(pluginJar, path.join(this.dir, 'plugins', path.basename(pluginJar)));
  }

  /**
   * Copies extra plugin jars in. The point of the suites is what happens beside your *other*
   * plugins, which a clean server never shows: point DISCORDEASYLOGIN_EXTRA_PLUGINS at a directory of
   * jars (or set a single jar path) to reproduce a real plugin set.
   */
  installExtraPlugins(source) {
    if (!source) return [];
    if (!fs.existsSync(source)) {
      throw new Error(`DISCORDEASYLOGIN_EXTRA_PLUGINS points at ${source}, which does not exist.`);
    }
    const jars = fs.statSync(source).isDirectory()
      ? fs.readdirSync(source).filter((name) => name.endsWith('.jar')).map((n) => path.join(source, n))
      : [source];
    const plugins = path.join(this.dir, 'plugins');
    fs.mkdirSync(plugins, { recursive: true });
    for (const jar of jars) {
      fs.copyFileSync(jar, path.join(plugins, path.basename(jar)));
    }
    return jars.map((jar) => path.basename(jar));
  }

  writePluginConfig(yaml) {
    const folder = path.join(this.dir, 'plugins', 'DiscordEasyLogin');
    fs.mkdirSync(folder, { recursive: true });
    fs.writeFileSync(path.join(folder, 'config.yml'), yaml);
  }

  /** Rewrites one key in server.properties. Only takes effect on the next boot. */
  setProperty(key, value) {
    const file = path.join(this.dir, 'server.properties');
    const lines = fs.readFileSync(file, 'utf8').split('\n');
    const index = lines.findIndex((line) => line.startsWith(`${key}=`));
    if (index === -1) lines.unshift(`${key}=${value}`);
    else lines[index] = `${key}=${value}`;
    fs.writeFileSync(file, lines.join('\n'));
  }

  clearTrustedSessions() {
    fs.rmSync(path.join(this.dir, 'plugins', 'DiscordEasyLogin', 'sessions.properties'), { force: true });
  }

  async start({ expectLockdown = false } = {}) {
    this.log.length = 0;
    this.exitCode = undefined;
    this.proc = spawn(
      this.javaBin,
      ['-Xms512M', '-Xmx1G', '-jar', this.paperJar, '--nogui'],
      { cwd: this.dir, stdio: ['pipe', 'pipe', 'pipe'] },
    );

    const collect = (chunk) => {
      for (const line of String(chunk).split('\n')) {
        if (line.trim()) this.log.push(stripColor(line.trimEnd()));
      }
    };
    this.proc.stdout.on('data', collect);
    this.proc.stderr.on('data', collect);

    this.exited = new Promise((resolve) => {
      this.proc.once('exit', (code) => {
        this.exitCode = code;
        resolve(code);
      });
    });

    await this.awaitLog(/Done \(/, { timeout: 240000, describe: 'the server to finish starting' });
    const expected = expectLockdown ? /LOCKED DOWN/ : /DiscordEasyLogin active: codes go to/;
    await this.awaitLog(expected, {
      timeout: 15000,
      describe: `DiscordEasyLogin to report ${expectLockdown ? 'lockdown' : 'that it is active'}`,
      from: 0,
    });
  }

  get running() {
    return this.proc !== null && this.exitCode === undefined;
  }

  /** Index to pass as `from` so a later awaitLog only sees lines produced after this point. */
  mark() {
    return this.log.length;
  }

  awaitLog(pattern, { timeout = 15000, from = this.log.length, describe } = {}) {
    return waitFor(() => this.log.slice(from).find((line) => pattern.test(line)), {
      timeout,
      describe: describe || `server log to match ${pattern}`,
    });
  }

  matchLog(pattern, from = 0) {
    for (const line of this.log.slice(from)) {
      const match = line.match(pattern);
      if (match) return match;
    }
    return null;
  }

  send(command) {
    if (!this.proc) throw new Error('cannot send a console command: the server is not running');
    this.proc.stdin.write(`${command}\n`);
  }

  /** Runs a console command and waits for the line it is expected to produce. */
  async console(command, pattern, timeout = 15000) {
    const from = this.mark();
    this.send(command);
    return this.awaitLog(pattern, { from, timeout, describe: `"${command}" to report ${pattern}` });
  }

  tail(lines = 40) {
    return this.log.slice(-lines).join('\n');
  }

  async stop() {
    if (!this.proc) return;
    const proc = this.proc;
    try {
      proc.stdin.write('stop\n');
    } catch {
      // Already gone; the kill below is the backstop.
    }
    const forceKill = setTimeout(() => proc.kill('SIGKILL'), 30000);
    await this.exited;
    clearTimeout(forceKill);
    this.proc = null;
    await delay(500);
  }

  /** Last-resort synchronous cleanup for signal handlers. */
  kill() {
    if (!this.proc) return;
    try {
      this.proc.kill('SIGKILL');
    } catch {
      // Nothing further we can do.
    }
    this.proc = null;
  }

  /** Stop, swap in a different plugin config, start again. */
  async restartWith(yaml, options) {
    await this.stop();
    this.writePluginConfig(yaml);
    await this.start(options);
  }
}

module.exports = {
  PaperServer, resolveJava, ensurePaperJar, ensureProtocolBridge, requireFreePort, MC_VERSION,
};
