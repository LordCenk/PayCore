package com.paycore.notification;

import com.paycore.auth.AuthenticatedMerchant;
import com.paycore.auth.CurrentMerchant;
import com.paycore.common.ApiException;
import com.paycore.customer.CustomerRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {

    private final JdbcTemplate jdbc;
    private final CustomerRepository customers;

    public NotificationController(JdbcTemplate jdbc, CustomerRepository customers) {
        this.jdbc = jdbc;
        this.customers = customers;
    }

    public record NotificationResponse(String eventId, String channel, String recipient, String template,
                                       Instant createdAt) {}

    @GetMapping("/api/v1/customers/{id}/notifications")
    public List<NotificationResponse> list(@PathVariable String id, @CurrentMerchant AuthenticatedMerchant merchant) {
        customers.findByIdAndMerchantId(id, merchant.id()).orElseThrow(() -> ApiException.notFound("Customer", id));
        return jdbc.query("SELECT event_id, channel, recipient, template, created_at FROM notifications "
                        + "WHERE customer_id = ? ORDER BY id",
                (rs, i) -> new NotificationResponse(rs.getString("event_id"), rs.getString("channel"),
                        rs.getString("recipient"), rs.getString("template"),
                        rs.getTimestamp("created_at").toInstant()),
                id);
    }
}
