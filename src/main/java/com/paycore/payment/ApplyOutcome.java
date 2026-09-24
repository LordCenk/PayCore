package com.paycore.payment;

/** Result of applying a processor outcome that may arrive more than once (response, retry, webhook). */
public enum ApplyOutcome {
    /** State changed. */
    APPLIED,
    /** Already in the resulting state: a duplicate, safely ignored. */
    ALREADY_APPLIED,
    /** Contradicts the current state (e.g. "failed" for a successful payment): ignored and audited. */
    CONFLICT
}
