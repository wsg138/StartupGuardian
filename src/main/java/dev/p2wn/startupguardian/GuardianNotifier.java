package dev.p2wn.startupguardian;

interface GuardianNotifier {

    void incident(
            Settings settings,
            Incident incident,
            boolean restartScheduled,
            boolean whitelisted);

    void persistenceFailure(Settings settings, Incident incident);

    void recovery(Settings settings, Incident incident);

    WebhookTestResult test(Settings settings);
}
