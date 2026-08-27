pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        providers.gradleProperty("featureApiRepository").orNull?.let { repositoryPath ->
            exclusiveContent {
                forRepository {
                    maven {
                        name = "featureApiLocal"
                        url = uri(repositoryPath)
                    }
                }
                filter {
                    includeModule("dev.evestaticmapplanner", "feature-api")
                }
            }
        }

        providers.gradleProperty("featureApiGitHubPackagesRepository").orNull?.let { repositoryUrl ->
            exclusiveContent {
                forRepository {
                    maven {
                        name = "featureApiGitHubPackages"
                        url = uri(repositoryUrl)

                        val repositoryUsername = providers.gradleProperty("githubPackagesUsername")
                            .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                            .orNull
                        val repositoryToken = providers.gradleProperty("githubPackagesToken")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                            .orNull
                        if (!repositoryUsername.isNullOrBlank() || !repositoryToken.isNullOrBlank()) {
                            credentials {
                                username = repositoryUsername
                                password = repositoryToken
                            }
                        }
                    }
                }
                filter {
                    includeModule("dev.evestaticmapplanner", "feature-api")
                }
            }
        }

        mavenCentral {
            content {
                excludeGroup("dev.evestaticmapplanner")
            }
        }
    }
}

rootProject.name = "EVE-Sovereignty-Pack"
