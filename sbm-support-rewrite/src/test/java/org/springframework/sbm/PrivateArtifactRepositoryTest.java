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

import org.apache.commons.io.FileUtils;
import org.apache.maven.shared.invoker.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junitpioneer.jupiter.SetSystemProperty;
import org.mockito.Mockito;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.maven.cache.LocalMavenArtifactCache;
import org.openrewrite.maven.cache.MavenArtifactCache;
import org.openrewrite.maven.tree.MavenRepository;
import org.powermock.reflect.Whitebox;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.sbm.boot.autoconfigure.SbmSupportRewriteConfiguration;
import org.springframework.sbm.parsers.RewriteProjectParser;
import org.springframework.sbm.parsers.RewriteProjectParsingResult;
import org.springframework.sbm.parsers.maven.RewriteMavenProjectParser;
import org.springframework.sbm.parsers.maven.SbmTestConfiguration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.FileSystemUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.*;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;

/**
 * @author Fabian Krüger
 */
@SpringBootTest(classes = {MyTEstCOnfig.class, SbmSupportRewriteConfiguration.class, SbmTestConfiguration.class})
@DirtiesContext // clear beans caching Maven settings
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PrivateArtifactRepositoryTest {


    private static MavenRepository originalMavenRepository;
    // localRepository.getUri() will differ from '"file:" + userHome + "/.m2/repository/"' because
    // MavenRepository.MAVEN_LOCAL_DEFAULT gets returned and this field is statically initialized.
    // For this test it means that running the test in isolation succeeds but running it in combination
    // with a test that loads MavenRepository before 'user.home' was changed in this test, it fails.
    // And maybe even worse, running this test before others would set the local maven repository to the
    // dummy dir used in this test.
    //
    // To prevent this it will be set to the original settings with this line:
    MavenRepository mavenLocalDefault = MavenRepository.MAVEN_LOCAL_DEFAULT;

    public static final String $USER_HOME_PLACEHOLDER = "${user.home}";
    public static final String $PORT_PLACEHOLDER = "${port}";
    public static final String TESTCODE_DIR = "testcode/reposilite-test";
    public static final String DEPENDENCY_CLASS_FQNAME = "com.example.dependency.DependencyClass";

    @Container
    static GenericContainer reposilite = new GenericContainer(DockerImageName.parse("dzikoysk/reposilite:3.4.10"))
            .withExposedPorts(8080)
            .withCopyFileToContainer(
                    MountableFile.forHostPath("./" + TESTCODE_DIR + "/reposilite-data/shared.configuration.json"),
                    "/app/data/shared.configuration.json"
            )
            .withCopyFileToContainer(
                    MountableFile.forHostPath("./" + TESTCODE_DIR + "/reposilite-test/reposilite-data/repositories/snapshots/com/example/dependency/dependency-project/maven-metadata.xml"),
                    "/app/data/repositories/snapshots/com/example/dependency/dependency-project/maven-metadata.xml"
            )
            .withCopyFileToContainer(
                    MountableFile.forHostPath("./" + TESTCODE_DIR + "/reposilite-test/reposilite-data/repositories/snapshots/com/example/dependency/dependency-project/maven-metadata.xml.md5"),
                    "/app/data/repositories/snapshots/com/example/dependency/dependency-project/maven-metadata.xml.md5"
            )
            .withCopyFileToContainer(
                    MountableFile.forHostPath("./" + TESTCODE_DIR + "/reposilite-test/reposilite-data/repositories/snapshots/com/example/dependency/dependency-project/maven-metadata.xml.sha1"),
                    "/app/data/repositories/snapshots/com/example/dependency/dependency-project/maven-metadata.xml.sha1"
            )
            .withCopyFileToContainer(
                    MountableFile.forHostPath("./" + TESTCODE_DIR + "/reposilite-test/reposilite-data/repositories/snapshots/com/example/dependency/dependency-project/1.0-SNAPSHOT/dependency-project-1.0-20231105.102337-1.jar"),
                    "/app/data/repositories/snapshots/com/example/dependency/dependency-project/1.0-SNAPSHOT/dependency-project-1.0-20231105.102337-1.jar"
            )
            .withCopyFileToContainer(
                    MountableFile.forHostPath("./" + TESTCODE_DIR + "/reposilite-test/reposilite-data/repositories/snapshots/com/example/dependency/dependency-project/1.0-SNAPSHOT/dependency-project-1.0-20231105.102337-1.jar.md5"),
                    "/app/data/repositories/snapshots/com/example/dependency/dependency-project/1.0-SNAPSHOT/dependency-project-1.0-20231105.102337-1.jar.md5"
            )
            .withCopyFileToContainer(
                    MountableFile.forHostPath("./" + TESTCODE_DIR + "/reposilite-test/reposilite-data/repositories/snapshots/com/example/dependency/dependency-project/1.0-SNAPSHOT/dependency-project-1.0-20231105.102337-1.jar.sha1"),
                    "/app/data/repositories/snapshots/com/example/dependency/dependency-project/1.0-SNAPSHOT/dependency-project-1.0-20231105.102337-1.jar.sha1"
            )
            .withCopyFileToContainer(
                    MountableFile.forHostPath("./" + TESTCODE_DIR + "/reposilite-test/reposilite-data/repositories/snapshots/com/example/dependency/dependency-project/1.0-SNAPSHOT/dependency-project-1.0-20231105.102337-1.pom"),
                    "/app/data/repositories/snapshots/com/example/dependency/dependency-project/1.0-SNAPSHOT/dependency-project-1.0-20231105.102337-1.pom"
            )
            .withCopyFileToContainer(
                    MountableFile.forHostPath("./" + TESTCODE_DIR + "/reposilite-test/reposilite-data/repositories/snapshots/com/example/dependency/dependency-project/1.0-SNAPSHOT/dependency-project-1.0-20231105.102337-1.pom.md5"),
                    "/app/data/repositories/snapshots/com/example/dependency/dependency-project/1.0-SNAPSHOT/dependency-project-1.0-20231105.102337-1.pom.md5"
            )
            .withCopyFileToContainer(
                    MountableFile.forHostPath("./" + TESTCODE_DIR + "/reposilite-test/reposilite-data/repositories/snapshots/com/example/dependency/dependency-project/1.0-SNAPSHOT/dependency-project-1.0-20231105.102337-1.pom.sha1"),
                    "/app/data/repositories/snapshots/com/example/dependency/dependency-project/1.0-SNAPSHOT/dependency-project-1.0-20231105.102337-1.pom.sha1"
            )
            // Create temp user 'user' with password 'secret'
            .withEnv("REPOSILITE_OPTS", "--token user:secret --shared-config shared.configuration.json");

    private static String originalUserHome;
    private static String newUserHome;

    @Autowired
    private RewriteProjectParser parser;

    @Autowired
    private RewriteMavenProjectParser comparingParser;
    private File localMavenRepository = Path.of(TESTCODE_DIR + "/user.home/.m2/repository").toFile();

    private Path dependencyPathInLocalMavenRepo = Path.of(TESTCODE_DIR + "/user.home/.m2/repository/com/example/dependency/dependency-project");

    @BeforeAll
    static void beforeAll(@TempDir Path tempDir) {
        originalUserHome = System.getProperty("user.home");
        newUserHome = Path.of(".").resolve(TESTCODE_DIR + "/user.home").toAbsolutePath().normalize().toString();
        System.setProperty("user.home", newUserHome);
        installMavenForTestIfNotExists(tempDir);
        originalMavenRepository = MavenRepository.MAVEN_LOCAL_DEFAULT;
        MavenRepository mavenRepository = new MavenRepository("local", new File(System.getProperty("user.home") + "/.m2/repository").toURI().toString(), "true", "true", true, null, null, false);
        Whitebox.setInternalState(MavenRepository.class, "MAVEN_LOCAL_DEFAULT", mavenRepository);
    }

    @AfterAll
    static void afterAll() {
        System.setProperty("user.home", originalUserHome);
        Whitebox.setInternalState(MavenRepository.class, "MAVEN_LOCAL_DEFAULT", originalMavenRepository);
    }

    @Test
    @Order(1)
    @DisplayName("Maven settings should be read")
    void mavenSettingsShouldBeRead() throws IOException, MavenInvocationException, InterruptedException {
        Integer port = reposilite.getMappedPort(8080);
        System.out.println("Reposilite: http://localhost:" + port + " login with user:secret");

        // create pom.xml with correct port for dependency-project
        Path dependencyPomTmplPath = Path.of(TESTCODE_DIR + "/dependency-project/pom.xml.template").toAbsolutePath().normalize();
        Path dependencyPomPath = renderPomXml(port, dependencyPomTmplPath);

        // create pom.xml with correct port for dependent-project
        Path dependentPomTmplPath = Path.of(TESTCODE_DIR + "/dependent-project/pom.xml.template").toAbsolutePath().normalize();
        Path dependentPomPath = renderPomXml(port, dependentPomTmplPath);

        // adjust path in settings.xml
        Path settingsXmlTmplPath = Path.of("./").resolve(newUserHome + "/.m2/settings.xml.template").toAbsolutePath().normalize();
        Path settingsXmlPath = renderSettingsXml(newUserHome, settingsXmlTmplPath);

        deployDependency(dependencyPomPath);
        // the project 'testcode/reposilite-test/reposilite-test' has been deployed to reposilite

        clearDependencyFromLocalMavenRepo();

        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(dependentPomPath.toFile());
        request.setShowErrors(true);
        request.setUserSettingsFile(Path.of(TESTCODE_DIR + "/user.home/.m2/settings.xml").toFile());
        request.setGoals(List.of("clean", "package"));
        request.setLocalRepositoryDirectory(localMavenRepository);
        request.setBatchMode(true);
        request.setGlobalChecksumPolicy(InvocationRequest.CheckSumPolicy.Warn);
        request.setOutputHandler(s -> System.out.println(s));
        Invoker invoker = new DefaultInvoker();
        invoker.setMavenHome(Path.of(TESTCODE_DIR + "/user.home/apache-maven-3.9.5").toFile());
        InvocationResult result = invoker.execute(request);
        if (result.getExitCode() != 0) {
            if (result.getExecutionException() != null) {
                fail("Maven clean package failed.", result.getExecutionException());
            } else {
                fail("Maven  clean package. Exit code: " + result.getExitCode());
            }
        }

        clearDependencyFromLocalMavenRepo();
        verifyDependenciesFromPrivateRepoWereResolved();
    }

    //    @Test
//    @Order(2)

    @DisplayName("verify dependencies from private repo were resolved")
    void verifyDependenciesFromPrivateRepoWereResolved() {
        // verify dependency does not exist in local Maven repo
        Path dependencyArtifactDir = dependencyPathInLocalMavenRepo.getParent();
        assertThat(dependencyArtifactDir).isDirectory();
        assertThat(dependencyArtifactDir.toFile().listFiles()).isEmpty();

        // scan a project that depends on this dependency
        Path migrateApplication = Path.of(TESTCODE_DIR + "/dependent-project");
        RewriteProjectParsingResult parsingResult = parser.parse(migrateApplication);

        // verify dependency was downloaded
        Path snapshotDir = dependencyPathInLocalMavenRepo.resolve("1.0-SNAPSHOT").toAbsolutePath().normalize();
        assertThat(snapshotDir).isDirectory();
        assertThat(Arrays.stream(snapshotDir.toFile().listFiles()).map(f -> f.getName()).findFirst().get()).matches("dependency-project-1.0-.*\\.jar");

        // verify that DependencyClass type can be resolved
        J.CompilationUnit cu = (J.CompilationUnit) parsingResult.sourceFiles().stream().filter(s -> s.getSourcePath().toFile().getName().endsWith(".java")).findFirst().get();
        List<String> fqClassesInUse = cu.getTypesInUse().getTypesInUse().stream().filter(JavaType.FullyQualified.class::isInstance).map(JavaType.FullyQualified.class::cast).map(JavaType.FullyQualified::getFullyQualifiedName).toList();

        // DependencyClass must be in list of used types
        assertThat(fqClassesInUse).contains(DEPENDENCY_CLASS_FQNAME);

        // type should be on classpath
        List<String> classpathFqNames = cu.getMarkers().findFirst(JavaSourceSet.class).get().getClasspath().stream().map(fqn -> fqn.getFullyQualifiedName()).toList();
        assertThat(classpathFqNames).contains(DEPENDENCY_CLASS_FQNAME);

        // Type of member should be resolvable
        J.ClassDeclaration classDeclaration = cu.getClasses().get(0);
        JavaType.Class type = (JavaType.Class) ((J.VariableDeclarations) classDeclaration.getBody().getStatements().get(0)).getType();
        assertThat(type.getFullyQualifiedName()).isEqualTo(DEPENDENCY_CLASS_FQNAME);
    }

    private static void installMavenForTestIfNotExists(Path tempDir) {
        if (!Path.of("./testcode/reposilite-test/user.home/apache-maven-3.9.5/bin/mvn").toFile().exists()) {
            String mavenDownloadUrl = "https://dlcdn.apache.org/maven/maven-3/3.9.5/binaries/apache-maven-3.9.5-bin.zip";
            try {
                Path mavenInstallDir = Path.of(TESTCODE_DIR + "/user.home");
                File downloadedMavenZipFile = tempDir.resolve("apache-maven-3.9.5-bin.zip").toFile();
                FileUtils.copyURLToFile(
                        new URL(mavenDownloadUrl),
                        downloadedMavenZipFile,
                        10000,
                        30000);
                unzip(downloadedMavenZipFile, mavenInstallDir);
                File file = mavenInstallDir.resolve("apache-maven-3.9.5/bin/mvn").toFile();
                file.setExecutable(true, false);
                assertThat(file.canExecute()).isTrue();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void unzip(File downloadedMavenZipFile, Path mavenInstallDir) {
        try {
            byte[] buffer = new byte[1024];
            ZipInputStream zis = null;

            zis = new ZipInputStream(new FileInputStream(downloadedMavenZipFile));

            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                File newFile = newFile(mavenInstallDir.toFile(), zipEntry);
                if (zipEntry.isDirectory()) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new IOException("Failed to create directory " + newFile);
                    }
                } else {
                    // fix for Windows-created archives
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory " + parent);
                    }

                    // write file content
                    FileOutputStream fos = new FileOutputStream(newFile);
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                    fos.close();
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
            zis.close();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }


    private Path renderSettingsXml(String testcodeDir, Path settingsXmlTmplPath) throws IOException {
        String settingsXmlContent = Files.readString(settingsXmlTmplPath);
        String replaced = settingsXmlContent.replace($USER_HOME_PLACEHOLDER, testcodeDir);
        Path settingsXmlPath = Path.of(settingsXmlTmplPath.toString().replace(".template", ""));
        return Files.writeString(settingsXmlPath, replaced);
    }


    private static String replace(String content, Object replacement, String regex) {
        Pattern compile = Pattern.compile(regex);
        Matcher matcher = compile.matcher(content);
        String s1 = matcher.replaceFirst("$1" + replacement + "$3");
        return s1;
    }

    private static Path renderPomXml(Integer port, Path pomXmlTmplPath) throws IOException {
        String given = Files.readString(pomXmlTmplPath);
        String replaced = given.replace($PORT_PLACEHOLDER, port.toString());
        Path pomXmlPath = Path.of(pomXmlTmplPath.toString().replace(".template", ""));
        return Files.writeString(pomXmlPath, replaced);
    }

    private void clearDependencyFromLocalMavenRepo() {
        try {
            FileSystemUtils.deleteRecursively(dependencyPathInLocalMavenRepo);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void deployDependency(Path pomXmlPath) throws MavenInvocationException {
        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(pomXmlPath.toFile());
        request.setShowErrors(true);
        request.setUserSettingsFile(Path.of(TESTCODE_DIR + "/user.home/.m2/settings.xml").toFile());
        request.setGoals(List.of("deploy"));
        request.setLocalRepositoryDirectory(localMavenRepository);
        request.setBatchMode(true);
        Invoker invoker = new DefaultInvoker();
        invoker.setMavenHome(Path.of(TESTCODE_DIR + "/user.home/apache-maven-3.9.5").toFile());
        InvocationResult result = invoker.execute(request);
        if (result.getExitCode() != 0) {
            if (result.getExecutionException() != null) {
                fail("Maven deploy failed.", result.getExecutionException());
            } else {
                fail("Maven deploy failed. Exit code: " + result.getExitCode());
            }
        }
    }

}


@Configuration
class MyTEstCOnfig {
    @Bean
    MavenArtifactCache mavenArtifactCache() {
        MavenArtifactCache mavenArtifactCache = new LocalMavenArtifactCache(Paths.get(System.getProperty("user.home"), ".m2", "repository")).orElse(
                new LocalMavenArtifactCache(Paths.get(System.getProperty("user.home"), ".rewrite", "cache", "artifacts"))
        );
        return mavenArtifactCache;
    }
}