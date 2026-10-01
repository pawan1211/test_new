
package com.fashion.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.fashion")
@EnableScheduling
@EntityScan(basePackages = "com.fashion")
@EnableJpaRepositories(basePackages = "com.fashion")
public class FashionApplication {

    public static void main(String[] args) {
        SpringApplication.run(FashionApplication.class, args);
    }
}
