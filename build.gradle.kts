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

configure<CheckstyleExtension> {
    toolVersion = "8.13"
    isIgnoreFailures = false
    isShowViolations = true
}

val ci by extra { project.hasProperty("ci") }

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}

tasks.register<Checkstyle>("checkstyle") {
    configFile = file("checkstyle.xml")
    source("app/src/main/java")
    include("**/*.java")
    exclude("**/gen/**")
    classpath = files()
}
