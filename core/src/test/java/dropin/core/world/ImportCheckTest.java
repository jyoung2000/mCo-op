package dropin.core.world;

import dropin.core.world.ImportCheck.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImportCheckTest {
    @Test
    void plainWorldOfTheRunningVersionIsAccepted() {
        assertEquals(Result.OK, ImportCheck.check(false, false, false, 100, 100));
    }

    @Test
    void anOpenWorldIsRefusedBeforeAnythingElse() {
        assertEquals(Result.OPEN, ImportCheck.check(true, true, true, 1, 100));
        assertEquals(Result.OPEN, ImportCheck.check(true, false, false, 100, 100));
    }

    @Test
    void aMarkedWorldIsRefusedBeforeTheVersionIsLookedAt() {
        assertEquals(Result.ALREADY_COOP, ImportCheck.check(false, true, true, 1, 100));
        assertEquals(Result.NEEDS_INVITE, ImportCheck.check(false, true, false, 200, 100));
    }

    @Test
    void olderAndNewerWorldsAreRefusedDifferently() {
        assertEquals(Result.TOO_OLD, ImportCheck.check(false, false, false, 99, 100));
        assertEquals(Result.TOO_NEW, ImportCheck.check(false, false, false, 101, 100));
    }
}
