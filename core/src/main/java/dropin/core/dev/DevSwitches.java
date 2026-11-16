package dropin.core.dev;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The only place that reads {@code dropin.selftest} and {@code dropin.dev.*} switches (DESIGN §4.12).
 * Outside a development environment every switch reads as absent, whatever the properties say.
 */
public final class DevSwitches {
    public static final String SELFTEST = "dropin.selftest";
    public static final String DEV_PREFIX = "dropin.dev.";
    public static final String OFFLINE_LAN = DEV_PREFIX + "offlineLan";

    private final boolean devEnvironment;
    private final Function<String, String> properties;

    public DevSwitches(boolean devEnvironment, Function<String, String> properties) {
        this.devEnvironment = devEnvironment;
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public static DevSwitches fromSystemProperties(boolean devEnvironment) {
        return new DevSwitches(devEnvironment, System::getProperty);
    }

    public boolean devEnvironment() {
        return devEnvironment;
    }

    /** The requested self-test, or empty when not in a development environment or not requested. */
    public Optional<SelfTestSpec> selfTest() {
        return read(SELFTEST).map(SelfTestSpec::parse);
    }

    public boolean offlineLan() {
        return flag(OFFLINE_LAN);
    }

    /** A {@code dropin.dev.*} boolean switch; false outside a development environment. */
    public boolean flag(String name) {
        return read(name).map("true"::equalsIgnoreCase).orElse(false);
    }

    /** A {@code dropin.selftest} / {@code dropin.dev.*} value; empty outside a development environment. */
    public Optional<String> read(String name) {
        if (!name.equals(SELFTEST) && !name.startsWith(DEV_PREFIX)) {
            throw new IllegalArgumentException("Not a dev switch: " + name);
        }
        if (!devEnvironment) {
            return Optional.empty();
        }
        String value = properties.apply(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }
}
