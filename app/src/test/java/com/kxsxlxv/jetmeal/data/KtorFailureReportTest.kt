package com.kxsxlxv.jetmeal.data

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.*
import org.junit.Test

class KtorFailureReportTest {
    @Test fun classifiesUnderlyingDnsFailureAndHidesRequestAndExceptionMessage() {
        val report = safeKtorFailureLine(
            "/rest/v1/nutrition_targets",
            IOException("Authorization: Bearer secret-token at https://private.example",
                UnknownHostException("private.example")))
        assertTrue(report.contains("layer=Ktor"))
        assertTrue(report.contains("route=Diary"))
        assertTrue(report.contains("cause=UnknownHost"))
        assertTrue(report.contains("kind=Dns"))
        assertTrue(report.contains("root=UnknownHostException"))
        assertFalse(report.contains("private.example"))
        assertFalse(report.contains("secret-token"))
        assertFalse(report.contains("nutrition_targets"))
    }

    @Test fun preservesTimeoutClassWithoutPersonalDetails() {
        val report = safeKtorFailureLine("/rest/v1/diary_entries",
            SocketTimeoutException("https://private.example?access_token=secret"))
        assertTrue(report.contains("cause=Timeout"))
        assertTrue(report.contains("kind=Timeout"))
        assertTrue(report.contains("exception=SocketTimeoutException"))
        assertFalse(report.contains("private.example"))
        assertFalse(report.contains("secret"))
    }

    @Test fun unknownPathIsOtherAndNotPersisted() {
        val report = safeKtorFailureLine("/rest/v1/private-data/user-id", IOException("private"))
        assertTrue(report.contains("route=Other"))
        assertFalse(report.contains("private-data"))
        assertFalse(report.contains("user-id"))
    }
}
