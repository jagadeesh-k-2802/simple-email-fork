plugins {
    checkstyle
}

val kotlin_version by extra("1.7.20")

buildscript {
    repositories {
        google()
        jcenter()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:7.4.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.7.20")
    }
}

val ci by extra { project.hasProperty("ci") }

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

// checkstyle {
//     toolVersion = "8.13"
//     configFile = file("checkstyle.xml")
//     isIgnoreFailures = false
//     isShowViolations = true
// }

configure<CheckstyleExtension> {
    toolVersion = "8.13"
    configFile = rootProject.file("checkstyle.xml")
    sourceSets = sourceSets
    // sourceSets
    isIgnoreFailures = false
    isShowViolations = true
    //include("**/*.java")
    //classpath = files()
}
