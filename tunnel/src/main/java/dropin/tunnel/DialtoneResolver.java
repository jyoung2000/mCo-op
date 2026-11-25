package dropin.tunnel;

import net.minecraft.client.multiplayer.ServerAddress;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Hashtable;
import java.util.Optional;

/**
 * 1.12.2 port of upstream's {@code ServerNameResolverMixin}: a server on port 25565 whose host has a TXT record
 * {@code e4mc-dialtone-resolver=<host>} is reached over Dialtone with a ticket from that resolver. 26.x let the
 * ticket ride inside the resolved address; 1.12.2 connects with an {@code InetAddress} and a port, so
 * {@code ConnectionMixin} asks here at connect time, with the host name the address was resolved from.
 */
public final class DialtoneResolver {
    private static final String PREFIX = "e4mc-dialtone-resolver=";
    private static volatile DirContext dirContext;

    private DialtoneResolver() {
    }

    /** The Dialtone ticket for this server, or null to connect over plain TCP. */
    public static String ticketFor(InetAddress address, int port) {
        if (address == null || port != 25565 || !Config.INSTANCE.dialtonePlayerEnabled.value()) {
            return null;
        }
        String host = address.getHostName();
        if (host == null || host.isEmpty() || host.equals(address.getHostAddress())) {
            return null;
        }
        return ticketFor(host);
    }

    private static String ticketFor(String host) {
        try {
            DirContext context = context();
            if (context == null) {
                return null;
            }
            Attributes attributes = context.getAttributes(host, new String[]{"TXT"});
            Attribute attribute = attributes.get("TXT");
            if (attribute == null) {
                return null;
            }
            var attrs = attribute.getAll();
            while (attrs.hasMore()) {
                var attr = attrs.next();
                if (attr instanceof String str && str.startsWith(PREFIX)) {
                    var resolverAddr = str.substring(PREFIX.length());
                    if (!host.endsWith(resolverAddr)) {
                        E4mcClient.LOGGER.warn("Ignoring resolver addr {} as it's not a suffix of the target address", resolverAddr);
                    }
                    var httpClient = HttpClient.newHttpClient();
                    var request = HttpRequest
                            .newBuilder(new URI("https://" + resolverAddr + "/.well-known/dialtone_ticket/" + host))
                            .build();
                    E4mcClient.LOGGER.info("req: {}", request);
                    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    E4mcClient.LOGGER.info("resp: {}", response);
                    if (response.statusCode() == 200 && response.body().startsWith("v1_")) {
                        return response.body().substring(3);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static DirContext context() {
        DirContext context = dirContext;
        if (context == null) {
            try {
                Class.forName("com.sun.jndi.dns.DnsContextFactory");
                Hashtable<String, String> environment = new Hashtable<>();
                environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
                environment.put("java.naming.provider.url", "dns:");
                environment.put("com.sun.jndi.dns.timeout.retries", "1");
                context = new InitialDirContext(environment);
                dirContext = context;
            } catch (Throwable e) {
                E4mcClient.LOGGER.warn("Failed to create a Dialtone redirect handler", e);
                return null;
            }
        }
        return context;
    }

    /**
     * An address typed by a player resolved the way the game resolves it (1.12.2 {@code ServerAddress.fromString},
     * which follows SRV records, then {@code InetAddress.getByName}). A {@link SmugglersInetSocketAddress} when the
     * server is reached over Dialtone.
     */
    public static Optional<InetSocketAddress> resolve(String address) {
        ServerAddress parsed = ServerAddress.fromString(address);
        InetAddress inet;
        try {
            inet = InetAddress.getByName(parsed.getIP());
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
        InetSocketAddress socket = new InetSocketAddress(inet, parsed.getPort());
        String ticket = ticketFor(inet, parsed.getPort());
        return Optional.of(ticket == null ? socket : new SmugglersInetSocketAddress(socket, ticket));
    }
}
