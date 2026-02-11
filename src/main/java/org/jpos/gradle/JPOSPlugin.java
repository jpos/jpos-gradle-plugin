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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.CopySpec;
import org.gradle.api.java.archives.Attributes;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.*;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.SourceSetContainer;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The JPOSPlugin is a Gradle plugin for building, installing, and running jPOS-based applications.
 * It sets up various tasks such as creating distribution archives, installing the app, and managing build information.
 * This class also defines custom tasks related to the jPOS project lifecycle, including handling distribution archives and test reports.
 */
public class JPOSPlugin implements Plugin<Project> {
    private static final Logger LOGGER = Logging.getLogger(JPOSPlugin.class);
    private static final String GROUP_NAME = "jPOS";
    private static final String SERVICES_PATH = "META-INF/services";

    /**
     * Default constructor for the JPOSPlugin class.
     * This constructor initializes an instance of the plugin, allowing it to be applied to a Gradle project.
     */
    public JPOSPlugin() {}

    /**
     * Applies the plugin to the specified project, setting up various tasks and configurations
     * specific to the jPOS project.
     *
     * @param project the Gradle project to which this plugin is applied
     */
    public void apply(Project project) {
        project.getPlugins().withType(JavaPlugin.class, plugin -> {
            var extension = project.getExtensions().create(JPOSPluginExtension.NAME, JPOSPluginExtension.class);
            extension.initConventions(project);

            ExtensionContainer ext = project.getExtensions();

            if (project.hasProperty("target")) {
                extension.getTarget().set("" + project.property("target"));
            }

            extension.getProperties().put("target", extension.getTarget().get());
            ext.add("target", extension.getTarget().get());

            var buildTimestampTask = createBuildTimestampTask(project);
            var addGitRevisionTask = createGitRevisionTask(project);

            // the build timestamp and git revision task are dependencies to the 'class' task
            project.getTasks().named(JavaPlugin.CLASSES_TASK_NAME).configure(javaClasses -> {
                if (extension.getAddBuildTime().get())
                    javaClasses.dependsOn(buildTimestampTask.get());
                if (extension.getAddGitRevision().get())
                    javaClasses.dependsOn(addGitRevisionTask.get());
            });

            // after the evaluation of the project we add the tasks,
            // we use this lifecycle event because we need the 'version' of the project
            project.afterEvaluate(pr -> {
                try {
                    extension.loadFromProject(project);
                    LOGGER.debug("Loaded config for the jpos plugin {} -  {}", pr.getName(), extension.asMap());
                    configureJar(project, extension);
                    createInstallAppTask(project, extension);
                    createDistTask(project, extension, "dist", Tar.class);
                    createDistTask(project, extension, "zip", Zip.class);
                    createDistNcTask(project, extension, "distnc", Tar.class);
                    createDistNcTask(project, extension, "zipnc", Zip.class);
                    createRunTask(project, extension);
                    createViewTestsTask(project, extension);
                    createFatJarTask(project, extension);
                } catch (IOException e) {
                    LOGGER.error(e.getMessage());
                    e.printStackTrace(System.err);
                    System.exit(1);
                }
            });
        });
    }

    /**
     * Creates the 'installApp' task, which installs the jPOS-based application by copying
     * necessary files into the specified installation directory.
     *
     * @param project the Gradle project
     * @param targetConfiguration the configuration settings for the jPOS application
     */
    private void createInstallAppTask(Project project, JPOSPluginExtension targetConfiguration) {
        project.getTasks().register("installApp", Sync.class, installApp -> {
            installApp.setDescription("Installs jPOS based application.");
            installApp.setGroup(GROUP_NAME);
            installApp.dependsOn(
              project.getTasks().getByName("jar")
            );
            installApp.with(
              distFiltered(project, targetConfiguration),
              distRaw(project, targetConfiguration),
              mainJar(project),
              depJars(project),
              webapps(project)
            );
            installApp.into(new File(targetConfiguration.getInstallDir().get()));
            installApp.getOutputs().upToDateWhen(_ -> false);
        });
    }

