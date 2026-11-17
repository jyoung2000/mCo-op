package dropin.core.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Modrinth's hash lookup and file download (DESIGN 4.14). The lookup is
 * {@code POST <base>/v2/version_files} with {@code {"hashes": [...], "algorithm": "sha512"}}; the answer
 * maps each known hash to a version object whose {@code files[]} carry {@code hashes}, {@code url},
 * {@code filename} and {@code size}. Hashes Modrinth does not know are simply absent.
 */
public final class ModrinthClient {
    public static final URI PRODUCTION = URI.create("https://api.modrinth.com");
    private static final int LOOKUP_BATCH = 200;

    /** One file Modrinth knows by its hash. */
    public record Hit(String versionId, String url, String filename, long size) {
    }

    private final URI base;
    private final String userAgent;
    private final Duration timeout;
    private final HttpClient http;

    /** @param userAgent Modrinth requires one that identifies the client uniquely */
    public ModrinthClient(URI base, String userAgent, Duration timeout) {
        this.base = base;
        this.userAgent = userAgent;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** SHA-512 (hex) to the file Modrinth has for it. Unknown hashes are not in the result. */
    public Map<String, Hit> lookup(Collection<String> sha512s) throws IOException, InterruptedException {
        Map<String, Hit> hits = new HashMap<>();
        java.util.List<String> all = java.util.List.copyOf(sha512s);
        for (int from = 0; from < all.size(); from += LOOKUP_BATCH) {
            JsonArray hashes = new JsonArray();
            all.subList(from, Math.min(all.size(), from + LOOKUP_BATCH)).forEach(hashes::add);
            JsonObject body = new JsonObject();
            body.add("hashes", hashes);
            body.addProperty("algorithm", "sha512");
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/v2/version_files")).timeout(timeout)
                    .header("User-Agent", userAgent).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Modrinth answered " + response.statusCode() + " to the hash lookup");
            }
            try {
                JsonObject versions = JsonParser.parseString(response.body()).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : versions.entrySet()) {
                    JsonObject version = entry.getValue().getAsJsonObject();
                    for (JsonElement element : version.getAsJsonArray("files")) {
                        JsonObject file = element.getAsJsonObject();
                        if (entry.getKey().equals(file.getAsJsonObject("hashes").get("sha512").getAsString())) {
                            hits.put(entry.getKey(), new Hit(version.get("id").getAsString(), file.get("url").getAsString(),
                                    file.get("filename").getAsString(), file.get("size").getAsLong()));
                        }
                    }
                }
            } catch (RuntimeException e) {
                throw new IOException("Modrinth's answer to the hash lookup could not be read", e);
            }
        }
        return hits;
    }

    /** Downloads {@code url} into {@code incoming}; the caller commits, which checks the hash. */
    public void download(String url, ModStore.Incoming incoming) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout.multipliedBy(20))
                .header("User-Agent", userAgent).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("the download answered " + response.statusCode());
            }
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0; ) {
                incoming.write(buffer, 0, n);
            }
        }
    }
}
