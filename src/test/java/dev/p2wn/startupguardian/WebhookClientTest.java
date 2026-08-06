package dev.p2wn.startupguardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookClientTest {

    @Test
    void repeatDelaysAreAbsoluteRatherThanCumulative() {
        assertEquals(
                List.of(0L, 1_500L, 3_000L),
                WebhookClient.repeatDelays(3, 1_500));
    }

    @Test
    void repeatDelayValidationRejectsInvalidValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WebhookClient.repeatDelays(0, 1_500));
        assertThrows(
                IllegalArgumentException.class,
                () -> WebhookClient.repeatDelays(3, -1));
    }
}
