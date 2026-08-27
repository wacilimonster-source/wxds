package com.example.litreader

import android.app.Application
import com.example.litreader.data.db.AppDatabase

class App : Application() {
    val database by lazy { AppDatabase.build(this) }
}
