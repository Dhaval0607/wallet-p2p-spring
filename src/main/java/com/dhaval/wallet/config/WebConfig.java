package com.dhaval.wallet.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Maps the two operator pages onto clean URLs. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/logs").setViewName("forward:/logs.html");
        registry.addViewController("/dashboard").setViewName("forward:/dashboard.html");
    }

    /**
     * The transaction template used by TransferService. Declared here rather than
     * relying on @Transactional so the retry loop can own transaction boundaries
     * explicitly -- a retry has to start a genuinely new transaction, which an
     * annotation on the same bean could not express.
     */
    @Bean
    org.springframework.transaction.support.TransactionTemplate transactionTemplate(
            org.springframework.transaction.PlatformTransactionManager manager) {
        return new org.springframework.transaction.support.TransactionTemplate(manager);
    }
}
