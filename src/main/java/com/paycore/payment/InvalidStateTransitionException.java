package com.paycore.payment;

import com.paycore.common.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidStateTransitionException extends ApiException {

    public InvalidStateTransitionException(String entity, String id, Enum<?> from, Enum<?> to) {
        super(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                entity + " " + id + " is " + from + " and cannot move to " + to);
    }
}