    /**
     * Creates the 'run' task to execute a jPOS-based application. This task ensures that the application
     * is properly installed before execution.
     *
     * @param project the Gradle project
     * @param targetConfiguration the configuration settings for the jPOS application
     */
    private void createRunTask(Project project, JPOSPluginExtension targetConfiguration) {
        project.getTasks().register("run", Exec.class, run -> {
            run.setDescription("Runs a jPOS based application.");
            run.setGroup(GROUP_NAME);
            run.dependsOn(
              project.getTasks().getByName("installApp")
            );
            run.workingDir(new File(targetConfiguration.getInstallDir().get()));
            if (DefaultNativePlatform.getCurrentOperatingSystem().isWindows()) {
                run.commandLine("bin/q2.bat");
            } else {
                run.commandLine("./bin/q2");
            }
        });
    }

    /**
     * Creates a task to generate the build timestamp information file.
     *
     * @param project the Gradle project
     * @return A provider for the created task for generating the build timestamp
     */
    private TaskProvider<BuildTimestampTask> createBuildTimestampTask(Project project) {
        return project.getTasks().register("createBuildTimestamp", BuildTimestampTask.class, task -> {
            task.setDescription("Creates jPOS buildinfo.properties resource.");
            task.setOutputFile(new File(getResourcesBuildDir(project), "buildinfo.properties"));
            task.setProjectName(project.getName());
            task.setProjectVersion(project.getVersion());
        });
    }

    /**
     * Creates a task to generate the Git revision information file.
     *
     * @param project the Gradle project
     * @return A provider for the created task for generating the Git revision
     */
    private TaskProvider<GitRevisionTask> createGitRevisionTask(Project project) {
        return project.getTasks().register("createGitRevision", GitRevisionTask.class, task -> {
            task.setDescription("Creates jPOS revision.properties resource.");
            task.setOutputFile(new File(getResourcesBuildDir(project), "revision.properties"));
            task.setProjectDir(project.getRootProject().getProjectDir());
        });
    }

    /**
     * Creates a task to generate the jPOS distribution, either as a tarball or a zip file.
     *
     * @param project the Gradle project
     * @param targetConfiguration the configuration settings for the jPOS application
     * @param taskName the name of the distribution task
     * @param clazz the type of archive task (Tar or Zip)
     */
    private void createDistTask(Project project, JPOSPluginExtension targetConfiguration, String taskName, Class<? extends AbstractArchiveTask> clazz) {
        project.getTasks().register(taskName, clazz, dist -> {
            dist.setGroup(GROUP_NAME);
            dist.setDescription(String.format("Generates jPOS distribution (%s).", clazz.getSimpleName()));
            dist.dependsOn(
              project.getTasks().getByName("jar")
            );
            dist.with(
              distFiltered(project, targetConfiguration),
              distRaw(project, targetConfiguration),
              mainJar(project),
              depJars(project),
              webapps(project)
            ).into(
              String.format("%s-%s", project.getName(), project.getVersion())
            );
            if (dist instanceof Tar tar) {
                tar.setCompression(Compression.GZIP);
                tar.getArchiveExtension().set("tar.gz");
            }
            dist.getOutputs().upToDateWhen(task -> false);
        });
    }

