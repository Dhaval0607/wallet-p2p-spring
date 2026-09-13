package com.dhaval.wallet.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Translates a libpq-style {@code DATABASE_URL} into the JDBC URL plus separate
 * credentials that Spring's DataSource expects.
 *
 * <p>Every managed Postgres host -- Render, Heroku, Neon, Railway, Fly -- hands out
 * {@code postgresql://user:password@host:port/dbname}. JDBC needs
 * {@code jdbc:postgresql://host:port/dbname} with username and password as
 * separate properties. Without this the app either fails to start on the platform
 * or forces someone to hand-assemble three environment variables out of one,
 * which is exactly the kind of deploy-time footgun that yields a green build and
 * a dead service.
 *
 * <p>Applied from {@code main} rather than as an {@code EnvironmentPostProcessor}:
 * the repackaged Boot jar hoists {@code META-INF/} to the archive root, off the
 * application classpath, so the {@code .imports} registration silently never
 * loads. Doing it explicitly is both more obvious and not subject to that.
 */
public final class DatabaseUrl {

    private DatabaseUrl() {}

    /**
     * Returns the Spring datasource properties implied by {@code raw}, or an empty
     * map when there is nothing to translate (absent, blank, or already JDBC).
     */
    public static Map<String, Object> translate(String raw) {
        Map<String, Object> properties = new HashMap<>();
        if (raw == null || raw.isBlank() || raw.startsWith("jdbc:")) {
            return properties;
        }
        if (!raw.startsWith("postgres://") && !raw.startsWith("postgresql://")) {
            return properties;
        }

        URI uri = URI.create(raw);
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");

        StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://")
                .append(uri.getHost()).append(':').append(port).append('/').append(database);

        // Preserve whatever query string the provider supplied (sslmode, options...).
        if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
            jdbcUrl.append('?').append(uri.getQuery());
        }
        properties.put("spring.datasource.url", jdbcUrl.toString());

        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                properties.put("spring.datasource.username", decode(userInfo.substring(0, colon)));
                properties.put("spring.datasource.password", decode(userInfo.substring(colon + 1)));
            } else {
                properties.put("spring.datasource.username", decode(userInfo));
            }
        }
        return properties;
    }

    /** Convenience for {@code main}: translate whatever is in the environment. */
    public static Map<String, Object> fromEnvironment() {
        return translate(System.getenv("DATABASE_URL"));
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
