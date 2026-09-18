package com.hiltech.spike.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
fun HiltechRtlAdaptiveApp(
    forceRtl: Boolean? = null,
    onLayoutResolved: (AdaptiveMode) -> Unit = {},
) {
    val inherited = LocalLayoutDirection.current
    val direction = when (forceRtl) {
        true -> LayoutDirection.Rtl
        false -> LayoutDirection.Ltr
        null -> inherited
    }

    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val mode = adaptiveMode(maxWidth.value.toInt())

                    LaunchedEffect(mode) {
                        onLayoutResolved(mode)
                    }

                    when (mode) {
                        AdaptiveMode.STACKED -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ProjectPane()
                                PayrollPane()
                                AssetPane()
                            }
                        }

                        AdaptiveMode.SPLIT -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(18.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    ProjectPane()
                                    PayrollPane()
                                }

                                Column(
                                    modifier = Modifier.width(300.dp),
                                ) {
                                    AssetPane()
                                }
                            }
                        }

                        AdaptiveMode.WIDE -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(20.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Column(
                                    modifier = Modifier.width(330.dp),
                                ) {
                                    ProjectPane()
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                ) {
                                    PayrollPane()
                                }

                                Column(
                                    modifier = Modifier.width(320.dp),
                                ) {
                                    AssetPane()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectPane() {
    Panel("المشروعات / Projects") {
        Text("مشروع القاهرة", fontWeight = FontWeight.Bold)
        Text(mixedArabicProjectLabel())
        Text("الحالة: يحتاج مراجعة • Status: Needs review")
        Text("المسؤول التالي: أحمد • Waiting on: Ahmed")
        Divider()
        Text("الموقع: مبنى رئيسي • Site A")
        Text("المرحلة: اختبار وتسليم • Test & Handover")
    }
}

@Composable
private fun PayrollPane() {
    Panel("المرتبات / Payroll") {
        Text("سبتمبر 2026 • September 2026", fontWeight = FontWeight.Bold)
        Text("الموظف: أحمد حسن • " + ltrIsolate("EMP-00421"))
        Text("صافي: " + ltrIsolate("EGP 18,450.00"))
        Text("فرق: " + ltrIsolate("+450.00"))
        Text("الإصدار: " + ltrIsolate("v4"))
        Divider()
        Text("المصدر: حضور + إضافي + خصومات")
        Text("Source: attendance + overtime + deductions")
    }
}

@Composable
private fun AssetPane() {
    Panel("الأصل / Asset") {
        Text("جهاز فلوك • " + ltrIsolate("Fluke-03"), fontWeight = FontWeight.Bold)
        Text("الرقم: " + ltrIsolate("SN-FLK-883104"))
        Text("الحالة: متاح • AVAILABLE")
        Text("IP: " + ltrIsolate("10.20.30.4"))
        Text("المعايرة: " + ltrIsolate("2026-12-14"))
        Divider()
        Text("الموقع: المخزن الرئيسي")
        Text("Custody: No active custodian")
    }
}

@Composable
private fun Panel(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}
