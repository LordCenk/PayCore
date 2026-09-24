package com.paycore.webhook.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Literal IPs only, so no DNS lookups happen. */
class WebhookTargetPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.8.8.8", "::1", "0.0.0.0", "10.1.2.3", "172.16.0.1", "172.31.255.255",
            "192.168.1.1", "169.254.169.254", "100.64.0.1", "100.127.255.255", "fd00::1", "fe80::1", "224.0.0.1",
            "::ffff:127.0.0.1"})
    void internalAddressesAreBlocked(String ip) throws Exception {
        assertThat(WebhookTargetPolicy.isInternal(InetAddress.getByName(ip))).as(ip).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "100.63.255.255", "100.128.0.1", "172.32.0.1", "2606:4700::1111"})
    void publicAddressesAreAllowed(String ip) throws Exception {
        assertThat(WebhookTargetPolicy.isInternal(InetAddress.getByName(ip))).as(ip).isFalse();
    }
}
