package dev.p2wn.startupguardian;

import java.util.Objects;
import java.util.Optional;

record WebhookResponse(int statusCode, Optional<String> retryAfter) {

    WebhookResponse {
        Objects.requireNonNull(retryAfter, "retryAfter");
    }
}
