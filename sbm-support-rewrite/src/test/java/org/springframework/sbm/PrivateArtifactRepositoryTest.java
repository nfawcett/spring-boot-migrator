/*
 * Copyright 2021 - 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.sbm;

import org.apache.maven.shared.invoker.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.sbm.boot.autoconfigure.SbmSupportRewriteConfiguration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.in;

/**
 * @author Fabian Krüger
 */
@SpringBootTest(classes = SbmSupportRewriteConfiguration.class)
@Testcontainers
public class PrivateArtifactRepositoryTest {
    @Container
    static GenericContainer reposilite = new GenericContainer(DockerImageName.parse("dzikoysk/reposilite:3.4.10"))
            .withExposedPorts(8080)
            .withEnv("REPOSILITE_OPTS", "--token user:secret");

    @BeforeEach
    void beforeEach() {

    }

    @Test
    @DisplayName("Maven settings should be read")
    @SetSystemProperty(key = "user.home", value = "testcode/reposilite-test/user.home")
    void mavenSettingsShouldBeRead() throws IOException, MavenInvocationException, InterruptedException {
        System.out.println(System.getenv("MAVEN_HOME"));

        Integer port = reposilite.getMappedPort(8080);

        // adjust host:port information in pom.xml
        Path pomXmlPath = Path.of("testcode/reposilite-test/reposilite-test/pom.xml").toAbsolutePath().normalize();
        String pomXmlContent = Files.readString(pomXmlPath);
        String pomXmlCurrentPort = replacePort(pomXmlContent, port);
        Files.writeString(pomXmlPath, pomXmlCurrentPort);

        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(pomXmlPath.toFile());
        request.setGoals(List.of("deploy"));
        Invoker invoker = new DefaultInvoker();
        invoker.setMavenHome(new File(System.getenv("MAVEN_HOME")));
        invoker.execute(request);
    }


    @Test
    @DisplayName("searchAndReplace")
    void searchAndReplace() {
        String given =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                                
                  <groupId>com.example</groupId>
                  <artifactId>reposilite-test</artifactId>
                  <version>1.0-SNAPSHOT</version>
                  <name>reposilite-test</name>
                                
                  <properties>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    <maven.compiler.source>1.7</maven.compiler.source>
                    <maven.compiler.target>1.7</maven.compiler.target>
                  </properties>
                                
                  <dependencies>
                    <dependency>
                      <groupId>javax.validation</groupId>
                      <artifactId>validation-api</artifactId>
                      <version>2.0.1.Final</version>
                    </dependency>
                  </dependencies>
                                
                  <distributionManagement>
                    <repository>
                      <id>reposilite-repository-releases</id>
                      <name>Reposilite Repository</name>
                      <url>http://localhost:8085/snapshots</url>
                    </repository>
                  </distributionManagement>
                </project>
                """;

        String s1 = replacePort(given, 1234);

        assertThat(s1).isEqualTo(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                                
                  <groupId>com.example</groupId>
                  <artifactId>reposilite-test</artifactId>
                  <version>1.0-SNAPSHOT</version>
                  <name>reposilite-test</name>
                                
                  <properties>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    <maven.compiler.source>1.7</maven.compiler.source>
                    <maven.compiler.target>1.7</maven.compiler.target>
                  </properties>
                                
                  <dependencies>
                    <dependency>
                      <groupId>javax.validation</groupId>
                      <artifactId>validation-api</artifactId>
                      <version>2.0.1.Final</version>
                    </dependency>
                  </dependencies>
                                
                  <distributionManagement>
                    <repository>
                      <id>reposilite-repository-releases</id>
                      <name>Reposilite Repository</name>
                      <url>http://localhost:1234/snapshots</url>
                    </repository>
                  </distributionManagement>
                </project>
                """
        );
    }

    private static String replacePort(String given, Integer port) {
        String regex = "(<url>http:\\/\\/localhost:)(\\d{1,5})(\\/snapshots<\\/url>)";
        Pattern compile = Pattern.compile(regex);
        Matcher matcher = compile.matcher(given);
        String s1 = matcher.replaceFirst("$1" + port + "$3");
        return s1;
    }
}
