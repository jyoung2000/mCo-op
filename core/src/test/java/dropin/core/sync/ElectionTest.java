package dropin.core.sync;

import dropin.core.sync.Election.Peer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ElectionTest {
    private static final String HOST = "00000000-0000-0000-0000-00000000000a";
    private static final String B = "00000000-0000-0000-0000-00000000000b";
    private static final String C = "00000000-0000-0000-0000-00000000000c";
    private static final String D = "00000000-0000-0000-0000-00000000000d";

    @Test
    void nobodyToElect() {
        assertEquals(List.of(), Election.order(List.of(), HOST));
        assertEquals(-1, Election.turnAfterMs(List.of(), B));
    }

    @Test
    void theNewestCompleteCopyComesFirstWhateverTheSpeed() {
        List<Peer> peers = List.of(new Peer(B, 6, 12_000), new Peer(C, 7, 100), new Peer(D, 5, 12_000));
        assertEquals(List.of(C, B, D), Election.order(peers, HOST));
    }

    @Test
    void amongEqualCopiesTheFasterUploadComesFirst() {
        List<Peer> peers = List.of(new Peer(B, 7, 500), new Peer(C, 7, 4_000), new Peer(D, 7, 0));
        assertEquals(List.of(C, B, D), Election.order(peers, HOST));
    }

    @Test
    void speedsOfTheSameStepDoNotDecideAndTheLowestUuidWins() {
        // 1100 and 1900 KB/s are in one step of a factor of two: two measurements of about the same link.
        assertEquals(Successor.speedStep(1_100), Successor.speedStep(1_900));
        List<Peer> peers = List.of(new Peer(D, 7, 1_900), new Peer(C, 7, 1_100), new Peer(B, 7, 1_500));
        assertEquals(List.of(B, C, D), Election.order(peers, HOST));
        // Everything from "fast enough" up counts as equal too.
        peers = List.of(new Peer(C, 7, 900_000), new Peer(B, 7, Successor.FAST_ENOUGH_KBPS));
        assertEquals(List.of(B, C), Election.order(peers, HOST));
    }

    @Test
    void theOrderDoesNotDependOnTheOrderOfTheList() {
        List<Peer> one = List.of(new Peer(B, 7, 0), new Peer(C, 7, 3_000), new Peer(D, 8, 0));
        List<Peer> other = List.of(new Peer(D, 8, 0), new Peer(C, 7, 3_000), new Peer(B, 7, 0));
        assertEquals(List.of(D, C, B), Election.order(one, HOST));
        assertEquals(Election.order(one, HOST), Election.order(other, HOST));
    }

    @Test
    void theHostAndMembersWithoutACompleteCopyAreLeftOut() {
        List<Peer> peers = List.of(new Peer(HOST, 9, 9_000), new Peer(B, 0, 9_000), new Peer("", 7, 9_000), new Peer(C, 3, 0));
        assertEquals(List.of(C), Election.order(peers, HOST));
    }

    @Test
    void aMemberWithTwoConnectionsCountsOnceWithItsBestValues() {
        List<Peer> peers = List.of(new Peer(B, 5, 0), new Peer(C, 6, 100), new Peer(B, 6, 4_000));
        assertEquals(List.of(B, C), Election.order(peers, HOST));
    }

    @Test
    void eachPlaceInTheOrderWaitsFortyFiveSecondsLonger() {
        List<String> order = List.of(C, B, D);
        assertEquals(0, Election.turnAfterMs(order, C));
        assertEquals(45_000, Election.turnAfterMs(order, B));
        assertEquals(90_000, Election.turnAfterMs(order, D));
        assertEquals(-1, Election.turnAfterMs(order, HOST));
    }

    @Test
    void theListRoundTripsThroughAMessage() throws IOException {
        List<Peer> peers = List.of(new Peer(B, 7, 2_048), new Peer(C, 0, 0));
        byte[] message = Election.write(new Wire.Out(Wire.PEERS), peers).toBytes();
        Wire.In in = new Wire.In(message);
        assertEquals(Wire.PEERS, in.type);
        assertEquals(peers, Election.read(in));
        assertEquals(List.of(), Election.read(new Wire.In(Election.write(new Wire.Out(Wire.PEERS), List.of()).toBytes())));
    }

    @Test
    void aMalformedListIsRefused() {
        assertThrows(IOException.class, () -> Election.read(new Wire.In(new Wire.Out(Wire.PEERS).i32(-1).toBytes())));
        assertThrows(IOException.class, () -> Election.read(new Wire.In(new Wire.Out(Wire.PEERS).i32(Election.MAX_PEERS + 1).toBytes())));
        // Announces two peers, carries one.
        assertThrows(IOException.class,
                () -> Election.read(new Wire.In(new Wire.Out(Wire.PEERS).i32(2).str(B).i64(7).i32(1).toBytes())));
    }
}
