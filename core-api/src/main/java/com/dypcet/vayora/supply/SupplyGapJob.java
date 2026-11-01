package com.dypcet.vayora.supply;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the morning check. A class of its own so the call goes through Spring's proxy and
 * gets the service's transaction: a timer method calling its own class would bypass it.
 */
@Component
class SupplyGapJob {

    private static final Logger log = LoggerFactory.getLogger(SupplyGapJob.class);

    private final SupplyGapService supply;

    SupplyGapJob(SupplyGapService supply) {
        this.supply = supply;
    }

    @Scheduled(cron = "${vayora.supply.detection-cron}", zone = "Asia/Kolkata")
    void run() {
        int nudged = supply.detectAndNudge();
        if (nudged > 0) {
            log.info("Told {} drivers about supply gaps", nudged);
        }
    }
}
