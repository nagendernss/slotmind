package com.slotmind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class SlotMindApplication {
    public static void main(String[] args) {
        SpringApplication.run(SlotMindApplication.class, args);
    }
}
