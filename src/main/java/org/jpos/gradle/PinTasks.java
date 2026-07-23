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

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.tasks.options.Option;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tasks to pin/unpin SNAPSHOT versions in gradle/libs.versions.toml.
 *
 * <p>During development, projects track {@code -SNAPSHOT} versions of jPOS, jPOS-EE
 * and related libraries. When entering a certification or QA phase, those versions
 * are pinned to a specific timestamped snapshot build (e.g.
 * {@code 3.0.2-20260720.022713-26}), as advertised by the repository's
 * version-level {@code maven-metadata.xml}. These tasks automate the round trip:</p>
 *
 * <ul>
 *   <li>{@code pins} — lists every SNAPSHOT (or pinned) version entry and the
 *       latest snapshot build available in the project's Maven repositories.</li>
 *   <li>{@code pin [--ref jpos] [--to 3.0.2-20260720.022713-26]} — pins version
 *       entries (all of them by default) to the latest snapshot build (or an
 *       explicit one given with {@code --to}), recording the original SNAPSHOT
 *       in a trailing {@code # pinned-from} comment.</li>
 *   <li>{@code unpin [--ref jpos]} — restores the original SNAPSHOT version(s).</li>
 * </ul>
 *
 * <p>Edits are line-based so comments and formatting in the catalog are preserved.</p>
 */
public final class PinTasks {
    private static final String MARKER = "pinned-from";
    private static final Pattern ENTRY = Pattern.compile(
        "^(\\s*)([A-Za-z0-9_.-]+)(\\s*=\\s*)(['\"])([^'\"]+)\\4\\s*(?:#\\s*" + MARKER + "\\s+(\\S+))?\\s*$");
    private static final Pattern MODULE = Pattern.compile("module\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GROUP = Pattern.compile("group\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern NAME = Pattern.compile("name\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern TIMESTAMP = Pattern.compile("<timestamp>([^<]+)</timestamp>");
    private static final Pattern BUILD_NUMBER = Pattern.compile("<buildNumber>([^<]+)</buildNumber>");
    private static final Pattern SNAPSHOT_VALUE = Pattern.compile("<value>([^<]+)</value>");

    private PinTasks() {}

    /** A SNAPSHOT-managed entry in the [versions] section. */
    record Entry(int line, String key, String version, String pinnedFrom) {
        boolean pinned() { return pinnedFrom != null; }
    }

    static void register(Project project) {
        String ccReason = "one-shot maintenance task that uses Project APIs at execution time";
        project.getTasks().register("pins", Pins.class, t -> {
            t.setGroup("jPOS");
            t.setDescription("Lists SNAPSHOT/pinned versions in libs.versions.toml and their latest snapshot builds.");
            t.notCompatibleWithConfigurationCache(ccReason);
        });
        project.getTasks().register("pin", Pin.class, t -> {
            t.setGroup("jPOS");
            t.setDescription("Pins SNAPSHOT versions in libs.versions.toml to specific timestamped snapshot builds.");
            t.notCompatibleWithConfigurationCache(ccReason);
        });
        project.getTasks().register("unpin", Unpin.class, t -> {
            t.setGroup("jPOS");
            t.setDescription("Restores pinned versions in libs.versions.toml back to their SNAPSHOTs.");
            t.notCompatibleWithConfigurationCache(ccReason);
        });
    }

    /** Lists SNAPSHOT/pinned entries and the latest snapshot builds available in the project's repositories. */
    @UntrackedTask(because = "reads libs.versions.toml and remote repository metadata")
    public static abstract class Pins extends DefaultTask {

        /** Default constructor used by Gradle to instantiate the task. */
        public Pins() {}

        /** Lists each SNAPSHOT/pinned entry with its state and latest available snapshot build. */
        @TaskAction
        public void run() {
            File toml = tomlFile(getProject());
            List<String> lines = readLines(toml);
            List<Entry> entries = entries(lines);
            if (entries.isEmpty()) {
                getLogger().lifecycle("No SNAPSHOT versions found in {}", toml);
                return;
            }
            List<String> repos = repoUrls(getProject());
            for (Entry e : entries) {
                String module = moduleFor(lines, e.key());
                String snapshot = e.pinned() ? e.pinnedFrom() : e.version();
                String latest = module == null ? null : latestBuild(module, snapshot, repos);
                String state = e.pinned() ? "pinned (was " + e.pinnedFrom() + ")" : "snapshot";
                String avail = latest == null ? "" : "  latest build: " + latest;
                getLogger().lifecycle(String.format("%-24s %-28s %-32s%s", e.key(), e.version(), state, avail));
            }
        }
    }

    /**
     * Pins version entries to specific timestamped snapshot builds, keeping the
     * original SNAPSHOT in a marker comment. With no options, pins every
     * SNAPSHOT-managed entry to its latest build.
     */
    @UntrackedTask(because = "edits libs.versions.toml in place")
    public static abstract class Pin extends DefaultTask {
        private String ref;
        private String version;

        /** Default constructor used by Gradle to instantiate the task. */
        public Pin() {}

        /**
         * Sets the version key to pin.
         *
         * @param ref version key in [versions], as shown by the 'pins' task (default: all)
         */
        @Option(option = "ref", description = "Version key in [versions] to pin (default: all SNAPSHOT-managed keys)")
        public void setRef(String ref) { this.ref = ref; }

        /**
         * Sets an explicit timestamped snapshot build to pin to.
         * The option is named {@code --to} because {@code --version} is Gradle's own flag.
         *
         * @param version timestamped snapshot build (default: latest from repository metadata)
         */
        @Option(option = "to", description = "Timestamped snapshot build to pin to (default: latest from repository metadata)")
        public void setTo(String version) { this.version = version; }

        /**
         * Pins the selected entries, recording the original SNAPSHOT in a marker comment.
         *
         * @throws IOException if libs.versions.toml cannot be rewritten
         */
        @TaskAction
        public void run() throws IOException {
            File toml = tomlFile(getProject());
            List<String> lines = new ArrayList<>(readLines(toml));
            List<Entry> entries = entries(lines);
            if (version != null && ref == null)
                throw new GradleException("--to requires --ref");
            List<Entry> targets;
            if (ref == null) {
                targets = entries;
                if (targets.isEmpty()) {
                    getLogger().lifecycle("No SNAPSHOT versions found in {}", toml);
                    return;
                }
            } else {
                targets = List.of(entries.stream().filter(x -> x.key().equals(ref)).findFirst()
                    .orElseThrow(() -> new GradleException("'" + ref + "' is not a SNAPSHOT-managed version. Candidates: "
                        + entries.stream().map(Entry::key).toList())));
            }
            List<String> repos = repoUrls(getProject());
            boolean changed = false;
            for (Entry e : targets) {
                String snapshot = e.pinned() ? e.pinnedFrom() : e.version();
                String to = version;
                if (to == null) {
                    String module = moduleFor(lines, e.key());
                    to = module == null ? null : latestBuild(module, snapshot, repos);
                    if (to == null) {
                        String msg = "No snapshot build found for '" + e.key() + "' (" + snapshot
                            + ") in the declared repositories";
                        if (ref != null)
                            throw new GradleException(msg);
                        getLogger().lifecycle("{}, skipping", msg);
                        continue;
                    }
                }
                Matcher m = ENTRY.matcher(lines.get(e.line()));
                if (!m.matches())
                    throw new GradleException("Cannot parse line " + (e.line() + 1) + " of " + toml);
                String q = m.group(4);
                lines.set(e.line(), m.group(1) + e.key() + m.group(3) + q + to + q + " # " + MARKER + " " + snapshot);
                getLogger().lifecycle("Pinned {} = {} ({} {})", e.key(), to, MARKER, snapshot);
                changed = true;
            }
            if (changed)
                writeLines(toml, lines);
        }
    }

    /** Restores pinned entries back to their original SNAPSHOT versions. */
    @UntrackedTask(because = "edits libs.versions.toml in place")
    public static abstract class Unpin extends DefaultTask {
        private String ref;

        /** Default constructor used by Gradle to instantiate the task. */
        public Unpin() {}

        /**
         * Sets the version key to unpin.
         *
         * @param ref version key to unpin (default: all pinned entries)
         */
        @Option(option = "ref", description = "Version key to unpin (default: all pinned entries)")
        public void setRef(String ref) { this.ref = ref; }

        /**
         * Restores the original SNAPSHOT version(s) recorded by the 'pin' task.
         *
         * @throws IOException if libs.versions.toml cannot be rewritten
         */
        @TaskAction
        public void run() throws IOException {
            File toml = tomlFile(getProject());
            List<String> lines = new ArrayList<>(readLines(toml));
            List<Entry> pinned = entries(lines).stream()
                .filter(Entry::pinned)
                .filter(e -> ref == null || e.key().equals(ref))
                .toList();
            if (pinned.isEmpty()) {
                getLogger().lifecycle(ref == null
                    ? "Nothing is pinned in " + toml
                    : "'" + ref + "' is not pinned in " + toml);
                return;
            }
            for (Entry e : pinned) {
                Matcher m = ENTRY.matcher(lines.get(e.line()));
                if (!m.matches())
                    throw new GradleException("Cannot parse line " + (e.line() + 1) + " of " + toml);
                String q = m.group(4);
                lines.set(e.line(), m.group(1) + e.key() + m.group(3) + q + e.pinnedFrom() + q);
                getLogger().lifecycle("Unpinned {} = {}", e.key(), e.pinnedFrom());
            }
            writeLines(toml, lines);
        }
    }

    // --- helpers ---

    static File tomlFile(Project project) {
        return new File(project.getRootProject().getProjectDir(), "gradle/libs.versions.toml");
    }

    static List<String> readLines(File toml) {
        if (!toml.isFile())
            throw new GradleException("No version catalog found at " + toml);
        try {
            return Files.readAllLines(toml.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void writeLines(File toml, List<String> lines) throws IOException {
        Files.write(toml.toPath(), lines);
    }

    /** SNAPSHOT or pinned entries in the [versions] section. */
    static List<Entry> entries(List<String> lines) {
        List<Entry> out = new ArrayList<>();
        boolean inVersions = false;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("[")) {
                inVersions = trimmed.equals("[versions]");
                continue;
            }
            if (!inVersions)
                continue;
            Matcher m = ENTRY.matcher(lines.get(i));
            if (!m.matches())
                continue;
            String version = m.group(5);
            String pinnedFrom = m.group(6);
            if (version.endsWith("-SNAPSHOT") || pinnedFrom != null)
                out.add(new Entry(i, m.group(2), version, pinnedFrom));
        }
        return out;
    }

    /** First module ("group:name") in [libraries] whose version.ref points at the given key. */
    static String moduleFor(List<String> lines, String ref) {
        Pattern refPat = Pattern.compile("version\\.ref\\s*=\\s*['\"]" + Pattern.quote(ref) + "['\"]");
        boolean inLibraries = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                inLibraries = trimmed.equals("[libraries]");
                continue;
            }
            if (!inLibraries || !refPat.matcher(line).find())
                continue;
            Matcher m = MODULE.matcher(line);
            if (m.find())
                return m.group(1);
            Matcher g = GROUP.matcher(line);
            Matcher n = NAME.matcher(line);
            if (g.find() && n.find())
                return g.group(1) + ":" + n.group(1);
        }
        return null;
    }

    static List<String> repoUrls(Project project) {
        return project.getRepositories().withType(MavenArtifactRepository.class).stream()
            .map(r -> r.getUrl().toString())
            .toList();
    }

    /**
     * Latest timestamped snapshot build (e.g. {@code 3.0.2-20260720.022713-26}) for the
     * given SNAPSHOT version, from the first repository that serves the version-level
     * maven-metadata.xml. Returns null if none is found.
     */
    static String latestBuild(String module, String snapshotVersion, List<String> repoUrls) {
        String[] ga = module.split(":");
        if (ga.length != 2 || !snapshotVersion.endsWith("-SNAPSHOT"))
            return null;
        String base = snapshotVersion.substring(0, snapshotVersion.length() - "-SNAPSHOT".length());
        String path = ga[0].replace('.', '/') + "/" + ga[1] + "/" + snapshotVersion + "/maven-metadata.xml";
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        for (String repo : repoUrls) {
            String url = (repo.endsWith("/") ? repo : repo + "/") + path;
            try {
                HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200)
                    continue;
                Matcher ts = TIMESTAMP.matcher(resp.body());
                Matcher bn = BUILD_NUMBER.matcher(resp.body());
                if (ts.find() && bn.find())
                    return base + "-" + ts.group(1) + "-" + bn.group(1);
                // non-standard metadata: fall back to the last unique <value> entry
                String last = null;
                Matcher v = SNAPSHOT_VALUE.matcher(resp.body());
                while (v.find())
                    if (!v.group(1).endsWith("-SNAPSHOT"))
                        last = v.group(1);
                if (last != null)
                    return last;
            } catch (Exception ignored) {
                // unreachable repo, unsupported scheme (file:), etc. -- try the next one
            }
        }
        return null;
    }
}
