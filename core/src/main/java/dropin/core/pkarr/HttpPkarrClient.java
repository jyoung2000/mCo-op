package dropin.core.pkarr;

import dropin.core.crypto.ZBase32;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * pkarr over HTTP relays. Publishes to every relay in the list and reads from all of them, keeping the
 * newest valid answer (DESIGN 4.3: "Publish to all, read until one answers").
 */
public final class HttpPkarrClient implements PkarrClient {
    /** Public relays that answered on 2026-11-17. Only the default of a config list (DESIGN 4.3). */
    public static final List<URI> DEFAULT_RELAYS = List.of(
            URI.create("https://relay.pkarr.org"),
            URI.create("https://pkarr.pubky.org"),
            URI.create("https://pkarr.pubky.app"));

    private final List<URI> relays;
    private final Duration timeout;
    private final HttpClient http;

    public HttpPkarrClient(List<URI> relays, Duration timeout) {
        if (relays.isEmpty()) {
            throw new IllegalArgumentException("no pkarr relays configured");
        }
        this.relays = List.copyOf(relays);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    private URI target(URI relay, byte[] publicKey) {
        String base = relay.toString();
        return URI.create((base.endsWith("/") ? base : base + "/") + ZBase32.encode(publicKey));
    }

    private List<CompletableFuture<HttpResponse<byte[]>>> sendAll(byte[] publicKey, byte[] body) {
        List<CompletableFuture<HttpResponse<byte[]>>> futures = new ArrayList<>();
        for (URI relay : relays) {
            HttpRequest.Builder request = HttpRequest.newBuilder(target(relay, publicKey)).timeout(timeout);
            request = body == null ? request.GET() : request.PUT(HttpRequest.BodyPublishers.ofByteArray(body));
            futures.add(http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray()));
        }
        return futures;
    }

    /**
     * DESIGN 4.3 "read until one answers": once a relay has answered, the others get this long to answer as well
     * (the newest of what came in wins), then the read returns. One slow relay must not hold up every launch.
     */
    static final long ANSWER_GRACE_MS = 1_000;

    @Override
    public Optional<SignedPacket> get(byte[] publicKey) throws IOException {
        SignedPacket newest = null;
        boolean answered = false;
        boolean limited = false;
        List<String> problems = new ArrayList<>();
        List<CompletableFuture<HttpResponse<byte[]>>> pending = new ArrayList<>(sendAll(publicKey, null));
        long graceEnds = Long.MAX_VALUE;
        while (!pending.isEmpty()) {
            if (answered && graceEnds == Long.MAX_VALUE) {
                graceEnds = System.currentTimeMillis() + ANSWER_GRACE_MS;
            }
            CompletableFuture<HttpResponse<byte[]>> future = pending.stream().filter(CompletableFuture::isDone).findFirst().orElse(null);
            if (future == null) {
                long now = System.currentTimeMillis();
                if (now >= graceEnds) {
                    pending.forEach(late -> late.cancel(true));
                    break;
                }
                try {
                    // Every request has its own time limit, so this wakes up at the latest when one of them ends.
                    CompletableFuture.anyOf(pending.toArray(CompletableFuture[]::new))
                            .get(Math.min(graceEnds - now, timeout.toMillis() * 2 + 1_000), java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
                    // Handled on the next pass: a finished request is read, a passed grace ends the read.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while reading the pkarr relays", e);
                }
                continue;
            }
            pending.remove(future);
            try {
                HttpResponse<byte[]> response = future.join();
                int status = response.statusCode();
                if (status == 200) {
                    SignedPacket packet = SignedPacket.fromRelayPayload(publicKey, response.body());
                    answered = true;
                    if (newest == null || packet.timestampMicros() > newest.timestampMicros()) {
                        newest = packet;
                    }
                } else if (status == 404) {
                    answered = true;
                } else {
                    limited |= status == 429;
                    problems.add(response.uri().getHost() + ": HTTP " + status);
                }
            } catch (RuntimeException e) {
                problems.add(String.valueOf(e.getCause() != null ? e.getCause() : e));
            }
        }
        if (!answered) {
            if (limited) {
                throw new RateLimitedException("pkarr relays are rate limiting: " + problems);
            }
            throw new IOException("no pkarr relay answered: " + problems);
        }
        return Optional.ofNullable(newest);
    }

    @Override
    public void put(SignedPacket packet) throws IOException {
        boolean stored = false;
        boolean stale = false;
        boolean limited = false;
        List<String> problems = new ArrayList<>();
        for (CompletableFuture<HttpResponse<byte[]>> future : sendAll(packet.publicKey(), packet.relayPayload())) {
            try {
                HttpResponse<byte[]> response = future.join();
                int status = response.statusCode();
                if (status == 200 || status == 204) {
                    stored = true;
                } else {
                    stale |= status == 409;
                    limited |= status == 429;
                    problems.add(response.uri().getHost() + ": HTTP " + status);
                }
            } catch (RuntimeException e) {
                problems.add(String.valueOf(e.getCause() != null ? e.getCause() : e));
            }
        }
        if (stored) {
            return;
        }
        if (stale) {
            throw new StaleRecordException("a newer record is already published: " + problems);
        }
        if (limited) {
            throw new RateLimitedException("pkarr relays are rate limiting: " + problems);
        }
        throw new IOException("no pkarr relay stored the record: " + problems);
    }
}
