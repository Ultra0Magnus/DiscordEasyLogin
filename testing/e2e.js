'use strict';

// End-to-end test for EasyGuard. Boots a throwaway Paper server with the built plugin, points
// it at a local mock webhook, drives real Minecraft clients through every path the gate has —
// including the ones that lock the server down — and cleans up after itself.
//
// Usage:  npm install && npm run e2e        (add -- --keep to retain the scratch server)
//
// For the suite that tries to *defeat* the gate rather than exercise it, see adversarial.js.
// Nothing here touches a real server or the real Discord.

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
  brokenConfig,
  registerEmergencyStop,
  runScenarios,
  report,
} = require('./lib/harness');
const scenarios = require('./scenarios');

const SERVER_DIR = path.join(TESTING, '.server');
const CACHE_DIR = path.join(TESTING, '.cache');

const MC_PORT = Number(process.env.EASYGUARD_TEST_PORT || 25599);
const WEBHOOK_PORT = Number(process.env.EASYGUARD_WEBHOOK_PORT || 8099);
const KEEP = process.argv.includes('--keep');
const ONLY = process.env.EASYGUARD_ONLY || '';

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
  // The timeout scenario waits this out, so it is the one value tuned short here. 30 is above
  // the plugin's validated floor of 15, which leaves slack on a slow machine.
  const config = {
    good: pluginConfig({ webhookPort: WEBHOOK_PORT, verificationTimeout: 30 }),
    broken: brokenConfig(),
  };

  const server = new PaperServer({ dir: SERVER_DIR, paperJar, javaBin, port: MC_PORT });
  server.writeBaseFiles();
  server.installPlugin(pluginJar);
  const extra = server.installExtraPlugins(process.env.EASYGUARD_EXTRA_PLUGINS);
  if (extra.length) console.log(`extra    ${extra.join(', ')}`);
  server.writePluginConfig(config.good);

  const webhook = new MockWebhook(WEBHOOK_PORT);
  registerEmergencyStop(server, webhook);

  let results = [];
  try {
    await webhook.start();
    console.log(`webhook  http://127.0.0.1:${WEBHOOK_PORT}/hook (mock)\n`);
    console.log('starting the test server, this takes a moment...');
    await server.start();
    console.log(`server   up on 127.0.0.1:${MC_PORT}\n`);

    results = await runScenarios(scenarios, {
      server,
      webhook,
      config,
      // The server goes with the connection details so a player can read why it was dropped.
      connect: { host: '127.0.0.1', port: MC_PORT, server },
    }, { only: ONLY });
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
