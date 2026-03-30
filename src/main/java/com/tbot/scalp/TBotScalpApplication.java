package com.tbot.scalp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TBotScalpApplication {
    public static void main(String[] args) {
        SpringApplication.run(TBotScalpApplication.class, args);
    }
}
