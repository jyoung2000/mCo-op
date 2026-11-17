package dropin.core.sync;

/**
 * What a sync session sends through: one whole frame at a time, in order. Whoever owns the transport
 * delivers received frames to the session's {@code onFrame} and its end to {@code onClosed}, one call
 * at a time.
 */
public interface Transport {
    void send(byte[] frame);

    void close();
}
