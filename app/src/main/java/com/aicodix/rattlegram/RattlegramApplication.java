package com.aicodix.rattlegram;

import android.app.Application;

public final class RattlegramApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Install before any Activity/service class (including its JNI initializer) loads.
        CrashLogger.install(this);
    }
}
