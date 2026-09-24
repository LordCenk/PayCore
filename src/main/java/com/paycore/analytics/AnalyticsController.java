package com.paycore.analytics;

import com.paycore.auth.CurrentMerchant;
import com.paycore.common.ApiException;
import com.paycore.merchant.Merchant;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read side of the analytics consumer. Eventually consistent: it lags the payments table slightly. */
@RestController
public class AnalyticsController {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AnalyticsController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record DailyStats(LocalDate day, String currency, long paymentsSucceeded, long amountSucceeded,
                             long paymentsFailed, long refundsSucceeded, long amountRefunded) {}

    @GetMapping("/api/v1/analytics/daily")
    public List<DailyStats> daily(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @CurrentMerchant Merchant merchant) {
        LocalDate end = to != null ? to : LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate start = from != null ? from : end.minusDays(30);
        if (start.isAfter(end)) {
            throw ApiException.badRequest("INVALID_RANGE", "from must not be after to");
        }
        return jdbc.query("""
                SELECT day, currency, payments_succeeded, amount_succeeded, payments_failed, refunds_succeeded,
                       amount_refunded
                  FROM merchant_daily_stats
                 WHERE merchant_id = ? AND day BETWEEN ? AND ?
                 ORDER BY day, currency
                """,
                (rs, i) -> new DailyStats(rs.getDate("day").toLocalDate(), rs.getString("currency"),
                        rs.getLong("payments_succeeded"), rs.getLong("amount_succeeded"),
                        rs.getLong("payments_failed"), rs.getLong("refunds_succeeded"), rs.getLong("amount_refunded")),
                merchant.getId(), Date.valueOf(start), Date.valueOf(end));
    }
}
