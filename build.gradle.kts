import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

plugins {
    kotlin("jvm") version "2.4.20"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The version lives once in gradle.properties; the SDK reads it at runtime
// for the User-Agent and the conformance driver identity.
val generateVersionResource = tasks.register("generateVersionResource") {
    val sdkVersion = project.version.toString()
    val outputDir = layout.buildDirectory.dir("generated/resources/sdk-version")
    inputs.property("version", sdkVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("com/budgetbakers/partner/sdk-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$sdkVersion\n")
    }
}

sourceSets.main {
    resources.srcDir(generateVersionResource)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Jar>("conformanceJar") {
    description = "Runnable fat jar of the contract-test conformance driver."
    archiveFileName.set("partner-sdk-conformance.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "com.budgetbakers.partner.conformance.MainKt")
    }
    from(sourceSets.main.get().output)
    val runtimeClasspath = configurations.runtimeClasspath
    dependsOn(runtimeClasspath)
    from({ runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    publishToMavenCentral()
    // CI passes the key as ORG_GRADLE_PROJECT_signingInMemoryKey; local
    // publishToMavenLocal runs unsigned.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates("com.budgetbakers", "partner-sdk", version.toString())
    pom {
        name.set("BudgetBakers Partner SDK")
        description.set("BudgetBakers Partner API server SDK for Kotlin and Java: typed client, webhook verification, hosted connect sessions.")
        inceptionYear.set("2026")
        url.set("https://github.com/BudgetBakers/partner-sdk-kotlin")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("budgetbakers")
                name.set("BudgetBakers")
                email.set("integration@budgetbakers.com")
                organization.set("BudgetBakers s.r.o.")
                organizationUrl.set("https://budgetbakers.com")
            }
        }
        scm {
            url.set("https://github.com/BudgetBakers/partner-sdk-kotlin")
            connection.set("scm:git:https://github.com/BudgetBakers/partner-sdk-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/BudgetBakers/partner-sdk-kotlin.git")
        }
    }
}
