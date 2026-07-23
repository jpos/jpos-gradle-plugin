/*
 * jPOS Project [http://jpos.org]
 * Copyright (C) 2000-2026 jPOS Software SRL
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jpos.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class VendorFunctionalTest {

    @TempDir
    File projectDir;

    private String settingsOriginal;

    private static final String POM =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
        "  <modelVersion>4.0.0</modelVersion>\n" +
        "  <groupId>org.example</groupId>\n" +
        "  <artifactId>demo</artifactId>\n" +
        "  <version>1.0.0</version>\n" +
        "  <dependencies>\n" +
        "    <dependency>\n" +
        "      <groupId>org.apache.commons</groupId>\n" +
        "      <artifactId>commons-lang3</artifactId>\n" +
        "      <version>3.20.0</version>\n" +
        "      <scope>runtime</scope>\n" +
        "    </dependency>\n" +
        "    <dependency>\n" +
        "      <groupId>junit</groupId>\n" +
        "      <artifactId>junit</artifactId>\n" +
        "      <version>4.13.2</version>\n" +
        "      <scope>test</scope>\n" +
        "    </dependency>\n" +
        "  </dependencies>\n" +
        "</project>\n";

    private static final String DEMO_JAVA =
        "package org.example.demo;\n" +
        "public class Demo {\n" +
        "    public String hello() { return \"hello\"; }\n" +
        "}\n";

    @BeforeEach
    void setUp() throws IOException {
        File repo = new File(projectDir, "maven-repo");
        File moduleDir = new File(repo, "org/example/demo/1.0.0");
        moduleDir.mkdirs();
        Files.writeString(new File(moduleDir, "demo-1.0.0.pom").toPath(), POM);
        writeJar(new File(moduleDir, "demo-1.0.0.jar"),
            Map.of("org/example/demo/marker.txt", "main".getBytes(StandardCharsets.UTF_8)));
        Map<String, byte[]> sources = new LinkedHashMap<>();
        sources.put("org/example/demo/Demo.java", DEMO_JAVA.getBytes(StandardCharsets.UTF_8));
        sources.put("messages.properties", "greeting=hello\n".getBytes(StandardCharsets.UTF_8));
        writeJar(new File(moduleDir, "demo-1.0.0-sources.jar"), sources);

        settingsOriginal = "rootProject.name = 'test-app'\n";
        writeFile("settings.gradle", settingsOriginal);
        writeFile("gradle/libs.versions.toml",
            "[versions]\n" +
            "demo = \"1.0.0\"\n" +
            "\n" +
            "[libraries]\n" +
            "demo = { module = \"org.example:demo\", version.ref = \"demo\" }\n");
        writeFile("build.gradle",
            "plugins {\n" +
            "    id 'java'\n" +
            "    id 'org.jpos.jposapp'\n" +
            "}\n" +
            "version = '1.0.0'\n" +
            "repositories {\n" +
            "    maven { url = '" + repo.toURI() + "' }\n" +
            "    mavenCentral()\n" +
            "}\n" +
            "dependencies {\n" +
            "    implementation libs.demo\n" +
            "}\n" +
            "tasks.register('printRuntime') {\n" +
            "    doLast { println 'RUNTIME=' + configurations.runtimeClasspath.files }\n" +
            "}\n");
    }

    @Test
    void vendorExtractsSourcesAndWiresBuild() throws Exception {
        run("vendor", "--lib", "demo");

        assertTrue(new File(projectDir, "vendor/demo/src/main/java/org/example/demo/Demo.java").isFile(),
            "Demo.java should be extracted");
        assertTrue(new File(projectDir, "vendor/demo/src/main/resources/messages.properties").isFile(),
            "messages.properties should be extracted as a resource");

        String marker = Files.readString(new File(projectDir, "vendor/demo/.vendored").toPath());
        String[] markerLines = marker.split("\n");
        assertEquals("org.example:demo:1.0.0", markerLines[0], "marker should record the resolved coordinate");
        assertEquals(2, markerLines.length, "marker should record a content digest on the second line");

        String generated = Files.readString(new File(projectDir, "vendor/demo/build.gradle").toPath());
        assertTrue(generated.contains("implementation 'org.apache.commons:commons-lang3:3.20.0'"),
            "runtime dep should map to implementation. Got:\n" + generated);
        assertFalse(generated.contains("junit:junit"), "test-scope dep should be skipped. Got:\n" + generated);
        assertTrue(generated.contains(new File(projectDir, "maven-repo").getAbsolutePath()),
            "generated build should reproduce the file repo URL. Got:\n" + generated);
        assertTrue(generated.contains("mavenCentral()"),
            "well-known repo URL should map back to its DSL shorthand. Got:\n" + generated);
        assertFalse(generated.contains("repo.maven.apache.org"),
            "Maven Central should not appear as a literal URL. Got:\n" + generated);

        String settings = Files.readString(new File(projectDir, "settings.gradle").toPath());
        assertEquals(settingsOriginal + "include ':vendor:demo'\n", settings,
            "settings.gradle should gain only the include line");
    }

    @Test
    void runtimeClasspathUsesRepositoryBeforeVendoring() {
        BuildResult result = run("printRuntime");
        String out = runtimeLine(result);
        assertTrue(out.contains("demo-1.0.0.jar"), "should resolve the repository demo jar. Got: " + out);
        assertFalse(out.contains("/vendor/demo/"), "should not resolve from vendor/ before vendoring. Got: " + out);
    }

    @Test
    void runtimeClasspathRedirectsToVendoredModule() {
        run("vendor", "--lib", "demo");
        BuildResult result = run("printRuntime");
        String out = runtimeLine(result);
        assertTrue(out.contains("/vendor/demo/"),
            "demo should now resolve to the local vendored project. Got: " + out);
        assertTrue(out.contains("commons-lang3"),
            "transitive dependency should still resolve. Got: " + out);
    }

    @Test
    void unvendorRestoresSettingsAndRemovesDirectory() throws Exception {
        run("vendor", "--lib", "demo");
        assertTrue(new File(projectDir, "vendor/demo").isDirectory());

        run("unvendor");

        assertFalse(new File(projectDir, "vendor").exists(), "vendor/ should be removed when empty");
        assertEquals(settingsOriginal, Files.readString(new File(projectDir, "settings.gradle").toPath()),
            "settings.gradle should be restored byte-for-byte");
    }

    @Test
    void revendoringSameCoordinatesIsANoOp() {
        run("vendor", "--lib", "demo");
        // in a multi-project build the task runs once per project applying the plugin,
        // so a second run over the same coordinates must succeed
        BuildResult result = run("vendor", "--lib", "demo");
        assertTrue(result.getOutput().contains("nothing to do"),
            "second vendor should be a no-op. Output: " + result.getOutput());
    }

    @Test
    void buildOutputsDoNotTripTheModificationGuard() throws Exception {
        run("vendor", "--lib", "demo");
        // simulate Gradle having built the vendored sub-project
        writeFile("vendor/demo/build/classes/java/main/org/example/demo/Demo.class", "bytecode");
        writeFile("vendor/demo/.gradle/config.bin", "state");

        run("unvendor");
        assertFalse(new File(projectDir, "vendor").exists(),
            "build outputs inside the vendored project must not count as modifications");
    }

    @Test
    void unvendorLeavesForeignDirectoriesAlone() throws Exception {
        writeFile("vendor/handmade/notes.txt", "not created by the vendor task\n");
        run("vendor", "--lib", "demo");

        run("unvendor");
        assertFalse(new File(projectDir, "vendor/demo").exists(), "vendored module should be removed");
        assertTrue(new File(projectDir, "vendor/handmade/notes.txt").isFile(),
            "directories without a .vendored marker must survive unvendor-all");

        BuildResult result = runAndFail("unvendor", "--lib", "handmade");
        assertTrue(result.getOutput().contains("no .vendored marker"),
            "explicitly unvendoring a foreign directory should refuse. Output: " + result.getOutput());
        assertTrue(new File(projectDir, "vendor/handmade/notes.txt").isFile());
    }

    @Test
    void unvendorRefusesWhenContentModified() throws Exception {
        run("vendor", "--lib", "demo");
        File patched = new File(projectDir, "vendor/demo/src/main/java/org/example/demo/Demo.java");
        Files.writeString(patched.toPath(), DEMO_JAVA.replace("hello", "patched"));

        BuildResult result = runAndFail("unvendor");
        assertTrue(result.getOutput().contains("has been modified since it was vendored"),
            "unvendor should refuse to discard local patches. Output: " + result.getOutput());
        assertTrue(patched.isFile(), "the patched file should survive the refused unvendor");
    }

    @Test
    void vendoringOverDifferentCoordinatesFails() throws Exception {
        run("vendor", "--lib", "demo");
        Files.writeString(new File(projectDir, "vendor/demo/.vendored").toPath(), "org.example:demo:0.9.9\n");
        BuildResult result = runAndFail("vendor", "--lib", "demo");
        assertTrue(result.getOutput().contains("already exists with different coordinates"),
            "vendor over a foreign directory should fail clearly. Output: " + result.getOutput());
    }

    @Test
    void unknownLibKeyListsAvailableKeys() {
        BuildResult result = runAndFail("vendor", "--lib", "nope");
        assertTrue(result.getOutput().contains("Available keys"),
            "should list available keys. Output: " + result.getOutput());
        assertTrue(result.getOutput().contains("demo"),
            "should mention the available 'demo' key. Output: " + result.getOutput());
    }

    // --- helpers ---

    private String runtimeLine(BuildResult result) {
        return result.getOutput().lines()
            .filter(l -> l.startsWith("RUNTIME="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("printRuntime produced no RUNTIME line:\n" + result.getOutput()));
    }

    private BuildResult run(String... args) {
        return runner(args).build();
    }

    private BuildResult runAndFail(String... args) {
        return runner(args).buildAndFail();
    }

    private GradleRunner runner(String... args) {
        String[] allArgs = new String[args.length + 1];
        System.arraycopy(args, 0, allArgs, 0, args.length);
        allArgs[args.length] = "--stacktrace";
        return GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(allArgs);
    }

    private void writeFile(String relativePath, String content) throws IOException {
        File file = new File(projectDir, relativePath);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content);
    }

    private static void writeJar(File jar, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar.toPath()))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
    }
}
