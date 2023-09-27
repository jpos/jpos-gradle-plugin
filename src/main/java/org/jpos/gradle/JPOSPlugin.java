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

import org.gradle.api.Project;
import org.gradle.api.Plugin;
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
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Properties;
import java.util.Map;
import java.util.stream.Collectors;

public class JPOSPlugin implements Plugin<Project> {
    private static final Logger LOGGER = Logging.getLogger(JPOSPlugin.class);
    private static String GROUP_NAME = "jPOS";

    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        String target = project.hasProperty("target") ? (String) project.getProperties().get("target") : "devel";
        ExtensionContainer ext = project.getExtensions();
        ext.add("target", target);
        project.getGradle().afterProject (gradle -> {
            try {
                Map<String,String> targetConfiguration = createTargetConfiguration(project, target);
                ext.add("targetConfiguration", new HashMap<String,String>());
                createBuildTimestampTask(project);
                createGitRevisionTask(project);
                configureJar (project);
                createInstallAppTask(project, targetConfiguration);
                createDistTask(project, targetConfiguration, "dist", Tar.class);
                createDistTask(project, targetConfiguration, "zip", Zip.class);
                createDistNcTask(project, targetConfiguration, "distnc", Tar.class);
                createDistNcTask(project, targetConfiguration, "zipnc", Zip.class);
                createRunTask(project, targetConfiguration);
                createViewTestsTask(project, targetConfiguration);
            } catch (IOException e) {
                LOGGER.error(e.getMessage());
                e.printStackTrace(System.err);
                System.exit(1);
            }
        });
    }

    private Sync createInstallAppTask(Project project, Map<String,String> targetConfiguration) {
        Sync installApp = project.getTasks().create("installApp", Sync.class);
        installApp.setDescription("Installs jPOS based application.");
        installApp.setGroup (GROUP_NAME);
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
        installApp.into(new File(targetConfiguration.get("installDir")));
        installApp.getOutputs().upToDateWhen(task -> false);
        return installApp;
    }

    private Exec createRunTask(Project project, Map<String,String> targetConfiguration) {
        var run = project.getTasks().create("run", Exec.class);
        run.setDescription("Runs a jPOS based application.");
        run.setGroup(GROUP_NAME);
        run.dependsOn(
                project.getTasks().getByName("installApp")
        );
        run.workingDir(new File(targetConfiguration.get("installDir")));
        if (DefaultNativePlatform.getCurrentOperatingSystem().isWindows()) {
            run.commandLine("bin/q2.bat");
        } else {
            run.commandLine("./bin/q2");
        }

        return run;
    }

    private void createBuildTimestampTask (Project project) {
        var task = project.getTasks().create(
          "createBuildTimestamp",
          BuildTimestampTask.class,
            new File(getResourcesBuildDir(project), "buildinfo.properties")
        );
        task.setDescription("Creates jPOS buildinfo.properties resource.");
    }
    private void createGitRevisionTask (Project project) {
        var task = project.getTasks().create(
          "createGitRevision",
          GitRevisionTask.class,
          new File(getResourcesBuildDir(project), "revision.properties")
        );
        task.setDescription("Creates jPOS revision.properties resource.");
    }
    
    private void createDistTask(Project project, Map<String,String> targetConfiguration, String taskName, Class<? extends AbstractArchiveTask> clazz) {
        var dist = project.getTasks().create(taskName, clazz);
        dist.setGroup (GROUP_NAME);
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

    private void createDistNcTask(Project project, Map<String,String> targetConfiguration, String taskName, Class<? extends AbstractArchiveTask> clazz) {
        var dist = project.getTasks().create(taskName, clazz);
        dist.setGroup (GROUP_NAME);
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


    private Map<String,String> createTargetConfiguration (Project project, String target) throws IOException {
        Map<String,String> targetConfiguration = new HashMap<>();
        targetConfiguration.put("archiveJarName", String.format("%s-%s.jar", project.getName(), project.getVersion()));
        targetConfiguration.put("archiveWarName", String.format("%s-%s.war", project.getName(), project.getVersion()));
        targetConfiguration.put("installDir", String.format("%s/install/%s", project.getBuildDir(), project.getName()));
        targetConfiguration.put("distDir", "src/dist");
        targetConfiguration.put("jarname", targetConfiguration.get("archiveJarName"));

        File cfgFile = new File (project.getRootDir(), String.format("%s.properties", target));
        if (cfgFile.exists()) {
            try (FileInputStream fis = new FileInputStream(cfgFile)) {
                Properties props = new Properties();
                props.load(fis);
                props.forEach((k,v) -> {
                    targetConfiguration.put((String)k, (String)v);
                });
            }
        }
        return targetConfiguration;
    }

    private CopySpec distFiltered (Project project, Map<String,String> targetConfiguration) {
        Map<String,Map<String,String>> hm = new HashMap<>();
        hm.put("tokens", targetConfiguration);
        File distDir = new File(project.getProjectDir(), targetConfiguration.get("distDir"));
        return project.copySpec(copy -> {
            copy
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
              );
        });
    }

    private CopySpec distBinFiltered (Project project, Map<String,String> targetConfiguration) {
        Map<String,Map<String,String>> hm = new HashMap<>();
        hm.put("tokens", targetConfiguration);
        File distBinDir = new File(project.getProjectDir(), targetConfiguration.get("distDir") + "/bin");
        return project.copySpec(copy -> {
            copy
              .from(distBinDir)
              .filter(
                hm,
                org.apache.tools.ant.filters.ReplaceTokens.class
              )
              .into ("bin");
        });
    }

    private CopySpec distRaw (Project project, Map<String,String> targetConfiguration) {
        File distDir = new File(project.getProjectDir(), targetConfiguration.get("distDir"));
        return project.copySpec(copy -> {
           copy
             .from(distDir)
             .include(
              "cfg/*.lmk",
              "cfg/*.ks",
              "cfg/*.jks",
              "cfg/*.ser",
              "cfg/authorized_keys"
           ).setFileMode(0600);
        });
    }

    private CopySpec mainJar (Project project) {
        return project.copySpec(copy -> {
            copy.from(project.getTasks().getByName("jar"));
        });
    }

    private CopySpec depJars (Project project) {
        return project.copySpec(copy -> {
            copy.from(project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME))
            .into("lib");
        });
    }

    private CopySpec webapps (Project project) {
        return project.copySpec(copy -> {
            copy
              .from(project.getBuildDir()).include("*.war")
              .into("webapps");
        });
    }

    @SuppressWarnings("unchecked")
    private void configureJar (Project project) {
        project.getTasks().getByName("jar").dependsOn(
            project.getTasks().getByName("createBuildTimestamp")
        );
        boolean ignoreGit = project.hasProperty("ignoreGit") && Boolean.parseBoolean((String) project.property("ignoreGit")); 
        if (!ignoreGit) {
            project.getTasks().getByName("jar").dependsOn(project.getTasks().getByName("createGitRevision")); 
        }
        Attributes attr = ((Jar)project.getTasks().getByName("jar")).getManifest().getAttributes();

        attr.put ("Implementation-Title",  project.getName());
        attr.put ("Implementation-Version", project.getVersion());
        attr.put ("Main-Class", "org.jpos.q2.Q2");
        attr.put ("Class-Path",
          project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME)
            .getFiles()
            .stream()
            .map(file -> "lib/" + file.getName())
            .collect(Collectors.joining(" "))
        );
    }

    private File getResourcesBuildDir (Project project) {
        JavaPluginExtension plugin = project.getExtensions().getByType(JavaPluginExtension.class);
        SourceSet sourceSet = plugin.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

        return sourceSet.getRuntimeClasspath().getFiles()
          .stream()
          .filter(f -> f.getPath().contains("resources/main"))
          .findFirst()
          .orElse(new File(project.getBuildDir(), "resources/main"));
    }

    private Task createViewTestsTask(Project project, Map<String, String> targetConfiguration) {
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
