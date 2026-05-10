package com.jpmc.tfpm.address.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * The composition root for the TFPM Address Enrichment Service.
 *
 * <p>Spring scans the entire {@code com.jpmc.tfpm.address} package tree;
 * Spring beans across all modules (domain interfaces are stand-alone Java
 * but their implementations live in adapter and app modules) are wired
 * here. Configuration properties classes are picked up via
 * {@link ConfigurationPropertiesScan}.
 *
 * <p>{@code DataSourceAutoConfiguration} is excluded because we manually
 * configure two HikariCP pools (legacy-read and app-write) in
 * {@code DataSourceConfig}; letting Spring guess based on the default
 * {@code spring.datasource.*} properties would create a pool that doesn't
 * match either of our intended users.
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@ConfigurationPropertiesScan(basePackages = "com.jpmc.tfpm.address")
@ComponentScan(basePackages = "com.jpmc.tfpm.address")
@EnableTransactionManagement
@EnableScheduling
@EnableAsync
@EnableRetry
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
