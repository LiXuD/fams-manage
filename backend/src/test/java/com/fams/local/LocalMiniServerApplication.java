package com.fams.local;

import com.fams.auth.MiniSmsPort;
import com.fams.auth.MiniWechatIdentityPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Manual operator launcher for WeChat DevTools simulator integration. The WeChat identity exchange
 * and SMS delivery are explicit local substitutes; this source set never ships in the production jar. */
@SpringBootApplication(scanBasePackages = "com.fams")
public class LocalMiniServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalMiniServerApplication.class, args);
    }

    @Configuration
    static class LocalDoubles {
        @Bean
        MiniWechatIdentityPort localMiniWechatIdentity() {
            return code -> new MiniWechatIdentityPort.Identity("local-mini-app", "local-openid-1");
        }

        @Bean
        MiniSmsPort localMiniSms(@Value("${fams.local-mini.sms-file:/tmp/fams-mini-sms.txt}") String smsFile) {
            return (registeredPhone, code) -> {
                try {
                    Files.writeString(Path.of(smsFile), code, StandardCharsets.UTF_8);
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException("Local SMS capture file unavailable", exception);
                }
            };
        }
    }
}
