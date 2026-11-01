package com.dypcet.vayora;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VayoraApplication {

    public static void main(String[] args) {
        SpringApplication.run(VayoraApplication.class, args);
    }
}
