package com.dypcet.vayora.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The platform's fee, charged to the passenger on top of the fare: lower on a ride by a
 * driver who keeps their rides (see {@link KeptRides}), and never higher than the standard.
 */
@Component
public class ServiceFee {

    private final BigDecimal rate;
    private final BigDecimal keptRidesRate;

    public ServiceFee(@Value("${vayora.payment.service-fee-rate}") BigDecimal rate,
            @Value("${vayora.payment.kept-rides.fee-rate}") BigDecimal keptRidesRate) {
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("vayora.payment.service-fee-rate must be 0 to 1");
        }
        // A reward only: no driver's record may make a ride dearer than the standard.
        if (keptRidesRate.signum() < 0 || keptRidesRate.compareTo(rate) > 0) {
            throw new IllegalArgumentException(
                    "vayora.payment.kept-rides.fee-rate must be 0 to the standard service fee rate");
        }
        this.rate = rate;
        this.keptRidesRate = keptRidesRate;
    }

    public BigDecimal rate() {
        return rate;
    }

    public BigDecimal keptRidesRate() {
        return keptRidesRate;
    }

    public BigDecimal on(BigDecimal fare) {
        return on(fare, false);
    }

    /** @param keptRides whether the ride's driver keeps their rides, by {@link KeptRides} */
    public BigDecimal on(BigDecimal fare, boolean keptRides) {
        return fare.multiply(keptRides ? keptRidesRate : rate).setScale(2, RoundingMode.HALF_UP);
    }
}
