package com.jordanbikong.ledger.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Rendered as 409: the key was already used for a different request. */
public class IdempotencyKeyMismatchException extends ErrorResponseException {

    public IdempotencyKeyMismatchException() {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                        "Idempotency-Key was already used for a different request"),
                null);
    }
}
