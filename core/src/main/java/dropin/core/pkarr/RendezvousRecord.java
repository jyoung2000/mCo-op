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
 */
public record RendezvousRecord(int v, String st, String addr, String lan, String host, String name, String sess,
                               String head, long seq, long ts, String next, long pv, String mh) {
    public static final int VERSION = 1;
    public static final String STARTING = "starting";
    public static final String HOST = "host";
    public static final String HANDOFF = "handoff";
    public static final String IDLE = "idle";

    public RendezvousRecord(int v, String st, String addr, String lan, String host, String name, String sess,
                            String head, long seq, long ts, String next) {
        this(v, st, addr, lan, host, name, sess, head, seq, ts, next, 0, "");
    }

    public RendezvousRecord withPolicy(long policyVersion, String modManifestHash) {
        return new RendezvousRecord(v, st, addr, lan, host, name, sess, head, seq, ts, next, policyVersion, modManifestHash);
    }

    public static RendezvousRecord of(String st, String host, String name, String sess, long ts) {
        return new RendezvousRecord(VERSION, st, "", "", host, name, sess, "", 0, ts, "");
    }

    public RendezvousRecord with(String st, String addr, String lan, long ts) {
        return new RendezvousRecord(v, st, addr, lan, host, name, sess, head, seq, ts, next, pv, mh);
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
                    json.has("pv") ? json.get("pv").getAsLong() : 0, text(json, "mh"));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a rendezvous record", e);
        }
    }

    private static String text(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsString() : "";
    }
}
