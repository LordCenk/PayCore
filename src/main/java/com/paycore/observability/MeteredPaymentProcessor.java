package com.paycore.observability;

import com.paycore.processor.MockPaymentProcessor;
import com.paycore.processor.PaymentProcessor;
import com.paycore.processor.ProcessorTimeoutException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Decorates the gateway with {@code paycore.processor.latency{operation, outcome}}: call latency and
 * outcome (succeeded, declined, not_found, timeout). The payment code doesn't know it's being measured.
 */
@Primary
@Component
public class MeteredPaymentProcessor implements PaymentProcessor {

    private final PaymentProcessor delegate;
    private final MeterRegistry registry;

    public MeteredPaymentProcessor(MockPaymentProcessor delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @FunctionalInterface
    private interface ProcessorCall<T> {
        T call() throws ProcessorTimeoutException;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public ChargeResult charge(ChargeRequest request) throws ProcessorTimeoutException {
        return time("charge", () -> delegate.charge(request), r -> r.outcome().name());
    }

    @Override
    public Optional<ChargeResult> getCharge(String reference) throws ProcessorTimeoutException {
        return time("get_charge", () -> delegate.getCharge(reference),
                r -> r.map(c -> c.outcome().name()).orElse("NOT_FOUND"));
    }

    @Override
    public RefundResult refund(RefundRequest request) throws ProcessorTimeoutException {
        return time("refund", () -> delegate.refund(request), r -> r.outcome().name());
    }

    @Override
    public Optional<RefundResult> getRefund(String reference) throws ProcessorTimeoutException {
        return time("get_refund", () -> delegate.getRefund(reference),
                r -> r.map(c -> c.outcome().name()).orElse("NOT_FOUND"));
    }

    private <T> T time(String operation, ProcessorCall<T> call, java.util.function.Function<T, String> outcome)
            throws ProcessorTimeoutException {
        Timer.Sample sample = Timer.start(registry);
        String result = "error";
        try {
            T value = call.call();
            result = outcome.apply(value).toLowerCase(java.util.Locale.ROOT);
            return value;
        } catch (ProcessorTimeoutException e) {
            result = "timeout";
            throw e;
        } finally {
            sample.stop(Timer.builder("paycore.processor.latency")
                    .description("Payment processor call latency")
                    .tag("operation", operation)
                    .tag("outcome", result)
                    .register(registry));
        }
    }
}
