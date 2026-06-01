plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
    signing
    alias(libs.plugins.nmcp)
}

group = "tech.codingzen"
version = "2.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // compileOnly: IntelliJ-honoured @CheckReturnValue on Res.ok/Res.failure flags discarded
    // factory results (e.g. Res.failure(e) inside rail{}). Annotation is stripped at runtime —
    // the published artifact has zero runtime dependencies.
    compileOnly(libs.jsr305)
    testImplementation(kotlin("test"))
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