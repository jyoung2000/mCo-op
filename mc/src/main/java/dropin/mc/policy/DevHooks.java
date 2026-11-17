package dropin.mc.policy;

import dropin.mc.DropIn;

/**
 * Faults the self-tests inject into a host (DESIGN 4.12). They are read only in a development environment;
 * a production jar ignores them.
 */
public final class DevHooks {
    /** The host serves mod files with one byte changed. */
    public static volatile boolean tamperMods;
    /** The host answers policy requests with this {policyJson, manifestJson} instead of its own. */
    public static volatile String[] policyOverride;

    private DevHooks() {
    }

    public static boolean active() {
        return DropIn.switches().devEnvironment();
    }
}
