package io.github.ultra0magnus.easyguard;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Posts messages to a Discord channel through an incoming webhook.
 * Blocking by design; callers must invoke {@link #send} off the server thread.
 */
public final class DiscordWebhookNotifier {

    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");
    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(5);

    /**
     * What happened to one message. {@code detail} explains a failure well enough to act on
     * ("HTTP 404" means the webhook was deleted) and never contains the webhook URL.
     */
    public record Delivery(boolean delivered, String detail) {

        static Delivery ok() {
            return new Delivery(true, "delivered");
        }

        static Delivery failed(String detail) {
            return new Delivery(false, detail);
        }
    }

    private final URI endpoint;
    private final Duration timeout;
    private final String username;
    private final List<String> pingUserIds;
    private final HttpClient client;

    public DiscordWebhookNotifier(URI endpoint, Duration timeout, String username, List<String> pingUserIds) {
        this(endpoint, timeout, username, pingUserIds, HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    DiscordWebhookNotifier(URI endpoint, Duration timeout, String username, List<String> pingUserIds,
                           HttpClient client) {
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.username = username;
        this.pingUserIds = List.copyOf(pingUserIds);
        this.client = client;
    }

    /**
     * Validates a configured webhook URL. Restricting the host by default means a typo cannot
     * quietly ship verification codes to somebody else's server.
     */
    public static URI validateEndpoint(String raw, boolean allowNonDiscordHost) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("discord.webhook_url is not set");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            // Never e.getMessage(): it echoes the whole input, and the input contains the webhook's
            // secret token. This message goes to the log and to whoever ran /easyguard reload.
            throw new IllegalArgumentException("discord.webhook_url is not a valid URL ("
                    + e.getReason() + (e.getIndex() >= 0 ? " at character " + (e.getIndex() + 1) : "")
                    + "); the URL itself is not shown because it contains a secret");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("discord.webhook_url has no host");
        }
        if (allowNonDiscordHost) {
            return uri;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("discord.webhook_url must use https");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        boolean discordHost = lower.equals("discord.com") || lower.equals("discordapp.com")
                || lower.endsWith(".discord.com") || lower.endsWith(".discordapp.com");
        if (!discordHost || !uri.getPath().startsWith("/api/webhooks/")) {
            throw new IllegalArgumentException("discord.webhook_url must look like "
                    + "https://discord.com/api/webhooks/... (set discord.allow_non_discord_host: true to override)");
        }
        return uri;
    }

    /**
     * @return delivered only if Discord accepted the message. A failure is what drives the
     *         fail-closed path, so it must never be optimistic.
     */
    public Delivery send(String content) {
        String payload = buildPayload(content);
        try {
            HttpResponse<String> response = post(payload);
            if (response.statusCode() == 429) {
                Duration wait = parseRetryAfter(response.body());
                Thread.sleep(wait.toMillis());
                response = post(payload);
            }
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return Delivery.ok();
            }
            return Delivery.failed(describeStatus(status));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Delivery.failed("interrupted");
        } catch (HttpTimeoutException e) {
            return Delivery.failed("Discord did not answer within " + timeout.toSeconds() + "s");
        } catch (IOException e) {
            // The class name says enough (ConnectException, UnknownHostException...) and, unlike
            // some messages, cannot contain the URL.
            return Delivery.failed("network error: " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            // Anything else must still come back as a failure, so the caller's fail-closed path and
            // console fallback run instead of the player silently waiting for the timeout.
            return Delivery.failed("unexpected error: " + e.getClass().getSimpleName());
        }
    }

    private static String describeStatus(int status) {
        return switch (status) {
            case 401, 403 -> "Discord returned HTTP " + status + " - the webhook token is wrong";
            case 404 -> "Discord returned HTTP 404 - the webhook was deleted; create a new one";
            case 429 -> "Discord returned HTTP 429 - rate limited even after waiting";
            default -> "Discord returned HTTP " + status;
        };
    }

    private HttpResponse<String> post(String payload) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "EasyGuard (Minecraft plugin)")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * {@code parse: []} means nothing in the text pings by itself - not {@code @everyone}, not a
     * role, not a user. Only the IDs listed in {@code users} do, and those come from validated
     * config, never from anything a player controls.
     */
    String buildPayload(String content) {
        StringBuilder text = new StringBuilder();
        StringBuilder users = new StringBuilder();
        for (String id : pingUserIds) {
            text.append("<@").append(id).append("> ");
            users.append(users.isEmpty() ? "" : ",").append('"').append(id).append('"');
        }
        text.append(content);
        return "{\"username\":\"" + escapeJson(username)
                + "\",\"content\":\"" + escapeJson(text.toString())
                + "\",\"allowed_mentions\":{\"parse\":[],\"users\":[" + users + "]}}";
    }

    private static Duration parseRetryAfter(String body) {
        if (body != null) {
            Matcher matcher = RETRY_AFTER.matcher(body);
            if (matcher.find()) {
                try {
                    long millis = (long) (Double.parseDouble(matcher.group(1)) * 1000);
                    return Duration.ofMillis(Math.clamp(millis, 250L, MAX_RETRY_WAIT.toMillis()));
                } catch (NumberFormatException ignored) {
                    // fall through to the default wait
                }
            }
        }
        return Duration.ofSeconds(1);
    }

    static String escapeJson(String input) {
        StringBuilder sb = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
