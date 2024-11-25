In order to use the jPOS APP Plugin

1. Add to your `build.gradle`

```groovy
plugins {
    id 'org.jpos.jposapp' version '0.0.10'
}
```

2. Add to your `settings.gradle`

```groovy
pluginManagement {
    repositories {
        maven { url = uri('https://jpos.org/maven') }
        gradlePluginPortal()
    }
}
```

See [Gradle Plugin Repository](https://plugins.gradle.org/plugin/org.jpos.jposapp) for latest version.


## Plugin configuration

To change the default values of the plugin, you can use the `jpos` property:

```groovy
jpos {
    target = "devel"                        // string, which configuration file read from the root. default "devel" (devel.properties)
    addGitRevision = true                   // boolean, if the file revision.properties should be created
    addBuildTime = true                     // boolean, if the file buildinfo.properties should be created
    archiveJarName = "jpos.jar"             // string, the name of the jar, default '${project}-${version}.jar'
    archiveWarName = "jpos.war"             // string, the name of the war, default '${project}-${version}.war'
    installDir = build/install/jpos         // string, the default install dir, default to '${build}/install/${project}'
    distDir = src/dist                      // string, path to the distribution folder
}
```

For example, if we want to create an inmutable build file, we can disable the git and build time info:

```groovy
jpos {
    addGitRevision = false
    addBuildTime false
}
```

This will generate always the same jar (and dist folder)

## Per-target excludes

If for some reason we want the plugin to exclude some files for a given target, we can add `<targetName>.exclude`.

 
