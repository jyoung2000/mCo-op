package dropin.mc.selftest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Result file read by {@code scripts/selftest.ps1}: {@code <gameDir>/dropin-selftest/result.json}.
 * Development only.
 */
public record SelfTestResult(String scenario, String role, String loader, boolean passed, String failure,
                             long durationMs, List<StepRunner.StepRecord> steps) {
    public static final String DIR = "dropin-selftest";
    public static final String FILE = "result.json";

    public String toJson() {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"scenario\": ").append(quote(scenario)).append(",\n");
        sb.append("  \"role\": ").append(quote(role)).append(",\n");
        sb.append("  \"loader\": ").append(quote(loader)).append(",\n");
        sb.append("  \"passed\": ").append(passed).append(",\n");
        sb.append("  \"failure\": ").append(failure == null ? "null" : quote(failure)).append(",\n");
        sb.append("  \"durationMs\": ").append(durationMs).append(",\n");
        sb.append("  \"steps\": [");
        for (int i = 0; i < steps.size(); i++) {
            StepRunner.StepRecord step = steps.get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {\"name\": ").append(quote(step.name()))
                    .append(", \"ticks\": ").append(step.ticks())
                    .append(", \"ok\": ").append(step.ok()).append('}');
        }
        sb.append(steps.isEmpty() ? "]\n" : "\n  ]\n");
        return sb.append("}\n").toString();
    }

    /** Writes atomically so the orchestrator never reads a half-written file. */
    public void write(Path gameDir) throws IOException {
        Path dir = gameDir.resolve(DIR);
        Files.createDirectories(dir);
        Path tmp = dir.resolve(FILE + ".tmp");
        Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
