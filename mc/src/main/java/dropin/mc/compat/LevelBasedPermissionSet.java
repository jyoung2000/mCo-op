package dropin.mc.compat;

/**
 * 1.21.1 port: the game of this version expresses command rights as an operator level (0-4), not as the
 * permission sets of 26.x. These are the levels the 26.x sets stand for, under the same names.
 */
public final class LevelBasedPermissionSet {
    public static final Integer ALL = 0;
    public static final Integer GAMEMASTER = 2;
    public static final Integer ADMIN = 3;
    public static final Integer OWNER = 4;

    private LevelBasedPermissionSet() {
    }
}
