plugins {
    kotlin("jvm") version "1.9.25"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":result-kit"))
    implementation("com.google.devtools.ksp:symbol-processing-api:1.9.25-1.0.20")

    testImplementation(kotlin("test"))
    testImplementation("com.github.tschuchortdev:kotlin-compile-testing-ksp:1.6.0")
    testImplementation("com.google.devtools.ksp:symbol-processing-api:1.9.25-1.0.20")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    explicitApi()
    jvmToolchain(21)
}
