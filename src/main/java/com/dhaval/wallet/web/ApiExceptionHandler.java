package com.dhaval.wallet.web;

import com.dhaval.wallet.obs.DomainMetrics;
import com.dhaval.wallet.store.StoreException;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/** Turns every failure into the same JSON error envelope, carrying the correlation id. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final DomainMetrics metrics;

    public ApiExceptionHandler(DomainMetrics metrics) {
        this.metrics = metrics;
    }

    /** The error envelope: {"error": {"code", "message", "correlation_id"}}. */
    public record ErrorBody(@JsonProperty("error") Map<String, String> error) {
        static ResponseEntity<ErrorBody> of(int status, String code, String message) {
            return ResponseEntity.status(status).body(new ErrorBody(Map.of(
                    "code", code,
                    "message", message == null ? "" : message,
                    "correlation_id", CorrelationIdFilter.currentId())));
        }
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException e) {
        return ErrorBody.of(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(StoreException.class)
    public ResponseEntity<ErrorBody> handleStore(StoreException e) {
        return switch (e.kind()) {
            case WALLET_NOT_FOUND -> ErrorBody.of(404, "wallet_not_found", "no wallet with that id");
            case TRANSFER_NOT_FOUND -> ErrorBody.of(404, "transfer_not_found", "no transfer with that id");
            case SAME_WALLET -> ErrorBody.of(422, "same_wallet", "from and to must be different wallets");
            case IDEMPOTENCY_CONFLICT -> {
                metrics.idempotencyConflict();
                log.atWarn()
                        .addKeyValue("event", "idempotency_conflict")
                        .log("idempotency key reused with a different body");
                yield ErrorBody.of(409, "idempotency_key_conflict",
                        "this idempotency_key was already used with different transfer parameters");
            }
            case INVARIANT_VIOLATION -> {
                // This should be unreachable. If it fires, something has broken the
                // locking discipline -- log it as loudly as possible.
                log.atError()
                        .addKeyValue("event", "invariant_violation")
                        .setCause(e)
                        .log("INVARIANT VIOLATION on the money path");
                yield ErrorBody.of(500, "invariant_violation", e.getMessage());
            }
        };
    }

    /**
     * A malformed body includes {@code "amount_paise": 12.5}. Jackson refuses to
     * coerce a decimal into a long, which is exactly the behaviour wanted: money
     * arrives as integer paise or it does not arrive.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException e) {
        metrics.rejectedInvalid();
        String detail = e.getMostSpecificCause().getMessage();
        if (detail != null && detail.length() > 300) {
            detail = detail.substring(0, 300);
        }
        return ErrorBody.of(400, "invalid_body", "could not parse request: " + detail);
    }

    /**
     * A request for a path that does not exist is a 404, not a 500.
     *
     * <p>Without this, the catch-all below turned every missing static resource
     * into a server error -- including the {@code favicon.ico} that every browser
     * requests unprompted. That is not a cosmetic mislabel: it inflates the error
     * rate that is supposed to mean something, and it would fail this project's
     * own "zero 5xx" CI assertion the moment anyone opened the service in a tab.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorBody> handleNoResource(NoResourceFoundException e) {
        return ErrorBody.of(404, "not_found", "no such path: " + e.getResourcePath());
    }

    /** Anything that already carries its own status keeps it. */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ErrorBody> handleErrorResponse(ErrorResponseException e) {
        int status = e.getStatusCode().value();
        if (status >= 500) {
            log.atError().addKeyValue("event", "unhandled_error").setCause(e).log("server error");
        }
        return ErrorBody.of(status, status >= 500 ? "internal_error" : "request_error",
                e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleUnexpected(Exception e) {
        log.atError().addKeyValue("event", "unhandled_error").setCause(e).log("unhandled exception");
        return ErrorBody.of(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "internal_error", "unexpected server error");
    }
}
