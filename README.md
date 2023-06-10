In order to use the jPOS APP Plugin

1. Add to your `build.gradle`

```
plugins {
    id 'org.jpos.jposapp' version '0.0.3'
}
```

2. Add to your `settings.gradle`

```
pluginManagement {
    repositories {
        maven { url = uri('https://jpos.org/maven') }
        gradlePluginPortal()
    }
}
```

See [Gradle Plugin Repository](https://plugins.gradle.org/plugin/org.jpos.jposapp) for latest version.

