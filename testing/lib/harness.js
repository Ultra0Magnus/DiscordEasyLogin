'use strict';

// Shared plumbing for the two suites: prerequisites, the plugin config the test server runs,
// the scenario loop, and the result table.

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const TESTING = path.resolve(__dirname, '..');

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
  if (!fs.existsSync(path.join(TESTING, 'node_modules', 'mineflayer'))) {
    throw new Error('mineflayer is not installed.\n  Fix: run npm install in testing/');
  }
}

/**
 * The plugin config the test server runs. Defaults suit a suite driving many joins quickly:
 * rate limiting off (it has deterministic unit coverage and would otherwise interfere) and a
 * verification timeout well clear of any scenario. Individual scenarios override what they test.
 */
function pluginConfig({
  webhookPort,
  enabled = true,
  webhookUrl = null,
  ttlSeconds = 300,
  maxAttempts = 3,
  cooldownSeconds = 0,
  maxPerMinute = 500,
  verificationTimeout = 120,
  trustHours = 24,
  logCodeToConsole = true,
  discordTimeout = 2,
} = {}) {
  return [
    `enabled: ${enabled}`,
    'discord:',
    `  webhook_url: "${webhookUrl ?? `http://127.0.0.1:${webhookPort}/hook`}"`,
    '  allow_non_discord_host: true',
    `  timeout_seconds: ${discordTimeout}`,
    'code:',
    `  length: 8`,
    `  ttl_seconds: ${ttlSeconds}`,
    `  max_attempts: ${maxAttempts}`,
    `  cooldown_seconds: ${cooldownSeconds}`,
    `  max_per_minute: ${maxPerMinute}`,
    'verification:',
    `  timeout_seconds: ${verificationTimeout}`,
    'trust:',
    `  duration_hours: ${trustHours}`,
    'fallback:',
    `  log_code_to_console_on_failure: ${logCodeToConsole}`,
    '',
  ].join('\n');
}

/** Unparseable on purpose: this is what a typo in the webhook URL looks like. */
function brokenConfig() {
  return ['enabled: true', 'discord:', '  webhook_url: "not a url at all"', ''].join('\n');
}

/** Signals and crashes must not leave a Java process holding the port. */
function registerEmergencyStop(server, webhook) {
  const stop = () => {
    server.kill();
    webhook.stop().catch(() => {});
  };
  process.on('SIGINT', () => {
    stop();
    process.exit(130);
  });
  process.on('SIGTERM', () => {
    stop();
    process.exit(143);
  });
  process.on('uncaughtException', (err) => {
    console.error('\nunexpected error:', err);
    stop();
    process.exit(2);
  });
}

/**
 * Runs scenarios in order, one server for all of them. A scenario that throws is recorded and
 * the run continues: usernames are per-scenario, so one failure does not cascade.
 */
async function runScenarios(scenarios, context, { only = '' } = {}) {
  const selected = only
    ? scenarios.filter((scenario) => scenario.name.toLowerCase().includes(only.toLowerCase()))
    : scenarios;
  if (!selected.length) throw new Error(`EASYGUARD_ONLY="${only}" matched no scenario.`);

  const results = [];
  for (const scenario of selected) {
    if (!context.server.running) {
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
      err.diagnostics = [err.diagnostics, `last server log lines:\n${context.server.tail(30)}`]
        .filter(Boolean)
        .join('\n\n');
      results.push({ ...scenario, status: 'failed', ms: Date.now() - started, error: err });
    }
  }
  return results;
}

function report(results, { notes = [] } = {}) {
  const width = Math.max(...results.map((result) => result.name.length));
  console.log(`\n${'='.repeat(width + 20)}`);
  for (const result of results) {
    const status = { passed: 'PASS', failed: 'FAIL', skipped: 'SKIP' }[result.status];
    console.log(`${status}  ${result.name.padEnd(width)}  ${(result.ms / 1000).toFixed(1)}s`);
  }
  console.log('='.repeat(width + 20));

  const failures = results.filter((result) => result.status === 'failed');
  const skipped = results.filter((result) => result.status === 'skipped');
  for (const failure of failures) {
    console.log(`\n--- ${failure.name} ---\n${failure.error.message}`);
    if (failure.error.diagnostics) console.log(`\n${failure.error.diagnostics}`);
  }

  if (notes.length) {
    console.log('\nBehaviour worth knowing about:');
    for (const note of notes) console.log(`  NOTE: ${note}`);
  }

  console.log(
    `\n${results.length - failures.length - skipped.length}/${results.length} scenarios passed`
      + `${failures.length ? `, ${failures.length} failed` : ''}`
      + `${skipped.length ? `, ${skipped.length} skipped` : ''}.`,
  );
  return failures.length === 0;
}

module.exports = {
  ROOT,
  TESTING,
  findPluginJar,
  requireDependencies,
  pluginConfig,
  brokenConfig,
  registerEmergencyStop,
  runScenarios,
  report,
};
