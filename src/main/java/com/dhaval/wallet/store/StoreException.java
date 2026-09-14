package com.dhaval.wallet.store;

/** Domain failures the web layer maps onto status codes. */
public class StoreException extends RuntimeException {

    public enum Kind {
        WALLET_NOT_FOUND,
        TRANSFER_NOT_FOUND,
        IDEMPOTENCY_CONFLICT,
        SAME_WALLET,
        FAUCET_LIMIT,
        INVARIANT_VIOLATION
    }

    private final Kind kind;

    public StoreException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static StoreException walletNotFound() {
        return new StoreException(Kind.WALLET_NOT_FOUND, "wallet not found");
    }

    public static StoreException transferNotFound() {
        return new StoreException(Kind.TRANSFER_NOT_FOUND, "transfer not found");
    }

    public static StoreException idempotencyConflict() {
        return new StoreException(Kind.IDEMPOTENCY_CONFLICT,
                "idempotency key reused with a different request");
    }

    public static StoreException sameWallet() {
        return new StoreException(Kind.SAME_WALLET, "from and to must differ");
    }

    public static StoreException faucetLimit(String message) {
        return new StoreException(Kind.FAUCET_LIMIT, message);
    }
}
