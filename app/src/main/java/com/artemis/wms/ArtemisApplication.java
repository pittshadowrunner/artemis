package com.artemis.wms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ArtemisApplication {
    public static void main(String[] args) { SpringApplication.run(ArtemisApplication.class, args); }
}
