package com.paycore.webhook.outbound;

import com.paycore.common.ApiException;
import com.paycore.config.PayCoreProperties;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Stops merchant webhook URLs from pointing PayCore at internal addresses (SSRF): loopback, private
 * networks, link-local (including cloud metadata at 169.254.169.254), carrier-grade NAT, multicast.
 *
 * <p>Checked when the URL is registered and again before every delivery, since DNS can change. The HTTP
 * client doesn't follow redirects, so a redirect can't be used to reach an internal address either.
 * A resolve-then-connect gap remains (DNS rebinding); closing it fully needs an egress proxy.
 * {@code paycore.webhooks.allow-private-targets=true} turns the check off for local development.
 */
@Component
public class WebhookTargetPolicy {

    private final boolean allowPrivate;

    public WebhookTargetPolicy(PayCoreProperties properties) {
        this.allowPrivate = properties.webhooks().allowPrivateTargets();
    }

    /** Rejects a URL at registration time. Hosts that don't resolve yet are allowed; delivery re-checks. */
    public void validateForRegistration(String url) {
        Optional<String> reason = check(url, false);
        if (reason.isPresent()) {
            throw ApiException.badRequest("INVALID_WEBHOOK_URL", "webhookUrl is not allowed: " + reason.get());
        }
    }

    /** Why a delivery to this URL must not be sent, if it mustn't. */
    public Optional<String> blockedReason(String url) {
        return check(url, true);
    }

    private Optional<String> check(String url, boolean requireResolvable) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return Optional.of("malformed URL");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            return Optional.of("scheme must be http or https");
        }
        if (uri.getHost() == null) {
            return Optional.of("missing host");
        }
        if (allowPrivate) {
            return Optional.empty();
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            return requireResolvable ? Optional.of("host does not resolve") : Optional.empty();
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                return Optional.of("host resolves to internal address " + address.getHostAddress());
            }
        }
        return Optional.empty();
    }

    static boolean isInternal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0                                   // 0.0.0.0/8 "this network"
                    || (first == 100 && second >= 64 && second <= 127);  // 100.64.0.0/10 carrier-grade NAT
        }
        if (a instanceof Inet6Address) {
            return (b[0] & 0xfe) == 0xfc;                       // fc00::/7 unique local
        }
        return false;
    }
}
