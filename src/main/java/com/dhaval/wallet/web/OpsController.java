package com.dhaval.wallet.web;

import com.dhaval.wallet.obs.DomainMetrics;
import com.dhaval.wallet.obs.LogRing;
import com.dhaval.wallet.store.Invariants;
import com.dhaval.wallet.store.Money.Wallet;
import com.dhaval.wallet.store.WalletRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operations surface: health, the live invariant audit, test funding, and the
 * public log stream.
 */
@RestController
public class OpsController {

    private static final Logger log = LoggerFactory.getLogger(OpsController.class);

    private final WalletRepository wallets;
    private final Auth auth;
    private final DomainMetrics metrics;
    private final DataSource dataSource;
    private final ObjectMapper json;
    private final String adminToken;
    private final String version;
    private final Instant startedAt = Instant.now();

    public OpsController(WalletRepository wallets, Auth auth, DomainMetrics metrics,
                         DataSource dataSource, ObjectMapper json,
                         @Value("${app.admin-token}") String adminToken,
                         @Value("${app.version}") String version) {
        this.wallets = wallets;
        this.auth = auth;
        this.metrics = metrics;
        this.dataSource = dataSource;
        this.json = json;
        this.adminToken = adminToken;
        this.version = version;
    }

    // ------------------------------------------------------------------
    // health
    // ------------------------------------------------------------------

    /**
     * Liveness: the process is up. Deliberately does NOT touch the database -- a
     * liveness probe that fails on a database blip would have the host restart a
     * perfectly healthy container and turn a brief outage into a long one.
     */
    @GetMapping("/healthz")
    public Map<String, Object> healthz() {
        return Map.of(
                "status", "ok",
                "version", version,
                "uptime_seconds", Duration.between(startedAt, Instant.now()).toSeconds());
    }

    /** Readiness: can we actually serve money operations? This one does check the database. */
    @GetMapping("/readyz")
    public ResponseEntity<Map<String, Object>> readyz() {
        try (Connection c = dataSource.getConnection()) {
            if (c.isValid(2)) {
                return ResponseEntity.ok(Map.of("status", "ready", "db", "ok"));
            }
            return ResponseEntity.status(503).body(Map.of("status", "degraded", "db", "invalid"));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                    "status", "degraded", "db", "unreachable", "error", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping("/")
    public Map<String, Object> index() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("POST /wallets", "get-or-create the caller's wallet (bearer token = identity)");
        endpoints.put("GET /wallets/{id}", "current balance");
        endpoints.put("POST /transfers", "move money; body: from, to, amount_paise, idempotency_key");
        endpoints.put("GET /transfers/{id}", "transfer status");
        endpoints.put("POST /admin/mint", "test funding (admin bearer token); NOT a transfer");
        endpoints.put("GET /invariants", "live invariant audit, recomputed from base tables");
        endpoints.put("GET /metrics", "prometheus metrics");
        endpoints.put("GET /dashboard", "live metrics dashboard");
        endpoints.put("GET /logs", "live structured log stream (public)");
        endpoints.put("GET /healthz", "liveness");
        endpoints.put("GET /readyz", "readiness (checks database)");

        return Map.of(
                "service", "wallet-p2p",
                "runtime", "java-spring-boot",
                "version", version,
                "money", "all amounts are integer paise; there is no float in this service",
                "endpoints", endpoints);
    }

    // ------------------------------------------------------------------
    // invariants
    // ------------------------------------------------------------------

    /**
     * Live audit, recomputed from the base tables on every call.
     *
     * <p>Answers 500 when an invariant is broken: the endpoint that reports on
     * correctness should not say 200 OK while correctness is false.
     */
    @GetMapping("/invariants")
    public ResponseEntity<Invariants> invariants() {
        Invariants inv = wallets.checkInvariants();
        metrics.recordInvariants(inv.totalBalancePaise(), inv.ledgerSumPaise());
        return ResponseEntity.status(inv.allInvariantsHold() ? 200 : 500).body(inv);
    }

    // ------------------------------------------------------------------
    // test funding
    // ------------------------------------------------------------------

    public record MintBody(
            @JsonProperty("wallet_id") String walletId,
            @JsonProperty("amount_paise") Long amountPaise,
            @JsonProperty("idempotency_key") String idempotencyKey) {}

    /** Test funding. Explicitly NOT a transfer -- see the write-up. */
    @PostMapping("/admin/mint")
    public ResponseEntity<WalletController.WalletView> mint(@RequestBody MintBody body,
                                                            HttpServletRequest request) {
        auth.requireAdmin(request, adminToken);

        if (body.walletId() == null || body.amountPaise() == null || body.amountPaise() <= 0) {
            throw new ApiException(422, "invalid_mint",
                    "wallet_id and a positive integer amount_paise are required");
        }
        if (body.idempotencyKey() == null || body.idempotencyKey().isBlank()) {
            throw new ApiException(422, "missing_idempotency_key",
                    "idempotency_key is required so a retried funding step cannot double the float");
        }

        WalletRepository.MintResult result =
                wallets.mint(body.walletId(), body.idempotencyKey(), body.amountPaise());

        if (result.applied()) {
            metrics.minted(body.amountPaise());
        }

        Wallet w = result.wallet();
        log.atInfo()
                .addKeyValue("event", "mint")
                .addKeyValue("wallet_id", w.id())
                .addKeyValue("amount_paise", body.amountPaise())
                .addKeyValue("applied", result.applied())
                .addKeyValue("balance_paise", w.balancePaise())
                .log("mint");

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (!result.applied()) {
            response.header("Idempotent-Replay", "true");
        }
        return response.body(WalletController.WalletView.of(w));
    }

    // ------------------------------------------------------------------
    // public log access
    //
    // The exercise asks for publicly-viewable logs. Handing out host-dashboard
    // credentials is not that, so the process tees its own JSON log stream into a
    // bounded ring buffer and serves it over SSE. Anyone with the URL can watch
    // the domain events scroll past during a burst, with no login.
    // ------------------------------------------------------------------

    @GetMapping("/logs/recent")
    public ResponseEntity<String> recentLogs(@RequestParam(defaultValue = "200") int n) throws IOException {
        int limit = Math.max(1, Math.min(n, 5000));
        List<String> lines = LogRing.get().snapshot(limit);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"count\":").append(lines.size())
          .append(",\"subscribers\":").append(LogRing.get().subscriberCount())
          .append(",\"lines\":[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(lines.get(i));
        }
        sb.append("]}");

        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(sb.toString());
    }

    @GetMapping(value = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs() {
        // No timeout: this is a long-lived tail, and a deadline would sever the
        // very stream someone is watching during a burst.
        SseEmitter emitter = new SseEmitter(0L);
        Object key = new Object();

        // Replay recent history so a viewer who connects mid-burst sees context.
        try {
            for (String line : LogRing.get().snapshot(100)) {
                emitter.send(SseEmitter.event().data(line));
            }
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        LogRing.get().subscribe(key, line -> {
            try {
                emitter.send(SseEmitter.event().data(line));
            } catch (IOException | IllegalStateException e) {
                LogRing.get().unsubscribe(key);
                emitter.complete();
            }
        });

        emitter.onCompletion(() -> LogRing.get().unsubscribe(key));
        emitter.onTimeout(() -> LogRing.get().unsubscribe(key));
        emitter.onError(e -> LogRing.get().unsubscribe(key));
        return emitter;
    }
}
