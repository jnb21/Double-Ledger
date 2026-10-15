package com.jordanbikong.ledger.journal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * A well-formed request that breaks a money rule (unbalanced, mixed
 * currencies). Rendered as 422: the JSON is valid, the request isn't.
 */
public class LedgerRuleException extends ErrorResponseException {

    public LedgerRuleException(String detail) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, problem(detail), null);
    }

    private static ProblemDetail problem(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, detail);
        problem.setTitle("Ledger rule violated");
        return problem;
    }
}
