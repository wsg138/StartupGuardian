package dev.p2wn.startupguardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardianServiceTest extends GuardianServiceTestSupport {

    @TempDir
    Path directory;

    @Test
    void failedStartupPersistsOwnershipAndAttemptOnlyAfterEachActionSucceeds() {
        List<String> sequence = new ArrayList<>();
        FakeRepository repository = new FakeRepository(sequence);
        FakeEnvironment environment = new FakeEnvironment(sequence, FAILED);
        FakeNotifier notifier = new FakeNotifier(sequence);
        GuardianService service = service(repository, notifier, environment, settings(false));

        service.startupCheck();

        assertEquals(
                List.of(
                        "save",
                        "whitelist",
                        "save",
                        "kick",
                        "schedule",
                        "save",
                        "alert"),
                sequence);
        Incident incident = repository.incident.orElseThrow();
        assertTrue(incident.guardianEnabledWhitelist());
        assertEquals(1, incident.automaticRestartAttempts());
        assertTrue(environment.whitelist);
        assertEquals(1, environment.nonOperator.kicks);
        assertEquals(0, environment.operator.kicks);
        assertTrue(service.hasPendingRestart());
        assertTrue(notifier.lastRestartScheduled);
    }

    @Test
    void emergencyOperatorKickingCanBeEnabled() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), FAILED);
        GuardianService service = service(
                repository,
                new FakeNotifier(new ArrayList<>()),
                environment,
                settings(true));

        service.startupCheck();

        assertEquals(1, environment.nonOperator.kicks);
        assertEquals(1, environment.operator.kicks);
    }

    @Test
    void playerKickingCanBeDisabledWhileWhitelistProtectionRemainsEnabled() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), FAILED);
        Settings noKickSettings = settings(false, false);
        GuardianService service = service(
                repository,
                new FakeNotifier(new ArrayList<>()),
                environment,
                noKickSettings);

        service.startupCheck();

        assertTrue(environment.whitelist);
        assertEquals(0, environment.nonOperator.kicks);
        assertEquals(0, environment.operator.kicks);
    }

    @Test
    void newIncidentSaveFailurePreventsDestructiveProtectionAndRestart() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        repository.saveFails = true;
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), FAILED);
        FakeNotifier notifier = new FakeNotifier(new ArrayList<>());
        GuardianService service = service(repository, notifier, environment, settings(false));

        service.startupCheck();

        assertFalse(environment.whitelist);
        assertEquals(0, environment.nonOperator.kicks);
        assertEquals(0, environment.operator.kicks);
        assertEquals(0, environment.scheduleCalls);
        assertEquals(1, notifier.persistenceFailures);
        assertEquals(0, notifier.incidents);
    }

    @Test
    void existingIncidentUpdateSaveFailurePreservesPendingRestartWithoutNewProtection() {
        List<String> sequence = new ArrayList<>();
        FakeRepository repository = new FakeRepository(sequence);
        FakeEnvironment environment = new FakeEnvironment(sequence, FAILED);
        FakeNotifier notifier = new FakeNotifier(sequence);
        GuardianService service = service(repository, notifier, environment, settings(false));
        service.startupCheck();
        FakeRestartTask originalTask = environment.lastTask;
        int originalWhitelistChanges = environment.whitelistChanges;
        int originalKicks = environment.nonOperator.kicks;
        repository.saveFails = true;
        sequence.clear();

        service.startupCheck();

        assertSame(originalTask, environment.lastTask);
        assertFalse(originalTask.cancelled);
        assertEquals(originalWhitelistChanges, environment.whitelistChanges);
        assertEquals(originalKicks, environment.nonOperator.kicks);
        assertEquals(List.of("save", "persistence-failure"), sequence);
        assertTrue(service.hasPendingRestart());
    }

    @Test
    void failedWhitelistEnableNeverCreatesFalseOwnership() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), FAILED);
        environment.protectionFails = true;
        FakeNotifier notifier = new FakeNotifier(new ArrayList<>());
        GuardianService service = service(repository, notifier, environment, settings(false));

        service.startupCheck();

        Incident incident = repository.incident.orElseThrow();
        assertFalse(incident.guardianEnabledWhitelist());
        assertFalse(environment.whitelist);
        assertEquals(0, environment.nonOperator.kicks);
        assertEquals(0, environment.scheduleCalls);
        assertEquals(1, notifier.incidents);

        environment.protectionFails = false;
        environment.whitelist = true;
        environment.health = HEALTHY;
        service.startupCheck();

        assertTrue(environment.whitelist);
        assertEquals(0, environment.whitelistChanges);
        assertTrue(repository.incident.isEmpty());
        assertEquals(1, notifier.recoveries);
    }

    @Test
    void repeatedEnforcementSendsAnotherAlertWithoutDuplicateRestart() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), FAILED);
        FakeNotifier notifier = new FakeNotifier(new ArrayList<>());
        GuardianService service = service(repository, notifier, environment, settings(false));

        service.manualCheck(true, commandSender());
        service.manualCheck(true, commandSender());

        assertEquals(2, notifier.incidents);
        assertEquals(1, environment.scheduleCalls);
        assertEquals(1, repository.incident.orElseThrow().automaticRestartAttempts());
    }

    @Test
    void failedRestartScheduleDoesNotConsumeAttemptOrClaimItWasScheduled() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), FAILED);
        environment.scheduleFails = true;
        FakeNotifier notifier = new FakeNotifier(new ArrayList<>());
        GuardianService service = service(repository, notifier, environment, settings(false));

        service.startupCheck();

        assertEquals(1, environment.scheduleCalls);
        assertEquals(0, repository.incident.orElseThrow().automaticRestartAttempts());
        assertFalse(service.hasPendingRestart());
        assertFalse(notifier.lastRestartScheduled);
    }

    @Test
    void automaticRestartLimitPersistsAcrossServiceInstances() {
        IncidentStore firstStore = new IncidentStore(directory, LOGGER);
        FakeEnvironment firstEnvironment = new FakeEnvironment(new ArrayList<>(), FAILED);
        GuardianService first = service(
                firstStore,
                new FakeNotifier(new ArrayList<>()),
                firstEnvironment,
                settings(false));
        first.startupCheck();

        IncidentStore secondStore = new IncidentStore(directory, LOGGER);
        FakeEnvironment secondEnvironment = new FakeEnvironment(new ArrayList<>(), FAILED);
        GuardianService second = service(
                secondStore,
                new FakeNotifier(new ArrayList<>()),
                secondEnvironment,
                settings(false));
        second.startupCheck();

        assertEquals(1, firstEnvironment.scheduleCalls);
        assertEquals(0, secondEnvironment.scheduleCalls);
        assertTrue(secondStore.load().orElseThrow().restartLoopStopped());
    }

    @Test
    void corruptedMarkerKeepsEmergencyBypassAndSuppressesHealthyRecovery() {
        UUID trustedPlayer = UUID.fromString("00000000-0000-0000-0000-000000000001");
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        repository.corrupted = true;
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), HEALTHY);
        environment.whitelist = true;
        FakeNotifier notifier = new FakeNotifier(new ArrayList<>());
        Settings settings = settings(
                false,
                false,
                new Settings.Bypass(List.of(trustedPlayer), "", false));
        GuardianService service = service(repository, notifier, environment, settings);

        assertTrue(service.allowsCriticalBypass(player(trustedPlayer, false, false)));
        Incident statusIncident = service.incident().orElseThrow();
        assertEquals("CORRUPTED-MARKER", statusIncident.incidentId());
        assertTrue(statusIncident.restartLoopStopped());

        service.startupCheck();

        assertTrue(repository.corrupted);
        assertTrue(repository.incident.isEmpty());
        assertTrue(environment.whitelist);
        assertEquals(0, environment.whitelistChanges);
        assertEquals(0, notifier.recoveries);
    }

    @Test
    void healthyRecoveryCancelsRestartRestoresWhitelistThenClearsMarker() {
        List<String> sequence = new ArrayList<>();
        FakeRepository repository = new FakeRepository(sequence);
        FakeEnvironment environment = new FakeEnvironment(sequence, FAILED);
        FakeNotifier notifier = new FakeNotifier(sequence);
        GuardianService service = service(repository, notifier, environment, settings(false));
        service.startupCheck();
        FakeRestartTask task = environment.lastTask;
        environment.health = HEALTHY;
        sequence.clear();

        service.startupCheck();

        assertEquals(List.of("cancel", "whitelist", "clear"), sequence);
        assertTrue(repository.incident.isEmpty());
        assertTrue(task.cancelled);
        assertFalse(service.hasPendingRestart());
        assertFalse(environment.whitelist);
        assertEquals(1, notifier.recoveries);
    }

    @Test
    void recoveryCancellationFailureRetainsMarkerForRetry() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), FAILED);
        FakeNotifier notifier = new FakeNotifier(new ArrayList<>());
        GuardianService service = service(repository, notifier, environment, settings(false));
        service.startupCheck();
        environment.lastTask.cancelFails = true;
        environment.health = HEALTHY;

        service.startupCheck();

        assertTrue(repository.incident.isPresent());
        assertTrue(environment.whitelist);
        assertTrue(service.hasPendingRestart());
        assertEquals(0, notifier.recoveries);
    }

    @Test
    void recoveryWhitelistFailureRetainsMarkerForRetry() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), FAILED);
        FakeNotifier notifier = new FakeNotifier(new ArrayList<>());
        GuardianService service = service(repository, notifier, environment, settings(false));
        service.startupCheck();
        environment.health = HEALTHY;
        environment.protectionFails = true;

        service.startupCheck();

        assertTrue(repository.incident.isPresent());
        assertTrue(environment.whitelist);
        assertFalse(service.hasPendingRestart());
        assertEquals(0, notifier.recoveries);
    }

    @Test
    void recoveryClearFailureRetainsMarkerAfterRecoveryActions() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), FAILED);
        FakeNotifier notifier = new FakeNotifier(new ArrayList<>());
        GuardianService service = service(repository, notifier, environment, settings(false));
        service.startupCheck();
        environment.health = HEALTHY;
        repository.clearFails = true;

        service.startupCheck();

        assertTrue(repository.incident.isPresent());
        assertFalse(environment.whitelist);
        assertFalse(service.hasPendingRestart());
        assertEquals(0, notifier.recoveries);
    }

    @Test
    void recoveryDoesNotChangeWhitelistWhenGuardianDidNotEnableIt() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        repository.incident = Optional.of(Incident.create(FAILED, true, false));
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), HEALTHY);
        environment.whitelist = true;
        GuardianService service = service(
                repository,
                new FakeNotifier(new ArrayList<>()),
                environment,
                settings(false));

        service.startupCheck();

        assertTrue(environment.whitelist);
        assertEquals(0, environment.whitelistChanges);
    }

    @Test
    void resetFailurePreservesMarkerAndPendingRestart() {
        List<String> sequence = new ArrayList<>();
        FakeRepository repository = new FakeRepository(sequence);
        FakeEnvironment environment = new FakeEnvironment(sequence, FAILED);
        GuardianService service = service(
                repository,
                new FakeNotifier(new ArrayList<>()),
                environment,
                settings(false));
        service.startupCheck();
        FakeRestartTask task = environment.lastTask;
        repository.clearFails = true;
        sequence.clear();

        assertFalse(service.reset());
        assertTrue(repository.incident.isPresent());
        assertFalse(task.cancelled);
        assertTrue(service.hasPendingRestart());
        assertEquals(List.of("clear"), sequence);
    }

    @Test
    void successfulResetClearsMarkerAndCancelsPendingRestart() {
        List<String> sequence = new ArrayList<>();
        FakeRepository repository = new FakeRepository(sequence);
        FakeEnvironment environment = new FakeEnvironment(sequence, FAILED);
        GuardianService service = service(
                repository,
                new FakeNotifier(new ArrayList<>()),
                environment,
                settings(false));
        service.startupCheck();
        FakeRestartTask task = environment.lastTask;
        sequence.clear();

        assertTrue(service.reset());
        assertTrue(repository.incident.isEmpty());
        assertTrue(task.cancelled);
        assertFalse(service.hasPendingRestart());
        assertEquals(List.of("clear", "cancel"), sequence);
    }

    @Test
    void restartCommandUsesFallbackWhenPrimaryDispatchFails() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), FAILED);
        environment.primaryDispatchResult = false;
        GuardianService service = service(
                repository,
                new FakeNotifier(new ArrayList<>()),
                environment,
                settings(false));
        service.startupCheck();

        environment.lastTask.run();

        assertEquals(List.of("restart", "stop"), environment.commands);
        assertFalse(service.hasPendingRestart());
    }

    @Test
    void totalRestartDispatchFailureIsLoggedSeverely() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), FAILED);
        environment.primaryDispatchResult = false;
        environment.fallbackDispatchResult = false;
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        LOGGER.addHandler(handler);
        try {
            GuardianService service = service(
                    repository,
                    new FakeNotifier(new ArrayList<>()),
                    environment,
                    settings(false));
            service.startupCheck();

            environment.lastTask.run();
        } finally {
            LOGGER.removeHandler(handler);
        }

        assertEquals(List.of("restart", "stop"), environment.commands);
        assertTrue(records.stream().anyMatch(record ->
                record.getLevel() == Level.SEVERE
                        && record.getMessage().contains("Neither the restart command")));
    }

    @Test
    void successfulRestartCommandDoesNotUseFallback() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), FAILED);
        environment.primaryDispatchResult = true;
        GuardianService service = service(
                repository,
                new FakeNotifier(new ArrayList<>()),
                environment,
                settings(false));
        service.startupCheck();

        environment.lastTask.run();

        assertEquals(List.of("restart"), environment.commands);
    }

    @Test
    void pluginDisableCancelsPendingRestart() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), FAILED);
        GuardianService service = service(
                repository,
                new FakeNotifier(new ArrayList<>()),
                environment,
                settings(false));
        service.startupCheck();
        FakeRestartTask task = environment.lastTask;

        service.close();

        assertTrue(task.cancelled);
        assertFalse(service.hasPendingRestart());
    }

    @Test
    void persistedMarkerRemainsWithoutOwnershipWhenWhitelistProtectionThrows() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), FAILED);
        environment.protectionFails = true;
        FakeNotifier notifier = new FakeNotifier(new ArrayList<>());
        GuardianService service = service(repository, notifier, environment, settings(false));

        service.startupCheck();

        Incident incident = repository.incident.orElseThrow();
        assertFalse(incident.guardianEnabledWhitelist());
        assertEquals(1, notifier.incidents);
        assertEquals(0, environment.scheduleCalls);
    }
}
