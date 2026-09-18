package com.hiltech.android

import android.app.Application
import android.content.Context
import com.hiltech.android.sync.AndroidSyncRuntime
import com.hiltech.android.sync.BootstrapSessionTokenProvider
import com.hiltech.shared.core.local.buildHiltechLocalDatabase
import com.hiltech.shared.core.local.getAndroidDatabaseBuilder
import com.hiltech.shared.core.network.createPlatformHttpClient
import java.util.UUID

class HiltechApplication : Application() {
    private val localDatabase by lazy {
        buildHiltechLocalDatabase(
            getAndroidDatabaseBuilder(this),
        )
    }

    private val httpClient by lazy {
        createPlatformHttpClient()
    }

    private val installationId by lazy {
        AndroidInstallationIdStore(this).getOrCreate()
    }

    val syncRuntime: AndroidSyncRuntime by lazy {
        AndroidSyncRuntime(
            database = localDatabase,
            httpClient = httpClient,
            apiBaseUrl = BuildConfig.HILTECH_API_BASE_URL,
            sessionTokenProvider = BootstrapSessionTokenProvider(),
            installationId = installationId,
            clientVersion = BuildConfig.VERSION_NAME,
        )
    }
}

private class AndroidInstallationIdStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(
        "hiltech_installation",
        Context.MODE_PRIVATE,
    )

    fun getOrCreate(): String {
        val existing = preferences.getString(INSTALLATION_ID_KEY, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }

        val created = UUID.randomUUID().toString()
        preferences.edit()
            .putString(INSTALLATION_ID_KEY, created)
            .apply()
        return created
    }

    private companion object {
        const val INSTALLATION_ID_KEY = "installation_id"
    }
}
