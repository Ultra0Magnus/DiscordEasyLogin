package io.github.ultra0magnus.discordeasylogin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ultra0magnus.discordeasylogin.DiscordWebhookNotifier.Delivery;
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

    private static final String SECRET = "SECRET-TOKEN-do-not-log";

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
            exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                exchange.getResponseBody().write(payload);
            }
            exchange.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private DiscordWebhookNotifier notifier(URI endpoint) {
        return notifier(endpoint, List.of());
    }

    private DiscordWebhookNotifier notifier(URI endpoint, List<String> pingUserIds) {
        return new DiscordWebhookNotifier(endpoint, Duration.ofSeconds(3), "DiscordEasyLogin", pingUserIds);
    }

    @Test
    void postsTheCodeAndReportsSuccess() throws IOException {
        URI endpoint = start(exchange -> respond(exchange, 204, ""));

        assertTrue(notifier(endpoint).send("Code: **ABCD1234**").delivered());
        assertEquals(1, bodies.size());
        String body = bodies.getFirst();
        assertTrue(body.contains("ABCD1234"), body);
        assertTrue(body.contains("\"username\":\"DiscordEasyLogin\""), body);
        assertTrue(body.contains("\"allowed_mentions\":{\"parse\":[],\"users\":[]}"), body);
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

        assertTrue(notifier(endpoint).send("Code: **ABCD1234**").delivered());
        assertEquals(2, calls.get());
    }

    @Test
    void reportsFailureOnServerError() throws IOException {
        URI endpoint = start(exchange -> respond(exchange, 500, "boom"));
        Delivery delivery = notifier(endpoint).send("Code: **ABCD1234**");
        assertFalse(delivery.delivered());
        assertTrue(delivery.detail().contains("500"), delivery.detail());
    }

    @Test
    void explainsADeletedWebhook() throws IOException {
        // Without the reason, "could not deliver" sends the owner looking at their network.
        URI endpoint = start(exchange -> respond(exchange, 404, "{\"message\": \"Unknown Webhook\"}"));
        Delivery delivery = notifier(endpoint).send("Code: **ABCD1234**");
        assertFalse(delivery.delivered());
        assertTrue(delivery.detail().contains("deleted"), delivery.detail());
    }

    @Test
    void reportsFailureWhenNothingIsListening() {
        // Fail-closed depends on this reporting failure rather than throwing.
        DiscordWebhookNotifier notifier = new DiscordWebhookNotifier(
                URI.create("http://127.0.0.1:1/hook"), Duration.ofMillis(500), "DiscordEasyLogin", List.of());
        Delivery delivery = notifier.send("Code: **ABCD1234**");
        assertFalse(delivery.delivered());
        assertFalse(delivery.detail().contains("127.0.0.1:1/hook"), delivery.detail());
    }

    @Test
    void pingsOnlyTheConfiguredUsers() throws IOException {
        URI endpoint = start(exchange -> respond(exchange, 204, ""));
        String owner = "123456789012345678";

        notifier(endpoint, List.of(owner)).send("Code: **ABCD1234**");

        String body = bodies.getFirst();
        assertTrue(body.contains("\"content\":\"<@" + owner + "> Code"), body);
        assertTrue(body.contains("\"allowed_mentions\":{\"parse\":[],\"users\":[\"" + owner + "\"]}"), body);
    }

    @Test
    void neverPingsEveryoneWhateverTheTextSays() {
        // parse:[] is what makes "@everyone" in a template or prefix inert; only listed users ping.
        String payload = new DiscordWebhookNotifier(URI.create("http://127.0.0.1/hook"), Duration.ofSeconds(1),
                "DiscordEasyLogin", List.of("123456789012345678")).buildPayload("@everyone @here <@&999> join request");
        assertTrue(payload.contains("\"parse\":[]"), payload);
        assertFalse(payload.contains("\"everyone\""), payload);
        assertFalse(payload.contains("\"roles\""), payload);
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
    void neverEchoesTheWebhookSecretInErrors() {
        // Regression: URISyntaxException's message repeats its whole input, token included, and
        // that message went to the log and to whoever ran /discordeasylogin reload.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DiscordWebhookNotifier.validateEndpoint(
                        "https://discord.com/api/webhooks/123456/" + SECRET + "|typo", false));
        assertFalse(error.getMessage().contains(SECRET), error.getMessage());
        assertTrue(error.getMessage().contains("not a valid URL"), error.getMessage());
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
