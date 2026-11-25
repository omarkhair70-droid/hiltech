package com.hiltech.shared.core.identity

import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.design.HiltechLanguage

/** Presentation only: recovery never changes the source's identity or access decision. */
internal enum class IdentityErrorKind { Failure, AccessDenied, Configuration }

internal enum class IdentityRecoveryAction { SignIn, Reauthenticate, Retry, SignOut, None }

internal data class IdentityErrorPresentation(
    val title: String,
    val message: String,
    val primaryAction: IdentityRecoveryAction,
    val primaryLabel: String?,
    val secondaryAction: IdentityRecoveryAction?,
    val secondaryLabel: String?,
    val supportCode: String?,
)

/** Raw provider/server messages are deliberately absent from this presentation boundary. */
internal fun identityErrorPresentation(
    state: HiltechShellState,
    language: HiltechLanguage,
): IdentityErrorPresentation? = when (state) {
    is HiltechShellState.Failure -> identityErrorPresentation(state.code, IdentityErrorKind.Failure, language)
    is HiltechShellState.AccessDenied -> identityErrorPresentation(state.code, IdentityErrorKind.AccessDenied, language)
    is HiltechShellState.ConfigurationRequired -> identityErrorPresentation(null, IdentityErrorKind.Configuration, language)
    else -> null
}

