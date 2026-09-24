package com.paycore.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs (retries, reconciliation, outbox relay, webhook delivery). Disabled in tests. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "paycore.jobs", name = "enabled", havingValue = "true")
public class SchedulingConfig {}
