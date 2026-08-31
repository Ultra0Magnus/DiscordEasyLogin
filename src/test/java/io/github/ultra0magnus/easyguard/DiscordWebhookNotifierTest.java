package io.github.ultra0magnus.easyguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DiscordWebhookNotifierTest {

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI start(Consumer<HttpExchange> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            handler.accept(exchange);
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private DiscordWebhookNotifier notifier(URI endpoint) {
        return new DiscordWebhookNotifier(endpoint, Duration.ofSeconds(3), "EasyGuard");
    }

    @Test
    void postsTheCodeAndReportsSuccess() throws IOException {
        URI endpoint = start(exchange -> respond(exchange, 204, ""));

        assertTrue(notifier(endpoint).send("Code: **ABCD1234**"));
        assertEquals(1, bodies.size());
        String body = bodies.getFirst();
        assertTrue(body.contains("ABCD1234"), body);
        assertTrue(body.contains("\"username\":\"EasyGuard\""), body);
        assertTrue(body.contains("\"allowed_mentions\":{\"parse\":[]}"), body);
    }

    @Test
    void retriesOnceWhenRateLimited() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        URI endpoint = start(exchange -> {
            if (calls.getAndIncrement() == 0) {
                respond(exchange, 429, "{\"retry_after\": 0.25}");
            } else {
                respond(exchange, 204, "");
            }
        });

        assertTrue(notifier(endpoint).send("Code: **ABCD1234**"));
        assertEquals(2, calls.get());
    }

    @Test
    void reportsFailureOnServerError() throws IOException {
        URI endpoint = start(exchange -> respond(exchange, 500, "boom"));
        assertFalse(notifier(endpoint).send("Code: **ABCD1234**"));
    }

    @Test
    void reportsFailureWhenNothingIsListening() {
        // Fail-closed depends on this returning false rather than throwing.
        DiscordWebhookNotifier notifier = new DiscordWebhookNotifier(
                URI.create("http://127.0.0.1:1/hook"), Duration.ofMillis(500), "EasyGuard");
        assertFalse(notifier.send("Code: **ABCD1234**"));
    }

    @Test
    void acceptsRealDiscordWebhookUrls() {
        assertEquals("discord.com",
                DiscordWebhookNotifier.validateEndpoint("https://discord.com/api/webhooks/123/abc", false).getHost());
    }

    @Test
    void rejectsUrlsThatCouldLeakCodesElsewhere() {
        assertThrows(IllegalArgumentException.class,
                () -> DiscordWebhookNotifier.validateEndpoint("", false));
        assertThrows(IllegalArgumentException.class,
                () -> DiscordWebhookNotifier.validateEndpoint("https://evil.example/api/webhooks/1/2", false));
        assertThrows(IllegalArgumentException.class,
                () -> DiscordWebhookNotifier.validateEndpoint("http://discord.com/api/webhooks/1/2", false));
        assertThrows(IllegalArgumentException.class,
                () -> DiscordWebhookNotifier.validateEndpoint("https://discord.com/api/channels/1", false));
    }

    @Test
    void allowsOtherHostsOnlyWhenExplicitlyOptedIn() {
        assertEquals("localhost",
                DiscordWebhookNotifier.validateEndpoint("http://localhost:8080/hook", true).getHost());
    }

    @Test
    void escapesJsonSpecialCharacters() {
        assertEquals("a\\\"b", DiscordWebhookNotifier.escapeJson("a\"b"));
        assertEquals("a\\nb", DiscordWebhookNotifier.escapeJson("a\nb"));
        assertEquals("a\\\\b", DiscordWebhookNotifier.escapeJson("a\\b"));
        assertEquals("\\u0001", DiscordWebhookNotifier.escapeJson("\u0001"));
    }
}
