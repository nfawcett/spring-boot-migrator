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
package org.springframework.sbm.parsers.maven;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetSystemProperty;
import org.openrewrite.maven.MavenExecutionContextView;
import org.openrewrite.maven.tree.MavenRepository;
import org.sonatype.plexus.components.cipher.DefaultPlexusCipher;
import org.sonatype.plexus.components.cipher.PlexusCipherException;
import org.sonatype.plexus.components.sec.dispatcher.DefaultSecDispatcher;
import org.springframework.sbm.parsers.RewriteExecutionContext;
import org.springframework.sbm.scopes.ProjectMetadata;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Fabian Krüger
 */
class MavenSettingsInitializerTest {

    // localRepository.getUri() will differ from '"file:" + userHome + "/.m2/repository/"' because
    // MavenRepository.MAVEN_LOCAL_DEFAULT gets returned and this field is statically initialized.
    // For this test it means that running the test in isolation succeeds but running it in combination
    // with a test that loads MavenRepository before 'user.home' was changed in this test, it fails.
    // And maybe even worse, running this test before others would set the local maven repository to the
    // dummy dir used in this test.
    //
    // To prevent this it will be set to the original settings with this line:
    MavenRepository mavenLocalDefault = MavenRepository.MAVEN_LOCAL_DEFAULT;

    @Test
    @SetSystemProperty(key = "user.home", value = "./testcode/maven-projects/project-with-maven-settings/user-home")
    void mavenParserMustAdhereToSettingsXmlTest() throws URISyntaxException, PlexusCipherException {

        RewriteExecutionContext executionContext = new RewriteExecutionContext();
        MavenSettingsInitializer sut = new MavenSettingsInitializer(new MavenPasswordDecrypter(new DefaultSecDispatcher(new DefaultPlexusCipher())), executionContext, new ProjectMetadata());
        sut.initializeMavenSettings();
        MavenExecutionContextView mavenExecutionContextView = MavenExecutionContextView.view(executionContext);

        assertThat(mavenExecutionContextView.getRepositories()).hasSize(1);

        MavenRepository mavenRepository = mavenExecutionContextView.getRepositories().get(0);

        assertThat(mavenRepository.getId()).isEqualTo("central");
        assertThat(mavenRepository.getUri()).isEqualTo("https://jcenter.bintray.com");
        assertThat(mavenRepository.getReleases()).isNull();
        assertThat(mavenRepository.getSnapshots()).isEqualToIgnoringCase("false");

        MavenRepository localRepository = mavenExecutionContextView.getLocalRepository();
        assertThat(localRepository.getSnapshots()).isNull();

        String tmpDir = removeTrailingSlash(System.getProperty("java.io.tmpdir"));
        String customLocalRepository = new URI("file://" + tmpDir).toString();
        assertThat(removeTrailingSlash(localRepository.getUri())).isEqualTo(customLocalRepository);
        assertThat(localRepository.getSnapshots()).isNull();
        assertThat(localRepository.isKnownToExist()).isTrue();
        assertThat(localRepository.getUsername()).isNull();
        assertThat(localRepository.getPassword()).isNull();
    }

    String removeTrailingSlash(String string) {
        if(string.endsWith("/")){
            return string.substring(0, string.length()-1);
        }
        return string;
    }

}