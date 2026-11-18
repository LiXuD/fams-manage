package com.fams.masterdata;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fams.product.contract.model.NumberingRule;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("fams-product")
class NumberingRuleJsonConfiguration {
    @Bean
    JsonMapperBuilderCustomizer numberingRuleJsonRules() {
        return builder -> builder.addMixIn(NumberingRule.class, NumberingRuleMixin.class);
    }

    abstract static class NumberingRuleMixin {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public abstract String getFixedPrefix();
    }
}
