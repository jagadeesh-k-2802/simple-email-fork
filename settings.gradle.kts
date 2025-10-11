pluginManagement {
    repositories {
        google()
        jcenter()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        jcenter()
        maven { url = uri("https://repo1.maven.org/maven2/") }
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://artifactory.appodeal.com/appodeal-public/") }
        mavenCentral()
    }
}

include(":app", ":colorpicker")
