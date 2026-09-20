package com.hiltech.android

import android.app.Application
import android.content.Context
import com.hiltech.android.identity.AndroidIdentityRuntime
import com.hiltech.android.sync.AndroidSessionTokenProvider
import com.hiltech.android.sync.AndroidSyncRuntime
import com.hiltech.shared.core.local.buildHiltechLocalDatabase
import com.hiltech.shared.core.local.getAndroidDatabaseBuilder
import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.createPlatformHttpClient
import com.hiltech.shared.core.people.HrDocumentsApiClient
import com.hiltech.shared.core.people.OnboardingApiClient
import com.hiltech.shared.core.people.PeopleApiClient
import com.hiltech.shared.core.people.WorkforceAssignmentApiClient
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

    val installationId by lazy {
        AndroidInstallationIdStore(this).getOrCreate()
    }

    val identityRuntime: AndroidIdentityRuntime by lazy {
        AndroidIdentityRuntime(
            context = this,
            httpClient = httpClient,
            apiBaseUrl = BuildConfig.HILTECH_API_BASE_URL,
            oidcIssuer = BuildConfig.HILTECH_OIDC_ISSUER_URI,
            oidcClientId = BuildConfig.HILTECH_OIDC_CLIENT_ID,
            installationId = installationId,
            clientVersion = BuildConfig.VERSION_NAME,
        )
    }

    private val productApi: HiltechApiClient by lazy {
        HiltechApiClient(
            client = httpClient,
            baseUrl = BuildConfig.HILTECH_API_BASE_URL,
            accessTokenProvider = {
                identityRuntime.currentAccessToken()
            },
            correlationIdProvider = {
                UUID.randomUUID().toString()
            },
        )
    }

    val peopleApi: PeopleApiClient by lazy {
        PeopleApiClient(productApi)
    }

    val workforceAssignmentApi:
        WorkforceAssignmentApiClient by lazy {
        WorkforceAssignmentApiClient(
            productApi,
        )
    }

    val onboardingApi: OnboardingApiClient by lazy {
        OnboardingApiClient(productApi)
    }

    val hrDocumentsApi:
        HrDocumentsApiClient by lazy {
        HrDocumentsApiClient(productApi)
    }

    val syncRuntime: AndroidSyncRuntime by lazy {
        AndroidSyncRuntime(
            database = localDatabase,
            httpClient = httpClient,
            apiBaseUrl = BuildConfig.HILTECH_API_BASE_URL,
            sessionTokenProvider = AndroidSessionTokenProvider {
                identityRuntime.currentAccessToken()
            },
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
