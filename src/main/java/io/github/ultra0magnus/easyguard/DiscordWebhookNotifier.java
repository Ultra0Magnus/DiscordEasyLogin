package io.github.ultra0magnus.easyguard;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

    private final URI endpoint;
    private final Duration timeout;
    private final String username;
    private final HttpClient client;

    public DiscordWebhookNotifier(URI endpoint, Duration timeout, String username) {
        this(endpoint, timeout, username, HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    DiscordWebhookNotifier(URI endpoint, Duration timeout, String username, HttpClient client) {
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.username = username;
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
            throw new IllegalArgumentException("discord.webhook_url is not a valid URL: " + e.getMessage());
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
     * @return true only if Discord accepted the message. A false result is what drives the
     *         fail-closed path, so it must never be optimistic.
     */
    public boolean send(String content) {
        String payload = buildPayload(content);
        try {
            HttpResponse<String> response = post(payload);
            if (response.statusCode() == 429) {
                Duration wait = parseRetryAfter(response.body());
                Thread.sleep(wait.toMillis());
                response = post(payload);
            }
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            return false;
        }
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

    private String buildPayload(String content) {
        return "{\"username\":\"" + escapeJson(username)
                + "\",\"content\":\"" + escapeJson(content)
                + "\",\"allowed_mentions\":{\"parse\":[]}}";
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
