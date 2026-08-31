'use strict';

// End-to-end test for EasyGuard. Boots a throwaway Paper server with the built plugin, points
// it at a local mock webhook, drives real Minecraft clients through every path the gate has —
// including the ones that lock the server down — and cleans up after itself.
//
// Usage:  npm install && npm run e2e        (add -- --keep to retain the scratch server)
//
// Nothing here touches a real server or the real Discord.

const fs = require('fs');
const path = require('path');

const { MockWebhook } = require('./lib/webhook');
const { PaperServer, resolveJava, ensurePaperJar, requireFreePort, MC_VERSION } = require('./lib/server');
const scenarios = require('./scenarios');

const ROOT = path.resolve(__dirname, '..');
const SERVER_DIR = path.join(__dirname, '.server');
const CACHE_DIR = path.join(__dirname, '.cache');

const MC_PORT = Number(process.env.EASYGUARD_TEST_PORT || 25599);
const WEBHOOK_PORT = Number(process.env.EASYGUARD_WEBHOOK_PORT || 8099);
const KEEP = process.argv.includes('--keep');
// Substring filter, for re-running one scenario while investigating a failure.
const ONLY = process.env.EASYGUARD_ONLY || '';

/**
 * Rate limiting is turned off and the verification timeout is shortened: the limiter has
 * deterministic unit-test coverage, and 30s is long enough that no scenario is racing it
 * while still bounding the timeout test. 30 is above the plugin's validated floor of 15.
 */
function goodConfig() {
  return [
    'enabled: true',
    'discord:',
    `  webhook_url: "http://127.0.0.1:${WEBHOOK_PORT}/hook"`,
    '  allow_non_discord_host: true',
    '  timeout_seconds: 2',
    'code:',
    '  cooldown_seconds: 0',
    '  max_per_minute: 500',
    'verification:',
    '  timeout_seconds: 30',
    'trust:',
    '  duration_hours: 24',
    'fallback:',
    '  log_code_to_console_on_failure: true',
    '',
  ].join('\n');
}

/** Unparseable on purpose: this is what a typo in the webhook URL looks like. */
function brokenConfig() {
  return ['enabled: true', 'discord:', '  webhook_url: "not a url at all"', ''].join('\n');
}

function findPluginJar() {
  const target = path.join(ROOT, 'target');
  const jars = fs.existsSync(target)
    ? fs
      .readdirSync(target)
      .filter((name) => /^EasyGuard-.*\.jar$/.test(name) && !name.includes('original'))
      .map((name) => path.join(target, name))
    : [];
  if (!jars.length) {
    throw new Error(
      'No built plugin found in target/.\n'
        + '  Fix: build it first — JAVA_HOME=/path/to/jdk-25 mvn -B package',
    );
  }
  return jars.sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs)[0];
}

function requireDependencies() {
  if (!fs.existsSync(path.join(__dirname, 'node_modules', 'mineflayer'))) {
    throw new Error('mineflayer is not installed.\n  Fix: run npm install in testing/');
  }
}

function formatDuration(ms) {
  return `${(ms / 1000).toFixed(1)}s`;
}

function report(results) {
  const width = Math.max(...results.map((r) => r.name.length));
  console.log(`\n${'='.repeat(width + 20)}`);
  for (const result of results) {
    const status = { passed: 'PASS', failed: 'FAIL', skipped: 'SKIP' }[result.status];
    console.log(`${status}  ${result.name.padEnd(width)}  ${formatDuration(result.ms)}`);
  }
  console.log('='.repeat(width + 20));

  const failures = results.filter((r) => r.status === 'failed');
  const skipped = results.filter((r) => r.status === 'skipped');
  for (const failure of failures) {
    console.log(`\n--- ${failure.name} ---\n${failure.error.message}`);
    if (failure.error.diagnostics) console.log(`\n${failure.error.diagnostics}`);
  }
  console.log(
    `\n${results.length - failures.length - skipped.length}/${results.length} scenarios passed`
      + `${failures.length ? `, ${failures.length} failed` : ''}`
      + `${skipped.length ? `, ${skipped.length} skipped` : ''}.`,
  );
  return failures.length === 0;
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
  const config = { good: goodConfig(), broken: brokenConfig() };
  const server = new PaperServer({ dir: SERVER_DIR, paperJar, javaBin, port: MC_PORT });
  server.writeBaseFiles();
  server.installPlugin(pluginJar);
  server.writePluginConfig(config.good);

  const webhook = new MockWebhook(WEBHOOK_PORT);

  // Signals and crashes must not leave a Java process holding the port.
  const emergencyStop = () => {
    server.kill();
    webhook.stop().catch(() => {});
  };
  process.on('SIGINT', () => {
    emergencyStop();
    process.exit(130);
  });
  process.on('SIGTERM', () => {
    emergencyStop();
    process.exit(143);
  });
  process.on('uncaughtException', (err) => {
    console.error('\nunexpected error:', err);
    emergencyStop();
    process.exit(2);
  });

  const results = [];
  try {
    await webhook.start();
    console.log(`webhook  http://127.0.0.1:${WEBHOOK_PORT}/hook (mock)\n`);
    console.log('starting the test server, this takes a moment...');
    await server.start();
    console.log(`server   up on 127.0.0.1:${MC_PORT}\n`);

    const context = {
      server,
      webhook,
      config,
      connect: { host: '127.0.0.1', port: MC_PORT },
    };

    const selected = ONLY
      ? scenarios.filter((scenario) => scenario.name.toLowerCase().includes(ONLY.toLowerCase()))
      : scenarios;
    if (!selected.length) {
      throw new Error(`EASYGUARD_ONLY="${ONLY}" matched no scenario.`);
    }

    for (const scenario of selected) {
      if (!server.running) {
        results.push({ ...scenario, status: 'skipped', ms: 0, error: null });
        continue;
      }
      const started = Date.now();
      process.stdout.write(`running: ${scenario.name} ... `);
      try {
        await scenario.run(context);
        console.log('ok');
        results.push({ ...scenario, status: 'passed', ms: Date.now() - started, error: null });
      } catch (err) {
        console.log('FAILED');
        err.diagnostics = [err.diagnostics, `last server log lines:\n${server.tail(30)}`]
          .filter(Boolean)
          .join('\n\n');
        results.push({ ...scenario, status: 'failed', ms: Date.now() - started, error: err });
      }
    }
  } finally {
    await server.stop().catch(() => server.kill());
    await webhook.stop().catch(() => {});
  }

  const passed = report(results);
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
