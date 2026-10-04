package com.fams.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.function.Supplier;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
@Profile("fams-product")
@MapperScan(basePackageClasses = AuthAccountMapper.class, annotationClass = Mapper.class)
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = AuthSessionManager.IDLE_TIMEOUT_SECONDS)
class AuthProductConfiguration {

    @Bean
    Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder authPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    Supplier<String> csrfTokenSupplier() {
        SecureRandom secureRandom = new SecureRandom();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return () -> {
            byte[] bytes = new byte[32];
            secureRandom.nextBytes(bytes);
            return encoder.encodeToString(bytes);
        };
    }

    @Bean
    AuthSessionManager authSessionManager(Clock authClock, Supplier<String> csrfTokenSupplier) {
        return new AuthSessionManager(authClock, csrfTokenSupplier);
    }

    @Bean
    AuthPayloadFingerprint authPayloadFingerprint(
            @Value("${fams.security.idempotency-hmac-key}") String secret) {
        return new AuthPayloadFingerprint(secret);
    }

    @Bean
    CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("FAMS_SESSION");
        serializer.setCookiePath("/api");
        serializer.setUseSecureCookie(true);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        return serializer;
    }

    @Bean
    SecurityFilterChain authSecurityFilterChain(
            HttpSecurity http,
            AuthRequestFilter authRequestFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .addFilterBefore(authRequestFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
