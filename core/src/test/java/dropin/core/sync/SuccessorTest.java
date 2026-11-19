package dropin.core.sync;

import dropin.core.sync.Successor.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SuccessorTest {
    private static final String HOST = "00000000-0000-0000-0000-00000000000a";
    private static final String B = "00000000-0000-0000-0000-00000000000b";
    private static final String C = "00000000-0000-0000-0000-00000000000c";

    @Test
    void nobodyConnected() {
        assertEquals(Optional.empty(), Successor.choose(List.of(), 7, HOST));
    }

    @Test
    void longestConnectedFullySyncedPeerWins() {
        List<Candidate> peers = List.of(new Candidate(C, 7, 2_000), new Candidate(B, 7, 1_000));
        assertEquals(Optional.of(B), Successor.choose(peers, 7, HOST));
    }

    @Test
    void aPeerBehindTheFinalCheckpointIsNotChosen() {
        List<Candidate> peers = List.of(new Candidate(B, 6, 1_000), new Candidate(C, 7, 2_000));
        assertEquals(Optional.of(C), Successor.choose(peers, 7, HOST));
    }

    @Test
    void nobodyHasAFullCopy() {
        List<Candidate> peers = List.of(new Candidate(B, 6, 1_000), new Candidate(C, 0, 2_000));
        assertEquals(Optional.empty(), Successor.choose(peers, 7, HOST));
    }

    @Test
    void theHostItselfAndNamelessPeersAreSkipped() {
        List<Candidate> peers = List.of(new Candidate(HOST, 7, 1), new Candidate("", 7, 2), new Candidate(null, 7, 3),
                new Candidate(C, 7, 4));
        assertEquals(Optional.of(C), Successor.choose(peers, 7, HOST));
    }

    @Test
    void tiesGoToTheLowestUuid() {
        List<Candidate> peers = List.of(new Candidate(C, 7, 1_000), new Candidate(B, 7, 1_000));
        assertEquals(Optional.of(B), Successor.choose(peers, 7, HOST));
    }

    @Test
    void aSecondConnectionOfTheSamePlayerDoesNotMatter() {
        List<Candidate> peers = List.of(new Candidate(B, 3, 1_000), new Candidate(B, 7, 5_000), new Candidate(C, 7, 6_000));
        assertEquals(Optional.of(B), Successor.choose(peers, 7, HOST));
    }

    @Test
    void theFastestUploadWinsOverTheLongestConnected() {
        List<Candidate> peers = List.of(new Candidate(B, 7, 1_000, 300), new Candidate(C, 7, 2_000, 2_500));
        assertEquals(Optional.of(C), Successor.choose(peers, 7, HOST));
    }

    @Test
    void aboutTheSameSpeedDoesNotDecide() {
        // 1100 and 1900 KB/s are in the same step (1024 to 2047): the longest connected wins.
        List<Candidate> peers = List.of(new Candidate(C, 7, 2_000, 1_900), new Candidate(B, 7, 1_000, 1_100));
        assertEquals(Optional.of(B), Successor.choose(peers, 7, HOST));
    }

    @Test
    void speedsThatAreFastEnoughCountAsEqual() {
        List<Candidate> peers = List.of(new Candidate(C, 7, 2_000, 900_000), new Candidate(B, 7, 1_000, 13_000));
        assertEquals(Optional.of(B), Successor.choose(peers, 7, HOST));
    }

    @Test
    void aMeasuredPeerWinsOverAnUnmeasuredOne() {
        List<Candidate> peers = List.of(new Candidate(B, 7, 1_000), new Candidate(C, 7, 2_000, 40));
        assertEquals(Optional.of(C), Successor.choose(peers, 7, HOST));
    }

    @Test
    void aFastPeerWithoutAFullCopyIsNotChosen() {
        List<Candidate> peers = List.of(new Candidate(B, 6, 1_000, 9_000), new Candidate(C, 7, 2_000, 40));
        assertEquals(Optional.of(C), Successor.choose(peers, 7, HOST));
    }

    @Test
    void sendDelayKeepsAConnectionUnderItsRate() {
        assertEquals(1000, Successor.sendDelayMs(2048 * 1024, 2048));
        assertEquals(15, Successor.sendDelayMs(32 * 1024, 2048));
        assertEquals(0, Successor.sendDelayMs(32 * 1024, 0));
        assertEquals(0, Successor.sendDelayMs(0, 128));
    }

    @Test
    void noCheckpointYetMeansNoSuccessor() {
        assertEquals(Optional.empty(), Successor.choose(List.of(new Candidate(B, 0, 1)), 0, HOST));
    }
}
