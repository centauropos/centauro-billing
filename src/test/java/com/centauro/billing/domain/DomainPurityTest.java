package com.centauro.billing.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code domain} no importa Spring ni JPA. */
class DomainPurityTest {

    private static final Path DOMAIN = Path.of("src", "main", "java", "com", "centauro", "billing", "domain");

    @Test
    void domainHasNoSpringOrPersistenceImports() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(DOMAIN)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(DomainPurityTest::importsFramework)
                    .map(Path::toString)
                    .toList();
        }
        assertThat(offenders).isEmpty();
    }

    private static boolean importsFramework(Path file) {
        try {
            String source = Files.readString(file);
            return source.contains("import org.springframework") || source.contains("import jakarta.persistence")
                    || source.contains("import org.hibernate");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
