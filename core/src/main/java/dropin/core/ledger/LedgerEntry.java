package dropin.core.ledger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dropin.core.crypto.Hkdf;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * One checkpoint (DESIGN 4.7). {@code mac} = HMAC(auth, canonical bytes); the entry hash is SHA-256 of
 * the canonical bytes, as 64 hex characters. The canonical bytes are every field except {@code mac},
 * length-prefixed, in declaration order.
 *
 * @param parent   hash of the previous entry, "" for the first
 * @param host     uuid of the player who hosted when the checkpoint was taken
 * @param ts       epoch seconds
 * @param root     manifest root after the checkpoint
 * @param changed  number of keys changed by the checkpoint
 * @param parents2 second parent of a merge entry, "" otherwise
 */
public record LedgerEntry(long seq, String parent, String host, String name, String sess, long ts, String root,
                          int changed, String parents2, String mac) {
    private static final HexFormat HEX = HexFormat.of();

    public static LedgerEntry create(byte[] auth, long seq, String parent, String host, String name, String sess,
                                     long ts, String root, int changed) {
        return createMerge(auth, seq, parent, "", host, name, sess, ts, root, changed);
    }

    /**
     * A merge entry (DESIGN 4.10): {@code parent} is the main world's head, {@code parents2} the head of the
     * branch that was merged into it.
     */
    public static LedgerEntry createMerge(byte[] auth, long seq, String parent, String parents2, String host, String name,
                                          String sess, long ts, String root, int changed) {
        LedgerEntry unsigned = new LedgerEntry(seq, parent, host, name, sess, ts, root, changed, parents2, "");
        return new LedgerEntry(seq, parent, host, name, sess, ts, root, changed, parents2,
                HEX.formatHex(Hkdf.hmac(auth, unsigned.canonical())));
    }

    public byte[] canonical() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("dropin-ledger-1");
            out.writeLong(seq);
            out.writeUTF(parent);
            out.writeUTF(host);
            out.writeUTF(name);
            out.writeUTF(sess);
            out.writeLong(ts);
            out.writeUTF(root);
            out.writeInt(changed);
            out.writeUTF(parents2);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String hash() {
        return HEX.formatHex(Hkdf.sha256(canonical()));
    }

    public boolean verify(byte[] auth) {
        try {
            return MessageDigest.isEqual(Hkdf.hmac(auth, canonical()), HEX.parseHex(mac));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("seq", seq);
        json.addProperty("parent", parent);
        json.addProperty("host", host);
        json.addProperty("name", name);
        json.addProperty("sess", sess);
        json.addProperty("ts", ts);
        json.addProperty("root", root);
        json.addProperty("changed", changed);
        if (!parents2.isEmpty()) {
            json.addProperty("parents2", parents2);
        }
        json.addProperty("mac", mac);
        return json.toString();
    }

    /** @throws RuntimeException if the text is not a ledger entry */
    public static LedgerEntry fromJson(String text) {
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        return new LedgerEntry(json.get("seq").getAsLong(), json.get("parent").getAsString(),
                json.get("host").getAsString(), json.get("name").getAsString(), json.get("sess").getAsString(),
                json.get("ts").getAsLong(), json.get("root").getAsString(), json.get("changed").getAsInt(),
                json.has("parents2") ? json.get("parents2").getAsString() : "", json.get("mac").getAsString());
    }
}
