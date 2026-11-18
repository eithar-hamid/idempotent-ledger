package io.github.eitharhamid.ledger.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fingerprints a transfer request so a reused idempotency key can be checked against the request
 * that first used it. Same key + same request = safe replay; same key + different request = client bug.
 */
final class RequestHasher {

    private RequestHasher() {
    }

    static String hash(TransferRequest request) {
        // Unit separator between fields so that ("ab", "c") and ("a", "bc") cannot collide.
        String canonical = String.join("\u001F",
                request.fromAccountId().toString(),
                request.toAccountId().toString(),
                Long.toString(request.amountMinor()),
                request.currency(),
                request.description() == null ? "" : request.description());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
