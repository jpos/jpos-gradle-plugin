In order to use the jPOS APP Plugin

1. Add to your `build.gradle`

```
plugins {
    id 'org.jpos.jposapp' version '0.0.1-SNAPSHOT'
}
```

2. Add to your `settings.gradle`

```
pluginManagement {
    repositories {
        mavenLocal()
        maven { url = uri('https://jpos.org/maven') }
        gradlePluginPortal()
    }
}
```
