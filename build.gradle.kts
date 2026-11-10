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

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:3.6.1")
    implementation("io.ktor:ktor-server-netty:3.6.1")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.38")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

spotless {
    kotlin {
        ktlint()
    }
    kotlinGradle {
        ktlint()
    }
}
