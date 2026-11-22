package com.hiltech.android

import android.app.Application
import android.content.Context
import com.hiltech.android.identity.AndroidIdentityRuntime
import com.hiltech.android.sync.AndroidSessionTokenProvider
import com.hiltech.android.sync.AndroidSyncRuntime
import com.hiltech.android.sync.AndroidVerifiedActorProvider
import com.hiltech.shared.core.field.FieldExecutionQueue
import com.hiltech.shared.core.field.FieldCompletionQueue
import com.hiltech.shared.core.field.FieldEvidenceQueue
import com.hiltech.shared.core.field.FieldEngineeringTestOfflineQueue
import com.hiltech.shared.core.field.FieldMaterialUsageQueue
import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.android.field.AndroidFieldEvidenceFiles
import com.hiltech.android.field.AndroidFieldEvidenceSyncRuntime
import com.hiltech.android.field.AndroidFieldEngineeringTestSyncRuntime
import com.hiltech.android.field.FieldEngineeringTestOnlineIdentity
import com.hiltech.android.field.FieldEvidenceOnlineIdentity
import com.hiltech.shared.core.local.buildHiltechLocalDatabase
import com.hiltech.shared.core.local.getAndroidDatabaseBuilder
import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.procurement.PurchaseRequirementApiClient
import com.hiltech.shared.core.work.WorkApiClient
import com.hiltech.shared.core.network.createPlatformHttpClient
import com.hiltech.shared.core.people.HrDocumentsApiClient
import com.hiltech.shared.core.people.OnboardingApiClient
import com.hiltech.shared.core.people.PeopleApiClient
import com.hiltech.shared.core.people.WorkforceAssignmentApiClient
import com.hiltech.shared.core.warehouse.WarehouseApiClient
import com.hiltech.shared.core.work.FieldAssignedWorkApiClient
import com.hiltech.shared.core.work.FieldAssignedWorkOfflineRepository
import com.hiltech.shared.core.work.FieldOfflineReadCache
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

    val warehouseApi by lazy { WarehouseApiClient(productApi) }
    val procurementApi by lazy { PurchaseRequirementApiClient(productApi) }
    val procurementWorkApi by lazy { WorkApiClient(productApi) }

    val fieldAssignedWorkApi:
        FieldAssignedWorkApiClient by lazy {
        FieldAssignedWorkApiClient(
            productApi,
        )
    }

    val fieldExecutionQueue by lazy {
        FieldExecutionQueue(localDatabase)
    }

    val fieldCompletionQueue by lazy {
        FieldCompletionQueue(localDatabase)
    }

    val fieldMaterialUsageQueue by lazy {
        FieldMaterialUsageQueue(localDatabase)
    }

    val fieldEvidenceQueue by lazy {
        FieldEvidenceQueue(localDatabase)
    }

    val fieldEngineeringTestOfflineQueue by lazy {
        FieldEngineeringTestOfflineQueue(localDatabase)
    }

    val fieldEvidenceFiles by lazy {
        AndroidFieldEvidenceFiles(this)
    }

    private val evidenceApi by lazy {
        EvidenceApiClient(productApi, httpClient)
    }

    val fieldEvidenceSyncRuntime by lazy {
        AndroidFieldEvidenceSyncRuntime(
            db = localDatabase,
            files = fieldEvidenceFiles,
            assignedWork = fieldAssignedWorkApi,
            evidence = evidenceApi,
            onlineIdentity = FieldEvidenceOnlineIdentity {
                identityRuntime.restoreIdentity()?.identityId
            },
            installationId = installationId,
        )
    }

    val fieldEngineeringTestSyncRuntime by lazy {
        AndroidFieldEngineeringTestSyncRuntime(
            db = localDatabase,
            assignedWork = fieldAssignedWorkApi,
            onlineIdentity = FieldEngineeringTestOnlineIdentity {
                identityRuntime.restoreIdentity()?.identityId
            },
            installationId = installationId,
        )
    }

    val fieldOfflineReadCache by lazy {
        FieldOfflineReadCache(localDatabase)
    }

    val fieldAssignedWorkOfflineRepository by lazy {
        FieldAssignedWorkOfflineRepository(
            fieldAssignedWorkApi,
            fieldOfflineReadCache,
        )
    }

    val syncRuntime: AndroidSyncRuntime by lazy {
        AndroidSyncRuntime(
            database = localDatabase,
            httpClient = httpClient,
            apiBaseUrl = BuildConfig.HILTECH_API_BASE_URL,
            sessionTokenProvider = AndroidSessionTokenProvider {
                identityRuntime.currentAccessToken()
            },
            verifiedActorProvider = AndroidVerifiedActorProvider {
                identityRuntime.restoreIdentity()?.identityId
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
