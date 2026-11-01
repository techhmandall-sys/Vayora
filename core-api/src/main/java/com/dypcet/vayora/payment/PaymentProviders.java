package com.dypcet.vayora.payment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** Which provider moves the money: Razorpay when its keys are set, the mock otherwise. */
@Configuration
public class PaymentProviders {

    @Bean
    @Primary
    public PaymentProvider paymentProvider(RazorpayClient razorpay, MockPaymentProvider mock, JdbcTemplate jdbc) {
        return razorpay.configured() ? new RazorpayPaymentProvider(razorpay, jdbc) : mock;
    }
}
