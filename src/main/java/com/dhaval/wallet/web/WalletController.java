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
}
