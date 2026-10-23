package com.example.ftps3;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FtpS3Application {
    public static void main(String[] args) {
        SpringApplication.run(FtpS3Application.class, args);
    }
}
