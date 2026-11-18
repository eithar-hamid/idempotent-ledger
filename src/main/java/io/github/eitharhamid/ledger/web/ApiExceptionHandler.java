package io.github.eitharhamid.ledger.web;

import io.github.eitharhamid.ledger.ledger.AccountNotFoundException;
import io.github.eitharhamid.ledger.ledger.LedgerExceptions.CurrencyMismatchException;
import io.github.eitharhamid.ledger.ledger.LedgerExceptions.IdempotencyConflictException;
import io.github.eitharhamid.ledger.ledger.LedgerExceptions.InsufficientFundsException;
import io.github.eitharhamid.ledger.ledger.LedgerExceptions.InvalidTransferException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain exceptions to RFC 9457 problem details. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail notFound(AccountNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Account not found", e);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException e) {
        return problem(HttpStatus.CONFLICT, "Idempotency key reused", e);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient funds", e);
    }

    @ExceptionHandler({CurrencyMismatchException.class, InvalidTransferException.class})
    ProblemDetail badRequest(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid transfer", e);
    }

    private static ProblemDetail problem(HttpStatus status, String title, RuntimeException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        detail.setTitle(title);
        return detail;
    }
}
