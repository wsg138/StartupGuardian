package dev.p2wn.startupguardian;

import java.io.IOException;

interface WebhookTransport {

    WebhookResponse send(String webhookUrl, String payload)
            throws IOException, InterruptedException;
}
