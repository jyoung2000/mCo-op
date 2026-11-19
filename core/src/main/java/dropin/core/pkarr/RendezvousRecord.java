package dropin.core.pkarr;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The rendezvous payload of DESIGN 4.3. Optional text fields are empty strings when absent.
 *
 * @param st   one of {@link #STARTING}, {@link #HOST}, {@link #HANDOFF}, {@link #IDLE}
 * @param addr the session's e4mc domain, once assigned
 * @param lan  {@code ip:port} hint for players on the same network
 * @param host UUID of the hosting player
 * @param sess 8 random bytes, hex; identifies one hosting attempt
 * @param head 16-byte ledger head, hex (empty before M2)
 * @param ts   epoch seconds when the record was written
 * @param next UUID of the successor, handoff only
 * @param pv   version of the host's owner policy, 0 if it has none (DESIGN 4.13)
 * @param mh   the policy's modManifestHash, empty if there is no policy
 * @param pl   the players connected besides the host: {@link #ID_CHARS} characters each (base64url of the first
 *             8 bytes of the UUID), sorted, at most {@link #PLAYERS_MAX}; empty if nobody (DESIGN 4.15)
 */
public record RendezvousRecord(int v, String st, String addr, String lan, String host, String name, String sess,
                               String head, long seq, long ts, String next, long pv, String mh, String pl) {
    public static final int VERSION = 1;
    public static final int ID_CHARS = 11;
    /** As many as fit in one pkarr packet beside the longest other fields. */
    public static final int PLAYERS_MAX = 12;

    public RendezvousRecord(int v, String st, String addr, String lan, String host, String name, String sess,
                            String head, long seq, long ts, String next, long pv, String mh) {
        this(v, st, addr, lan, host, name, sess, head, seq, ts, next, pv, mh, "");
    }

    /** The short form of a player's UUID in {@link #pl}, or "" if the text is not a UUID. */
    public static String playerId(String uuid) {
        try {
            long high = java.util.UUID.fromString(uuid).getMostSignificantBits();
            byte[] bytes = java.nio.ByteBuffer.allocate(8).putLong(high).array();
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** This record with the connected players (UUIDs; the host itself and unreadable ones are left out). */
    public RendezvousRecord withPlayers(java.util.Collection<String> uuids) {
        java.util.TreeSet<String> ids = new java.util.TreeSet<>();
        for (String uuid : uuids) {
            String id = playerId(uuid);
            if (!id.isEmpty() && !uuid.equals(host)) {
                ids.add(id);
            }
        }
        StringBuilder out = new StringBuilder();
        ids.stream().limit(PLAYERS_MAX).forEach(out::append);
        return new RendezvousRecord(v, st, addr, lan, host, name, sess, head, seq, ts, next, pv, mh, out.toString());
    }

    /** True if this record says the player is in the session: the host, or one of {@link #pl}. */
    public boolean isOnline(String uuid) {
        if (!HOST.equals(st)) {
            return false;
        }
        if (uuid.equals(host)) {
            return true;
        }
        String id = playerId(uuid);
        if (id.isEmpty()) {
            return false;
        }
        for (int i = 0; i + ID_CHARS <= pl.length(); i += ID_CHARS) {
            if (pl.startsWith(id, i)) {
                return true;
            }
        }
        return false;
    }
    public static final String STARTING = "starting";
    public static final String HOST = "host";
    public static final String HANDOFF = "handoff";
    public static final String IDLE = "idle";

    public RendezvousRecord(int v, String st, String addr, String lan, String host, String name, String sess,
                            String head, long seq, long ts, String next) {
        this(v, st, addr, lan, host, name, sess, head, seq, ts, next, 0, "");
    }

    public RendezvousRecord withPolicy(long policyVersion, String modManifestHash) {
        return new RendezvousRecord(v, st, addr, lan, host, name, sess, head, seq, ts, next, policyVersion, modManifestHash, pl);
    }

    /** Hex characters of a ledger head in the record: its first 16 bytes. */
    public static final int HEAD_CHARS = 32;

    /**
     * This record with the session's newest checkpoint (DESIGN 4.3: heartbeat and clean exit carry head and seq).
     *
     * @param headHash the full hash of the newest ledger entry; empty if there is none
     */
    public RendezvousRecord withHead(String headHash, long headSeq) {
        String shortHead = headHash == null ? "" : headHash.substring(0, Math.min(HEAD_CHARS, headHash.length()));
        return new RendezvousRecord(v, st, addr, lan, host, name, sess, shortHead, shortHead.isEmpty() ? 0 : headSeq, ts, next, pv, mh, pl);
    }

    public static RendezvousRecord of(String st, String host, String name, String sess, long ts) {
        return new RendezvousRecord(VERSION, st, "", "", host, name, sess, "", 0, ts, "");
    }

    public RendezvousRecord with(String st, String addr, String lan, long ts) {
        return new RendezvousRecord(v, st, addr, lan, host, name, sess, head, seq, ts, next, pv, mh, pl);
    }

    /** The "handoff" form of this record (DESIGN 4.9): the quitting host names who hosts next. No address. */
    public RendezvousRecord handoffTo(String successorUuid, long ts) {
        return new RendezvousRecord(v, HANDOFF, "", "", host, name, sess, head, seq, ts, successorUuid, pv, mh);
    }

    public boolean isState(String state) {
        return state.equals(st);
    }

    public String toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("v", v);
        json.addProperty("st", st);
        put(json, "addr", addr);
        put(json, "lan", lan);
        json.addProperty("host", host);
        json.addProperty("name", name);
        json.addProperty("sess", sess);
        put(json, "head", head);
        json.addProperty("seq", seq);
        json.addProperty("ts", ts);
        put(json, "next", next);
        if (pv > 0) {
            json.addProperty("pv", pv);
            put(json, "mh", mh);
        }
        put(json, "pl", pl);
        return json.toString();
    }

    private static void put(JsonObject json, String key, String value) {
        if (value != null && !value.isEmpty()) {
            json.addProperty(key, value);
        }
    }

    /** @throws IllegalArgumentException if the text is not a version-1 record */
    public static RendezvousRecord fromJson(String text) {
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            int version = json.get("v").getAsInt();
            if (version != VERSION) {
                throw new IllegalArgumentException("rendezvous record version " + version + " is not supported");
            }
            return new RendezvousRecord(version, json.get("st").getAsString(), text(json, "addr"), text(json, "lan"),
                    text(json, "host"), text(json, "name"), text(json, "sess"), text(json, "head"),
                    json.has("seq") ? json.get("seq").getAsLong() : 0, json.get("ts").getAsLong(), text(json, "next"),
                    json.has("pv") ? json.get("pv").getAsLong() : 0, text(json, "mh"), playerIds(text(json, "pl")));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a rendezvous record", e);
        }
    }

    private static String playerIds(String pl) {
        if (pl.length() % ID_CHARS != 0 || pl.length() > ID_CHARS * PLAYERS_MAX || !pl.matches("[A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("bad player list in the rendezvous record");
        }
        return pl;
    }

    private static String text(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsString() : "";
    }
}