    /**
     * Creates a distribution task for generating a compressed archive of the jPOS-based application
     * without the configuration files. The archive format can be either a tarball or a zip file.
     *
     * @param project the Gradle project
     * @param targetConfiguration the configuration settings for the jPOS application
     * @param taskName the name of the distribution task
     * @param clazz the class representing the type of archive task (Tar or Zip)
     */
    private void createDistNcTask(Project project, JPOSPluginExtension targetConfiguration, String taskName, Class<? extends AbstractArchiveTask> clazz) {
        project.getTasks().register(taskName, clazz, dist -> {
            dist.setGroup(GROUP_NAME);
            dist.setDescription(String.format("Generates jPOS distribution without configuration (%s).", clazz.getSimpleName()));
            dist.dependsOn(
              project.getTasks().getByName("jar")
            );
            dist.with(
              distBinFiltered(project, targetConfiguration),
              mainJar(project),
              depJars(project),
              webapps(project)
            );
            if (clazz == Tar.class) {
                ((Tar) dist).setCompression(Compression.GZIP);
                dist.getArchiveExtension().set("tar.gz");
            }
            dist.getArchiveAppendix().set("nc");
            dist.getOutputs().upToDateWhen(task -> false);
        });
    }

    /**
     * Creates a {@link CopySpec} that copies filtered distribution files for the jPOS application.
     * This includes filtering configuration files using token replacement.
     *
     * @param project the Gradle project
     * @param targetConfiguration the configuration settings for the jPOS application
     * @return the {@link CopySpec} for the filtered distribution files
     */
    private CopySpec distFiltered(Project project, JPOSPluginExtension targetConfiguration) {
        Map<String, Map<String, String>> hm = new HashMap<>();
        var tokens = targetConfiguration.asMap();
        project.getExtensions().getExtraProperties().getProperties().forEach(
            (k,v) -> tokens.put(k, v.toString())
        );

        hm.put("tokens", tokens);
        File distDir = new File(project.getProjectDir(), targetConfiguration.getDistDir().get());
        return project.copySpec(copy -> copy
                .from(distDir)
                .exclude(
                        "cfg/*.lmk",
                        "cfg/*.jks",
                        "cfg/*.ks",
                        "cfg/*.ser",
                        "cfg/*.p12",
                        "cfg/*.pfx",
                        "**/*.jpg",
                        "**/*.gif",
                        "**/*.png",
                        "**/*.pdf",
                        "**/*.ico",
                        "**/*.war",
                        "**/*.dat")
                .exclude(excludedFiles(project))
                .filter(
                        hm,
                        org.apache.tools.ant.filters.ReplaceTokens.class
                )
                .eachFile(details -> {
                    if (details.getSourcePath().startsWith("bin")) {
                        details.permissions(p -> p.unix(0754));
                    }
                })
        );
    }

    /**
     * Creates a {@link CopySpec} that copies filtered binary files for the jPOS application.
     * This includes filtering binary scripts using token replacement.
     *
     * @param project the Gradle project
     * @param targetConfiguration the configuration settings for the jPOS application
     * @return the {@link CopySpec} for the filtered binary files
     */
    private CopySpec distBinFiltered(Project project, JPOSPluginExtension targetConfiguration) {
        Map<String, Map<String, String>> hm = new HashMap<>();
        var tokens = targetConfiguration.asMap();
        project.getExtensions().getExtraProperties().getProperties().forEach(
            (k,v) -> tokens.put(k, v.toString())
        );

        hm.put("tokens", tokens);
        File distBinDir = new File(project.getProjectDir(), targetConfiguration.getDistDir().get() + "/bin");
        return project.copySpec(copy -> copy
                .from(distBinDir)
                .exclude(excludedFiles(project))
                .filter(
                        hm,
                        org.apache.tools.ant.filters.ReplaceTokens.class
                )
                .filePermissions(permissions -> permissions.unix(0754))
                .into("bin"));
    }

    /**
     * Creates a {@link CopySpec} that copies raw configuration files for the jPOS application.
     * These files are copied without any filtering.
     *
     * @param project the Gradle project
     * @param targetConfiguration the configuration settings for the jPOS application
     * @return the {@link CopySpec} for the raw configuration files
     */
    private CopySpec distRaw(Project project, JPOSPluginExtension targetConfiguration) {
        File distDir = new File(project.getProjectDir(), targetConfiguration.getDistDir().get());
        return project.copySpec(copy -> copy
                .from(distDir)
                .exclude(excludedFiles(project))
                .include(
                        "cfg/*.lmk",
                        "cfg/*.ks",
                        "cfg/*.jks",
                        "cfg/*.p12",
                        "cfg/*.pfx",
                        "cfg/*.ser",
                        "cfg/authorized_keys"
                ).filePermissions(permissions -> permissions.unix(0600)));
    }

