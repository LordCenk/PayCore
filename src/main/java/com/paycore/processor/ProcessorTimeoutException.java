package com.paycore.processor;

/**
 * The gateway did not answer in time. The operation may or may not have happened,
 * so callers must treat the outcome as unknown, never as a failure.
 */
public class ProcessorTimeoutException extends Exception {

    public ProcessorTimeoutException(String message) {
        super(message);
    }
}
