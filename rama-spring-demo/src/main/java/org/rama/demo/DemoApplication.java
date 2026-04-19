package org.rama.demo;

import org.rama.repository.BaseRepositoryImpl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EntityScan(basePackages = {"org.rama.demo.entity", "org.rama.entity"})
@EnableJpaRepositories(
    basePackages = {"org.rama.demo.repository", "org.rama.repository"},
    repositoryBaseClass = BaseRepositoryImpl.class
)
@EnableAsync
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
