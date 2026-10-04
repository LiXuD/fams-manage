package com.fams.contract.probe;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration
public class JsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer contractProbeJsonRules() {
        return builder -> builder
                .withCoercionConfig(LogicalType.Textual, coercion -> coercion
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .changeDefaultPropertyInclusion(value ->
                        value.withValueInclusion(JsonInclude.Include.ALWAYS));
    }
}
