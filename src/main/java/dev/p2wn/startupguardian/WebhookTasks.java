package dev.p2wn.startupguardian;

interface WebhookTasks {

    boolean submit(Runnable task);

    boolean schedule(Runnable task, long delayMillis);

    boolean closed();

    void close();
}
