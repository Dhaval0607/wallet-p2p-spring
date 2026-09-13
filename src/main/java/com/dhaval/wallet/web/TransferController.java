package com.dhaval.wallet.web;

import com.dhaval.wallet.obs.DomainMetrics;
import com.dhaval.wallet.store.Money;
import com.dhaval.wallet.store.Money.Transfer;
import com.dhaval.wallet.store.Money.TransferRequest;
import com.dhaval.wallet.store.Money.TransferResult;
import com.dhaval.wallet.store.Money.User;
import com.dhaval.wallet.store.Money.Wallet;
import com.dhaval.wallet.store.TransferService;
import com.dhaval.wallet.store.WalletRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
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

/** Transfer endpoints. */
@RestController
public class TransferController {

    private static final Logger log = LoggerFactory.getLogger(TransferController.class);

    private final TransferService transfers;
    private final WalletRepository wallets;
    private final Auth auth;
    private final DomainMetrics metrics;

    public TransferController(TransferService transfers, WalletRepository wallets,
                              Auth auth, DomainMetrics metrics) {
        this.transfers = transfers;
        this.wallets = wallets;
        this.auth = auth;
        this.metrics = metrics;
    }

    /**
     * The request body.
     *
     * <p>{@code amountPaise} is a boxed {@code Long} so a missing field is
     * distinguishable from an explicit 0, and it is an integer type so money never
     * touches a float: a JSON number with a decimal point fails to deserialize,
     * by design.
     */
    public record TransferBody(
            String from,
            String to,
            @JsonProperty("amount_paise") Long amountPaise,
            @JsonProperty("idempotency_key") String idempotencyKey) {}

    /** The wire shape of a transfer. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransferView(
            String id,
            String status,
            @JsonProperty("decline_reason") String declineReason,
            String from,
            String to,
            @JsonProperty("amount_paise") long amountPaise,
            @JsonProperty("idempotency_key") String idempotencyKey,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("completed_at") String completedAt) {

        static TransferView of(Transfer t) {
            return new TransferView(t.id(), t.status(), t.declineReason(), t.fromWalletId(),
                    t.toWalletId(), t.amountPaise(), t.idempotencyKey(), t.createdAt(), t.completedAt());
        }
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferView> createTransfer(@RequestBody TransferBody body,
                                                       HttpServletRequest request) {
        User user = auth.require(request);

        // --- Validation runs BEFORE the idempotency key is claimed. A request that
        // was never valid must not burn the caller's key: they have to be able to
        // fix the typo and retry with the same key. ---
        if (body.idempotencyKey() == null || body.idempotencyKey().isBlank()) {
            throw reject("missing_idempotency_key", "idempotency_key is required");
        }
        if (body.idempotencyKey().length() > 255) {
            throw reject("invalid_idempotency_key", "idempotency_key must be at most 255 characters");
        }
        if (body.from() == null || body.from().isBlank() || body.to() == null || body.to().isBlank()) {
            throw reject("missing_wallet", "both from and to are required");
        }
        if (body.from().equals(body.to())) {
            throw reject("same_wallet", "from and to must be different wallets");
        }
        if (body.amountPaise() == null) {
            throw reject("missing_amount", "amount_paise is required (integer paise)");
        }
        if (body.amountPaise() <= 0) {
            throw reject("invalid_amount", "amount_paise must be a positive integer");
        }

        // Ownership: you may only move money out of a wallet you own. Checked
        // before the transaction so an unauthorized caller cannot consume a key.
        Wallet from = wallets.getWallet(body.from());
        if (!from.userId().equals(user.id())) {
            metrics.rejectedInvalid();
            throw new ApiException(403, "not_wallet_owner",
                    "bearer token does not own the source wallet");
        }
        wallets.getWallet(body.to());

        log.atInfo()
                .addKeyValue("event", "transfer_created")
                .addKeyValue("from", body.from())
                .addKeyValue("to", body.to())
                .addKeyValue("amount_paise", body.amountPaise())
                .addKeyValue("idempotency_key", body.idempotencyKey())
                .log("transfer requested");

        TransferResult result = transfers.transfer(new TransferRequest(
                user.id(), body.from(), body.to(), body.amountPaise(), body.idempotencyKey()));

        Transfer t = result.transfer();

        if (result.replay()) {
            metrics.idempotentReplay();
            log.atInfo()
                    .addKeyValue("event", "idempotent_replay")
                    .addKeyValue("transfer_id", t.id())
                    .addKeyValue("idempotency_key", t.idempotencyKey())
                    .addKeyValue("replayed_status", t.status())
                    .log("idempotent replay hit");
        } else if (Money.STATUS_SUCCEEDED.equals(t.status())) {
            metrics.transferSucceeded(t.amountPaise());
            log.atInfo()
                    .addKeyValue("event", "transfer_succeeded")
                    .addKeyValue("transfer_id", t.id())
                    .addKeyValue("debited_wallet", t.fromWalletId())
                    .addKeyValue("credited_wallet", t.toWalletId())
                    .addKeyValue("amount_paise", t.amountPaise())
                    .log("transfer debited and credited");
        } else {
            metrics.transferDeclinedInsufficientFunds();
            log.atWarn()
                    .addKeyValue("event", "transfer_declined")
                    .addKeyValue("transfer_id", t.id())
                    .addKeyValue("from", t.fromWalletId())
                    .addKeyValue("amount_paise", t.amountPaise())
                    .addKeyValue("reason", String.valueOf(t.declineReason()))
                    .log("transfer declined");
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replay()) {
            response.header("Idempotent-Replay", "true");
        }

        // A declined transfer is a successfully recorded business outcome, not an
        // HTTP failure: 201 with status="declined". A retry of a decline has to
        // return the identical document, which it could not do behind a 4xx.
        return response.body(TransferView.of(t));
    }

    @GetMapping("/transfers/{id}")
    public TransferView getTransfer(@PathVariable String id, HttpServletRequest request) {
        auth.require(request);
        return TransferView.of(transfers.getTransfer(id));
    }

    /** Client-side validation failure: never reaches the database, never burns a key. */
    private ApiException reject(String code, String message) {
        metrics.rejectedInvalid();
        log.atWarn()
                .addKeyValue("event", "transfer_rejected")
                .addKeyValue("reason", code)
                .log("transfer rejected");
        return new ApiException(422, code, message);
    }
}
