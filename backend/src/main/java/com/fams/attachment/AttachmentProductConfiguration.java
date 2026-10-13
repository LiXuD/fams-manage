package com.fams.attachment;

import java.io.IOException;
import java.nio.file.Path;
import com.fams.auth.AttachmentActorContext;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public class AttachmentProductConfiguration {
    @Bean AttachmentStorage attachmentStorage(@Value("${fams.attachments.quarantine-root}") String root)
            throws IOException { return new LocalAttachmentStorage(Path.of(root)); }

    @Bean MalwareScanner attachmentScanner(@Value("${fams.attachments.clamd-host:127.0.0.1}") String host,
            @Value("${fams.attachments.clamd-port:3310}") int port,
            @Value("${fams.attachments.scan-timeout-ms:10000}") int timeout) {
        return new ClamdScanner(host, port, timeout);
    }

    @Bean AttachmentIntake attachmentIntake(AttachmentStorage storage, MalwareScanner scanner) {
        return new AttachmentIntake(storage, scanner);
    }

    @Bean AttachmentWriter attachmentWriter(AttachmentStorage storage, AttachmentIntake intake,
            PlatformTransactionManager transactions, AttachmentObjectGuard guard) {
        return new AttachmentWriter(storage, intake, transactions, guard::requireStored);
    }

    @Bean AttachmentUploadService attachmentUploadService(AttachmentWriter writer, AttachmentRegistry registry,
            AttachmentActorContext actors, @org.springframework.beans.factory.annotation.Qualifier("inboundAttachmentPort") AttachmentBusinessPort inbound) {
        return new AttachmentUploadService(writer, registry, actors, inbound);
    }

    @Bean MultipartConfigElement attachmentMultipartConfig() {
        return new MultipartConfigElement("", 20L * 1024 * 1024, 21L * 1024 * 1024, 0);
    }
}
