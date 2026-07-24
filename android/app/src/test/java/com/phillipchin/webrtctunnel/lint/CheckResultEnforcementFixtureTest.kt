package com.phillipchin.webrtctunnel.lint

import com.android.tools.lint.checks.CheckResultDetector
import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

/**
 * FIX8 P2-001-A: "Add a committed negative fixture/rule test for at least one ignored
 * authoritative result; do not rely only on a historical temporary edit." This project enforces
 * "never discard a Result from a snapshot/restore/mutation/persist function" via Android Lint's
 * built-in `CheckResultDetector` (`@CheckResult`/`@get:CheckResult`, promoted to error severity
 * in `android/app/build.gradle.kts`'s `lint { }` block). That protection is otherwise entirely
 * reactive — it only ever fires against whatever `@CheckResult` call sites this project happens
 * to have today. This test runs the REAL detector (not a reimplementation) against a small,
 * self-contained fixture — independent of this project's own production code — so the
 * enforcement mechanism itself has a permanent, committed proof it still works, rather than a
 * comment recording that someone manually verified it once.
 */
class CheckResultEnforcementFixtureTest {
    private val annotationStub =
        java(
            """
            package androidx.annotation;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.METHOD})
            public @interface CheckResult {
                String suggest() default "";
            }
            """,
        ).indented()

    @Test
    fun ignoredCheckResultCallIsFlaggedByTheRealDetector() {
        lint()
            .files(
                annotationStub,
                kotlin(
                    """
                    package test
                    import androidx.annotation.CheckResult
                    class Sample {
                        @CheckResult
                        fun mutate(): Result<Unit> = Result.success(Unit)
                        fun caller() {
                            mutate()
                        }
                    }
                    """,
                ).indented(),
            )
            .issues(CheckResultDetector.CHECK_RESULT)
            .run()
            // CheckResultDetector.CHECK_RESULT's own built-in default severity is Warning; this
            // project promotes it to error via android/app/build.gradle.kts's `lint { error +=
            // "CheckResult" }` DSL block — a plain Gradle severity override that a standalone
            // TestLintTask fixture run (independent of this module's build config) does not
            // inherit. What matters here is that the real detector fires at all on the ignored
            // call — proven at its default severity.
            .expectWarningCount(1)
    }

    @Test
    fun consumedCheckResultCallIsClean() {
        lint()
            .files(
                annotationStub,
                kotlin(
                    """
                    package test
                    import androidx.annotation.CheckResult
                    class Sample {
                        @CheckResult
                        fun mutate(): Result<Unit> = Result.success(Unit)
                        fun caller() {
                            val outcome = mutate()
                            outcome.getOrThrow()
                        }
                    }
                    """,
                ).indented(),
            )
            .issues(CheckResultDetector.CHECK_RESULT)
            .run()
            .expectClean()
    }
}
