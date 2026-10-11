package com.vettid.app.distribution

/**
 * What a distribution does for its push service at process start (`Application.onCreate`, before any service of the
 * app runs). `full` (Google Play, VettID's own releases): Firebase is initialised in a build that carries the
 * Firebase options. `foss` (F-Droid): nothing, the build has no push service (README "F-Droid build").
 *
 * Each distribution's source set (`src/full`, `src/foss`) has a `DistributionApplication`, the application's base
 * class, and a `DistributionModule`, which binds this and the `PushProvider`.
 */
fun interface PushStartup {
    fun start()
}
