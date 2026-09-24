package com.paycore.merchant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "merchants")
public class Merchant {

    public enum Status { ACTIVE, SUSPENDED }

    @Id
    private String id;

    private String name;
    private String email;

    @Column(name = "api_key_hash")
    private String apiKeyHash;

    @Column(name = "webhook_url")
    private String webhookUrl;

    @Column(name = "webhook_secret")
    private String webhookSecret;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Version
    private Long version;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Merchant() {}

    public Merchant(String id, String name, String email, String apiKeyHash, String webhookUrl,
                    String webhookSecret, Instant now) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.apiKeyHash = apiKeyHash;
        this.webhookUrl = webhookUrl;
        this.webhookSecret = webhookSecret;
        this.status = Status.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getWebhookUrl() { return webhookUrl; }
    public String getWebhookSecret() { return webhookSecret; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
