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

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ExtraPathsFunctionalTest {

    @TempDir
    File projectDir;

    @BeforeEach
    void setUp() throws IOException {
        writeFile("settings.gradle", "rootProject.name = 'test-app'\n");

        writeFile("build.gradle",
            "plugins {\n" +
            "    id 'java'\n" +
            "    id 'org.jpos.jposapp'\n" +
            "}\n" +
            "version = '1.0.0'\n" +
            "repositories {\n" +
            "    maven { url = 'https://jpos.org/maven' }\n" +
            "    mavenCentral()\n" +
            "}\n" +
            "dependencies {\n" +
            "    implementation 'org.jpos:jpos:3.0.2-SNAPSHOT'\n" +
            "}\n" +
            "jpos {\n" +
            "    extraPaths = ['html', 'docs']\n" +
            "}\n"
        );

        writeFile("devel.properties", "test_token=REPLACED_VALUE\n");

        writeFile("src/main/java/test/App.java",
            "package test;\npublic class App {}\n"
        );

        writeFile("src/dist/bin/q2", "#!/bin/bash\necho @test_token@\n");

        // Extra path: html (text files + binary)
        writeFile("src/dist/html/index.html", "<h1>@test_token@</h1>\n");
        writeFile("src/dist/html/sub/page.html", "<p>@test_token@</p>\n");
        Files.write(new File(projectDir, "src/dist/html/logo.png").toPath(),
            new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        // Extra path: docs
        writeFile("src/dist/docs/README.txt", "Version: @test_token@\n");
    }

    @Test
    void distncIncludesExtraPaths() throws Exception {
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("distnc", "--stacktrace")
            .build();

        Path archive = findArchive("build/distributions", "-nc", ".tar.gz");
        Path extractDir = projectDir.toPath().resolve("build/extract-distnc");
        extractTarGz(archive, extractDir);

        Set<String> files = listFilesRecursive(extractDir);

        assertTrue(files.stream().anyMatch(f -> f.endsWith("bin/q2")),
            "Should contain bin/q2. Files: " + files);
        assertTrue(files.stream().anyMatch(f -> f.endsWith("html/index.html")),
            "Should contain html/index.html. Files: " + files);
        assertTrue(files.stream().anyMatch(f -> f.endsWith("html/sub/page.html")),
            "Should contain html/sub/page.html. Files: " + files);
        assertTrue(files.stream().anyMatch(f -> f.endsWith("html/logo.png")),
            "Should contain html/logo.png (binary). Files: " + files);
        assertTrue(files.stream().anyMatch(f -> f.endsWith("docs/README.txt")),
            "Should contain docs/README.txt. Files: " + files);

        // distnc should not contain cfg/ or deploy/
        assertTrue(files.stream().noneMatch(f -> f.contains("/cfg/")),
            "Should not contain cfg/. Files: " + files);
        assertTrue(files.stream().noneMatch(f -> f.contains("/deploy/")),
            "Should not contain deploy/. Files: " + files);
    }

    @Test
    void distncTokenReplacementInExtraPaths() throws Exception {
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("distnc", "--stacktrace")
            .build();

        Path archive = findArchive("build/distributions", "-nc", ".tar.gz");
        Path extractDir = projectDir.toPath().resolve("build/extract-tokens");
        extractTarGz(archive, extractDir);

        // Find html/index.html in the extracted tree
        Path indexHtml = findFileEndingWith(extractDir, "html/index.html");
        assertNotNull(indexHtml, "html/index.html should exist in archive");
        String indexContent = Files.readString(indexHtml);
        assertTrue(indexContent.contains("REPLACED_VALUE"),
            "Token should be replaced in html/index.html. Got: " + indexContent);
        assertFalse(indexContent.contains("@test_token@"),
            "Raw token should not remain in html/index.html. Got: " + indexContent);

        Path readmeTxt = findFileEndingWith(extractDir, "docs/README.txt");
        assertNotNull(readmeTxt, "docs/README.txt should exist in archive");
        String readmeContent = Files.readString(readmeTxt);
        assertTrue(readmeContent.contains("REPLACED_VALUE"),
            "Token should be replaced in docs/README.txt. Got: " + readmeContent);
    }

    @Test
    void zipncIncludesExtraPaths() throws Exception {
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("zipnc", "--stacktrace")
            .build();

        Path archive = findArchive("build/distributions", "-nc", ".zip");
        Set<String> entries = listZipEntries(archive);

        assertTrue(entries.stream().anyMatch(e -> e.endsWith("html/index.html")),
            "zipnc should contain html/index.html. Entries: " + entries);
        assertTrue(entries.stream().anyMatch(e -> e.endsWith("html/sub/page.html")),
            "zipnc should contain html/sub/page.html. Entries: " + entries);
        assertTrue(entries.stream().anyMatch(e -> e.endsWith("html/logo.png")),
            "zipnc should contain html/logo.png. Entries: " + entries);
        assertTrue(entries.stream().anyMatch(e -> e.endsWith("docs/README.txt")),
            "zipnc should contain docs/README.txt. Entries: " + entries);
    }

    @Test
    void distncWithoutExtraPathsWorksNormally() throws Exception {
        // Override build.gradle without extraPaths
        writeFile("build.gradle",
            "plugins {\n" +
            "    id 'java'\n" +
            "    id 'org.jpos.jposapp'\n" +
            "}\n" +
            "version = '1.0.0'\n" +
            "repositories {\n" +
            "    maven { url = 'https://jpos.org/maven' }\n" +
            "    mavenCentral()\n" +
            "}\n" +
            "dependencies {\n" +
            "    implementation 'org.jpos:jpos:3.0.2-SNAPSHOT'\n" +
            "}\n"
        );

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("distnc", "--stacktrace")
            .build();

        Path archive = findArchive("build/distributions", "-nc", ".tar.gz");
        Path extractDir = projectDir.toPath().resolve("build/extract-noextra");
        extractTarGz(archive, extractDir);

        Set<String> files = listFilesRecursive(extractDir);

        assertTrue(files.stream().anyMatch(f -> f.endsWith("bin/q2")),
            "Should contain bin/q2. Files: " + files);
        assertTrue(files.stream().noneMatch(f -> f.contains("/html/")),
            "Should not contain html/ without extraPaths. Files: " + files);
        assertTrue(files.stream().noneMatch(f -> f.contains("/docs/")),
            "Should not contain docs/ without extraPaths. Files: " + files);
    }

    // --- helpers ---

    private void writeFile(String relativePath, String content) throws IOException {
        File file = new File(projectDir, relativePath);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content);
    }

    private Path findArchive(String dir, String contains, String suffix) throws IOException {
        Path buildDir = projectDir.toPath().resolve(dir);
        assertTrue(Files.isDirectory(buildDir), dir + " should exist");
        return Files.list(buildDir)
            .filter(p -> {
                String name = p.getFileName().toString();
                return name.contains(contains) && name.endsWith(suffix);
            })
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "No archive matching '*" + contains + "*" + suffix + "' in " + dir));
    }

    private void extractTarGz(Path archive, Path destDir) throws Exception {
        Files.createDirectories(destDir);
        ProcessBuilder pb = new ProcessBuilder("tar", "xzf", archive.toString(), "-C", destDir.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exitCode = proc.waitFor();
        assertEquals(0, exitCode, "tar extraction failed: " + output);
    }

    private Set<String> listFilesRecursive(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream
                .filter(Files::isRegularFile)
                .map(p -> dir.relativize(p).toString())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private Path findFileEndingWith(Path dir, String suffix) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> dir.relativize(p).toString().endsWith(suffix))
                .findFirst().orElse(null);
        }
    }

    private Set<String> listZipEntries(Path zipPath) throws IOException {
        Set<String> entries = new LinkedHashSet<>();
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                var entry = en.nextElement();
                if (!entry.isDirectory()) {
                    entries.add(entry.getName());
                }
            }
        }
        return entries;
    }
}
