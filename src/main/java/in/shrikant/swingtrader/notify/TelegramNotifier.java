package in.shrikant.swingtrader.notify;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Sends the daily summary to Shrikant's phone via a Telegram bot
 * (blueprint §8 Phase 3: "daily Telegram/email summary to yourself").
 *
 * Setup (one-time, ~2 minutes):
 *   1. In Telegram, talk to @BotFather → /newbot → copy the bot token.
 *   2. Send your new bot any message, then open
 *      https://api.telegram.org/bot{TOKEN}/getUpdates and read your chat id.
 *   3. Put telegram.bot_token and telegram.chat_id in config.properties.
 *
 * Unconfigured (blank token/chat) → {@link #isConfigured()} is false and
 * send() is a no-op returning false; the summary still prints to stdout.
 * Failures are printed, never thrown — a dead notifier must not kill the
 * trading cycle (the cycle's own logs remain the source of truth).
 */
public final class TelegramNotifier {

    private final String botToken;
    private final String chatId;
    private final HttpClient client;

    public TelegramNotifier(String botToken, String chatId) {
        this.botToken = botToken == null ? "" : botToken.trim();
        this.chatId = chatId == null ? "" : chatId.trim();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean isConfigured() {
        return !botToken.isEmpty() && !chatId.isEmpty();
    }

    /** Sends {@code text}; true on Telegram 200 OK. Never throws. */
    public boolean send(String text) {
        if (!isConfigured()) return false;
        try {
            String body = "chat_id=" + URLEncoder.encode(chatId, StandardCharsets.UTF_8)
                    + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("Telegram send failed: HTTP " + response.statusCode()
                        + " " + response.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("Telegram send failed: " + e.getMessage());
            return false;
        }
    }
}
