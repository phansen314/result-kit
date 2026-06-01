pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "result-kit"

// result-kit-ksp is intentionally NOT included in the build: the @TraceContext KSP
// processor is shelved (kept on disk under result-kit-ksp/) and not shipped in this
// release. Re-add ":result-kit-ksp" here to build/publish it in a future version.
include("result-kit")
