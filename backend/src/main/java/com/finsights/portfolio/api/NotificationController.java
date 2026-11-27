package com.finsights.portfolio.api;

import com.finsights.portfolio.service.NotificationEmail;
import com.finsights.portfolio.service.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets the signed-in user see exactly what the weekly digest would say, without sending anything. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/digest/preview")
    NotificationEmail digestPreview() {
        return notifications.weeklyDigest();
    }
}
