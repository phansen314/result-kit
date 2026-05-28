plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
    signing
    alias(libs.plugins.nmcp)
}

group = "tech.codingzen"
version = "1.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":result-kit"))
    compileOnly(libs.ksp.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlin.compile.testing.ksp)
    testImplementation(libs.ksp.api)
    // Needed for runtime tests that compile-and-invoke wrappers around suspend methods.
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    explicitApi()
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

java {
    withSourcesJar()
    withJavadocJar()
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Result-Kit KSP")
                description.set("KSP annotation processor for Result-Kit @TraceContext traced-wrapper generation")
                url.set("https://github.com/phansen314/result-kit")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }

                developers {
                    developer {
                        id.set("phansen314")
                        name.set("phansen314")
                        email.set("codingzen314@gmail.com")
                    }
                }

                scm {
                    url.set("https://github.com/phansen314/result-kit")
                    connection.set("scm:git:https://github.com/phansen314/result-kit.git")
                    developerConnection.set("scm:git:https://github.com/phansen314/result-kit.git")
                }
            }
        }
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}

nmcp {
    publishAllPublicationsToCentralPortal {
        username = providers.gradleProperty("mavenCentralUsername")
        password = providers.gradleProperty("mavenCentralPassword")
        publishingType = "AUTOMATIC"
    }
}
