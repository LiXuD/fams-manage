package com.fams.runtime.compatibility;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;

@Configuration(proxyBeanMethods = false)
@Profile("runtime-compatibility")
@MapperScan(basePackageClasses = RuntimeProbeMapper.class)
@EnableJdbcHttpSession
final class RuntimeCompatibilityConfiguration {
}
