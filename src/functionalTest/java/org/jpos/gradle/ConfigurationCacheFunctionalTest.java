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
import java.nio.file.Files;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationCacheFunctionalTest {

    @TempDir
    File projectDir;

    @BeforeEach
    void setUp() throws IOException {
        writeFile("settings.gradle", "rootProject.name = 'cc-app'\n");
        writeFile("build.gradle",
            "plugins {\n" +
            "    id 'java'\n" +
            "    id 'org.jpos.jposapp'\n" +
            "}\n" +
            "version = '1.0.0'\n"
        );
        writeFile("devel.properties", "test_token=REPLACED\n");
        writeFile("src/main/java/test/App.java", "package test;\npublic class App {}\n");
        writeFile("src/dist/bin/q2", "#!/bin/bash\necho @test_token@\n");
        writeFile("gradle/libs.versions.toml", "[versions]\njpos = \"3.0.2-SNAPSHOT\"\n\n[libraries]\n");
    }

    @Test
    void distStoresAndReusesConfigurationCache() {
        BuildResult first = run("dist", "--configuration-cache");
        assertTrue(first.getOutput().contains("Configuration cache entry stored"),
            "first run should store a configuration cache entry. Output: " + first.getOutput());

        BuildResult second = run("dist", "--configuration-cache");
        assertTrue(second.getOutput().contains("Reusing configuration cache"),
            "second run should reuse the configuration cache. Output: " + second.getOutput());
    }

    @Test
    void jarManifestIsCorrectUnderConfigurationCache() throws Exception {
        run("jar", "--configuration-cache");

        File[] jars = new File(projectDir, "build/libs").listFiles((d, n) -> n.endsWith(".jar"));
        assertNotNull(jars);
        assertEquals(1, jars.length, "expected exactly one jar in build/libs");
        try (JarFile jar = new JarFile(jars[0])) {
            var attrs = jar.getManifest().getMainAttributes();
            assertEquals("org.jpos.q2.Q2", attrs.getValue("Main-Class"));
            assertEquals("cc-app", attrs.getValue("Implementation-Title"));
            String classPath = attrs.getValue("Class-Path");
            assertTrue(classPath == null || !classPath.contains("provider"),
                "Class-Path must be the resolved value, not a Provider's toString. Got: " + classPath);
        }
    }

    @Test
    void fatjarWorksUnderConfigurationCache() {
        BuildResult first = run("fatjar", "--configuration-cache");
        assertTrue(first.getOutput().contains("Configuration cache entry stored"),
            "fatjar should be configuration-cache compatible. Output: " + first.getOutput());
        assertTrue(new File(projectDir, "build/libs/cc-app-1.0.0-all.jar").isFile());

        BuildResult second = run("fatjar", "--configuration-cache");
        assertTrue(second.getOutput().contains("Reusing configuration cache"),
            "second fatjar run should reuse the configuration cache. Output: " + second.getOutput());
    }

    @Test
    void maintenanceTasksDegradeGracefully() {
        // pins is declared notCompatibleWithConfigurationCache: it must still run
        // (Gradle just disables the cache for that invocation)
        BuildResult result = run("pins", "--configuration-cache");
        assertTrue(result.getOutput().contains("jpos"),
            "pins should list the snapshot entry. Output: " + result.getOutput());
    }

    // --- helpers ---

    private BuildResult run(String... args) {
        String[] allArgs = new String[args.length + 1];
        System.arraycopy(args, 0, allArgs, 0, args.length);
        allArgs[args.length] = "--stacktrace";
        return GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(allArgs)
            .build();
    }

    private void writeFile(String relativePath, String content) throws IOException {
        File file = new File(projectDir, relativePath);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content);
    }
}
