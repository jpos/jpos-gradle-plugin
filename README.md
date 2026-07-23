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

## Installing embedded module resources

The plugin provides an `installResources` task that runs `org.jpos.q2.install.Install` and extracts resources packaged under `META-INF/q2/installs`.

By default, resources are installed into `jpos.installDir`:

```bash
./gradlew installResources
```

You can override the target directory with the task option:

```bash
./gradlew installResources --outputDir=/path/to/install
```

## Pinning SNAPSHOT versions

During development, projects typically track `-SNAPSHOT` versions of jPOS, jPOS-EE
and related libraries in `gradle/libs.versions.toml`. When entering a certification
or QA phase, those versions can be pinned to specific timestamped snapshot builds
(e.g. `3.0.2-20260720.022713-26`, as published in the repository's
`maven-metadata.xml`) — and later restored — without editing the catalog by hand:

```bash
./gradlew pins             # list SNAPSHOT/pinned versions and their latest available builds
./gradlew pin              # pin every SNAPSHOT version to its latest snapshot build
./gradlew pin --ref jpos   # pin only the 'jpos' version key to its latest snapshot build
./gradlew pin --ref jpos --to 3.0.2-20260720.022713-26   # pin to a specific build
./gradlew unpin --ref jpos # restore the original SNAPSHOT for 'jpos'
./gradlew unpin            # restore all pinned versions
```

Pinning rewrites only the affected line, preserving comments and formatting, and
records the original SNAPSHOT in a trailing comment so `unpin` can restore it:

```toml
jpos = "3.0.2-20260720.022713-26" # pinned-from 3.0.2-SNAPSHOT
```

Any entry in `[versions]` whose value ends in `-SNAPSHOT` (or was previously pinned)
is eligible — jPOS, jPOS-EE, or any in-house library. The latest build for each
version is discovered from the project's declared Maven repositories.

## Vendoring a dependency

During certification or QA you sometimes need to patch a dependency locally — try a
fix, add logging, work around a bug — before the change lands upstream. The `vendor`
task extracts a library's `-sources.jar` into a `vendor/<name>/` sub-project and
redirects every reference to that module (direct **and** transitive) to the local
project via Gradle dependency substitution:

```bash
./gradlew vendor --lib jposee_txn   # <lib> is a key from gradle/libs.versions.toml [libraries]
./gradlew unvendor --lib jposee_txn # remove one vendored module
./gradlew unvendor                  # remove all vendored modules
```

`vendor` creates:

```
vendor/<name>/
├── build.gradle          # java-library, group/version, repositories + dependencies from the POM
├── .vendored             # marker: group:name:version
└── src/main/{java,resources}/...   # extracted from the sources jar
```

and adds `include ':vendor:<name>'` to `settings.gradle`.

Your `build.gradle` and `gradle/libs.versions.toml` are **never modified**: the version
catalog entry stays exactly as-is, and `resolutionStrategy.dependencySubstitution`
redirects the coordinate to the local project. Because the redirect happens at
resolution time, transitive references to the same module are caught too, and vendored
modules that depend on each other are wired together automatically.

`unvendor` removes the `vendor/<name>` directory and its `include` line, restoring the
build. To protect your local patches it refuses to run if the vendored content has been
modified since it was vendored (a content digest is recorded in `.vendored`); remove
the directory manually if you really mean to discard the changes. Re-sync your IDE
(or just run any Gradle task) after vendoring/unvendoring so the substitution is
picked up.

Caveat: a `-sources.jar` contains source only — no tests, no annotation-processed or
otherwise generated code. Modules that rely on code generation may need manual work in
the vendored sub-project before they build.

## Per-target excludes

If for some reason we want the plugin to exclude some files for a given target, we can add `<targetName>.exclude`.

 
