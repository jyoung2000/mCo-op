package dropin.core.sync;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * A short sync connection that never touches world data (DESIGN 4.13, 4.14): it learns the host's policy,
 * mod manifest and member list before this PC joins, and downloads the mod files the host must serve
 * because Modrinth does not know them. Same handshake and encryption as {@link SyncClientSession}.
 * <p>
 * {@link #start}, {@link #onFrame} and {@link #onClosed} must be called one at a time; the other methods
 * may be called from any thread.
 */
public final class PolicyClientSession {
    public interface Local {
        String worldId();

        byte[] auth();

        byte[] enc();

        String playerUuid();

        /** Version of the newest valid policy this PC holds for the world; 0 if none. */
        long policyVersion();

        /** The member list this PC knows, as JSON; null to share none. */
        String membersToOffer();

        /**
         * The host's policy and mod manifest; both empty if the host has none.
         *
         * @return {policyJson, manifestJson} to send back when this PC holds a newer one, else null
         */
        String[] policy(String policyJson, String manifestJson);

        void members(String json);

        /** Path policy as the host sees it: may this connection carry mod files right now? */
        void bulk(boolean allowed);

        /** Bytes of a requested mod file, in order. They are untrusted until the whole file's hash was checked. */
        void modPiece(String sha512, byte[] piece, boolean last) throws IOException;

        /** The host does not serve this file: it is not in its mod list, or the path does not allow it. */
        void modFailed(String sha512, boolean refusedByPath);

        /** The connection ended before it was closed from this side. */
        void failed(String why);
    }

    private final Local local;
    private final Transport transport;
    private final SecureRandom random;
    private final byte[] nonceC = new byte[SecureChannel.NONCE_LENGTH];
    private byte[] nonceS;
    private SecureChannel channel;
    private volatile boolean closed;

    public PolicyClientSession(Local local, Transport transport, SecureRandom random) {
        this.local = local;
        this.transport = transport;
        this.random = random;
    }

    public void start() {
        random.nextBytes(nonceC);
        transport.send(new Wire.Out(Wire.HELLO).i32(Wire.PROTO).str(local.worldId()).str(local.playerUuid())
                .bytes(nonceC).toBytes());
    }

    public void onFrame(byte[] frame) {
        if (closed) {
            return;
        }
        try {
            if (channel == null) {
                handshake(new Wire.In(frame));
            } else {
                byte[] message;
                synchronized (this) {
                    message = channel.open(frame);
                }
                dispatch(new Wire.In(message));
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    public void onClosed() {
        if (!closed) {
            closed = true;
            local.failed("the connection to the host was lost");
        }
    }

    /** Says goodbye and closes the connection. */
    public void close() {
        if (!closed) {
            send(new Wire.Out(Wire.BYE));
            closed = true;
            transport.close();
        }
    }

    public boolean closed() {
        return closed;
    }

    /** Asks the host for one file of its mod list. The answer arrives through {@link Local#modPiece}. */
    public void requestMod(String sha512) {
        send(new Wire.Out(Wire.MOD_GET).str(sha512));
    }

    private void fail(String why) {
        if (!closed) {
            closed = true;
            transport.close();
            local.failed(why);
        }
    }

    private void handshake(Wire.In in) throws IOException {
        if (in.type == Wire.CHALLENGE && nonceS == null) {
            nonceS = in.bytes();
            if (nonceS.length != SecureChannel.NONCE_LENGTH) {
                throw new IOException("Bad sync challenge");
            }
            transport.send(new Wire.Out(Wire.AUTH_C).bytes(SecureChannel.proof(local.auth(), nonceC, nonceS, "c")).toBytes());
        } else if (in.type == Wire.AUTH_S && nonceS != null) {
            if (!MessageDigest.isEqual(in.bytes(), SecureChannel.proof(local.auth(), nonceC, nonceS, "s"))) {
                throw new IOException("The host does not hold the world key");
            }
            synchronized (this) {
                channel = new SecureChannel(local.enc(), nonceC, nonceS, true);
            }
            send(new Wire.Out(Wire.POLICY_REQ).i64(local.policyVersion()));
            String members = local.membersToOffer();
            if (members != null) {
                send(new Wire.Out(Wire.MEMBERS).str(members));
            }
        } else {
            throw new IOException("Unexpected handshake message " + in.type);
        }
    }

    private void dispatch(Wire.In in) throws IOException {
        switch (in.type) {
            case Wire.HEAD -> {
                in.str();
                in.i64();
                in.str();
                local.bulk(in.u8() != 0);
            }
            case Wire.POLICY -> {
                String[] newer = local.policy(in.str(), in.str());
                if (newer != null) {
                    send(new Wire.Out(Wire.POLICY).str(newer[0]).str(newer[1]));
                }
            }
            case Wire.MEMBERS -> local.members(in.str());
            case Wire.MOD_DATA -> {
                String sha512 = in.str();
                int flags = in.u8();
                byte[] piece = in.bytes();
                if ((flags & (Wire.MOD_ABSENT | Wire.MOD_REFUSED)) != 0) {
                    local.modFailed(sha512, (flags & Wire.MOD_REFUSED) != 0);
                } else {
                    local.modPiece(sha512, piece, (flags & Wire.MOD_LAST) != 0);
                }
            }
            case Wire.BYE -> onClosed();
            default -> {
                // World data messages (checkpoints and the like) are not for this connection.
            }
        }
    }

    private synchronized void send(Wire.Out message) {
        if (!closed && channel != null) {
            transport.send(channel.seal(message.toBytes()));
        }
    }
}
