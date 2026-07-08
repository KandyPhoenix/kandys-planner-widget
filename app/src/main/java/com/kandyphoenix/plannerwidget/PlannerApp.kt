package com.kandyphoenix.plannerwidget

import android.app.Application

class PlannerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WorkScheduler.schedulePeriodic(this)
    }
}
