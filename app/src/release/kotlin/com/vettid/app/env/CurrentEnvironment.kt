package com.vettid.app.env

import com.vettid.core.data.env.AppEnvironment

/** This build talks to production. */
internal val currentEnvironment: AppEnvironment = ProductionEnvironment
