plugins {
    kotlin("jvm") version "2.4.21"
    application
    id("com.diffplug.spotless") version "8.11.0"
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "com.paulrule.mathapi.ApplicationKt"
}

val junitVersion = "6.1.3"
val assertjVersion = "3.27.7"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:3.6.1")
    implementation("io.ktor:ktor-server-netty:3.6.1")
    implementation("io.ktor:ktor-server-status-pages:3.6.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.2")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.38")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
    testImplementation("io.ktor:ktor-server-test-host:3.6.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Black-box HTTP tests against BASE_URL (Java :8080 or Kotlin :8081). Not part of `check`, so `build` needs no service.
testing {
    suites {
        register<JvmTestSuite>("contractTest") {
            useJUnitJupiter(junitVersion)
            dependencies {
                implementation("org.assertj:assertj-core:$assertjVersion")
                implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
            }
            targets.all {
                testTask.configure {
                    providers.gradleProperty("record").orNull?.let { systemProperty("record", it) }
                    systemProperty(
                        "goldenFile",
                        layout.projectDirectory
                            .file("src/contractTest/resources/golden/responses.json")
                            .asFile.path,
                    )
                    systemProperty(
                        "goldenActualFile",
                        layout.buildDirectory
                            .file("golden-actual.json")
                            .get()
                            .asFile.path,
                    )
                    outputs.upToDateWhen { false } // the service under test is outside Gradle's view
                }
            }
        }
    }
}

spotless {
    kotlin {
        ktlint()
    }
    kotlinGradle {
        ktlint()
    }
}
