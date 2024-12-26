lint:
    gradle -Plint clean jar

idea:
    gradle idea

# Publish to Maven Local
plocal:
    gradle publishPluginMavenPublicationToMavenLocalRepository
