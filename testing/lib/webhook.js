'use strict';

// Stands in for the Discord webhook so the tests never post to a real channel.
// Stopping it is how the "Discord is down" scenario simulates an outage.

const http = require('http');

const PLAYER = /Player: `([^`]+)`/;
const CODE = /Code: \*\*([A-Z0-9]+)\*\*/;

class MockWebhook {
  constructor(port) {
    this.port = port;
    this.payloads = [];
    this.server = null;
  }

  start() {
    return new Promise((resolve, reject) => {
      const server = http.createServer((req, res) => {
        let body = '';
        req.on('data', (chunk) => (body += chunk));
        req.on('end', () => {
          this.record(body);
          res.writeHead(204);
          res.end();
        });
      });
      server.once('error', reject);
      server.listen(this.port, '127.0.0.1', () => {
        this.server = server;
        resolve(this);
      });
    });
  }

  record(body) {
    let content = body;
    try {
      content = JSON.parse(body).content ?? body;
    } catch {
      // Keep the raw body: a payload we cannot parse is still evidence something was sent.
    }
    this.payloads.push({
      content,
      player: content.match(PLAYER)?.[1] ?? null,
      code: content.match(CODE)?.[1] ?? null,
    });
  }

  /** The most recent code issued to a player, or null if none was delivered. */
  codeFor(player) {
    for (let i = this.payloads.length - 1; i >= 0; i -= 1) {
      const payload = this.payloads[i];
      if (payload.player === player && payload.code) return payload.code;
    }
    return null;
  }

  countFor(player) {
    return this.payloads.filter((payload) => payload.player === player).length;
  }

  stop() {
    return new Promise((resolve) => {
      const server = this.server;
      if (!server) return resolve();
      this.server = null;
      server.close(() => resolve());
      server.closeAllConnections?.();
    });
  }

  get running() {
    return this.server !== null;
  }
}

module.exports = { MockWebhook };
