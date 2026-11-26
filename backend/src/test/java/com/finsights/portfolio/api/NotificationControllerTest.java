package com.finsights.portfolio.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.service.NotificationEmail;
import com.finsights.portfolio.service.NotificationService;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationControllerTest {
    @Test
    void previewReturnsTheDigestWithoutSending() {
        NotificationService service = mock(NotificationService.class);
        NotificationEmail email = new NotificationEmail("s", "b", List.of());
        when(service.weeklyDigest()).thenReturn(email);
        assertThat(new NotificationController(service).digestPreview()).isSameAs(email);
    }
}
