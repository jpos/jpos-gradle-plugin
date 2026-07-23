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
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.tasks.options.Option;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tasks to vendor/unvendor a dependency into the consuming build.
 *
 * <p>During certification or QA, a jPOS user sometimes needs to patch a dependency
 * locally. The {@code vendor} task extracts a library's {@code -sources.jar} into a
 * {@code vendor/<name>/} sub-project and wires it into the build through Gradle
 * dependency substitution, so every reference to that module — direct and transitive —
 * resolves to the local project. {@code unvendor} reverses it.</p>
 *
 * <p>The consuming project's {@code build.gradle} and {@code gradle/libs.versions.toml}
 * are never modified: the version-catalog entry stays as-is and
 * {@code resolutionStrategy.dependencySubstitution} performs the redirect.</p>
 *
 * <ul>
 *   <li>{@code vendor --lib <key>} — extracts the library referenced by the catalog
 *       {@code <key>} into {@code vendor/<name>/}, generates its build script and
 *       registers it in {@code settings.gradle}.</li>
 *   <li>{@code unvendor [--lib <key>]} — removes a vendored module (all of them by
 *       default), restoring {@code settings.gradle}.</li>
 * </ul>
 */
public final class VendorTasks {
    private static final Pattern MODULE = Pattern.compile("module\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GROUP = Pattern.compile("group\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern NAME = Pattern.compile("name\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern VERSION_REF = Pattern.compile("version\\.ref\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern VERSION = Pattern.compile("(?<![.\\w])version\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern DEPENDENCY = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);

    private VendorTasks() {}

    static void register(Project project) {
        project.getTasks().register("vendor", Vendor.class, t -> {
            t.setGroup("jPOS");
            t.setDescription("Extracts a dependency's sources into vendor/<name>/ and redirects it there via dependency substitution.");
        });
        project.getTasks().register("unvendor", Unvendor.class, t -> {
            t.setGroup("jPOS");
            t.setDescription("Removes a vendored module (all by default) and restores settings.gradle.");
        });
    }

    /**
     * Applies dependency substitutions for every vendored module found under
     * {@code <rootProjectDir>/vendor/*}, redirecting the module coordinate recorded in
     * each {@code .vendored} marker to its local {@code :vendor:<name>} project.
     *
     * <p>If a marker exists but its {@code :vendor:<name>} project is not part of the
     * build (i.e. the {@code include} line is missing from {@code settings.gradle}), the
     * substitution is skipped with a warning rather than failing the build.</p>
     *
     * @param project the project whose configurations should redirect to vendored modules
     */
    static void applySubstitutions(Project project) {
        File vendorRoot = new File(project.getRootProject().getProjectDir(), "vendor");
        File[] dirs = vendorRoot.listFiles(File::isDirectory);
        if (dirs == null)
            return;
        for (File dir : dirs) {
            File marker = new File(dir, ".vendored");
            if (!marker.isFile())
                continue;
            String[] ga = readMarker(marker);
            if (ga == null)
                continue;
            String dirName = dir.getName();
            if (project.findProject(":vendor:" + dirName) == null) {
                project.getLogger().warn(
                    "Vendored module marker vendor/{}/.vendored found but ':vendor:{}' is not included in the build; skipping substitution.",
                    dirName, dirName);
                continue;
            }
            String group = ga[0];
            String name = ga[1];
            project.getConfigurations().configureEach(cfg ->
                cfg.getResolutionStrategy().dependencySubstitution(ds ->
                    ds.substitute(ds.module(group + ":" + name)).using(ds.project(":vendor:" + dirName))));
        }
    }

    /** Extracts a catalog library into a local {@code vendor/<name>} sub-project. */
    @UntrackedTask(because = "extracts sources and edits settings.gradle outside build outputs")
    public static abstract class Vendor extends DefaultTask {
        private String lib;

        /** Default constructor used by Gradle to instantiate the task. */
        public Vendor() {}

        /**
         * Sets the catalog library key to vendor.
         *
         * @param lib a library key from the root project's gradle/libs.versions.toml [libraries] section
         */
        @Option(option = "lib", description = "Library key in libs.versions.toml [libraries] to vendor")
        public void setLib(String lib) { this.lib = lib; }

        /**
         * Extracts the referenced library's sources into {@code vendor/<name>/}, generates
         * its build script and marker, and registers it in {@code settings.gradle}.
         *
         * @throws IOException if files cannot be written
         */
        @TaskAction
        public void run() throws IOException {
            if (lib == null)
                throw new GradleException("--lib <key> is required. Available keys: " + libraryKeys(getProject()));
            Project project = getProject();
            File toml = tomlFile(project);
            Lib module = catalogLibrary(toml, lib);
            if (module == null)
                throw new GradleException("'" + lib + "' is not a library in " + toml
                    + ". Available keys: " + libraryKeys(project));
            if (module.version() == null)
                throw new GradleException("Could not determine a version for '" + lib
                    + "' from " + toml + " (no inline version and no resolvable version.ref)");

            String coord = module.group() + ":" + module.name() + ":" + module.version();
            String name = module.name();
            File vendorRoot = new File(project.getRootProject().getProjectDir(), "vendor");
            File dir = new File(vendorRoot, name);
            if (dir.exists()) {
                // in a multi-project build this task runs once per project applying the
                // plugin; later runs find the directory the first one created and must
                // be a no-op, not an error
                String[] ga = readMarker(new File(dir, ".vendored"));
                if (ga != null && coord.equals(String.join(":", ga))) {
                    getLogger().lifecycle("vendor/{} already contains {}, nothing to do.", name, coord);
                    return;
                }
                throw new GradleException("vendor/" + name + " already exists"
                    + (ga == null ? " (no .vendored marker)" : " with different coordinates " + String.join(":", ga))
                    + ". Run 'unvendor --lib " + lib + "' first (commit your changes) or remove it manually.");
            }

            File sourcesJar = resolveArtifact(project, coord + ":sources", "jar", "sources jar");
            File pom = resolveArtifact(project, coord + "@pom", "pom", "pom");

            extractSources(sourcesJar, dir);

            List<Dep> deps = parsePomDependencies(Files.readString(pom.toPath()));
            Map<String, String> vendored = vendoredModules(vendorRoot, name);
            Files.writeString(new File(dir, "build.gradle").toPath(),
                buildScript(project, module, deps, vendored));
            Files.writeString(new File(dir, ".vendored").toPath(), coord + "\n" + digest(dir) + "\n");

            addInclude(project, name);

            getLogger().lifecycle("Vendored {} into vendor/{} ({} source dependency(ies)).",
                coord, name, deps.size());
            getLogger().lifecycle("References to {}:{} now redirect to :vendor:{}. Re-sync your IDE / next Gradle "
                + "invocation picks up the substitution.", module.group(), module.name(), name);
        }
    }

    /** Removes vendored modules and restores {@code settings.gradle}. */
    @UntrackedTask(because = "deletes vendored sources and edits settings.gradle outside build outputs")
    public static abstract class Unvendor extends DefaultTask {
        private String lib;

        /** Default constructor used by Gradle to instantiate the task. */
        public Unvendor() {}

        /**
         * Sets the vendored module to remove.
         *
         * @param lib catalog library key or vendor/&lt;name&gt; directory name (default: all vendored modules)
         */
        @Option(option = "lib", description = "Library key (or vendor/<name>) to unvendor (default: all)")
        public void setLib(String lib) { this.lib = lib; }

        /**
         * Removes the selected vendored module(s), restoring {@code settings.gradle}.
         *
         * @throws IOException if files cannot be rewritten or deleted
         */
        @TaskAction
        public void run() throws IOException {
            Project project = getProject();
            File vendorRoot = new File(project.getRootProject().getProjectDir(), "vendor");
            List<String> names = new ArrayList<>();
            if (lib == null) {
                File[] dirs = vendorRoot.listFiles(File::isDirectory);
                if (dirs != null)
                    for (File d : dirs)
                        names.add(d.getName());
            } else {
                names.add(resolveName(project, vendorRoot, lib));
            }
            if (names.isEmpty()) {
                getLogger().lifecycle("Nothing is vendored under {}", vendorRoot);
                return;
            }
            for (String name : names) {
                File dir = new File(vendorRoot, name);
                if (!dir.isDirectory()) {
                    getLogger().lifecycle("vendor/{} does not exist, skipping", name);
                    continue;
                }
                guardUnmodified(dir);
                removeInclude(project, name);
                deleteRecursively(dir.toPath());
                getLogger().lifecycle("Removed vendor/{}", name);
            }
            File[] remaining = vendorRoot.listFiles();
            if (remaining != null && remaining.length == 0)
                Files.deleteIfExists(vendorRoot.toPath());
        }
    }

    // --- catalog helpers ---

    /** A resolved library coordinate from the version catalog. */
    record Lib(String group, String name, String version) {}

    /** A dependency parsed from a POM, mapped to a Gradle configuration ("api"/"implementation"). */
    record Dep(String group, String name, String version, String configuration) {}

    static File tomlFile(Project project) {
        return new File(project.getRootProject().getProjectDir(), "gradle/libs.versions.toml");
    }

    static List<String> readLines(File file) {
        if (!file.isFile())
            throw new GradleException("File not found: " + file);
        try {
            return Files.readAllLines(file.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** All library keys declared in the [libraries] section. */
    static List<String> libraryKeys(Project project) {
        List<String> keys = new ArrayList<>();
        boolean inLibraries = false;
        for (String line : readLines(tomlFile(project))) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                inLibraries = trimmed.equals("[libraries]");
                continue;
            }
            if (!inLibraries || trimmed.isEmpty() || trimmed.startsWith("#"))
                continue;
            int eq = trimmed.indexOf('=');
            if (eq > 0)
                keys.add(trimmed.substring(0, eq).trim());
        }
        return keys;
    }

    /** Resolves a [libraries] entry to its group, name and version (or null if the key is absent). */
    static Lib catalogLibrary(File toml, String key) {
        List<String> lines = readLines(toml);
        Pattern keyPat = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*=");
        boolean inLibraries = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                inLibraries = trimmed.equals("[libraries]");
                continue;
            }
            if (!inLibraries || !keyPat.matcher(line).find())
                continue;
            String group;
            String name;
            Matcher mod = MODULE.matcher(line);
            if (mod.find()) {
                String[] gn = mod.group(1).split(":");
                if (gn.length != 2)
                    return null;
                group = gn[0];
                name = gn[1];
            } else {
                Matcher g = GROUP.matcher(line);
                Matcher n = NAME.matcher(line);
                if (!g.find() || !n.find())
                    return null;
                group = g.group(1);
                name = n.group(1);
            }
            return new Lib(group, name, versionOf(lines, line));
        }
        return null;
    }

    /** Resolves the version for a [libraries] line, following version.ref into [versions] when present. */
    static String versionOf(List<String> lines, String libraryLine) {
        Matcher ref = VERSION_REF.matcher(libraryLine);
        if (ref.find())
            return versionForRef(lines, ref.group(1));
        Matcher inline = VERSION.matcher(libraryLine);
        return inline.find() ? inline.group(1) : null;
    }

    /** The value of a [versions] key, or null. */
    static String versionForRef(List<String> lines, String ref) {
        Pattern pat = Pattern.compile("^\\s*" + Pattern.quote(ref) + "\\s*=\\s*['\"]([^'\"]+)['\"]");
        boolean inVersions = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                inVersions = trimmed.equals("[versions]");
                continue;
            }
            if (!inVersions)
                continue;
            Matcher m = pat.matcher(line);
            if (m.find())
                return m.group(1);
        }
        return null;
    }

