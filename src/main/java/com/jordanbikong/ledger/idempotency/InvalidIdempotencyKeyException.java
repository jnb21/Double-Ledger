package com.jordanbikong.ledger.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Rendered as 400: the Idempotency-Key header is blank or too long. */
public class InvalidIdempotencyKeyException extends ErrorResponseException {

    public InvalidIdempotencyKeyException() {
        super(HttpStatus.BAD_REQUEST,
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                        "Idempotency-Key must be 1 to " + IdempotencyService.MAX_KEY_LENGTH + " characters"),
                null);
    }
}
