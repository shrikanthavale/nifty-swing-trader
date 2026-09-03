package com.shrikane.swingtrader.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Loads config/config.properties (which is gitignored — NEVER commit API
 * credentials to this public repo; copy config.properties.example and fill in).
 */
public record AppConfig(
        String kiteApiKey,
        String kiteApiSecret,
        String dbPath,
        double startingCapital,
        int kiteRedirectPort,
        String tokenPath,
        String telegramBotToken,
        String telegramChatId
) {
    public static AppConfig load(String path) throws IOException {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(path)) {
            p.load(in);
        }
        return new AppConfig(
                required(p, "kite.api_key"),
                required(p, "kite.api_secret"),
                p.getProperty("db.path", "swingtrader.db"),
                Double.parseDouble(p.getProperty("capital.starting", "100000")),
                Integer.parseInt(p.getProperty("kite.redirect_port", "5000")),
                p.getProperty("kite.token_path", "config/access_token.properties"),
                p.getProperty("telegram.bot_token", ""),
                p.getProperty("telegram.chat_id", "")
        );
    }

    private static String required(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required config key: " + key);
        }
        return v;
    }
}
