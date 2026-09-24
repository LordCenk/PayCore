package com.paycore.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import com.paycore.outbox.EventPublisher;
import com.paycore.outbox.OutboxRelay;
import com.paycore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** When the broker is down, events wait in the outbox; nothing is lost and nothing is half-done. */
class OutboxRelayFailureIT extends IntegrationTest {

    @MockitoBean
    EventPublisher publisher;

    @Autowired
    OutboxRelay relay;

    @Test
    void brokerOutageLeavesEventsQueuedUntilItRecovers() throws Exception {
        payId(fixture("tok_success", "http://127.0.0.1:1/hooks"), 100);
        doThrow(new RuntimeException("broker down")).when(publisher).publishAll(anyList());

        assertThatThrownBy(() -> relay.relayBatch()).hasMessageContaining("broker down");
        assertThat(count("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM webhook_deliveries")).isZero();

        doNothing().when(publisher).publishAll(anyList());
        assertThat(relay.relayBatch()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")).isZero();
        assertThat(count("SELECT count(*) FROM webhook_deliveries")).isEqualTo(2);
    }
}
