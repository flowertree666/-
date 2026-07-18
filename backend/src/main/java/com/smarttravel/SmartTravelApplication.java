package com.smarttravel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmartTravelApplication {
    public static void main(String[] args) { SpringApplication.run(SmartTravelApplication.class, args); }
}
