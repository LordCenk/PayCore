package com.paycore.paymentmethod;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A tokenized payment method. PayCore never stores a real card number. */
@Entity
@Table(name = "payment_methods")
public class PaymentMethod {

    public enum Type { CARD, UPI }

    public enum Status { ACTIVE, DISABLED }

    @Id
    private String id;

    @Column(name = "customer_id")
    private String customerId;

    @Enumerated(EnumType.STRING)
    private Type type;

    private String provider;
    private String token;

    @Column(name = "last_four")
    private String lastFour;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "created_at")
    private Instant createdAt;

    protected PaymentMethod() {}

    public PaymentMethod(String id, String customerId, Type type, String provider, String token, String lastFour,
                         Instant now) {
        this.id = id;
        this.customerId = customerId;
        this.type = type;
        this.provider = provider;
        this.token = token;
        this.lastFour = lastFour;
        this.status = Status.ACTIVE;
        this.createdAt = now;
    }

    public String getId() { return id; }
    public String getCustomerId() { return customerId; }
    public Type getType() { return type; }
    public String getProvider() { return provider; }
    public String getToken() { return token; }
    public String getLastFour() { return lastFour; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
