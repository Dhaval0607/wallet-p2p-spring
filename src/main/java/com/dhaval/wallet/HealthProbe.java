package com.dhaval.wallet;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Probes the local /healthz and reports the result through the exit code.
 *
 * <p>Invoked as {@code java -jar app.jar --healthcheck}. Having the application
 * be its own health probe means the Docker HEALTHCHECK does not depend on curl
 * or wget existing in the runtime image.
 */
final class HealthProbe {

    private HealthProbe() {}

    static int run() {
        String port = System.getenv().getOrDefault("PORT", "8080");
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build()) {

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz"))
                            .timeout(Duration.ofSeconds(3))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return 0;
            }
            System.err.println("unhealthy: healthz returned " + response.statusCode());
            return 1;
        } catch (Exception e) {
            System.err.println("unhealthy: " + e.getMessage());
            return 1;
        }
    }
}
