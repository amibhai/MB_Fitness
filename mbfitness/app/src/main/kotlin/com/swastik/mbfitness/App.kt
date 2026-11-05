package com.swastik.mbfitness

import android.app.Application

/**
 * Manual DI container (SPEC 5.3). Everything added here must stay lazy: this class also runs
 * when a boot receiver starts the process before the first unlock, when the database and
 * settings can't be opened yet.
 */
class App : Application()
