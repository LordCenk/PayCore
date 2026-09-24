package com.paycore.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    private String id;

    @Column(name = "merchant_id")
    private String merchantId;

    private String name;
    private String email;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Customer() {}

    public Customer(String id, String merchantId, String name, String email, Instant now) {
        this.id = id;
        this.merchantId = merchantId;
        this.name = name;
        this.email = email;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }
}
