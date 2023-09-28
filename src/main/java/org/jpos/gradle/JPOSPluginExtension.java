package org.jpos.gradle;

import org.gradle.api.Project;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.options.Option;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Extension for the JPOSPlugin
 */
public interface JPOSPluginExtension {

    /**
     * Target configuration to use
     */
    @Input
    @Option(option = "devel", description = "The target configuration to use")
    Property<String> getTarget();

    /**
     * If the task that add the git metadata is executed
     */
    @Input
    @Option(option = "true", description = "If the resulting dist folder should contain revision.properties info")
    Property<Boolean> getAddGitRevision();

    /**
     * If the task that add the build time metadata is executed
     */
    @Input
    @Option(option = "true", description = "If the resulting dist folder should contain buildinfo.properties file")
    Property<Boolean> getAddBuildTime();


    /**
     * The jar that will be built
     */
    @Input
    @Option(option = "true", description = "If the resulting dist folder should contain buildinfo.properties file")
    Property<String> getArchiveJarName();

    /**
     * Where to put the war
     */
    @Input
    @Option(option = "true", description = "If the resulting dist folder should contain buildinfo.properties file")
    Property<String> getArchiveWarName();

    /**
     * Where to put the artifacts
     */
    @Input
    @Option(option = "true", description = "If the resulting dist folder should contain buildinfo.properties file")
    Property<String> getInstallDir();

    /**
     * The dist folder that will be used as a source
     */
    @Input
    @Option(option = "true", description = "If the resulting dist folder should contain buildinfo.properties file")
    Property<String> getDistDir();

    @Internal
    MapProperty<String, Object> getProperties();

    default void loadFromProject(Project project) throws IOException {

        initConventions(project);
        File cfgFile = new File(project.getRootDir(), String.format("%s.properties", getTarget().get()));
        if (cfgFile.exists()) {
            try (FileInputStream fis = new FileInputStream(cfgFile)) {
                Properties props = new Properties();
                props.load(fis);
                Map<String, Object> startingProps = new HashMap<>();
                props.forEach((k, v) -> {
                    switch (k.toString()) {
                        case "archiveJarName":
                        case "jarname":
                            getArchiveJarName().set(v.toString());
                            break;
                        case "archiveWarName":
                            getArchiveWarName().set(v.toString());
                            break;
                        case "installDir":
                            getInstallDir().set(v.toString());
                            break;
                        case "distDir":
                            getDistDir().set(v.toString());
                            break;
                        default:
                            startingProps.put(k.toString(), v);
                            break;
                    }
                });
                getProperties().set(startingProps);
            }
        }
    }

    default Map<String, String> asMap() {
        HashMap<String, String> toRet = new HashMap<>();
        for (Map.Entry<String, Object> entry : getProperties().get().entrySet()) {
            toRet.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        toRet.put("archiveJarName", getArchiveJarName().get());
        toRet.put("archiveWarName", getArchiveWarName().get());
        toRet.put("installDir", getInstallDir().get());
        toRet.put("distDir", getDistDir().get());
        toRet.put("jarname", getArchiveJarName().get());
        return toRet;
    }

    default void initConventions(Project project) {
        getTarget().convention("devel");
        getAddBuildTime().convention(true);
        getAddGitRevision().convention(true);
        getArchiveJarName().convention(String.format("%s-%s.jar", project.getName(), project.getVersion()));
        getArchiveWarName().convention(String.format("%s-%s.war", project.getName(), project.getVersion()));
        getInstallDir().convention(String.format("%s/install/%s", project.getBuildDir(), project.getName()));
        getDistDir().convention("src/dist");
    }
}
