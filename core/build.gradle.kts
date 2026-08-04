plugins {
    bluemap.java
}

dependencies {
    api ( "de.bluecolored:bluemap-api" )

    api ( libs.aircompressor )
    api ( libs.bluenbt )
    api ( libs.caffeine )
    api ( libs.commons.dbcp2 )
    api ( libs.configurate.hocon )
    api ( libs.configurate.gson )
    api ( libs.lz4 )
}

tasks.register("zipResourceExtensions", type = Zip::class) {
    from(fileTree("src/main/resourceExtensions"))
    archiveFileName = "resourceExtensions.zip"
    destinationDirectory = file("src/main/resources/de/bluecolored/bluemap/")
}

tasks.processResources {
    dependsOn("zipResourceExtensions")

    val versionString = project.version.toString()
    val gitHashString = gitHash() + if (gitClean()) "" else " (dirty)"

    // What gets expanded into version.json is derived from git, not from a file, so Gradle sees no
    // changed input when only the version moves and keeps a stale version.json - which then travels
    // all the way into the shipped jar. CI never noticed (its workspace is always fresh), but a
    // local build hands BlueMap the version of whenever this task last ran. That matters beyond
    // cosmetics: WebFilesManager stamps the web root with VERSION + GIT_HASH to decide whether to
    // re-extract the bundled web-app, so a frozen version means the web-app silently stops updating.
    inputs.property("version", versionString)
    inputs.property("gitHash", gitHashString)

    from("src/main/resources") {
        include("de/bluecolored/bluemap/version.json")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        expand (
            "version" to versionString,
            "gitHash" to gitHashString,
        )
    }
}

tasks.getByName("sourcesJar") {
    dependsOn("zipResourceExtensions")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = "bluemap-${project.name}"
            version = project.version.toString()

            from(components["java"])

            versionMapping {
                usage("java-api") {
                    fromResolutionResult()
                }
            }
        }
    }
}