    /**
     * Creates a {@link CopySpec} for the main jar file generated by the project.
     *
     * @param project the Gradle project
     * @return the {@link CopySpec} for the main jar file
     */
    private CopySpec mainJar(Project project) {
        return project.copySpec(copy -> copy.from(project.getTasks().getByName("jar"))).exclude(excludedFiles(project));
    }

    /**
     * Creates a {@link CopySpec} for copying the dependency jar files used by the project.
     *
     * @param project the Gradle project
     * @return the {@link CopySpec} for the dependency jar files
     */
    private CopySpec depJars(Project project) {
        return project.copySpec(copy -> copy
                .from(project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME))
                .exclude(excludedFiles(project))
                .into("lib"));
    }

    /**
     * Creates a {@link CopySpec} for copying the web application (WAR) files generated by the project.
     *
     * @param project the Gradle project
     * @return the {@link CopySpec} for the web application files
     */
    private CopySpec webapps(Project project) {
        return project.copySpec(copy -> copy
                .from(project.getLayout().getBuildDirectory())
                .include("*.war")
                .exclude(excludedFiles(project))
                .into("webapps"));
    }

    /**
     * Configures the jar task for the project, setting attributes such as the implementation
     * title, version, and main class, as well as setting the classpath for the jar.
     *
     * @param project the Gradle project
     * @param extension the jPOS plugin extension settings
     */
    private void configureJar(Project project, JPOSPluginExtension extension) {
        project.getTasks().withType(Jar.class, task -> {
            if (!task.getArchiveClassifier().getOrElse("").isEmpty()) {
                // do nothing on sources, javadoc, or custom classifiers
                return;
            }
            // if we are running with both git and build time disabled, we need to always
            // generate a jar, otherwise the copy task will copy nothing and the dist
            // folder will be invalid i.e. two sequential ./gradlew clean iA will produce
            // and invalid dist folder
            if (!extension.getAddGitRevision().get() &&
                    !extension.getAddBuildTime().get())
                task.getOutputs().upToDateWhen(task1 -> false);

            task.doFirst(t -> {

                LOGGER.info("Configuring jar class-path for project {}", project.getName());
                Attributes attr = task.getManifest().getAttributes();
                // change the default name of the jar
                task.getArchiveFileName().set(extension.getArchiveJarName());

                attr.put("Implementation-Title", project.getName());
                attr.put("Implementation-Version", project.getVersion());
                attr.put("Main-Class", "org.jpos.q2.Q2");
                attr.put("Class-Path",
                        project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME)
                                .getFiles()
                                .stream()
                                .map(file -> "lib/" + file.getName())
                                .collect(Collectors.joining(" "))
                );
            });
        });
    }

    /**
     * Retrieves the resources directory for the build.
     * This method is used to determine the location of the resources before the classpath is resolved.
     *
     * @param project the Gradle project
     * @return the file representing the build resources directory
     */
    private File getResourcesBuildDir(Project project) {
        JavaPluginExtension plugin = project.getExtensions().getByType(JavaPluginExtension.class);
        SourceSet sourceSet = plugin.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        return sourceSet.getOutput().getResourcesDir();
    }

    /**
     * Creates a task that opens the test results in a browser.
     * The task launches the default system browser and points to the test results file.
     *
     * @param project the Gradle project
     * @param targetConfiguration the configuration settings for the jPOS application
     */
    private void createViewTestsTask(Project project, JPOSPluginExtension targetConfiguration) {
       project.getTasks().register("viewTests", task -> {
            task.setDescription("Open a browser with the tests results");
            task.setGroup(GROUP_NAME);
            task.doLast(_ -> {
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        File reportFile = project.getLayout().getBuildDirectory()
                          .file("reports/tests/test/index.html")
                          .get().getAsFile();
                        Desktop.getDesktop().browse(reportFile.toURI());
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Can't open test results file", e);
                }
            });
        });
    }

    @SuppressWarnings("unchecked")
    private String[] excludedFiles (Project project) {
        JPOSPluginExtension ext = (JPOSPluginExtension) project.getExtensions().getByName(JPOSPluginExtension.NAME);
        return (String[]) ext.getProperties().get().get(JPOSPluginExtension.EXCLUDED_FILES);
    }

    private void createFatJarTask(Project project, JPOSPluginExtension extension) {
        project.getTasks().register("fatjar", Jar.class, fatjar -> {
            fatjar.setGroup(GROUP_NAME);
            fatjar.setDescription("Builds an executable fat JAR (application + runtime dependencies), merging META-INF/services/* for ServiceLoader.");

            fatjar.dependsOn(project.getTasks().named(JavaPlugin.CLASSES_TASK_NAME));

            fatjar.getArchiveClassifier().set("all");
            fatjar.getArchiveFileName().set(
              String.format("%s-%s-all.jar", project.getName(), project.getVersion())
            );

            fatjar.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);

            // Project output (classes + resources).
            SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
            var mainOutput = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).getOutput();
            fatjar.from(mainOutput);

            // Runtime classpath (jars + dirs)
            var runtimeCp = project.getConfigurations()
              .getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);

            // Expand jars into trees; keep directories as-is (no FileCollection.map()).
            var expandedRuntime = project.files((java.util.concurrent.Callable<Object>) () ->
              runtimeCp.getFiles().stream()
                .map(f -> f.isDirectory() ? f : project.zipTree(f))
                .collect(java.util.stream.Collectors.toList())
            );

            // Include runtime deps, but exclude META-INF/services/** (we will merge them ourselves).
            fatjar.from(expandedRuntime, spec -> spec
              .exclude(SERVICES_PATH + "/**")
              .exclude("META-INF/*.SF")
              .exclude("META-INF/*.DSA")
              .exclude("META-INF/*.RSA")
              .exclude("META-INF/SIG-*")
            );

            // Temp dir for merged services
            var mergedServicesDir = project.getLayout().getBuildDirectory()
              .dir("tmp/fatjar-merged-services")
              .map(d -> d.getAsFile());

            fatjar.exclude("META-INF/*.SF");
            fatjar.exclude("META-INF/*.DSA");
            fatjar.exclude("META-INF/*.RSA");
            fatjar.exclude("META-INF/SIG-*");
            fatjar.from(mergedServicesDir);
            fatjar.doFirst(task -> {
                File outDir = mergedServicesDir.get();

                try {
                    // Clean and recreate the output directory
                    if (outDir.exists()) {
                        deleteRecursively(outDir.toPath());
                    }
                    Files.createDirectories(outDir.toPath());

                    // Collect all sources that may contribute META-INF/services/*
                    List<File> sources = new ArrayList<>();

                    // Project output (classes + resources)
                    for (File f : mainOutput.getFiles()) {
                        sources.add(f);
                    }

                    // Runtime classpath (dirs + jars)
                    for (File f : runtimeCp.getFiles()) {
                        sources.add(f);
                    }

                    // Merge service descriptors
                    Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();

                    List<String> signedJars = new ArrayList<>();
                    for (File src : sources) {
                        if (!src.exists()) {
                            continue;
                        }
                        if (src.isDirectory()) {
                            mergeServicesFromDirectory(src.toPath(), merged);
                        } else if (src.getName().endsWith(".jar")) {
                            // Detect signatures for logging
                            List<String> sigs = findJarSignatures(src);
                            if (!sigs.isEmpty()) {
                                signedJars.add(
                                  src.getName() + " -> " + String.join(", ", sigs)
                                );
                            }
                            mergeServicesFromJar(src, merged);
                        }
                    }
                    if (!signedJars.isEmpty()) {
                        LOGGER.lifecycle(
                          "fatjar: stripped signature metadata from {} signed dependency JAR(s):",
                          signedJars.size()
                        );
                        for (String s : signedJars) {
                            LOGGER.lifecycle("  - {}", s);
                        }
                    }

                    // Write merged META-INF/services/* files
                    Path servicesRoot = outDir.toPath().resolve(SERVICES_PATH);
                    Files.createDirectories(servicesRoot);

                    for (Map.Entry<String, LinkedHashSet<String>> entry : merged.entrySet()) {
                        String relativePath = entry.getKey(); // META-INF/services/<fqcn>
                        Path target = outDir.toPath().resolve(relativePath);

                        Files.createDirectories(target.getParent());

                        try (BufferedWriter writer = Files.newBufferedWriter(
                          target,
                          StandardCharsets.UTF_8,
                          StandardOpenOption.CREATE,
                          StandardOpenOption.TRUNCATE_EXISTING)) {

                            for (String provider : entry.getValue()) {
                                writer.write(provider);
                                writer.newLine();
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(
                      "Failed to merge META-INF/services entries for fatjar", e
                    );
                }
            });
            fatjar.getManifest().attributes(Map.of(
              "Implementation-Title", project.getName(),
              "Implementation-Version", String.valueOf(project.getVersion()),
              "Main-Class", "org.jpos.q2.Q2"
            ));
        });
    }

    private static void mergeServicesFromDirectory(Path root, Map<String, LinkedHashSet<String>> merged) throws IOException {
        Path servicesDir = root.resolve(SERVICES_PATH);
        if (!Files.isDirectory(servicesDir)) return;

        try (Stream<Path> paths = Files.walk(servicesDir)) {
            paths.filter(Files::isRegularFile).forEach(p -> {
                Path rel = root.relativize(p); // META-INF/services/...
                String relPath = rel.toString().replace(File.separatorChar, '/');

                try {
                    List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                    merged.computeIfAbsent(relPath, __ -> new LinkedHashSet<>());
                    addServiceLines(merged.get(relPath), lines);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException uioe) {
            throw uioe.getCause();
        }
    }

    private static void mergeServicesFromJar(File jarFile, Map<String, LinkedHashSet<String>> merged) throws IOException {
        try (JarFile jf = new JarFile(jarFile)) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry entry = en.nextElement();
                if (entry.isDirectory()) continue;

                String name = entry.getName();
                if (!name.startsWith(SERVICES_PATH + "/")) continue;

                try (InputStream in = jf.getInputStream(entry);
                     BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

                    List<String> lines = new ArrayList<>();
                    String line;
                    while ((line = r.readLine()) != null) {
                        lines.add(line);
                    }

                    merged.computeIfAbsent(name, __ -> new LinkedHashSet<>());
                    addServiceLines(merged.get(name), lines);
                }
            }
        }
    }

    private static void addServiceLines(LinkedHashSet<String> target, List<String> lines) {
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();

            // Preserve ServiceLoader semantics:
            // - ignore blank lines
            // - ignore comments (leading '#')
            if (line.isEmpty() || line.startsWith("#")) continue;

            target.add(line);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
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

    private static List<String> findJarSignatures(File jarFile) throws IOException {
        List<String> signatures = new ArrayList<>();
        try (JarFile jf = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                if (name.startsWith("META-INF/") &&
                  (name.endsWith(".SF")
                    || name.endsWith(".RSA")
                    || name.endsWith(".DSA")
                    || name.startsWith("META-INF/SIG-"))) {
                    signatures.add(name);
                }
            }
        }
        return signatures;
    }
}
