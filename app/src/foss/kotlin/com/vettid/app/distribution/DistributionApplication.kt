package com.vettid.app.distribution

import android.app.Application

/** The `foss` distribution's application base (F-Droid): no push service, so no WorkManager and nothing to configure. */
abstract class DistributionApplication : Application()
