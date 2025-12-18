lint:
    gradle -Plint clean jar

plocal:
    gradle publishPluginMavenPublicationToMavenLocalRepository publishToMavenLocal

