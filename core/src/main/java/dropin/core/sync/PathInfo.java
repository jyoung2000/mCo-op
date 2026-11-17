package dropin.core.sync;

/**
 * Reads "direct or relayed" out of what iroh-java reports for a connection (DESIGN 3 row 9). The
 * native side writes one line per path:
 * {@code path <id> active <true|false> rtt <ms> local <addr> remote <addr>}, where an address is
 * {@code ip v4 priv}, {@code ip v6 glob}, ... for a direct path and {@code relay <url>} for a relayed one.
 */
public final class PathInfo {
    private PathInfo() {
    }

    /** True only if the path in use goes straight to the peer's IP address. */
    public static boolean direct(String debugInfo) {
        if (debugInfo == null) {
            return false;
        }
        for (String line : debugInfo.split("\n")) {
            if (line.contains(" active true ")) {
                int remote = line.indexOf(" remote ");
                return remote >= 0 && line.substring(remote + 8).startsWith("ip ");
            }
        }
        return false;
    }
}
