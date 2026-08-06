package dev.p2wn.startupguardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardianServiceTest extends GuardianServiceTestSupport {

    @TempDir
    Path directory;

    @Test
    void failedStartupPersistsBeforeProtectionAndSchedulesRestart() {
        List<String> sequence = new ArrayList<>();
        FakeRepository repository = new FakeRepository(sequence);
        FakeEnvironment environment = new FakeEnvironment(sequence, FAILED);
        FakeNotifier notifier = new FakeNotifier(sequence);
        GuardianService service = service(repository, notifier, environment, settings(false));

        service.startupCheck();

        assertEquals(List.of("save", "whitelist", "kick", "alert", "schedule"), sequence);
        assertTrue(repository.incident.isPresent());
        assertTrue(environment.whitelist);
        assertEquals(1, environment.nonOperator.kicks);
        assertEquals(0, environment.operator.kicks);
        assertTrue(service.hasPendingRestart());
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
    void healthyRecoveryClearsMarkerCancelsRestartAndRestoresWhitelist() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), FAILED);
        FakeNotifier notifier = new FakeNotifier(new ArrayList<>());
        GuardianService service = service(repository, notifier, environment, settings(false));
        service.startupCheck();
        FakeRestartTask task = environment.lastTask;
        environment.health = HEALTHY;

        service.startupCheck();

        assertTrue(repository.incident.isEmpty());
        assertTrue(task.cancelled);
        assertFalse(service.hasPendingRestart());
        assertFalse(environment.whitelist);
        assertEquals(1, notifier.recoveries);
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
    void persistedMarkerRemainsWhenProtectionThrows() {
        FakeRepository repository = new FakeRepository(new ArrayList<>());
        FakeEnvironment environment = new FakeEnvironment(new ArrayList<>(), FAILED);
        environment.protectionFails = true;
        FakeNotifier notifier = new FakeNotifier(new ArrayList<>());
        GuardianService service = service(repository, notifier, environment, settings(false));

        service.startupCheck();

        assertTrue(repository.incident.isPresent());
        assertEquals(1, notifier.incidents);
        assertEquals(1, environment.scheduleCalls);
    }
}
