package com.family.dialer

import android.app.Application
import com.family.dialer.data.AppDatabase

class App : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }
}