internal fun identityErrorPresentation(
    code: String?,
    kind: IdentityErrorKind,
    language: HiltechLanguage,
): IdentityErrorPresentation {
    fun present(
        titleAr: String,
        titleEn: String,
        messageAr: String,
        messageEn: String,
        action: IdentityRecoveryAction,
    ): IdentityErrorPresentation {
        val secondary = IdentityRecoveryAction.SignOut.takeIf {
            kind == IdentityErrorKind.AccessDenied && action != IdentityRecoveryAction.SignOut &&
                action != IdentityRecoveryAction.None
        }
        return IdentityErrorPresentation(
            title = language.text(titleAr, titleEn),
            message = language.text(messageAr, messageEn),
            primaryAction = action,
            primaryLabel = identityRecoveryLabel(action, language),
            secondaryAction = secondary,
            secondaryLabel = secondary?.let { identityRecoveryLabel(it, language) },
            supportCode = if (kind == IdentityErrorKind.Configuration) null else identitySupportCode(code),
        )
    }

    if (kind == IdentityErrorKind.Configuration) return present(
        "التطبيق يحتاج إلى إعداد", "App setup is needed",
        "هذه النسخة غير جاهزة للاتصال بحسابات الشركة. اطلب من مسؤول النظام إكمال إعدادها، ثم أعد فتح HILTECH.",
        "This copy is not ready to connect to your company's accounts. Ask your administrator to complete its setup, then reopen HILTECH.",
        IdentityRecoveryAction.None,
    )

    return when (code) {
        "OIDC_ATTEMPT_MISSING", "OIDC_CODE_MISSING", "OIDC_AUTHORIZATION_ERROR" -> present(
            "لم يكتمل تسجيل الدخول", "Sign-in was not completed",
            "ابدأ تسجيل الدخول من التطبيق، وأكمل الخطوات في المتصفح، ثم عد إلى HILTECH.",
            "Start sign-in from the app, complete the steps in your browser, then return to HILTECH.",
            IdentityRecoveryAction.SignIn,
        )
        "OIDC_CALLBACK_INVALID", "OIDC_STATE_MISMATCH", "OIDC_CODE_EXCHANGE_FAILED",
        "OIDC_TOKEN_TYPE_UNSUPPORTED", "OIDC_TOKEN_RESPONSE_INVALID", "OIDC_ID_TOKEN_MISSING" -> present(
            "تعذر تأكيد تسجيل الدخول", "Sign-in could not be verified",
            "لم نتمكن من التحقق من نتيجة تسجيل الدخول. ابدأ محاولة جديدة من التطبيق. إذا تكررت المشكلة، أعطِ المسؤول رمز المرجع.",
            "We could not verify the sign-in result. Start a new attempt from the app. If this continues, give your administrator the reference code.",
            IdentityRecoveryAction.SignIn,
        )
        "UNAUTHENTICATED", "SESSION_EXPIRED", "SESSION_NOT_ACTIVE", "SESSION_REVOKED",
        "OIDC_REFRESH_UNAVAILABLE", "OIDC_REFRESH_FAILED" -> present(
            "يلزم تسجيل الدخول مجددًا", "Sign in again",
            "الجلسة الحالية لم تعد متاحة. سجّل الدخول مجددًا ليتحقق HILTECH من وصولك الحالي.",
            "The current session is no longer available. Sign in again so HILTECH can check your current access.",
            IdentityRecoveryAction.SignIn,
        )
        "REAUTH_REQUIRED", "MFA_REQUIRED" -> present(
            "أكد تسجيل دخولك", "Confirm your sign-in",
            "يحتاج هذا الإجراء إلى تأكيد هويتك. أكمل خطوات التأكيد في المتصفح بالحساب نفسه.",
            "This action needs you to confirm your identity. Complete the confirmation in your browser using the same account.",
            IdentityRecoveryAction.Reauthenticate,
        )
        "REAUTH_PROOF_INVALID", "REAUTH_PROOF_STALE", "REAUTH_AUTH_TIME_MISSING",
        "REAUTH_SUBJECT_MISMATCH", "REAUTH_SESSION_MISMATCH" -> present(
            "لم يكتمل تأكيد الهوية", "Identity confirmation was not completed",
            "تعذر التحقق من تأكيد الهوية لهذه الجلسة. أعد التأكيد بالحساب نفسه، ثم تابع الإجراء بعد نجاحه.",
            "We could not verify the identity confirmation for this session. Confirm again using the same account, then continue after it succeeds.",
            IdentityRecoveryAction.Reauthenticate,
        )
        "IDENTITY_NOT_PROVISIONED" -> present(
            "حساب HILTECH غير مرتبط بعد", "Your HILTECH account is not linked yet",
            "حساب تسجيل الدخول غير مرتبط بعد بوصولك إلى HILTECH. اطلب من المسؤول إكمال إعداد حسابك.",
            "Your sign-in account is not yet linked to HILTECH access. Ask your administrator to finish setting up your account.",
            IdentityRecoveryAction.SignOut,
        )
        "IDENTITY_NOT_ACTIVE" -> present(
            "الحساب غير نشط", "Account is inactive",
            "الوصول بهذا الحساب متوقف حاليًا. تواصل مع المسؤول لمراجعة حالته قبل محاولة الدخول مجددًا.",
            "Access with this account is currently inactive. Ask your administrator to review its status before signing in again.",
            IdentityRecoveryAction.SignOut,
        )
        "NO_ACTIVE_ORGANIZATION_MEMBERSHIP" -> present(
            "الوصول إلى الشركة غير متاح", "Company access is unavailable",
            "حسابك غير مرتبط حاليًا بشركة يمكنه الدخول إليها. اطلب من المسؤول مراجعة انضمامك إلى الشركة.",
            "Your account currently has no active company access. Ask your administrator to review your company membership.",
            IdentityRecoveryAction.SignOut,
        )
        "DEVICE_REVOKED" -> present(
            "تم إيقاف وصول هذا الجهاز", "This device's access has been stopped",
            "لا يمكن متابعة العمل من هذا الجهاز حاليًا. تواصل مع المسؤول لمراجعة وصول الجهاز.",
            "You cannot continue from this device right now. Ask your administrator to review this device's access.",
            IdentityRecoveryAction.SignOut,
        )
        "DEVICE_INSTALLATION_CONFLICT" -> present(
            "الجهاز مرتبط بحساب آخر", "Device is linked to another account",
            "هذه النسخة من التطبيق مرتبطة بحساب آخر. سجّل الخروج واطلب من المسؤول مراجعة الوصول إلى هذا الجهاز.",
            "This app installation is linked to another account. Sign out and ask your administrator to review access to this device.",
            IdentityRecoveryAction.SignOut,
        )
        "DEVICE_NOT_REGISTERED" -> present(
            "يلزم التحقق من الجهاز", "Device verification is needed",
            "سجّل الدخول من هذا الجهاز للتحقق من وصوله إلى HILTECH. إذا استمرت المشكلة، تواصل مع المسؤول.",
            "Sign in on this device to verify its HILTECH access. If the problem continues, contact your administrator.",
            IdentityRecoveryAction.SignIn,
        )
        "PERMISSION_DENIED", "OBJECT_NOT_VISIBLE", "ACTION_NOT_ALLOWED_IN_CONTEXT" -> present(
            "الوصول غير متاح", "Access is unavailable",
            "المعلومات أو الإجراء المطلوب غير متاح لهذا الحساب. تواصل مع المسؤول إذا كان ضمن مسؤولياتك.",
            "The requested information or action is unavailable to this account. Contact your administrator if it is part of your responsibilities.",
            IdentityRecoveryAction.SignOut,
        )
        "NETWORK_UNAVAILABLE" -> present(
            "تعذر الاتصال", "Could not connect",
            "تحقق من اتصال الإنترنت ثم أعد المحاولة. لم نتمكن من تأكيد حالة الحساب الحالية.",
            "Check your internet connection, then try again. We could not confirm the account's current status.",
            IdentityRecoveryAction.Retry,
        )
        "TEMPORARY_UNAVAILABLE", "INTERNAL_ERROR", "HTTP_ERROR", "AUTHORIZATION_UNAVAILABLE" -> present(
            "الخدمة غير متاحة مؤقتًا", "Service is temporarily unavailable",
            "تعذر إكمال طلب الحساب حاليًا. أعد المحاولة بعد قليل. إذا استمرت المشكلة، أعطِ المسؤول رمز المرجع.",
            "We could not complete the account request right now. Try again shortly. If this continues, give your administrator the reference code.",
            IdentityRecoveryAction.Retry,
        )
        "RATE_LIMITED" -> present(
            "انتظر قليلًا قبل المحاولة", "Wait before trying again",
            "تم إرسال محاولات كثيرة في وقت قصير. انتظر قليلًا ثم أعد المحاولة.",
            "Too many attempts were made in a short time. Wait a little, then try again.",
            IdentityRecoveryAction.Retry,
        )
        "SESSION_NOT_FOUND", "DEVICE_NOT_FOUND", "STATE_CONFLICT", "VERSION_CONFLICT" -> present(
            "تغيرت معلومات الأمان", "Security information has changed",
            "لم تعد معلومات الجلسة أو الجهاز مطابقة للطلب. أعد تحميل المعلومات قبل متابعة العمل.",
            "The session or device information no longer matches the request. Reload the information before continuing.",
            IdentityRecoveryAction.Retry,
        )
        "OIDC_ISSUER_MISMATCH", "OIDC_AUTHORIZATION_CODE_UNAVAILABLE", "OIDC_PKCE_S256_UNAVAILABLE",
        "PROVIDER_SESSION_REFERENCE_MISSING" -> present(
            "خدمة تسجيل الدخول تحتاج مراجعة", "Sign-in service needs attention",
            "تعذر التحقق من خدمة تسجيل الدخول. اطلب من مسؤول النظام مراجعة إعداد الاتصال، وأعطِه رمز المرجع.",
            "We could not verify the sign-in service. Ask your administrator to review the connection setup and give them the reference code.",
            IdentityRecoveryAction.None,
        )
        "IDENTITY_RUNTIME_COMPONENT_MISSING" -> present(
            "يلزم تحديث HILTECH", "HILTECH needs an update",
            "هذه النسخة تفتقد جزءًا لازمًا لتسجيل الدخول. اطلب تثبيت تحديث HILTECH المعتمد ثم أعد فتح التطبيق.",
            "This installation is missing a component needed for sign-in. Ask for the approved HILTECH update to be installed, then reopen the app.",
            IdentityRecoveryAction.None,
        )
        "DEVICE_CONTEXT_REQUIRED", "INVALID_DEVICE_INSTALLATION_ID", "INVALID_INSTALLATIONID",
        "INVALID_DEVICE_PLATFORM", "INVALID_DEVICE_METADATA", "INVALID_SESSION_ID", "INVALID_DEVICE_ID",
        "MALFORMED_REQUEST", "REJECTED_VALIDATION" -> present(
            "تعذر التحقق من طلب التطبيق", "The app request could not be verified",
            "تعذر التحقق من بيانات طلب الأمان. اطلب من المسؤول مراجعة إعداد هذه النسخة من HILTECH، وأعطِه رمز المرجع.",
            "We could not verify the security request details. Ask your administrator to review this HILTECH installation and give them the reference code.",
            IdentityRecoveryAction.None,
        )
        "OIDC_SYSTEM_BROWSER_UNAVAILABLE" -> present(
            "تعذر فتح متصفح تسجيل الدخول", "Could not open the sign-in browser",
            "تحقق من وجود متصفح متاح على الجهاز ثم ابدأ تسجيل الدخول مجددًا. إذا تعذر فتحه، تواصل مع المسؤول.",
            "Check that a browser is available on this device, then start sign-in again. If it cannot open, contact your administrator.",
            IdentityRecoveryAction.SignIn,
        )
        "OIDC_LOGOUT_FAILED" -> present(
            "تعذر تأكيد تسجيل الخروج", "Sign-out could not be confirmed",
            "لم نتمكن من تأكيد تسجيل الخروج لدى خدمة الحسابات. سجّل الدخول مجددًا إذا أردت متابعة استخدام HILTECH.",
            "We could not confirm sign-out with the account service. Sign in again if you want to continue using HILTECH.",
            IdentityRecoveryAction.SignIn,
        )
        else -> if (kind == IdentityErrorKind.AccessDenied) present(
            "الوصول غير متاح", "Access is unavailable",
            "لم نتمكن من تأكيد إتاحة الوصول لهذا الحساب. تواصل مع المسؤول وأعطِه رمز المرجع.",
            "We could not confirm access for this account. Contact your administrator and give them the reference code.",
            IdentityRecoveryAction.SignOut,
        ) else present(
            "تعذر إكمال عملية الحساب", "The account request could not be completed",
            "أعد المحاولة. إذا استمرت المشكلة، تواصل مع المسؤول وأعطِه رمز المرجع.",
            "Try again. If the problem continues, contact your administrator and give them the reference code.",
            IdentityRecoveryAction.Retry,
        )
    }
}

private fun identityRecoveryLabel(action: IdentityRecoveryAction, language: HiltechLanguage): String? = when (action) {
    IdentityRecoveryAction.SignIn -> language.text("تسجيل الدخول من جديد", "Start sign-in again")
    IdentityRecoveryAction.Reauthenticate -> language.text("تأكيد تسجيل الدخول", "Confirm sign-in")
    IdentityRecoveryAction.Retry -> language.text("إعادة المحاولة", "Retry")
    IdentityRecoveryAction.SignOut -> language.text("تسجيل الخروج", "Sign out")
    IdentityRecoveryAction.None -> null
}

private val safeIdentitySupportCode = Regex("[A-Z][A-Z0-9_]{0,79}")

/** A malformed code must not turn the support-reference slot into another raw-message channel. */
private fun identitySupportCode(code: String?): String =
    code?.takeIf { safeIdentitySupportCode.matches(it) } ?: "IDENTITY_ERROR"
