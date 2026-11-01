package com.foodmap.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
    // Spring Data JPA auditing — populates @CreatedDate and @LastModifiedDate
    // on any entity annotated with @EntityListeners(AuditingEntityListener.class)
}
