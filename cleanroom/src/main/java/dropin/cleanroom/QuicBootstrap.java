package dropin.cleanroom;

import dropin.tunnel.Agnos;
import dropin.tunnel.E4mcClient;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Puts the bundled netty-quic jar into the JDK's app class loader.
 *
 * <p>Cleanroom's class loader hands every {@code io.netty.*} class to its parent, the app class loader
 * (Foundation's {@code ActualClassLoader} class loader exclusions), so netty-quic's classes cannot be loaded from the
 * mod jar. They cannot be relocated either: e4mc's prebuilt quiche natives bind to {@code io.netty.incubator.codec.quic}
 * (session-notes.md). The jar ships nested at {@code dropin/libs/netty-quic.jar}; it is copied to the config folder and
 * appended to the app class loader with the JDK's own {@code appendToClassPathForInstrumentation}, which Cleanroom
 * makes reachable by opening the boot modules at startup (ImagineBreaker, {@code Foundation.breakModuleAndReflection}).
 * Development runs take the same path: the quic jar is never on a run classpath.
 */
final class QuicBootstrap {
    private static final String RESOURCE = "/dropin/libs/netty-quic.jar";
    private static final String PROBE = "io.netty.incubator.codec.quic.Quic";
    private static boolean installed;

    private QuicBootstrap() {
    }

    static synchronized void install() {
        if (installed) {
            return;
        }
        ClassLoader app = ClassLoader.getSystemClassLoader();
        if (present(app)) {
            installed = true;
            return;
        }
        try (InputStream in = QuicBootstrap.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The mod jar has no " + RESOURCE);
            }
            byte[] bytes = in.readAllBytes();
            Path dir = Agnos.configDir().resolve("dropin").resolve("libs");
            Files.createDirectories(dir);
            Path jar = dir.resolve("netty-quic-" + HexFormat.of().formatHex(sha256(bytes), 0, 8) + ".jar");
            if (!Files.isRegularFile(jar) || Files.size(jar) != bytes.length) {
                Path tmp = Files.createTempFile(dir, "netty-quic", ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            Method append = app.getClass().getDeclaredMethod("appendToClassPathForInstrumentation", String.class);
            append.setAccessible(true);
            append.invoke(app, jar.toAbsolutePath().toString());
            if (!present(app)) {
                throw new IllegalStateException("netty-quic is still not visible after appending " + jar);
            }
            E4mcClient.LOGGER.info("netty-quic added to the app class loader from {}", jar);
            installed = true;
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Could not make netty-quic available to the game", e);
        }
    }

    private static boolean present(ClassLoader loader) {
        try {
            Class.forName(PROBE, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
