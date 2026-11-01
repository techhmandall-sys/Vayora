package com.dypcet.vayora.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the seat-release reminders that have come due. A class of its own so the call goes
 * through Spring's proxy and gets the service's transaction.
 */
@Component
class ReleaseReminderJob {

    private static final Logger log = LoggerFactory.getLogger(ReleaseReminderJob.class);

    private final ReleaseReminders reminders;

    ReleaseReminderJob(ReleaseReminders reminders) {
        this.reminders = reminders;
    }

    @Scheduled(cron = "${vayora.reminders.release-cron}", zone = "Asia/Kolkata")
    void run() {
        int sent = reminders.remindDue();
        if (sent > 0) {
            log.info("Reminded {} passengers they can release their seat", sent);
        }
    }
}
