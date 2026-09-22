package dev.p2wn.startupguardian;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ensures major deterministic feature families retain concrete regression evidence. */
class FullFeatureCoverageContractTest {

    @Test
    void majorFeatureFamiliesRetainRegressionEvidence() {
        Path root = Path.of("").toAbsolutePath().normalize();
        coverage().forEach((feature, paths) -> paths.forEach(relative ->
                assertTrue(Files.isRegularFile(root.resolve(relative)),
                        feature + " lost required regression evidence: " + relative)));
    }

    private static Map<String, List<String>> coverage() {
        Map<String, List<String>> coverage = new LinkedHashMap<>();
        coverage.put("guardian startup and recovery service", List.of(
                "src/test/java/dev/p2wn/startupguardian/GuardianServiceTest.java",
                "src/test/java/dev/p2wn/startupguardian/GuardianServiceBypassTest.java"));
        coverage.put("incident state and corruption ownership", List.of(
                "src/test/java/dev/p2wn/startupguardian/IncidentStateTest.java",
                "src/test/java/dev/p2wn/startupguardian/CorruptionOwnershipRegressionTest.java"));
        coverage.put("restart policy", List.of(
                "src/test/java/dev/p2wn/startupguardian/RestartPolicyTest.java"));
        coverage.put("settings loading and validation", List.of(
                "src/test/java/dev/p2wn/startupguardian/SettingsLoaderTest.java",
                "src/test/java/dev/p2wn/startupguardian/SettingsValidationTest.java"));
        coverage.put("plugin health value semantics", List.of(
                "src/test/java/dev/p2wn/startupguardian/PluginHealthTest.java"));
        coverage.put("Discord webhook client", List.of(
                "src/test/java/dev/p2wn/startupguardian/WebhookClientTest.java"));
        return coverage;
    }
}
