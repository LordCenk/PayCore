package com.paycore.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paycore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReconciliationIT extends IntegrationTest {

    @Autowired
    ReconciliationService reconciliation;

    @Test
    void consistentDataProducesACleanReport() throws Exception {
        Fixture f = fixture("tok_success");
        String paymentId = payId(f, 100);
        refund(f, paymentId, "r", "{}");
        payId(fixture("tok_decline"), 100);

        ReconciliationReport report = reconciliation.run();

        assertThat(report.paymentsChecked()).isEqualTo(2);
        assertThat(report.clean()).isTrue();
    }

    @Test
    void detectsAPaymentWeMarkedFailedThatTheProcessorCharged() throws Exception {
        String paymentId = payId(fixture("tok_success"), 100);
        jdbc.update("UPDATE payments SET status = 'FAILED' WHERE id = ?", paymentId);

        ReconciliationReport report = reconciliation.run();

        assertThat(report.processorMismatches()).singleElement().asString().contains(paymentId, "SUCCEEDED");
        assertThat(count("SELECT count(*) FROM audit_logs WHERE entity_id = ? AND action = 'RECONCILIATION_MISMATCH'",
                paymentId)).isEqualTo(1);
    }

    @Test
    void detectsMissingAndUnbalancedLedgerEntries() throws Exception {
        String paymentId = payId(fixture("tok_success"), 100);
        jdbc.update("DELETE FROM ledger_entries WHERE payment_id = ? AND entry_type = 'CREDIT'", paymentId);
        String tx = jdbc.queryForObject("SELECT transaction_id FROM ledger_entries WHERE payment_id = ?", String.class,
                paymentId);

        ReconciliationReport report = reconciliation.run();
        assertThat(report.unbalancedTransactions()).containsExactly(tx);

        jdbc.update("DELETE FROM ledger_entries WHERE payment_id = ?", paymentId);
        assertThat(reconciliation.run().paymentsMissingLedger()).containsExactly(paymentId);
    }

    @Test
    void stuckRefundIsResolvedFromTheProcessorsRecord() throws Exception {
        Fixture f = fixture("tok_success");
        String paymentId = payId(f, 100);
        String refundId = body(refund(f, paymentId, "r", "{}")).get("id").asString();
        // As if PayCore crashed after the processor refunded but before recording it.
        jdbc.update("UPDATE refunds SET status = 'PENDING' WHERE id = ?", refundId);
        jdbc.update("UPDATE payments SET status = 'REFUND_PENDING' WHERE id = ?", paymentId);
        jdbc.update("DELETE FROM ledger_entries WHERE refund_id = ?", refundId);

        ReconciliationReport report = reconciliation.run();

        assertThat(report.refundsResolved()).isEqualTo(1);
        assertThat(paymentStatus(paymentId)).isEqualTo("REFUNDED");
        assertThat(report.clean()).isTrue();
    }

    @Test
    void adminEndpointRequiresTheAdminKey() throws Exception {
        mvc.perform(post("/api/v1/admin/reconciliation/run")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/reconciliation/run").header("X-Admin-Key", "admin_dev_key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processorMismatches").isArray());
    }
}
