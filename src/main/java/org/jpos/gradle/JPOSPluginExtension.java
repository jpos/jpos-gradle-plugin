package org.jpos.gradle;

import org.gradle.api.Project;
import org.gradle.api.plugins.BasePluginExtension;
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
     * Retrieves the target configuration property to use.
     *
     * @return the Property representing the target configuration
     */
    @Input
    @Option(option = "devel", description = "The target configuration to use")
    Property<String> getTarget();

    /**
     * Determines if the task that adds the git metadata should be executed.
     *
     * @return the Property indicating whether to include revision.properties info in the distribution folder
     */
    @Input
    @Option(option = "true", description = "If the resulting dist folder should contain revision.properties info")
    Property<Boolean> getAddGitRevision();

    /**
     * Determines if the task that adds the build time metadata should be executed.
     *
     * @return the Property indicating whether to include buildinfo.properties file in the distribution folder
     */
    @Input
    @Option(option = "true", description = "If the resulting dist folder should contain buildinfo.properties file")
    Property<Boolean> getAddBuildTime();


    /**
     * Retrieves the name of the JAR file to be built.
     *
     * @return the Property representing the name of the archive JAR
     */
    @Input
    @Option(option = "true", description = "archive's jar name")
    Property<String> getArchiveJarName();

    /**
     * Retrieves the name of the WAR file to be placed in the distribution.
     *
     * @return the Property representing the name of the archive WAR
     */
    @Input
    @Option(option = "true", description = "archive's war name")
    Property<String> getArchiveWarName();

    /**
     * Retrieves the directory path where the artifacts will be installed.
     *
     * @return the Property indicating the installation directory
     */
    @Input
    @Option(option = "true", description = "install directory")
    Property<String> getInstallDir();

    /**
     * Retrieves the directory that will serve as the source for the distribution.
     *
     * @return the Property indicating the source directory for the distribution
     */
    @Input
    @Option(option = "true", description = "dist directory")
    Property<String> getDistDir();

    /**
     * Retrieves a map of properties that can be used internally by the plugin.
     *
     * @return the MapProperty of internal properties
     */
    @Internal
    MapProperty<String, Object> getProperties();

    /**
     * Loads the plugin properties from the project-specific configuration file.
     * The configuration file name is derived from the target environment and must exist at the project root level.
     *
     * @param project The Gradle project from which to load properties.
     * @throws IOException If there is an error reading the configuration file.
     */
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

    /**
     * Converts the current properties to a map for easy access and manipulation.
     *
     * @return A map representation of the current properties.
     */
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

    /**
     * Initializes the convention values for the plugin properties based on the given project.
     * This sets up default values for properties such as the target environment, build time, git revision,
     * and naming conventions for JAR and WAR archives.
     *
     * @param project The Gradle project whose settings will be used to initialize the conventions.
     */
    default void initConventions(Project project) {
        BasePluginExtension basePluginExtension = project.getExtensions().getByType(BasePluginExtension.class);
        String archivesName = basePluginExtension.getArchivesName().get();

        getTarget().convention("devel");
        getAddBuildTime().convention(true);
        getAddGitRevision().convention(true);
        getArchiveJarName().convention(String.format("%s-%s.jar", archivesName, project.getVersion()));
        getArchiveWarName().convention(String.format("%s-%s.war", archivesName, project.getVersion()));
        getInstallDir().convention(String.format("%s/install/%s", project.getLayout().getBuildDirectory().getAsFile().get(), project.getName()));
        getDistDir().convention("src/dist");
    }
}
