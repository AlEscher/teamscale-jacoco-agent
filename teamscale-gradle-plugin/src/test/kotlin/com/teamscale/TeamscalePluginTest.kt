package com.teamscale

import com.squareup.moshi.Moshi
import com.teamscale.TestwiseCoverageReportAssert.Companion.assertThat
import com.teamscale.report.testwise.model.ETestExecutionResult
import com.teamscale.report.testwise.model.TestwiseCoverageReport
import okio.BufferedSource
import okio.buffer
import okio.source
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File

/**
 * You must run
 * ./gradlew pluginUnderTestMetadata
 * each time before running the tests in IntelliJ.
 */
class TeamscalePluginTest {

    companion object {

        /** Set this to true to enable debugging of the Gradle Plugin via port 5005. */
        private const val DEBUG_PLUGIN = false

        /** Set this to true to enable debugging of the impacted tests engine via port 5005. */
        private const val DEBUG_TEST_ENGINE = false
    }

    @field:TempDir
    lateinit var temporaryFolder: File

    @BeforeEach
    fun setup() {
        File("src/test/resources/calculator_groovy").copyRecursively(temporaryFolder)
    }

    @Test
    fun `teamscale plugin can be configured`() {
        assertThat(
            build(false, false, "clean", "tasks").output
        ).contains("SUCCESS")
    }

    @Test
    fun `unit tests can be executed normally`() {
        assertThat(
            build(
                true, false, "clean", "unitTest",
                "-PexcludeFailingTests=true"
            ).output
        ).contains("SUCCESS (18 tests, 12 successes, 0 failures, 6 skipped)")
    }

    @Test
    fun `impacted unit tests produce coverage`() {
        val build = build(
            true, true,
            "clean",
            "unitTest",
            "--impacted",
            "--run-all-tests"
        )
        assertThat(build.output).contains("FAILURE (21 tests, 14 successes, 1 failures, 6 skipped)")
            .doesNotContain("you did not provide all relevant class files")
        val testwiseCoverageReportFile =
            File(temporaryFolder, "build/reports/testwise-coverage/unitTest/Unit-Tests.json")
        assertThat(testwiseCoverageReportFile).exists()

        val source: BufferedSource = testwiseCoverageReportFile.source().buffer()
        val testwiseCoverageReport =
            Moshi.Builder().build().adapter(TestwiseCoverageReport::class.java).fromJson(source)
        assertThat(testwiseCoverageReport!!)
            .containsExecutionResult("com/example/project/IgnoredJUnit4Test/systemTest", ETestExecutionResult.SKIPPED)
            .containsExecutionResult("com/example/project/JUnit4Test/systemTest", ETestExecutionResult.PASSED)
            .containsExecutionResult(
                "com/example/project/JUnit5Test/withValueSource(String)",
                ETestExecutionResult.PASSED
            )
            .containsExecutionResult("com/example/project/FailingRepeatedTest/testRepeatedTest()", ETestExecutionResult.FAILURE)
            .containsExecutionResult("FibonacciTest/test[4]", ETestExecutionResult.PASSED)
            .containsCoverage(
                "com/example/project/JUnit4Test/systemTest",
                "com/example/project/Calculator.java",
                "13,16,20-22"
            )
            // 19 Tests because JUnit 5 parameterized tests are grouped
            .hasSize(19)
    }

    @Test
    fun `unit tests without server config produce coverage`() {
        val build = build(
            true, true,
            "clean",
            "unitTest",
            "-PwithoutServerConfig=true"
        )
        assertThat(build.output).contains("FAILURE (21 tests, 14 successes, 1 failures, 6 skipped)")
            .doesNotContain("you did not provide all relevant class files")
        val testwiseCoverageReportFile =
            File(temporaryFolder, "build/reports/testwise-coverage/unitTest/Unit-Tests.json")
        assertThat(testwiseCoverageReportFile).exists()

        val source = testwiseCoverageReportFile.source().buffer()
        val testwiseCoverageReport =
            Moshi.Builder().build().adapter(TestwiseCoverageReport::class.java).fromJson(source)
        assertThat(testwiseCoverageReport!!)
            .containsExecutionResult("com/example/project/IgnoredJUnit4Test/systemTest", ETestExecutionResult.SKIPPED)
            .containsExecutionResult("com/example/project/JUnit4Test/systemTest", ETestExecutionResult.PASSED)
            .containsExecutionResult(
                "com/example/project/JUnit5Test/withValueSource(String)",
                ETestExecutionResult.PASSED
            )
            .containsExecutionResult("com/example/project/FailingRepeatedTest/testRepeatedTest()", ETestExecutionResult.FAILURE)
            .containsExecutionResult("FibonacciTest/test[4]", ETestExecutionResult.PASSED)
            .containsCoverage(
                "com/example/project/JUnit4Test/systemTest",
                "com/example/project/Calculator.java",
                "13,16,20-22"
            )
            // 19 Tests because JUnit 5 parameterized tests are grouped
            .hasSize(19)
    }

    private fun build(executesTask: Boolean, expectFailure: Boolean, vararg arguments: String): BuildResult {
        val runnerArgs = arguments.toMutableList()
        val runner = GradleRunner.create()
        runnerArgs.add("--stacktrace")

        if (DEBUG_TEST_ENGINE || DEBUG_PLUGIN) {
            runner.withDebug(true)
            runnerArgs.add("--refresh-dependencies")
            runnerArgs.add("--debug")
        }
        if (executesTask && DEBUG_TEST_ENGINE) {
            runnerArgs.add("--debug-jvm")
        }

        runner
            .withProjectDir(temporaryFolder)
            .withPluginClasspath()
            .withArguments(runnerArgs)
            .withGradleVersion("6.5")

        if (DEBUG_PLUGIN) {
            runner.withDebug(true)
        }

        val buildResult =
            if (expectFailure) {
                runner.buildAndFail()
            } else {
                runner.build()
            }

        if (DEBUG_TEST_ENGINE || DEBUG_PLUGIN) {
            println(buildResult.output)
        }

        return buildResult
    }
}

