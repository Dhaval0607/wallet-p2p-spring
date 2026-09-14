package com.dhaval.wallet.web;

import com.dhaval.wallet.obs.DomainMetrics;
import com.dhaval.wallet.store.Money.User;
import com.dhaval.wallet.store.Money.Wallet;
import com.dhaval.wallet.store.WalletRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Wallet endpoints. */
@RestController
public class WalletController {

    private static final Logger log = LoggerFactory.getLogger(WalletController.class);

    private final WalletRepository wallets;
    private final Auth auth;
    private final DomainMetrics metrics;

    public WalletController(WalletRepository wallets, Auth auth, DomainMetrics metrics) {
        this.wallets = wallets;
        this.auth = auth;
        this.metrics = metrics;
    }

    /** The wire shape of a wallet. Balance is integer paise. */
    public record WalletView(
            String id,
            @JsonProperty("user_id") String userId,
            @JsonProperty("balance_paise") long balancePaise,
            @JsonProperty("created_at") String createdAt) {

        static WalletView of(Wallet w) {
            return new WalletView(w.id(), w.userId(), w.balancePaise(), w.createdAt());
        }
    }

    /** Get-or-create the caller's wallet. 201 if created, 200 if it already existed. */
    @PostMapping("/wallets")
    public ResponseEntity<WalletView> createWallet(HttpServletRequest request) {
        User user = auth.require(request);
        WalletRepository.GetOrCreate result = wallets.getOrCreateWallet(user.id());

        if (result.created()) {
            metrics.walletCreated();
        } else {
            metrics.walletReused();
        }

        log.atInfo()
                .addKeyValue("event", "wallet_get_or_create")
                .addKeyValue("wallet_id", result.wallet().id())
                .addKeyValue("user_id", result.wallet().userId())
                .addKeyValue("created", result.created())
                .addKeyValue("balance_paise", result.wallet().balancePaise())
                .log("wallet get-or-create");

        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(WalletView.of(result.wallet()));
    }

    @GetMapping("/wallets/{id}")
    public WalletView getWallet(@PathVariable String id, HttpServletRequest request) {
        auth.require(request);
        return WalletView.of(wallets.getWallet(id));
    }

    /** The faucet request body. Integer paise, and idempotent like everything else here. */
    public record FundBody(
            @JsonProperty("amount_paise") Long amountPaise,
            @JsonProperty("idempotency_key") String idempotencyKey) {}

    /**
     * Self-service test funding for the caller's own wallet. No admin token.
     *
     * <p>This is what lets anyone reproduce the invariant gates against the
     * deployed URL without a secret being passed around out of band. It is
     * bounded per call and per wallet, and it is deliberately <b>not</b> a
     * transfer: the money lands in {@code mints}, which is what keeps
     * conservation a checkable equation rather than an assertion.
     */
    @PostMapping("/wallets/{id}/fund")
    public ResponseEntity<WalletView> fund(@PathVariable String id,
                                           @RequestBody FundBody body,
                                           HttpServletRequest request) {
        User user = auth.require(request);

        if (body.amountPaise() == null || body.amountPaise() <= 0) {
            throw new ApiException(422, "invalid_amount",
                    "amount_paise is required and must be a positive integer");
        }
        if (body.idempotencyKey() == null || body.idempotencyKey().isBlank()) {
            throw new ApiException(422, "missing_idempotency_key",
                    "idempotency_key is required so a retried funding step cannot double the float");
        }

        Wallet target = wallets.getWallet(id);
        if (!target.userId().equals(user.id())) {
            throw new ApiException(403, "not_wallet_owner",
                    "the faucet only funds a wallet your own bearer token owns");
        }

        WalletRepository.MintResult result =
                wallets.fund(id, body.idempotencyKey(), body.amountPaise());

        if (result.applied()) {
            metrics.minted(body.amountPaise());
        }

        log.atInfo()
                .addKeyValue("event", "faucet_fund")
                .addKeyValue("wallet_id", result.wallet().id())
                .addKeyValue("amount_paise", body.amountPaise())
                .addKeyValue("applied", result.applied())
                .addKeyValue("balance_paise", result.wallet().balancePaise())
                .log("faucet fund");

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (!result.applied()) {
            response.header("Idempotent-Replay", "true");
        }
        return response.body(WalletView.of(result.wallet()));
    }
}
