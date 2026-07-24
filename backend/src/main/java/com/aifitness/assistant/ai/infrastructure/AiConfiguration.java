package com.aifitness.assistant.ai.infrastructure;

import com.aifitness.assistant.ai.application.AiInputRedactor;
import com.aifitness.assistant.ai.application.AiOrchestrator;
import com.aifitness.assistant.ai.application.AiProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"local", "test", "staging-experience"})
public class AiConfiguration {

    @Bean
    AiInputRedactor aiInputRedactor() {
        return new AiInputRedactor();
    }

    @Bean
    @ConditionalOnMissingBean(AiProvider.class)
    AiProvider disabledAiProvider() {
        return AiProvider.disabled();
    }

    @Bean
    AiOrchestrator aiOrchestrator(
            @Value("${fitness.ai.enabled:false}") boolean enabled,
            AiProvider provider,
            AiInputRedactor redactor) {
        return new AiOrchestrator(enabled, provider, redactor);
    }
}
