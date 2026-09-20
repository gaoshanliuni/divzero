package dev.mineagent.runtime.worker.media;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class MediaAddressPolicy {
    private final DnsResolver resolver;

    public MediaAddressPolicy() {
        this(InetAddress::getAllByName);
    }

    public MediaAddressPolicy(DnsResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public void requireAllowed(String source, Set<String> explicitlyAllowedHosts) {
        Objects.requireNonNull(explicitlyAllowedHosts, "explicitlyAllowedHosts");
        final URI uri;
        try {
            uri = URI.create(Objects.requireNonNull(source, "source"));
        } catch (RuntimeException invalid) {
            throw new MediaToolException("invalid media URL", invalid);
        }
        String host = uri.getHost();
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || host == null || uri.getUserInfo() != null) {
            throw new MediaToolException("media source must be an HTTP(S) URL without credentials");
        }
        String normalized;
        try {
            normalized = java.net.IDN.toASCII(host).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalid) {
            throw new MediaToolException("invalid media host", invalid);
        }
        if (explicitlyAllowedHosts.stream().map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals)) {
            return;
        }
        final InetAddress[] addresses;
        try {
            addresses = resolver.resolve(normalized);
        } catch (UnknownHostException unresolved) {
            throw new MediaToolException("media host cannot be resolved", unresolved);
        }
        if (addresses.length == 0) {
            throw new MediaToolException("media host has no addresses");
        }
        for (InetAddress address : addresses) {
            if (privateOrSpecial(address)) {
                throw new MediaToolException("media host resolves to a private or special network");
            }
        }
    }

    private static boolean privateOrSpecial(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
        }
        if (bytes.length != 4) {
            return true;
        }
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        return first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127)
                || (first == 198 && (second == 18 || second == 19));
    }

    @FunctionalInterface
    public interface DnsResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
