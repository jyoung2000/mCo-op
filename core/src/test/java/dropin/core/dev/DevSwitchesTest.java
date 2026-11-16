package dropin.core.dev;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gate scenario {@code prod-guard} (MILESTONES M0). */
class DevSwitchesTest {
    private static final Map<String, String> ALL_ON = Map.of(
            DevSwitches.SELFTEST, "lan-join:host",
            DevSwitches.OFFLINE_LAN, "true",
            "dropin.dev.somethingElse", "true");

    @Test
    void productionIgnoresSelfTest() {
        var switches = new DevSwitches(false, ALL_ON::get);
        assertEquals(Optional.empty(), switches.selfTest());
    }

    @Test
    void productionIgnoresOfflineLan() {
        var switches = new DevSwitches(false, ALL_ON::get);
        assertFalse(switches.offlineLan());
    }

    @Test
    void productionIgnoresEveryDevSwitch() {
        var switches = new DevSwitches(false, ALL_ON::get);
        assertFalse(switches.flag("dropin.dev.somethingElse"));
        assertEquals(Optional.empty(), switches.read("dropin.dev.somethingElse"));
    }

    @Test
    void productionNeverLooksAtTheProperties() {
        var switches = new DevSwitches(false, name -> {
            throw new AssertionError("read " + name + " in production");
        });
        assertEquals(Optional.empty(), switches.selfTest());
        assertFalse(switches.offlineLan());
    }

    @Test
    void productionDoesNotFailOnMalformedSelfTest() {
        var switches = new DevSwitches(false, name -> "garbage");
        assertEquals(Optional.empty(), switches.selfTest());
    }

    @Test
    void devReadsSelfTest() {
        var switches = new DevSwitches(true, ALL_ON::get);
        assertEquals(Optional.of(new SelfTestSpec("lan-join", "host")), switches.selfTest());
    }

    @Test
    void devReadsOfflineLan() {
        assertTrue(new DevSwitches(true, ALL_ON::get).offlineLan());
        assertFalse(new DevSwitches(true, name -> null).offlineLan());
        assertFalse(new DevSwitches(true, name -> "false").offlineLan());
    }

    @Test
    void devWithoutSelfTestPropertyIsEmpty() {
        assertEquals(Optional.empty(), new DevSwitches(true, name -> null).selfTest());
        assertEquals(Optional.empty(), new DevSwitches(true, name -> " ").selfTest());
    }

    @Test
    void devRejectsMalformedSelfTest() {
        for (String bad : new String[]{"lan-join", ":host", "lan-join:", "a:b:c"}) {
            var switches = new DevSwitches(true, name -> bad);
            assertThrows(IllegalArgumentException.class, switches::selfTest, bad);
        }
    }

    @Test
    void onlyDevSwitchNamesAreAccepted() {
        var switches = new DevSwitches(true, ALL_ON::get);
        assertThrows(IllegalArgumentException.class, () -> switches.read("user.home"));
        assertThrows(IllegalArgumentException.class, () -> switches.flag("dropin.rendezvous"));
    }
}
