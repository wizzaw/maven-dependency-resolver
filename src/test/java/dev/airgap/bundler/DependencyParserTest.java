package dev.airgap.bundler;

import dev.airgap.bundler.model.ParsedInput;
import dev.airgap.bundler.parser.DependencyParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DependencyParserTest {

    private final DependencyParser parser = new DependencyParser();

    @Test
    void parsesStandardGavCoordinatesAndIgnoresComments() {
        String input = """
                # Core libraries
                org.apache.commons:commons-lang3:3.17.0
                com.google.guava:guava:33.3.1-jre // inline comment
                io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.1.114.Final
                """;

        ParsedInput result = parser.parse(input, 50);
        assertEquals(3, result.dependencies().size());
        assertEquals("org.apache.commons", result.dependencies().get(0).groupId());
        assertEquals("commons-lang3", result.dependencies().get(0).artifactId());
        assertEquals("3.17.0", result.dependencies().get(0).version());

        assertEquals("linux-x86_64", result.dependencies().get(2).classifier());
        assertEquals("4.1.114.Final", result.dependencies().get(2).version());
    }

    @Test
    void parsesMultipleXmlDependencyBlocksMixedWithCoordinates() {
        String input = """
                org.slf4j:slf4j-api:2.0.16
                <dependency>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId>
                    <version>2.18.1</version>
                </dependency>
                <dependency>
                    <groupId>org.junit</groupId>
                    <artifactId>junit-bom</artifactId>
                    <version>5.11.3</version>
                    <type>pom</type>
                    <scope>import</scope>
                </dependency>
                """;

        ParsedInput result = parser.parse(input, 50);
        assertEquals(2, result.dependencies().size());
        assertEquals(1, result.boms().size());
        assertEquals("junit-bom", result.boms().get(0).artifactId());
    }

    @Test
    void parsesFullPomXmlWithPropertiesParentAndBoms() {
        String pomXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.3.5</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <guava.version>33.3.1-jre</guava.version>
                    </properties>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.cloud</groupId>
                                <artifactId>spring-cloud-dependencies</artifactId>
                                <version>2023.0.3</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>${guava.version}</version>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.13.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """;

        ParsedInput result = parser.parse(pomXml, 50);
        assertEquals(1, result.parentPoms().size());
        assertEquals("spring-boot-starter-parent", result.parentPoms().get(0).artifactId());

        assertEquals(1, result.boms().size());
        assertEquals("spring-cloud-dependencies", result.boms().get(0).artifactId());

        assertEquals(1, result.dependencies().size());
        assertEquals("33.3.1-jre", result.dependencies().get(0).version());

        assertEquals(1, result.plugins().size());
        assertEquals("maven-compiler-plugin", result.plugins().get(0).artifactId());
    }

    @Test
    void enforcesMaxInputDependenciesLimit() {
        String input = """
                org.a:lib1:1.0
                org.a:lib2:1.0
                org.a:lib3:1.0
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parser.parse(input, 2));
        assertTrue(ex.getMessage().contains("exceeds maximum"));
    }
}
