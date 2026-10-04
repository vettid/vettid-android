import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** Shared build constants for every module. */
object VettIdBuild {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 37
    const val MIN_SDK = 31
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Maps a Gradle path like `:feature:messages` to `com.vettid.feature.messages`. */
internal fun Project.vettIdNamespace(): String =
    "com.vettid" + path.replace(':', '.').replace('-', '_')
