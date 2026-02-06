plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
    signing
    id("com.gradleup.nmcp") version "1.4.4"
}

group = "tech.codingzen"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
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
                name.set("Result-Kit")
                description.set("Functional error handling library for Kotlin with Railway-Oriented Programming support")
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