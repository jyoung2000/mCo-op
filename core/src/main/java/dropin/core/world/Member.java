package dropin.core.world;

/**
 * Someone who has played a co-op world with the mod (DESIGN 4.15).
 *
 * @param firstSeen epoch seconds
 * @param lastSeen  epoch seconds
 * @param role      {@link #OWNER} or {@link #MEMBER}
 */
public record Member(String uuid, String name, long firstSeen, long lastSeen, String role) {
    public static final String OWNER = "owner";
    public static final String MEMBER = "member";

    public boolean isOwner() {
        return OWNER.equals(role);
    }
}
