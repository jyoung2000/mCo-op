package dropin.core.mods;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModsTest {
    private static final String SHA512_A = "a".repeat(128);
    private static final String SHA1_A = "b".repeat(40);

    private static Path jar(Path file, String entryName, String entryText, String filler) throws IOException {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            if (entryName != null) {
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(entryText.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("filler.txt"));
            zip.write(filler.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    private static Path fabricJar(Path file, String id, String version) throws IOException {
        return jar(file, "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\"}", id + version);
    }

    private static ModManifest.Entry entry(String path, long size) {
        return new ModManifest.Entry(path, size, SHA512_A, SHA1_A, "mod", "1.0", ModManifest.PEER, "", "");
    }

    private static List<String> names(List<ModManifest.Entry> entries) {
        return entries.stream().map(ModManifest.Entry::path).toList();
    }

    private static List<String> jarsIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void scanListsOnlyTopLevelJarsAndReadsTheirIds(@TempDir Path dir) throws IOException {
        Path mods = dir.resolve("mods");
        fabricJar(mods.resolve("alpha-1.0.jar"), "alpha", "1.0");
        jar(mods.resolve("beta.jar"), "META-INF/neoforge.mods.toml",
                "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n[[mods]]\nmodId=\"beta\"\nversion=\"2.5\"\n", "beta");
        jar(mods.resolve("plain.jar"), null, "", "plain");
        fabricJar(mods.resolve("dropin-0.1.jar"), "dropin_coop", "0.1");
        fabricJar(mods.resolve(".hidden.jar"), "hidden", "1");
        fabricJar(mods.resolve(".dropin-disabled").resolve("20260101-000000").resolve("old.jar"), "old", "1");
        Files.writeString(mods.resolve("readme.txt"), "not a mod");

        List<ModManifest.Entry> found = ModScan.scan(mods, Set.of("dropin_coop"), "fabric");
        assertEquals(List.of("alpha-1.0.jar", "beta.jar", "plain.jar"), names(found));
        assertEquals("alpha", found.get(0).modId());
        assertEquals("1.0", found.get(0).version());
        assertEquals("beta", found.get(1).modId());
        assertEquals("2.5", found.get(1).version());
        assertEquals("", found.get(2).modId());
        assertEquals("plain.jar", found.get(2).displayName());
        assertEquals(Files.size(mods.resolve("alpha-1.0.jar")), found.get(0).size());
        assertEquals(SwapHelper.sha512(mods.resolve("alpha-1.0.jar")), found.get(0).sha512());
        assertEquals(40, found.get(0).sha1().length());
        assertEquals(List.of(), ModScan.scan(dir.resolve("nowhere"), Set.of(), "fabric"));
    }

    @Test
    void manifestHashDependsOnContentNotOnInputOrder() {
        ModManifest.Entry a = entry("a.jar", 10);
        ModManifest.Entry b = entry("b.jar", 20);
        ModManifest one = new ModManifest(List.of(a, b));
        ModManifest two = new ModManifest(List.of(b, a));
        assertEquals(one.hash(), two.hash());
        assertEquals(64, one.hash().length());
        assertEquals(List.of("a.jar", "b.jar"), names(two.files()));
        assertEquals(30, one.totalBytes());
        assertNotEquals(one.hash(), new ModManifest(List.of(a, entry("b.jar", 21))).hash());
        assertNotEquals(one.hash(), new ModManifest(List.of(a)).hash());
        assertNotEquals(one.hash(), ModManifest.EMPTY.hash());
        assertEquals(one.hash(), ModManifest.fromJson(one.toJson()).hash());
        assertThrows(IllegalArgumentException.class, () -> ModManifest.fromJson("{\"files\":[{}]}"));
    }

    @Test
    void modrinthSourcesBecomePartOfTheManifest() throws IOException {
        ModManifest manifest = new ModManifest(List.of(entry("a.jar", 10)));
        ModManifest known = manifest.withSources(Map.of(SHA512_A,
                new ModrinthClient.Hit("ver123", "https://cdn.modrinth.com/data/x/a.jar", "a.jar", 10)));
        assertEquals(ModManifest.MODRINTH, known.files().get(0).source());
        assertEquals("ver123", known.files().get(0).versionId());
        assertNotEquals(manifest.hash(), known.hash());
        known.validate(ModManifest.Limits.DEFAULT);
        assertEquals(known.hash(), ModManifest.fromJson(known.toJson()).hash());
        // A hit whose size differs, or whose URL is not https, is not trusted as a source.
        assertEquals(ModManifest.PEER, manifest.withSources(Map.of(SHA512_A,
                new ModrinthClient.Hit("v", "https://cdn.modrinth.com/a.jar", "a.jar", 11))).files().get(0).source());
        assertEquals(ModManifest.PEER, manifest.withSources(Map.of(SHA512_A,
                new ModrinthClient.Hit("v", "http://cdn.modrinth.com/a.jar", "a.jar", 10))).files().get(0).source());
        assertEquals(manifest.hash(), known.withSources(Map.of()).hash());
    }

    @Test
    void diffSaysWhatToDownloadWhatToRemoveAndWhatIsKept(@TempDir Path dir) throws IOException {
        Path owner = dir.resolve("owner");
        fabricJar(owner.resolve("a.jar"), "a", "1");
        fabricJar(owner.resolve("b.jar"), "b", "2");
        ModManifest wanted = new ModManifest(ModScan.scan(owner, Set.of(), "fabric"));

        Path mine = dir.resolve("mine");
        Files.createDirectories(mine);
        Files.copy(owner.resolve("a.jar"), mine.resolve("a.jar"));
        fabricJar(mine.resolve("b.jar"), "b", "1");
        fabricJar(mine.resolve("c.jar"), "c", "1");
        fabricJar(mine.resolve("Minimap.jar"), "minimap", "1");

        ModDiff diff = ModDiff.of(wanted, ModScan.scan(mine, Set.of(), "fabric"), Set.of("minimap.jar"));
        assertEquals(List.of("b.jar"), names(diff.toDownload()));
        assertEquals(List.of("b.jar", "c.jar"), names(diff.toRemove()));
        assertEquals(List.of("Minimap.jar"), names(diff.keptExtras()));
        assertEquals(Files.size(owner.resolve("b.jar")), diff.downloadBytes());
        assertFalse(diff.mayJoin());
        assertFalse(diff.mayHost());

        ModDiff nothingKept = ModDiff.of(wanted, ModScan.scan(mine, Set.of(), "fabric"), Set.of());
        assertEquals(List.of("Minimap.jar", "b.jar", "c.jar"), names(nothingKept.toRemove()));

        ModDiff same = ModDiff.of(wanted, ModScan.scan(owner, Set.of(), "fabric"), Set.of());
        assertTrue(same.mayJoin());
        assertTrue(same.mayHost());

        Files.copy(mine.resolve("Minimap.jar"), owner.resolve("Minimap.jar"));
        ModDiff withExtra = ModDiff.of(wanted, ModScan.scan(owner, Set.of(), "fabric"), Set.of("Minimap.jar"));
        assertTrue(withExtra.mayJoin());
        assertFalse(withExtra.mayHost());

        assertTrue(ModDiff.of(ModManifest.EMPTY, List.of(), Set.of()).mayHost());
    }

    @Test
    void unsafeFileNamesAreRefused() {
        for (String name : List.of("../evil.jar", "..\\evil.jar", "sub/evil.jar", "sub\\evil.jar", "C:evil.jar", "evil.txt",
                ".evil.jar", "", "evil\u0000.jar", "a..jar", "x".repeat(250) + ".jar")) {
            assertThrows(ModManifest.ModException.class, () -> ModManifest.safeName(name), name);
            ModManifest manifest = new ModManifest(List.of(entry(name, 1)));
            assertThrows(ModManifest.ModException.class, () -> manifest.validate(ModManifest.Limits.DEFAULT), name);
        }
        assertDoesNotThrowValidate(new ModManifest(List.of(entry("Good Mod-1.2+mc26.3.jar", 1))));
    }

    private static void assertDoesNotThrowValidate(ModManifest manifest) {
        try {
            manifest.validate(ModManifest.Limits.DEFAULT);
        } catch (ModManifest.ModException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aManifestOverTheCapsOrMalformedIsRefused() {
        ModManifest.Limits limits = new ModManifest.Limits(100, 150, 2);
        assertDoesNotThrowValidate(new ModManifest(List.of(entry("a.jar", 100))));
        assertThrows(ModManifest.ModException.class, () -> new ModManifest(List.of(entry("a.jar", 101))).validate(limits));
        assertThrows(ModManifest.ModException.class,
                () -> new ModManifest(List.of(entry("a.jar", 100), entry("b.jar", 51))).validate(limits));
        assertThrows(ModManifest.ModException.class,
                () -> new ModManifest(List.of(entry("a.jar", 1), entry("b.jar", 1), entry("c.jar", 1))).validate(limits));
        assertThrows(ModManifest.ModException.class, () -> new ModManifest(List.of(entry("a.jar", -1))).validate(limits));
        assertThrows(ModManifest.ModException.class,
                () -> new ModManifest(List.of(entry("a.jar", 1), entry("A.JAR", 1))).validate(limits));
        ModManifest.Entry badHash = new ModManifest.Entry("a.jar", 1, "../" + "a".repeat(125), SHA1_A, "", "", ModManifest.PEER, "", "");
        assertThrows(ModManifest.ModException.class, () -> new ModManifest(List.of(badHash)).validate(limits));
        ModManifest.Entry httpUrl = entry("a.jar", 1).withSource(ModManifest.MODRINTH, "v", "http://example.invalid/a.jar");
        assertThrows(ModManifest.ModException.class, () -> new ModManifest(List.of(httpUrl)).validate(limits));
        ModManifest.Entry oddSource = entry("a.jar", 1).withSource("ftp", "", "");
        assertThrows(ModManifest.ModException.class, () -> new ModManifest(List.of(oddSource)).validate(limits));
        ModManifest.Entry tab = new ModManifest.Entry("a.jar", 1, SHA512_A, SHA1_A, "mod\tx", "", ModManifest.PEER, "", "");
        assertThrows(ModManifest.ModException.class, () -> new ModManifest(List.of(tab)).validate(limits));
    }

    @Test
    void theStoreOnlyKeepsFilesThatMatchTheManifest(@TempDir Path dir) throws IOException {
        Path good = fabricJar(dir.resolve("src").resolve("a.jar"), "a", "1");
        Path other = fabricJar(dir.resolve("src").resolve("other.jar"), "other", "1");
        ModManifest.Entry entry = ModScan.scan(dir.resolve("src"), Set.of(), "fabric").get(0);
        ModStore store = new ModStore(dir.resolve("modstore"));
        assertFalse(store.has(entry.sha512()));

        assertThrows(IOException.class, () -> store.put(other, entry));
        assertFalse(store.has(entry.sha512()));
        assertEquals(List.of(), jarsIn(store.dir()));

        byte[] bytes = Files.readAllBytes(good);
        try (ModStore.Incoming incoming = store.receive(entry)) {
            incoming.write(bytes, 0, bytes.length);
            assertThrows(IOException.class, () -> incoming.write(new byte[1], 0, 1));
        }
        assertEquals(List.of(), jarsIn(store.dir()));

        try (ModStore.Incoming incoming = store.receive(entry)) {
            incoming.write(bytes, 0, bytes.length - 1);
            assertThrows(IOException.class, incoming::commit);
        }
        assertFalse(store.has(entry.sha512()));

        store.put(good, entry);
        assertTrue(store.has(entry.sha512()));
        assertEquals(List.of(entry.sha512() + ".jar"), jarsIn(store.dir()));
        Files.writeString(store.file(entry.sha512()), "damaged");
        assertFalse(store.has(entry.sha512()));
        assertThrows(IllegalArgumentException.class, () -> store.file("../x"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSwapPlanMakesTheFolderMatchTheManifest(@TempDir Path dir) throws IOException {
        Path owner = dir.resolve("owner");
        fabricJar(owner.resolve("a.jar"), "a", "1");
        fabricJar(owner.resolve("b.jar"), "b", "2");
        ModManifest manifest = new ModManifest(ModScan.scan(owner, Set.of(), "fabric"));
        ModStore store = new ModStore(dir.resolve("modstore"));
        // Only b has to come from the store: a is already in place.
        store.put(owner.resolve("b.jar"), manifest.files().get(1));

        Path mods = dir.resolve("mods");
        Files.createDirectories(mods);
        Files.copy(owner.resolve("a.jar"), mods.resolve("a.jar"));
        fabricJar(mods.resolve("b.jar"), "b", "1");
        fabricJar(mods.resolve("extra.jar"), "extra", "1");
        fabricJar(mods.resolve("minimap.jar"), "minimap", "1");
        fabricJar(mods.resolve("dropin.jar"), "dropin_coop", "1");

        Instant now = Instant.parse("2026-11-17T10:20:30Z");
        SwapPlan plan = SwapPlan.of(manifest, List.of("minimap.jar", "dropin.jar"), 42, mods, store.dir(), now);
        assertEquals(mods.resolve(".dropin-disabled").resolve("20261117-102030"), plan.disabledDir());
        List<String> moved = new ArrayList<>();
        List<String> installed = new ArrayList<>();
        SwapHelper.apply((Map<String, Object>) SwapHelper.parseJson(plan.toJson()), moved, installed);

        assertEquals(List.of("b.jar", "extra.jar"), moved);
        assertEquals(List.of("b.jar"), installed);
        assertEquals(List.of("a.jar", "b.jar", "dropin.jar", "minimap.jar"), jarsIn(mods));
        assertEquals(List.of("b.jar", "extra.jar"), jarsIn(plan.disabledDir()));
        ModDiff after = ModDiff.of(manifest, ModScan.scan(mods, Set.of("dropin_coop"), "fabric"), Set.of("minimap.jar"));
        assertTrue(after.mayJoin());
        assertFalse(after.mayHost());
    }

    @Test
    void modrinthLookupAndDownloadAgainstAFakeServer(@TempDir Path dir) throws Exception {
        Path jar = fabricJar(dir.resolve("src").resolve("known.jar"), "known", "1");
        fabricJar(dir.resolve("src").resolve("unknown.jar"), "unknown", "1");
        List<ModManifest.Entry> files = ModScan.scan(dir.resolve("src"), Set.of(), "fabric");
        ModManifest.Entry known = files.get(0);
        ModManifest.Entry unknown = files.get(1);
        byte[] bytes = Files.readAllBytes(jar);
        AtomicReference<String> seenAgent = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        AtomicReference<byte[]> served = new AtomicReference<>(bytes);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/v2/version_files", exchange -> {
            seenAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String answer = "{\"" + known.sha512() + "\":{\"id\":\"VER1\",\"project_id\":\"P1\",\"files\":["
                    + "{\"hashes\":{\"sha512\":\"" + "0".repeat(128) + "\",\"sha1\":\"x\"},\"url\":\"" + base + "/wrong.jar\","
                    + "\"filename\":\"sources.jar\",\"primary\":false,\"size\":3},"
                    + "{\"hashes\":{\"sha512\":\"" + known.sha512() + "\",\"sha1\":\"" + known.sha1() + "\"},\"url\":\"" + base
                    + "/data/known.jar\",\"filename\":\"known.jar\",\"primary\":true,\"size\":" + bytes.length + "}]}}";
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/data/known.jar", exchange -> {
            exchange.sendResponseHeaders(200, served.get().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(served.get());
            }
        });
        server.createContext("/broken", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            ModrinthClient client = new ModrinthClient(URI.create(base), "dropin-coop/test", Duration.ofSeconds(10));
            Map<String, ModrinthClient.Hit> hits = client.lookup(List.of(known.sha512(), unknown.sha512()));
            assertEquals(Set.of(known.sha512()), hits.keySet());
            ModrinthClient.Hit hit = hits.get(known.sha512());
            assertEquals("VER1", hit.versionId());
            assertEquals(base + "/data/known.jar", hit.url());
            assertEquals(bytes.length, hit.size());
            assertEquals("dropin-coop/test", seenAgent.get());
            assertTrue(seenBody.get().contains("\"algorithm\":\"sha512\""), seenBody.get());
            assertTrue(seenBody.get().contains(unknown.sha512()), seenBody.get());
            assertEquals(Map.of(), client.lookup(List.of()));

            ModStore store = new ModStore(dir.resolve("modstore"));
            try (ModStore.Incoming incoming = store.receive(known)) {
                client.download(hit.url(), incoming);
                incoming.commit();
            }
            assertTrue(store.has(known.sha512()));

            // The same URL now serves something else: it never reaches the store.
            Files.delete(store.file(known.sha512()));
            byte[] tampered = bytes.clone();
            tampered[tampered.length - 1] ^= 1;
            served.set(tampered);
            try (ModStore.Incoming incoming = store.receive(known)) {
                client.download(hit.url(), incoming);
                assertThrows(IOException.class, incoming::commit);
            }
            assertFalse(store.has(known.sha512()));
            assertEquals(List.of(), jarsIn(store.dir()));

            served.set(new byte[bytes.length + 5]);
            try (ModStore.Incoming incoming = store.receive(known)) {
                assertThrows(IOException.class, () -> client.download(hit.url(), incoming));
            }
            try (ModStore.Incoming incoming = store.receive(known)) {
                assertThrows(IOException.class, () -> client.download(base + "/broken", incoming));
            }
            ModrinthClient broken = new ModrinthClient(URI.create(base + "/broken/"), "dropin-coop/test", Duration.ofSeconds(10));
            assertNull(broken.lookup(List.of()).get("x"));
        } finally {
            server.stop(0);
        }
    }
}
