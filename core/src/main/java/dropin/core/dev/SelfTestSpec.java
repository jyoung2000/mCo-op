package dropin.core.dev;

/** Parsed value of {@code -Ddropin.selftest=<scenario>:<role>}. */
public record SelfTestSpec(String scenario, String role) {
    public static SelfTestSpec parse(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1 || value.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException("Expected <scenario>:<role>, got: " + value);
        }
        return new SelfTestSpec(value.substring(0, colon), value.substring(colon + 1));
    }
}
