package io.agentlink.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;

@Component
public class OutboundUrlPolicy {
    private final boolean allowPrivateNetworks;

    public OutboundUrlPolicy(@Value("${agentlink.security.allow-private-agent-networks:false}") boolean allowPrivateNetworks) {
        this.allowPrivateNetworks = allowPrivateNetworks;
    }

    public URI validate(String rawUrl) {
        URI uri = URI.create(rawUrl);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("only http and https Agent endpoints are supported");
        }
        if (uri.getUserInfo() != null || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Agent endpoint must not contain credentials and must have a host");
        }
        if (!allowPrivateNetworks) {
            blockPrivateAddresses(uri.getHost());
        }
        return uri;
    }

    private void blockPrivateAddresses(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("private or local Agent endpoints are disabled by default");
                }
            }
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("unable to resolve Agent endpoint host", failure);
        }
    }
}
