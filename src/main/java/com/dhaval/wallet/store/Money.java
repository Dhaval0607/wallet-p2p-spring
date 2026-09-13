package com.dhaval.wallet.store;

/**
 * Domain records. Money is {@code long} paise everywhere in this service: from
 * the JSON decoder, through the transaction, into a {@code bigint} column and
 * back. There is no {@code double}, no {@code float} and no {@code BigDecimal}
 * anywhere on the money path, so there is no place for a rounding rule to hide.
 */
public final class Money {
    private Money() {}

    /** An authenticated caller. The bearer token is the identity. */
    public record User(String id) {}

    /** A user's single balance. */
    public record Wallet(String id, String userId, long balancePaise, String createdAt) {}

    /** One attempted money movement and its terminal outcome. */
    public record Transfer(
            String id,
            String status,
            String declineReason,
            String fromWalletId,
            String toWalletId,
            long amountPaise,
            String idempotencyKey,
            String createdAt,
            String completedAt) {}

    /**
     * A transfer plus how it was arrived at, so the web layer can pick the right
     * status code and the right domain counter.
     *
     * @param replay true when this call hit an existing idempotency key and
     *               returned the stored outcome instead of moving money again
     */
    public record TransferResult(Transfer transfer, boolean replay) {}

    /** A validated, normalized transfer instruction. */
    public record TransferRequest(
            String requesterUserId,
            String fromWalletId,
            String toWalletId,
            long amountPaise,
            String idempotencyKey) {}

    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_DECLINED = "declined";
    public static final String REASON_INSUFFICIENT_FUNDS = "insufficient_funds";
}
