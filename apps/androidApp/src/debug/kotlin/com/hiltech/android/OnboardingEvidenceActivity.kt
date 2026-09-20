package com.hiltech.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hiltech.shared.core.HiltechOnboardingState
import com.hiltech.shared.core.HiltechShell
import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.identity.IdentityOrganizationDto
import com.hiltech.shared.core.people.CertificationListDto
import com.hiltech.shared.core.people.EmployeeDocumentListDto
import com.hiltech.shared.core.people.OnboardingCaseDto
import com.hiltech.shared.core.people.OnboardingRequirementDto

class OnboardingEvidenceActivity :
    ComponentActivity() {
    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(savedInstanceState)

        setContent {
            HiltechShell(
                state =
                    HiltechShellState.SignedIn(
                        identity =
                            fixtureIdentity(),
                        onboarding =
                            HiltechOnboardingState(
                                ownCase =
                                    fixtureOnboarding(),
                                ownDocuments =
                                    EmployeeDocumentListDto(
                                        items =
                                            emptyList(),
                                        correlationId =
                                            "phase3-proof",
                                    ),
                                ownCertifications =
                                    CertificationListDto(
                                        items =
                                            emptyList(),
                                        correlationId =
                                            "phase3-proof",
                                    ),
                            ),
                    ),
            )
        }
    }
}

private fun fixtureIdentity():
    IdentityBootstrapDto =
    IdentityBootstrapDto(
        identityId =
            "11111111-1111-1111-1111-111111111111",
        identityStatus = "ACTIVE",
        identityVersion = 1,
        primaryOrganizationId =
            "22222222-2222-2222-2222-222222222222",
        organizations =
            listOf(
                IdentityOrganizationDto(
                    membershipId =
                        "33333333-3333-3333-3333-333333333333",
                    organizationId =
                        "22222222-2222-2222-2222-222222222222",
                    organizationCode =
                        "HILTECH",
                    displayName =
                        "HILTECH",
                    organizationType =
                        "HILTECH",
                    membershipType =
                        "EMPLOYEE",
                    roleLabel =
                        "Technician",
                    primary = true,
                    membershipVersion = 1,
                ),
            ),
        teams = emptyList(),
    )

private fun fixtureOnboarding():
    OnboardingCaseDto =
    OnboardingCaseDto(
        caseId =
            "44444444-4444-4444-4444-444444444444",
        organizationId =
            "22222222-2222-2222-2222-222222222222",
        employeeId =
            "55555555-5555-5555-5555-555555555555",
        employeeCode = "EMP-014",
        employeeDisplayName =
            "أحمد محمد",
        employeeState =
            "PREBOARDING",
        employeeVersion = 3,
        policyConfigRevisionId =
            "66666666-6666-6666-6666-666666666666",
        policyCode =
            "FIELD-EMPLOYEE",
        policyRevisionNumber = 2,
        state = "OPEN",
        requirements =
            listOf(
                OnboardingRequirementDto(
                    requirementKey =
                        "CONTACT_MOBILE",
                    label =
                        "رقم الموبايل",
                    responsibility =
                        "EMPLOYEE",
                    blocking = true,
                    status =
                        "SATISFIED",
                    sourceStateCode =
                        "PRESENT",
                    sortOrder = 10,
                ),
                OnboardingRequirementDto(
                    requirementKey =
                        "NATIONAL_ID_DOCUMENT",
                    label =
                        "صورة بطاقة الرقم القومي",
                    responsibility =
                        "EMPLOYEE",
                    blocking = true,
                    status =
                        "NEEDS_EMPLOYEE",
                    actionCode =
                        "SUBMIT_DOCUMENT",
                    sourceStateCode =
                        "MISSING",
                    sortOrder = 20,
                ),
                OnboardingRequirementDto(
                    requirementKey =
                        "WORKFORCE_ASSIGNMENT",
                    label =
                        "تعيين الفريق والمدير المباشر",
                    responsibility =
                        "HILTECH",
                    blocking = true,
                    status =
                        "WAITING_HILTECH",
                    sourceStateCode =
                        "MISSING",
                    sortOrder = 30,
                ),
            ),
        blockingSatisfied = false,
        caseVersion = 4,
        startedAt =
            "2026-09-20T04:00:00Z",
    )
