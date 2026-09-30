/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.ejb;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.jar.JarFile;

import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.plugins.ejb.utils.JarContentChecker;
import org.apache.maven.testing.plugin.Basedir;
import org.apache.maven.testing.plugin.InjectMojo;
import org.apache.maven.testing.plugin.MojoTest;
import org.apache.maven.testing.plugin.stubs.ProducedArtifactStub;
import org.apache.maven.testing.plugin.stubs.ProjectStub;
import org.apache.maven.testing.plugin.stubs.SessionMock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EJB plugin Test Case
 */
@MojoTest
class EjbMojoTest {
    static final String DEFAULT_JAR_NAME = "testJar";

    @TempDir
    Path tempDir;

    /**
     * check test environment
     */
    @Test
    @Basedir("${basedir}/src/test/resources/unit/ejbmojotest")
    @InjectMojo(goal = "ejb")
    void testTestEnvironment(EjbMojo mojo) {
        // Perform lookup on the Mojo to make sure everything is ok
        assertNotNull(mojo);
        assertEquals("foo", mojo.getProject().getGroupId());
    }

    /**
     * Basic jar creation test.
     *
     * @throws Exception if any exception occurs
     */
    @Test
    void testDefaultWithoutClientJar() throws Exception {
        final TestProject project = createTestProject("default-noclient");
        final EjbMojo mojo = lookupMojoWithDefaultSettings(project);

        setupDefaultProject(project);

        set(mojo, "generateClient", Boolean.FALSE);
        set(mojo, "ejbVersion", "2.1");

        mojo.execute();

        assertJarCreation(project, true, false);
        // without classifier the jar replaces the main artifact
        verify(projectManager)
                .attachArtifact(
                        eq((Project) project.stub),
                        same(project.stub.getMainArtifact().orElseThrow()),
                        eq(Path.of(project.getBuildDirectory(), DEFAULT_JAR_NAME + ".jar")));
        verify(session, never())
                .createProducedArtifact(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    /**
     * Classified jar creation test.
     *
     * @throws Exception if any exception occurs
     */
    @Test
    void testClassifiedJarWithoutClientJar() throws Exception {
        final TestProject project = createTestProject("classified-noclient");
        final EjbMojo mojo = lookupMojoWithDefaultSettings(project);

        setupDefaultProject(project);

        set(mojo, "generateClient", Boolean.FALSE);
        set(mojo, "ejbVersion", "2.1");
        set(mojo, "classifier", "classified");

        mojo.execute();

        assertJarCreation(project, true, false, "classified");
    }

    /**
     * Basic jar creation test with client jar.
     *
     * @throws Exception if any exception occurs
     */
    @Test
    void testDefaultWithClientJar() throws Exception {
        final TestProject project = createTestProject("default-client");
        final EjbMojo mojo = lookupMojoWithDefaultSettings(project);

        setupDefaultProject(project);

        set(mojo, "generateClient", Boolean.TRUE);
        set(mojo, "ejbVersion", "2.1");

        mojo.execute();

        assertJarCreation(project, true, true);
    }

    /**
     * Classified jar creation test with client jar.
     *
     * @throws Exception if any exception occurs
     */
    @Test
    void testClassifiedJarWithClientJar() throws Exception {
        final TestProject project = createTestProject("classified-client");
        final EjbMojo mojo = lookupMojoWithDefaultSettings(project);

        setupDefaultProject(project);

        set(mojo, "generateClient", Boolean.TRUE);
        set(mojo, "ejbVersion", "2.1");
        set(mojo, "classifier", "classified");
        set(mojo, "clientClassifier", "classified-client");

        mojo.execute();

        assertJarCreation(project, true, true, "classified");
        // both jars are attached as supplemental artifacts of type ejb and ejb-client
        verify(session)
                .createProducedArtifact(
                        "org.apache.maven.test", "maven-test-plugin", "classified-client", "classified", null, "ejb");
        verify(session)
                .createProducedArtifact(
                        "org.apache.maven.test",
                        "maven-test-plugin",
                        "classified-client",
                        "classified-client",
                        null,
                        "ejb-client");
        verify(projectManager, times(2))
                .attachArtifact(any(Project.class), any(ProducedArtifact.class), any(Path.class));
    }

    /**
     * Default ejb jar inclusion and exclusion test.
     *
     * @throws Exception if any exception occurs
     */
    @Test
    void testDefaultInclusionsExclusions() throws Exception {

        final TestProject project = createTestProject("includes-excludes-default");
        final EjbMojo mojo = lookupMojoWithDefaultSettings(project);

        // put this on the target output dir
        project.addFile("META-INF/ejb-jar.xml", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb/AppBean.class", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb/AppCMP.class", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb/AppSession.class", TestProject.OUTPUT_FILE);

        // put this on the root dir
        project.addFile("pom.xml", TestProject.ROOT_FILE);

        // start creating the environment
        project.setupBuildEnvironment();

        set(mojo, "generateClient", Boolean.FALSE);
        set(mojo, "ejbVersion", "2.1");

        mojo.execute();

        assertJarCreation(project, true, false);
        assertJarContent(
                project,
                new String[] {
                    "META-INF/MANIFEST.MF",
                    "META-INF/ejb-jar.xml",
                    "META-INF/maven/org.apache.maven.test/maven-test-plugin/pom.xml",
                    "META-INF/maven/org.apache.maven.test/maven-test-plugin/pom.properties",
                    "org/sample/ejb/AppBean.class",
                    "org/sample/ejb/AppCMP.class",
                    "org/sample/ejb/AppSession.class"
                },
                null);
    }

    /**
     * Client jar default inclusion and exclusion test.
     *
     * @throws Exception if any exception occurs
     */
    @Test
    void testClientJarDefaultInclusionsExclusions() throws Exception {

        final TestProject project = createTestProject("includes-excludes-client");
        final EjbMojo mojo = lookupMojoWithDefaultSettings(project);

        // put this on the target output dir
        project.addFile("META-INF/ejb-jar.xml", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb/AppBean.class", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb/AppCMP.class", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb/AppSession.class", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb/AppStub.class", TestProject.OUTPUT_FILE);

        // put this on the root dir
        project.addFile("pom.xml", TestProject.ROOT_FILE);

        // start creating the environment
        project.setupBuildEnvironment();

        set(mojo, "generateClient", Boolean.TRUE);
        set(mojo, "ejbVersion", "2.1");

        mojo.execute();

        assertJarCreation(project, true, true);
        assertClientJarContent(
                project,
                new String[] {
                    "META-INF/MANIFEST.MF",
                    "META-INF/maven/org.apache.maven.test/maven-test-plugin/pom.xml",
                    "META-INF/maven/org.apache.maven.test/maven-test-plugin/pom.properties",
                    "org/sample/ejb/AppStub.class"
                },
                new String[] {
                    "META-INF/ejb-jar.xml",
                    "org/sample/ejb/AppBean.class",
                    "org/sample/ejb/AppCMP.class",
                    "org/sample/ejb/AppSession.class"
                });
    }

    /**
     * Client jar inclusion test.
     *
     * @throws Exception if any exception occurs
     */
    @Test
    void testClientJarInclusions() throws Exception {
        final List<String> inclusions = new LinkedList<>();
        inclusions.add("**/*Include.class");

        final TestProject project = createTestProject("client-includes");
        final EjbMojo mojo = lookupMojoWithSettings(project, inclusions, new LinkedList<>(), null);

        // put this on the target output dir
        project.addFile("META-INF/ejb-jar.xml", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb/AppInclude.class", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb/AppExclude.class", TestProject.OUTPUT_FILE);

        // put this on the root dir
        project.addFile("pom.xml", TestProject.ROOT_FILE);

        // start creating the environment
        project.setupBuildEnvironment();

        set(mojo, "generateClient", Boolean.TRUE);
        set(mojo, "ejbVersion", "2.1");

        mojo.execute();

        assertJarCreation(project, true, true);
        assertClientJarContent(
                project,
                new String[] {
                    "META-INF/MANIFEST.MF",
                    "META-INF/maven/org.apache.maven.test/maven-test-plugin/pom.xml",
                    "META-INF/maven/org.apache.maven.test/maven-test-plugin/pom.properties",
                    "org/sample/ejb/AppInclude.class"
                },
                new String[] {"META-INF/ejb-jar.xml", "org/sample/ejb/AppExclude.class"});
    }

    /**
     * Client jar exclusions test.
     *
     * @throws Exception if any exception occurs
     */
    @Test
    void testClientJarExclusions() throws Exception {

        final List<String> exclusions = new LinkedList<>();
        exclusions.add("**/*Exclude.class");

        final TestProject project = createTestProject("client-excludes");
        final EjbMojo mojo = lookupMojoWithSettings(project, new LinkedList<>(), exclusions, null);

        // put this on the target output dir
        project.addFile("META-INF/ejb-jar.xml", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb/AppInclude.class", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb/AppExclude.class", TestProject.OUTPUT_FILE);

        // put this on the root dir
        project.addFile("pom.xml", TestProject.ROOT_FILE);

        // start creating the environment
        project.setupBuildEnvironment();

        set(mojo, "generateClient", Boolean.TRUE);
        set(mojo, "ejbVersion", "2.1");

        mojo.execute();

        assertJarCreation(project, true, true);
        assertClientJarContent(
                project,
                new String[] {
                    "META-INF/MANIFEST.MF",
                    "META-INF/maven/org.apache.maven.test/maven-test-plugin/pom.xml",
                    "META-INF/maven/org.apache.maven.test/maven-test-plugin/pom.properties",
                    "org/sample/ejb/AppInclude.class"
                },
                new String[] {"META-INF/ejb-jar.xml", "org/sample/ejb/AppExclude.class"});
    }

    /**
     * Main jar exclusions test.
     *
     * @throws Exception if any exception occurs
     */
    @Test
    void testMainJarExclusions() throws Exception {
        final List<String> exclusions = new LinkedList<>();
        exclusions.add("**/*Exclude.class");

        final TestProject project = createTestProject("main-excludes");
        final EjbMojo mojo = lookupMojoWithSettings(project, new LinkedList<>(), new LinkedList<>(), exclusions);

        // put this on the target output dir
        project.addFile("META-INF/ejb-jar.xml", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb/AppInclude.class", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb/AppExclude.class", TestProject.OUTPUT_FILE);

        // put this on the root dir
        project.addFile("pom.xml", TestProject.ROOT_FILE);

        // start creating the environment
        project.setupBuildEnvironment();

        set(mojo, "generateClient", Boolean.TRUE);
        set(mojo, "ejbVersion", "2.1");

        mojo.execute();

        assertJarCreation(project, true, true);
        assertJarContent(
                project,
                new String[] {
                    "META-INF/MANIFEST.MF",
                    "META-INF/maven/org.apache.maven.test/maven-test-plugin/pom.xml",
                    "META-INF/maven/org.apache.maven.test/maven-test-plugin/pom.properties",
                    "org/sample/ejb/AppInclude.class"
                },
                new String[] {"META-INF/ejb-jar.xml", "org/sample/ejb/AppExclude.class"});
    }

    /**
     * Client jar inclusion test with a sub-package.
     *
     * @throws Exception if any exception occurs
     */
    @Test
    void testClientJarInclusionsWithSubPackage() throws Exception {
        final List<String> inclusions = new LinkedList<>();
        inclusions.add("org/sample/ejb/*.class");

        final TestProject project = createTestProject("client-includes-subpackage");

        final EjbMojo mojo = lookupMojoWithSettings(project, inclusions, new LinkedList<>(), null);

        // put this on the target output dir
        project.addFile("META-INF/ejb-jar.xml", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb/App.class", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb/impl/AppImpl.class", TestProject.OUTPUT_FILE);

        // put this on the root dir
        project.addFile("pom.xml", TestProject.ROOT_FILE);

        // start creating the environment
        project.setupBuildEnvironment();

        set(mojo, "generateClient", Boolean.TRUE);
        set(mojo, "ejbVersion", "2.1");

        mojo.execute();

        assertJarCreation(project, true, true);
        assertClientJarContent(
                project,
                new String[] {
                    "META-INF/MANIFEST.MF",
                    "META-INF/maven/org.apache.maven.test/maven-test-plugin/pom.xml",
                    "META-INF/maven/org.apache.maven.test/maven-test-plugin/pom.properties",
                    "org/sample/ejb/App.class"
                },
                new String[] {"META-INF/ejb-jar.xml", "org/sample/ejb/impl/AppImpl.class", "org/sample/ejb/impl"});
    }

    /**
     * Client jar exclusions test that leaves an empty package.
     *
     * @throws Exception if any exception occurs
     */
    @Test
    void testClientJarExclusionsWithEmptyPackage() throws Exception {

        final LinkedList<String> exclusions = new LinkedList<>();
        exclusions.add("org/sample/ejb/**");

        final TestProject project = createTestProject("client-excludes-emptypackage");
        final EjbMojo mojo = lookupMojoWithSettings(project, new LinkedList<>(), exclusions, null);

        // put this on the target output dir
        project.addFile("META-INF/ejb-jar.xml", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb/AppOne.class", TestProject.OUTPUT_FILE);
        project.addFile("org/sample/ejb2/AppTwo.class", TestProject.OUTPUT_FILE);

        // put this on the root dir
        project.addFile("pom.xml", TestProject.ROOT_FILE);

        // start creating the environment
        project.setupBuildEnvironment();

        set(mojo, "generateClient", Boolean.TRUE);
        set(mojo, "ejbVersion", "2.1");

        mojo.execute();

        assertJarCreation(project, true, true);

        // We check that the created jar does not contain the org/sample/ejb package empty
        assertClientJarContent(
                project,
                new String[] {
                    "META-INF/MANIFEST.MF",
                    "META-INF/maven/org.apache.maven.test/maven-test-plugin/pom.xml",
                    "META-INF/maven/org.apache.maven.test/maven-test-plugin/pom.properties",
                    "org/sample/ejb2/AppTwo.class"
                },
                new String[] {"META-INF/ejb-jar.xml", "org/sample/ejb/AppOne.class", "org/sample/ejb"});
    }

    /**
     * Tests if the mojo throws an exception when the EJB version is &lt; 3.0 and no deployment descriptor is present.
     * The case with deployment descriptor present is covered by previous tests.
     *
     * @throws Exception if any exception occurs
     */
    @Test
    void testEjbComplianceVersionTwoDotOneWithoutDescriptor() throws Exception {
        final TestProject project = createTestProject("compliance-nodescriptor-2.1");
        final EjbMojo mojo = lookupMojoWithDefaultSettings(project);

        // put this on the root dir
        project.addFile("pom.xml", TestProject.ROOT_FILE);

        // start creating the environment
        project.setupBuildEnvironment();

        set(mojo, "generateClient", Boolean.FALSE);
        set(mojo, "ejbVersion", "2.1");

        assertThrows(MojoException.class, mojo::execute, "No deployment descriptor present.");
    }

    /**
     * Tests if the jar is created under EJB version 3.0 with deployment descriptor present.
     *
     * @throws Exception if any exception occurs
     */
    @Test
    void testEjbComplianceVersionThreeWithDescriptor() throws Exception {

        final TestProject project = createTestProject("compliance-descriptor-3");
        final EjbMojo mojo = lookupMojoWithDefaultSettings(project);

        // put this on the target dir
        project.addFile("META-INF/ejb-jar.xml", TestProject.OUTPUT_FILE);

        // put this on the root dir
        project.addFile("pom.xml", TestProject.ROOT_FILE);

        // start creating the environment
        project.setupBuildEnvironment();

        set(mojo, "generateClient", Boolean.FALSE);
        set(mojo, "ejbVersion", "3.0");

        mojo.execute();

        assertJarCreation(project, true, false);
    }

    /**
     * Tests if the jar is created under EJB version 3.0 without deployment descriptor present.
     *
     * @throws Exception if any exception occurs
     */
    @Test
    void testEjbCompliance30WithoutDescriptor() throws Exception {
        final TestProject project = createTestProject("compliance-nodescriptor-3");
        final EjbMojo mojo = lookupMojoWithDefaultSettings(project);

        // put this on the root dir
        project.addFile("pom.xml", TestProject.ROOT_FILE);

        // start creating the environment
        project.setupBuildEnvironment();

        set(mojo, "generateClient", Boolean.FALSE);
        set(mojo, "ejbVersion", "3.0");

        mojo.execute();

        assertJarCreation(project, true, false);
    }

    @Test
    void testEjb1VersionValidation() {
        assertThrows(MojoException.class, () -> EjbMojo.validateEjbVersion("1.1"));
    }

    @Test
    void testEjb2VersionValidation() {
        EjbMojo.validateEjbVersion("2.1");
    }

    @Test
    void testEjb3VersionValidation() {
        EjbMojo.validateEjbVersion("3.2");
    }

    @Test
    void testEjb4VersionValidation() {
        EjbMojo.validateEjbVersion("4.0");
    }

    private ProjectManager projectManager;

    private Session session;

    /**
     * Test project with a temporary base directory, populated with empty files.
     */
    static final class TestProject {
        static final int OUTPUT_FILE = 1;

        static final int ROOT_FILE = 2;

        private final Path root;

        private final List<String> outputFiles = new ArrayList<>();

        private final List<String> rootFiles = new ArrayList<>();

        final ProjectStub stub;

        TestProject(Path root, String version) {
            this.root = root;
            stub = new ProjectStub();
            stub.setBasedir(root);
            stub.setPomPath(root.resolve("pom.xml"));
            stub.setModel(Model.newBuilder()
                    .groupId("org.apache.maven.test")
                    .artifactId("maven-test-plugin")
                    .version(version)
                    .name("Test Project " + version)
                    .build(Build.newBuilder()
                            .directory(getBuildDirectory())
                            .outputDirectory(getOutputDirectory())
                            .build())
                    .build());
            ProducedArtifactStub artifact = new ProducedArtifactStub();
            artifact.setGroupId("org.apache.maven.test");
            artifact.setArtifactId("maven-test-plugin");
            artifact.setVersion(version);
            artifact.setExtension("jar");
            stub.setMainArtifact(artifact);
        }

        String getBuildDirectory() {
            return root.resolve("target").toString();
        }

        String getOutputDirectory() {
            return root.resolve("target/classes").toString();
        }

        void addFile(String name, int type) {
            (type == OUTPUT_FILE ? outputFiles : rootFiles).add(name);
        }

        void setupBuildEnvironment() throws IOException {
            Files.createDirectories(Path.of(getOutputDirectory()));
            for (String name : outputFiles) {
                touch(Path.of(getOutputDirectory()).resolve(name));
            }
            for (String name : rootFiles) {
                touch(root.resolve(name));
            }
        }

        private static void touch(Path file) throws IOException {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                Files.createFile(file);
            }
        }
    }

    private static void set(Object target, String field, Object value) throws ReflectiveOperationException {
        java.lang.reflect.Field f = EjbMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private TestProject createTestProject(final String testName) {
        return new TestProject(tempDir.resolve(testName), testName);
    }

    private void setupDefaultProject(final TestProject project) throws Exception {
        // put this on the target dir
        project.addFile("META-INF/ejb-jar.xml", TestProject.OUTPUT_FILE);
        // put this on the root dir
        project.addFile("pom.xml", TestProject.ROOT_FILE);
        // start creating the environment
        project.setupBuildEnvironment();
    }

    private EjbMojo lookupMojoWithSettings(
            final TestProject project, List<String> clientIncludes, List<String> clientExcludes, List<String> excludes)
            throws Exception {
        session = SessionMock.getMockSession(tempDir.resolve("local-repo").toString());
        when(session.getSystemProperties()).thenReturn(new HashMap<>());
        ProducedArtifact producedArtifact = mock(ProducedArtifact.class);
        doReturn(producedArtifact)
                .when(session)
                .createProducedArtifact(anyString(), anyString(), anyString(), any(), any(), anyString());
        projectManager = mock(ProjectManager.class);

        final EjbMojo mojo = new EjbMojo();
        set(mojo, "project", project.stub);
        set(mojo, "session", session);
        set(mojo, "projectManager", projectManager);
        set(mojo, "log", mock(Log.class));
        set(mojo, "mavenFileFilter", null);
        set(mojo, "outputDirectory", new File(project.getBuildDirectory()));
        set(mojo, "sourceDirectory", new File(project.getOutputDirectory()));
        set(mojo, "jarName", DEFAULT_JAR_NAME);
        set(mojo, "ejbJar", EjbMojo.DEFAULT_EJBJAR);
        set(mojo, "ejbVersion", "3.1");
        set(mojo, "clientExcludes", clientExcludes);
        set(mojo, "clientIncludes", clientIncludes);
        set(mojo, "excludes", excludes);
        set(mojo, "clientClassifier", EjbMojo.DEFAULT_CLIENT_CLASSIFIER);

        return mojo;
    }

    private EjbMojo lookupMojoWithDefaultSettings(final TestProject project) throws Exception {
        return lookupMojoWithSettings(project, new LinkedList<>(), new LinkedList<>(), null);
    }

    private void assertJarCreation(
            final TestProject project, boolean ejbJarCreated, boolean ejbClientJarCreated, String classifier) {
        String checkedJarFile;
        String checkedClientJarFile;

        if (classifier == null) {
            checkedJarFile = project.getBuildDirectory() + "/" + DEFAULT_JAR_NAME + ".jar";
            checkedClientJarFile = project.getBuildDirectory() + "/" + DEFAULT_JAR_NAME + "-client.jar";
        } else {
            checkedJarFile = project.getBuildDirectory() + "/" + DEFAULT_JAR_NAME + "-" + classifier + ".jar";
            checkedClientJarFile =
                    project.getBuildDirectory() + "/" + DEFAULT_JAR_NAME + "-" + classifier + "-client.jar";
        }

        assertEquals(ejbJarCreated, new File(checkedJarFile).exists(), "Invalid value for ejb-jar creation");
        assertEquals(
                ejbClientJarCreated,
                new File(checkedClientJarFile).exists(),
                "Invalid value for ejb-jar client creation");
    }

    private void assertJarCreation(final TestProject project, boolean ejbJarCreated, boolean ejbClientJarCreated) {
        assertJarCreation(project, ejbJarCreated, ejbClientJarCreated, null);
    }

    private void doAssertJarContent(
            final TestProject project,
            final String fileName,
            final String[] expectedFiles,
            final String[] unexpectedFiles)
            throws IOException {
        String checkedJarFile = project.getBuildDirectory() + "/" + fileName;
        if (expectedFiles != null) {
            final JarContentChecker inclusionChecker = new JarContentChecker();

            // set expected jar contents
            for (String expectedFile : expectedFiles) {
                inclusionChecker.addFile(new File(expectedFile));
            }
            try (JarFile jar = new JarFile(checkedJarFile)) {
                assertTrue(inclusionChecker.isOK(jar));
            }
        }
        if (unexpectedFiles != null) {
            final JarContentChecker exclusionChecker = new JarContentChecker();
            for (String unexpectedFile : unexpectedFiles) {
                exclusionChecker.addFile(new File(unexpectedFile));
            }
            try (JarFile jar = new JarFile(checkedJarFile)) {
                assertFalse(exclusionChecker.isOK(jar));
            }
        }
    }

    private void assertJarContent(
            final TestProject project, final String[] expectedFiles, final String[] unexpectedFiles)
            throws IOException {

        doAssertJarContent(project, DEFAULT_JAR_NAME + ".jar", expectedFiles, unexpectedFiles);
    }

    private void assertClientJarContent(
            final TestProject project, final String[] expectedFiles, final String[] unexpectedFiles)
            throws IOException {

        doAssertJarContent(project, DEFAULT_JAR_NAME + "-client.jar", expectedFiles, unexpectedFiles);
    }
}
