package com.example.consumer;

import org.rama.repository.BaseRepositoryImpl;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * A consumer-shaped application root: it lives outside {@code org.rama} and pulls
 * the starter's entities and repositories in exactly the way the consumer manual
 * documents. Nothing here is demo-specific — the point is to exercise the wiring a
 * real downstream service has, which is where starter#46 was reported.
 */
@SpringBootApplication
@EntityScan(basePackages = {"org.rama.entity"})
@EnableJpaRepositories(
        basePackages = {"com.example.consumer.repository", "org.rama.repository"},
        repositoryBaseClass = BaseRepositoryImpl.class
)
public class ConsumerShapedApplication {
}
