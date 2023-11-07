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

import lombok.RequiredArgsConstructor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.maven.MavenExecutionContextView;
import org.openrewrite.maven.MavenSettings;
import org.openrewrite.maven.tree.MavenRepository;
import org.sonatype.plexus.components.sec.dispatcher.DefaultSecDispatcher;
import org.springframework.core.io.Resource;
import org.springframework.sbm.scopes.ProjectMetadata;
import org.springframework.sbm.utils.ResourceUtil;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author Fabian Krüger
 */
@Component
@RequiredArgsConstructor
public class MavenSettingsInitializer {

    private final MavenPasswordDecrypter mavenPasswordDecrypter;
    private final ExecutionContext executionContext;
    private final ProjectMetadata projectMetadata;
    /**
     * @deprecated initialization in ExecutionoContext is done in ProjectParser
     */
    public void initializeMavenSettings() {
        String repo = "file://" + Path.of(System.getProperty("user.home")).resolve(".m2/repository") + "/";
        MavenRepository mavenRepository = new MavenRepository("local", new File(System.getProperty("user.home") + "/.m2/repository").toURI().toString(), "true", "true", true, null, null, false);
        MavenSettings mavenSettings = new MavenSettings(repo, null, null, null, null, null);
        // Read .m2/settings.xml
        // TODO: Add support for global Maven settings (${maven.home}/conf/settings.xml).
        Path mavenSettingsFile = Path.of(System.getProperty("user.home")).resolve(".m2/settings.xml");
        MavenExecutionContextView mavenExecutionContextView = MavenExecutionContextView.view(executionContext);
        if (Files.exists(mavenSettingsFile)) {
            mavenSettings = MavenSettings.parse(mavenSettingsFile, mavenExecutionContextView);
        }
        mavenExecutionContextView.setMavenSettings(mavenSettings);
        projectMetadata.setMavenSettings(mavenSettings);
    }

    public MavenSettings initializeMavenSettings(Resource mavenSettingsFile, Path securitySettingsFilePath) {
        Parser.Input input = new Parser.Input(ResourceUtil.getPath(mavenSettingsFile), () -> ResourceUtil.getInputStream(mavenSettingsFile));
        MavenSettings mavenSettings = MavenSettings.parse(input, executionContext);
        mavenPasswordDecrypter.decryptMavenServerPasswords(mavenSettings, securitySettingsFilePath);
        MavenExecutionContextView.view(executionContext).setMavenSettings(mavenSettings);
        return mavenSettings;
    }

}
