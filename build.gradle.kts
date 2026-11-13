plugins {
    id("org.sonarqube") version "5.1.0.4882"
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}

sonar {
    properties {
        property("sonar.projectKey", "sg-expense-tracker")
        property("sonar.projectName", "sg-expense-tracker")
        property("sonar.host.url", "http://localhost:9000")
        // Unit-test coverage from AGP's JaCoCo report (app:createDebugUnitTestCoverageReport).
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            "${rootDir}/app/build/reports/coverage/test/debug/report.xml",
        )
    }
}

// Make `./gradlew sonar` produce the coverage report first, so coverage is never stale or missing.
tasks.named("sonar") {
    dependsOn(":app:createDebugUnitTestCoverageReport")
}