    // --- artifact resolution ---

    static File resolveArtifact(Project project, String notation, String extension, String what) {
        Set<File> files;
        try {
            Configuration cfg = project.getConfigurations().detachedConfiguration(
                project.getDependencies().create(notation));
            cfg.setTransitive(false);
            files = cfg.resolve();
        } catch (Exception e) {
            throw new GradleException("Could not resolve " + what + " for " + notation
                + " from the declared repositories: " + e.getMessage(), e);
        }
        return files.stream()
            .filter(f -> f.getName().endsWith("." + extension))
            .findFirst()
            .orElseThrow(() -> new GradleException("Could not resolve " + what + " for " + notation
                + " from the declared repositories"));
    }

    // --- sources extraction ---

    static void extractSources(File sourcesJar, File dir) throws IOException {
        File javaDir = new File(dir, "src/main/java");
        File resourcesDir = new File(dir, "src/main/resources");
        try (JarFile jar = new JarFile(sourcesJar)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory())
                    continue;
                String entryName = entry.getName();
                if (entryName.startsWith("META-INF/"))
                    continue;
                File base = entryName.endsWith(".java") ? javaDir : resourcesDir;
                File target = new File(base, entryName);
                Files.createDirectories(target.getParentFile().toPath());
                try (InputStream in = jar.getInputStream(entry)) {
                    Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // --- pom parsing ---

    static List<Dep> parsePomDependencies(String pom) {
        List<Dep> deps = new ArrayList<>();
        // ponytail: plain <dependency> scan; a pom with <dependencyManagement> would also match here,
        // upgrade to strip that block if a vendored pom ever needs it.
        Matcher blocks = DEPENDENCY.matcher(pom);
        while (blocks.find()) {
            String block = blocks.group(1);
            String scope = tag(block, "scope");
            if ("test".equals(scope) || "provided".equals(scope) || "system".equals(scope))
                continue;
            if ("true".equals(tag(block, "optional")))
                continue;
            String group = tag(block, "groupId");
            String name = tag(block, "artifactId");
            if (group == null || name == null)
                continue;
            String configuration = "compile".equals(scope) ? "api" : "implementation";
            deps.add(new Dep(group, name, tag(block, "version"), configuration));
        }
        return deps;
    }

    private static String tag(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">\\s*([^<]+?)\\s*</" + tag + ">").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    // --- build script generation ---

    static String buildScript(Project project, Lib module, List<Dep> deps, Map<String, String> vendored) {
        StringBuilder sb = new StringBuilder();
        sb.append("plugins {\n    id 'java-library'\n}\n\n");
        sb.append("group = '").append(module.group()).append("'\n");
        sb.append("version = '").append(module.version()).append("'\n\n");

        sb.append("repositories {\n");
        for (MavenArtifactRepository repo : project.getRepositories().withType(MavenArtifactRepository.class)) {
            String url = repo.getUrl().toString();
            String noSlash = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            if ("https://repo.maven.apache.org/maven2".equals(noSlash)) {
                sb.append("    mavenCentral()\n");
                continue;
            }
            URI uri = URI.create(url);
            if ("file".equals(uri.getScheme()) && noSlash.endsWith("/.m2/repository")) {
                sb.append("    mavenLocal()\n");
                continue;
            }
            sb.append("    maven {\n        url = '").append(url).append("'\n");
            if ("http".equals(uri.getScheme()))
                sb.append("        allowInsecureProtocol = true\n");
            sb.append("    }\n");
        }
        sb.append("}\n\n");

        sb.append("dependencies {\n");
        for (Dep d : deps) {
            String ga = d.group() + ":" + d.name();
            String local = vendored.get(ga);
            if (local != null) {
                sb.append("    api project(':vendor:").append(local).append("')\n");
            } else if (d.version() != null) {
                sb.append("    ").append(d.configuration()).append(" '")
                    .append(ga).append(":").append(d.version()).append("'\n");
            } else {
                sb.append("    ").append(d.configuration()).append(" '").append(ga).append("'\n");
                project.getLogger().lifecycle("Dependency {} has no version in the POM; the generated "
                    + "vendor/{} build.gradle may need manual attention.", ga, module.name());
            }
        }
        sb.append("}\n\n");

        sb.append(SIBLING_SUBSTITUTION);
        return sb.toString();
    }

    private static final String SIBLING_SUBSTITUTION =
        "// redirect other vendored modules to their local projects\n" +
        "def vendorRoot = new File(rootDir, 'vendor')\n" +
        "configurations.all {\n" +
        "    resolutionStrategy.dependencySubstitution {\n" +
        "        (vendorRoot.listFiles() ?: new File[0]).each { d ->\n" +
        "            def marker = new File(d, '.vendored')\n" +
        "            if (marker.exists() && d.name != project.name) {\n" +
        "                def ga = marker.readLines()[0].tokenize(':')\n" +
        "                substitute module(\"${ga[0]}:${ga[1]}\") using project(\":vendor:${d.name}\")\n" +
        "            }\n" +
        "        }\n" +
        "    }\n" +
        "}\n";

    /** Map of "group:name" to vendored directory name, for every marker under vendorRoot except {@code except}. */
    static Map<String, String> vendoredModules(File vendorRoot, String except) {
        Map<String, String> map = new LinkedHashMap<>();
        File[] dirs = vendorRoot.listFiles(File::isDirectory);
        if (dirs == null)
            return map;
        for (File dir : dirs) {
            if (dir.getName().equals(except))
                continue;
            String[] ga = readMarker(new File(dir, ".vendored"));
            if (ga != null)
                map.put(ga[0] + ":" + ga[1], dir.getName());
        }
        return map;
    }

    /** Reads a {@code .vendored} marker's first line into {group, name(, version)}, or null if unreadable. */
    static String[] readMarker(File marker) {
        if (!marker.isFile())
            return null;
        try {
            List<String> lines = Files.readAllLines(marker.toPath());
            if (lines.isEmpty())
                return null;
            String[] parts = lines.get(0).trim().split(":");
            return parts.length >= 2 ? parts : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The content digest recorded on the marker's second line, or null if absent. */
    static String markerDigest(File marker) {
        try {
            List<String> lines = Files.readAllLines(marker.toPath());
            return lines.size() > 1 && !lines.get(1).isBlank() ? lines.get(1).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * SHA-256 over the relative paths and contents of every regular file under {@code dir},
     * except the {@code .vendored} marker itself (which stores this digest).
     */
    static String digest(File dir) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Path root = dir.toPath();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                .filter(p -> !p.getFileName().toString().equals(".vendored"))
                .sorted()
                .toList();
            for (Path p : files) {
                md.update(root.relativize(p).toString().replace(File.separatorChar, '/')
                    .getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(Files.readAllBytes(p));
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    // --- settings.gradle editing ---

    static File settingsFile(Project project) {
        File root = project.getRootProject().getProjectDir();
        File groovy = new File(root, "settings.gradle");
        if (groovy.isFile())
            return groovy;
        if (new File(root, "settings.gradle.kts").isFile())
            throw new GradleException("Only settings.gradle (Groovy DSL) is supported; found settings.gradle.kts");
        throw new GradleException("No settings.gradle found in " + root);
    }

    static void addInclude(Project project, String name) throws IOException {
        File settings = settingsFile(project);
        List<String> lines = new ArrayList<>(readLines(settings));
        Pattern pat = includePattern(name);
        for (String line : lines)
            if (pat.matcher(line).matches())
                return;
        lines.add("include ':vendor:" + name + "'");
        Files.write(settings.toPath(), lines);
    }

    static void removeInclude(Project project, String name) throws IOException {
        File settings = settingsFile(project);
        List<String> lines = readLines(settings);
        Pattern pat = includePattern(name);
        List<String> kept = new ArrayList<>(lines.size());
        for (String line : lines)
            if (!pat.matcher(line).matches())
                kept.add(line);
        if (kept.size() != lines.size())
            Files.write(settings.toPath(), kept);
    }

    private static Pattern includePattern(String name) {
        return Pattern.compile("^\\s*include\\s+['\"]:vendor:" + Pattern.quote(name) + "['\"]\\s*$");
    }

    /** Resolves an unvendor --lib value to a vendored directory name, via the catalog or as a raw dir name. */
    static String resolveName(Project project, File vendorRoot, String lib) {
        Lib module = catalogLibrary(tomlFile(project), lib);
        if (module != null)
            return module.name();
        if (new File(vendorRoot, lib).isDirectory())
            return lib;
        throw new GradleException("'" + lib + "' is not a known library key and vendor/" + lib
            + " does not exist. Vendored: " + vendoredModules(vendorRoot, null).values());
    }

    // --- data-loss guard ---

    /**
     * Fails if the vendored directory's content no longer matches the digest recorded
     * at vendor time, so local patches are not silently discarded. Markers without a
     * digest (or unreadable ones) are not verified.
     */
    static void guardUnmodified(File dir) throws IOException {
        String recorded = markerDigest(new File(dir, ".vendored"));
        if (recorded != null && !recorded.equals(digest(dir)))
            throw new GradleException("vendor/" + dir.getName() + " has been modified since it was vendored. "
                + "Unvendoring would discard those changes; remove the directory manually if that is really "
                + "what you want.");
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path))
            return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException uioe) {
            throw uioe.getCause();
        }
    }
}
