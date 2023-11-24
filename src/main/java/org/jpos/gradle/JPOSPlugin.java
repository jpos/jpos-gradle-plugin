/*
 * jPOS Project [http://jpos.org]
 * Copyright (C) 2000-2022 jPOS Software SRL
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
import org.gradle.api.Task;
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
import org.gradle.api.tasks.bundling.*;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class JPOSPlugin implements Plugin<Project> {
    private static final Logger LOGGER = Logging.getLogger(JPOSPlugin.class);
    private static final String GROUP_NAME = "jPOS";

    public void apply(Project project) {
        project.getPlugins().withType(JavaPlugin.class, plugin -> {

            var extension = project.getExtensions().create("jpos", JPOSPluginExtension.class);
            extension.initConventions(project);

            ExtensionContainer ext = project.getExtensions();

            if (project.hasProperty("target"))
                extension.getTarget().set("" + project.property("target"));
            ext.add("target", extension.getTarget().get());

            var buildTimestampTask = createBuildTimestampTask(project);
            var addGitRevisionTask = createGitRevisionTask(project);

            // the build timestamp and git revision task are dependencies to the 'class' task
            project.getTasks().named(JavaPlugin.CLASSES_TASK_NAME).configure(javaClasses -> {
                if (extension.getAddBuildTime().get())
                    javaClasses.dependsOn(buildTimestampTask);
                if (extension.getAddGitRevision().get())
                    javaClasses.dependsOn(addGitRevisionTask);
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
                } catch (IOException e) {
                    LOGGER.error(e.getMessage());
                    e.printStackTrace(System.err);
                    System.exit(1);
                }
            });
        });


    }

    private Sync createInstallAppTask(Project project, JPOSPluginExtension targetConfiguration) {
        Sync installApp = project.getTasks().create("installApp", Sync.class);
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
        installApp.getOutputs().upToDateWhen(task -> false);
        return installApp;
    }

    private Exec createRunTask(Project project, JPOSPluginExtension targetConfiguration) {
        var run = project.getTasks().create("run", Exec.class);
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

        return run;
    }

    private BuildTimestampTask createBuildTimestampTask(Project project) {
        var task = project.getTasks().create(
                "createBuildTimestamp",
                BuildTimestampTask.class,
                new File(getResourcesBuildDir(project), "buildinfo.properties")
        );
        task.setDescription("Creates jPOS buildinfo.properties resource.");
        return task;
    }

    private GitRevisionTask createGitRevisionTask(Project project) {
        var task = project.getTasks().create(
                "createGitRevision",
                GitRevisionTask.class,
                new File(getResourcesBuildDir(project), "revision.properties")
        );
        task.setDescription("Creates jPOS revision.properties resource.");
        return task;
    }

    private void createDistTask(Project project, JPOSPluginExtension targetConfiguration, String taskName, Class<? extends AbstractArchiveTask> clazz) {
        var dist = project.getTasks().create(taskName, clazz);
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
        if (clazz == Tar.class) {
            ((Tar) dist).setCompression(Compression.GZIP);
            dist.getArchiveExtension().set("tar.gz");
        }
        dist.getOutputs().upToDateWhen(task -> false);
    }

    private void createDistNcTask(Project project, JPOSPluginExtension targetConfiguration, String taskName, Class<? extends AbstractArchiveTask> clazz) {
        var dist = project.getTasks().create(taskName, clazz);
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
    }


    private CopySpec distFiltered(Project project, JPOSPluginExtension targetConfiguration) {
        Map<String, Map<String, String>> hm = new HashMap<>();
        hm.put("tokens", targetConfiguration.asMap());
        File distDir = new File(project.getProjectDir(), targetConfiguration.getDistDir().get());
        return project.copySpec(copy -> copy
                .from(distDir)
                .exclude(
                        "cfg/*.lmk",
                        "cfg/*.jks",
                        "cfg/*.ks",
                        "cfg/*.ser",
                        "cfg/*.p12",
                        "**/*.jpg",
                        "**/*.gif",
                        "**/*.png",
                        "**/*.pdf",
                        "**/*.ico",
                        "**/*.war",
                        "**/*.dat")
                .filter(
                        hm,
                        org.apache.tools.ant.filters.ReplaceTokens.class
                ));
    }

    private CopySpec distBinFiltered(Project project, JPOSPluginExtension targetConfiguration) {
        Map<String, Map<String, String>> hm = new HashMap<>();
        hm.put("tokens", targetConfiguration.asMap());
        File distBinDir = new File(project.getProjectDir(), targetConfiguration.getDistDir().get() + "/bin");
        return project.copySpec(copy -> copy
                .from(distBinDir)
                .filter(
                        hm,
                        org.apache.tools.ant.filters.ReplaceTokens.class
                )
                .into("bin"));
    }

    private CopySpec distRaw(Project project, JPOSPluginExtension targetConfiguration) {
        File distDir = new File(project.getProjectDir(), targetConfiguration.getDistDir().get());
        return project.copySpec(copy -> copy
                .from(distDir)
                .include(
                        "cfg/*.lmk",
                        "cfg/*.ks",
                        "cfg/*.jks",
                        "cfg/*.ser",
                        "cfg/authorized_keys"
                ).setFileMode(0600));
    }

    private CopySpec mainJar(Project project) {
        return project.copySpec(copy -> copy.from(project.getTasks().getByName("jar")));
    }

    private CopySpec depJars(Project project) {
        return project.copySpec(copy -> copy
                .from(project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME))
                .into("lib"));
    }

    private CopySpec webapps(Project project) {
        return project.copySpec(copy -> copy
                .from(project.getBuildDir()).include("*.war")
                .into("webapps"));
    }

    private void configureJar(Project project, JPOSPluginExtension extension) {
        project.getTasks().withType(Jar.class, task -> {

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
     * We use the mainSourceSet.output.resourcesDir to get the default resources dir.
     * <p>
     * In jpos-app.gradle we used the runtime classpath to find the first
     * directory that contain 'resources/main', but we can't do that here
     * because we need the path before the class path resolution.
     */
    private File getResourcesBuildDir(Project project) {
        JavaPluginExtension plugin = project.getExtensions().getByType(JavaPluginExtension.class);
        SourceSet sourceSet = plugin.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        return sourceSet.getOutput().getResourcesDir();
    }

    private Task createViewTestsTask(Project project, JPOSPluginExtension targetConfiguration) {
        var run = project.getTasks().create("viewTests");
        run.setDescription("Open a browser with the tests results");
        run.setGroup(GROUP_NAME);
        run.doLast(task -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(Paths.get(project.getBuildDir().getPath(), "reports/tests/test", "index.html").toUri());
                }
            } catch (IOException e) {
                throw new RuntimeException("Can't open test results file", e);
            }
        });
        return run;
    }

}
