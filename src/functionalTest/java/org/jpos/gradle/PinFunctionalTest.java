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

import com.sun.net.httpserver.HttpServer;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PinFunctionalTest {

    private static final String TOML =
        "[versions]\n" +
        "jpos = \"3.0.2-SNAPSHOT\"\n" +
        "jposee = '3.0.2-SNAPSHOT'\n" +
        "# a comment that must survive edits\n" +
        "commonsLang3 = \"3.20.0\"\n" +
        "\n" +
        "[libraries]\n" +
        "jpos = { module = \"org.jpos:jpos\", version.ref = \"jpos\" }\n" +
        "jposee_core = { module = \"org.jpos.ee:jposee-core\", version.ref = \"jposee\" }\n" +
        "commonsLang3 = { module = \"org.apache.commons:commons-lang3\", version.ref = \"commonsLang3\" }\n";

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
            "version = '1.0.0'\n"
        );
        writeFile("gradle/libs.versions.toml", TOML);
    }

    @Test
    void pinAllToLatestSnapshotBuilds() throws Exception {
        HttpServer server = metadataServer(Map.of(
            "/org/jpos/jpos/3.0.2-SNAPSHOT/maven-metadata.xml",
            metadataXml("3.0.2", "20260718.120000", "3"),
            "/org/jpos/ee/jposee-core/3.0.2-SNAPSHOT/maven-metadata.xml",
            metadataXml("3.0.2", "20260717.090000", "7")
        ));
        try {
            writeBuildGradleWithRepo(server.getAddress().getPort());

            run("pin");

            String pinned = readToml();
            assertTrue(pinned.contains("jpos = \"3.0.2-20260718.120000-3\" # pinned-from 3.0.2-SNAPSHOT\n"),
                "jpos should be pinned to the latest build. Got:\n" + pinned);
            assertTrue(pinned.contains("jposee = '3.0.2-20260717.090000-7' # pinned-from 3.0.2-SNAPSHOT\n"),
                "jposee should be pinned to the latest build. Got:\n" + pinned);

            run("unpin");
            assertEquals(TOML, readToml(), "unpin should restore the original catalog");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pinSingleRefToLatestSnapshotBuild() throws Exception {
        HttpServer server = metadataServer(Map.of(
            "/org/jpos/jpos/3.0.2-SNAPSHOT/maven-metadata.xml",
            metadataXml("3.0.2", "20260718.120000", "3")
        ));
        try {
            writeBuildGradleWithRepo(server.getAddress().getPort());

            run("pin", "--ref", "jpos");

            String pinned = readToml();
            assertTrue(pinned.contains("jpos = \"3.0.2-20260718.120000-3\" # pinned-from 3.0.2-SNAPSHOT\n"),
                "jpos should be pinned to the latest build. Got:\n" + pinned);
            assertTrue(pinned.contains("jposee = '3.0.2-SNAPSHOT'"),
                "jposee should be untouched. Got:\n" + pinned);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pinAndUnpinRoundTripWithExplicitBuild() throws Exception {
        run("pin", "--ref", "jpos", "--to", "3.0.2-20260718.120000-1");

        String pinned = readToml();
        assertTrue(pinned.contains("jpos = \"3.0.2-20260718.120000-1\" # pinned-from 3.0.2-SNAPSHOT\n"),
            "jpos should be pinned with marker. Got:\n" + pinned);
        assertTrue(pinned.contains("jposee = '3.0.2-SNAPSHOT'"),
            "jposee should be untouched. Got:\n" + pinned);
        assertTrue(pinned.contains("# a comment that must survive edits"),
            "Comments should be preserved. Got:\n" + pinned);

        run("unpin");

        assertEquals(TOML, readToml(), "unpin should restore the original catalog");
    }

    @Test
    void repinKeepsOriginalSnapshot() throws Exception {
        run("pin", "--ref", "jpos", "--to", "3.0.2-20260701.100000-1");
        run("pin", "--ref", "jpos", "--to", "3.0.2-20260718.120000-2");

        assertTrue(readToml().contains("jpos = \"3.0.2-20260718.120000-2\" # pinned-from 3.0.2-SNAPSHOT\n"),
            "Re-pinning should keep the original SNAPSHOT. Got:\n" + readToml());

        run("unpin", "--ref", "jpos");
        assertEquals(TOML, readToml());
    }

    @Test
    void pinPreservesQuoteStyle() throws Exception {
        run("pin", "--ref", "jposee", "--to", "3.0.2-20260718.120000-1");
        assertTrue(readToml().contains("jposee = '3.0.2-20260718.120000-1' # pinned-from 3.0.2-SNAPSHOT\n"),
            "Single quotes should be preserved. Got:\n" + readToml());
    }

    @Test
    void pinRejectsNonSnapshotRef() {
        BuildResult result = runAndFail("pin", "--ref", "commonsLang3", "--to", "3.21.0");
        assertTrue(result.getOutput().contains("not a SNAPSHOT-managed version"),
            "Should reject non-SNAPSHOT ref. Output: " + result.getOutput());
        assertTrue(result.getOutput().contains("jpos"),
            "Should list candidates. Output: " + result.getOutput());
    }

    @Test
    void pinRejectsToWithoutRef() {
        BuildResult result = runAndFail("pin", "--to", "3.0.2-20260718.120000-1");
        assertTrue(result.getOutput().contains("--to requires --ref"),
            "Should reject --to without --ref. Output: " + result.getOutput());
    }

    @Test
    void pinsListsSnapshotEntries() {
        BuildResult result = run("pins");
        assertTrue(result.getOutput().contains("jpos"), result.getOutput());
        assertTrue(result.getOutput().contains("jposee"), result.getOutput());
        assertFalse(result.getOutput().contains("commonsLang3  "),
            "Non-SNAPSHOT versions should not be listed. Output: " + result.getOutput());
    }

    // --- helpers ---

    private HttpServer metadataServer(Map<String, String> pathToBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = pathToBody.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String metadataXml(String base, String timestamp, String buildNumber) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<metadata modelVersion=\"1.1.0\">\n" +
            "  <versioning>\n" +
            "    <snapshot>\n" +
            "      <timestamp>" + timestamp + "</timestamp>\n" +
            "      <buildNumber>" + buildNumber + "</buildNumber>\n" +
            "    </snapshot>\n" +
            "    <snapshotVersions>\n" +
            "      <snapshotVersion>\n" +
            "        <extension>jar</extension>\n" +
            "        <value>" + base + "-" + timestamp + "-" + buildNumber + "</value>\n" +
            "      </snapshotVersion>\n" +
            "    </snapshotVersions>\n" +
            "  </versioning>\n" +
            "</metadata>\n";
    }

    private void writeBuildGradleWithRepo(int port) throws IOException {
        writeFile("build.gradle",
            "plugins {\n" +
            "    id 'java'\n" +
            "    id 'org.jpos.jposapp'\n" +
            "}\n" +
            "version = '1.0.0'\n" +
            "repositories {\n" +
            "    maven {\n" +
            "        url = 'http://127.0.0.1:" + port + "/'\n" +
            "        allowInsecureProtocol = true\n" +
            "    }\n" +
            "}\n"
        );
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

    private String readToml() throws IOException {
        return Files.readString(new File(projectDir, "gradle/libs.versions.toml").toPath());
    }

    private void writeFile(String relativePath, String content) throws IOException {
        File file = new File(projectDir, relativePath);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content);
    }
}
