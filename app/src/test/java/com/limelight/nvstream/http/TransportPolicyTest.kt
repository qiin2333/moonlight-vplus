package com.limelight.nvstream.http

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class TransportPolicyTest {
    private fun notice(s: TransportPolicyStatus = status(), sequence: String = "1") = TransportPolicyNotification(
        s.sessionId,s.connectionEpoch,sequence,s.controlEpoch,s.accepted.revision,s.confirmed?.revision,
        s.receipts.filter { it.firstSentFrame != null }.maxWithOrNull { a,b ->
            TransportPolicyCodec.compareIdentity(a.policy.revision,b.policy.revision) }?.policy?.revision,
        s.receipts.filter { it.firstSentFrame != null }.maxWithOrNull { a,b ->
            TransportPolicyCodec.compareIdentity(a.policy.revision,b.policy.revision) }?.firstSentFrame,
        s.accepted.source,s.receipts.find { it.policy.revision == s.accepted.revision }?.failure ?: "none",
        (if(s.encoderReady) 8 else 0) or (if(s.pending) 4 else 0) or (if(s.confirmed != null) 1 else 0) or
            (if(s.receipts.any { it.firstSentFrame != null }) 2 else 0))

    @Test fun noticeValidationRejectsMalformedAndContradictoryUnsignedProgress() {
        val n=notice()
        assertTrue(n.valid())
        assertEquals("18446744073709551615",n.connectionEpoch)
        for(bad in listOf(n.copy(sequence="0"),n.copy(connectionEpoch="18446744073709551616"),
            n.copy(flags=128),n.copy(source="unknown"),n.copy(failure="unknown"),
            n.copy(appliedRevision="2"),n.copy(firstSentRevision="1",firstSentFrame="0"))) assertFalse(bad.valid())
        val applied=n.copy(appliedRevision="2",firstSentRevision="2",firstSentFrame="0",flags=11)
        assertTrue(applied.valid())
        assertFalse(applied.copy(flags=15).valid())
    }
    @Test fun noticeWaterlinesRejectOldIdentityReorderingAndStoppedRevival() {
        val n=notice()
        val next=n.copy(sequence="2")
        assertFalse(n.advances(n))
        assertTrue(next.advances(n))
        assertFalse(next.copy(sessionId="1").advances(n))
        assertFalse(next.copy(connectionEpoch="42").advances(n))
        assertFalse(next.copy(source="googcc").advances(n))
        assertTrue(next.copy(flags=4).advances(n))
        val stopped=next.copy(flags=16)
        assertTrue(stopped.advances(n))
        assertFalse(next.copy(sequence="3").advances(stopped))
    }
    @Test fun noticeCoalescingBlocksWritesUntilTheQueryCatchesUp() {
        val f=Fixture()
        try {
            val next=notice().copy(acceptedRevision="3")
            val prior=f.service.view.status
            assertTrue(f.service.receiveNotification(next))
            assertFalse(f.service.receiveNotification(next))
            assertFalse(f.service.receiveNotification(next.copy(sequence="2",connectionEpoch="42")))
            assertFalse(f.service.view.canSubmit)
            assertFalse(f.service.setModes(true,true,6000))
            assertEquals(1,f.executor.scheduled.size)
            f.executor.drainScheduled()
            assertEquals(prior,f.service.view.status)
            assertFalse(f.service.view.canSubmit)
            f.http.current=status("3")
            assertTrue(f.service.receiveNotification(next.copy(sequence="2")))
            assertTrue(f.service.receiveNotification(next.copy(sequence="3")))
            assertEquals(1,f.executor.scheduled.size)
            f.executor.drainScheduled()
            assertTrue(f.service.view.canSubmit)
            assertEquals("3",f.service.view.status!!.accepted.revision)
            assertTrue(f.http.writes.isEmpty())
            f.service.stop()
            assertFalse(f.service.receiveNotification(next.copy(sequence="4")))
            assertNull(f.service.view.status)
        } finally { f.service.stop() }
    }
    @Test fun notificationDuringHttpCannotPublishAnOlderActionableResponse() {
        val f=Fixture()
        try {
            val old=f.service.view.status
            f.http.onQuery={ f.service.receiveNotification(notice().copy(acceptedRevision="3")) }
            f.executor.tick()
            assertEquals(old,f.service.view.status)
            assertFalse(f.service.view.canSubmit)
            assertNotNull(f.service.view.error)
            assertTrue(f.http.writes.isEmpty())
            f.http.onQuery=null
            f.http.current=status("3")
            f.executor.drainScheduled()
            assertTrue(f.service.view.canSubmit)
        } finally { f.service.stop() }
    }
    @Test fun notificationRefreshIntervalIsBoundedAndReadOnlyDoesNotInheritHostBudget() {
        val http=FakeTransport()
        val executor=ManualExecutor()
        var now=10L
        val service=TransportPolicyService("4294967295",{http},executor,readOnly=true,
            expectedEpoch=http.current.connectionEpoch,nanoClock={now})
        try {
            service.start(); executor.tick()
            now+=100
            assertTrue(service.receiveNotification(notice(http.current)))
            assertEquals(249999900L,executor.delays.single())
            assertFalse(service.setManualBudget(6000))
            executor.drainScheduled()
            assertFalse(service.view.canSubmit)
            assertNull(service.stopAndGetReconnectBudget())
            assertTrue(http.writes.isEmpty())
        } finally { service.stop() }
    }
    @Test fun boundedReceiptHistoryPermitsLaterQueriesWithoutInventingCurrentFirstSend() {
        val s=status().copy(confirmed=status().accepted,pending=false)
        val historic=notice(s).copy(appliedRevision="2",firstSentRevision="1",firstSentFrame="0",flags=11)
        assertTrue(historic.valid())
        assertTrue(historic.satisfiedBy(s))
        assertFalse(historic.copy(firstSentRevision="2").satisfiedBy(s))
        assertFalse(historic.copy(firstSentFrame=null).valid())
    }
    @Test fun currentReadinessMayFallWithoutErasingHistoricalSdkOrSendReceipts() {
        val base=status().copy(confirmed=status().accepted,pending=false,
            receipts=listOf(TransportPolicyReceipt(status().accepted,true,"0","none")))
        val ready=notice(base)
        val rebuilding=notice(base.copy(encoderReady=false),"2")
        assertTrue(rebuilding.valid())
        assertTrue(rebuilding.advances(ready))
        assertTrue(ready.satisfiedBy(base.copy(encoderReady=false)))
        assertTrue(rebuilding.satisfiedBy(base.copy(encoderReady=false)))
        assertEquals(ready.appliedRevision,rebuilding.appliedRevision)
        assertEquals("0",rebuilding.firstSentFrame)
        assertFalse(rebuilding.satisfiedBy(base.copy(confirmed=null)))
    }
    @Test fun nativeProviderIsReadAfterBlockingHttpBeforePublishingTheResponse() {
        val http=FakeTransport()
        val executor=ManualExecutor()
        var native: TransportPolicyNotification?=null
        val service=TransportPolicyService("4294967295",{http},executor,
            expectedEpoch=http.current.connectionEpoch,notificationProvider={native})
        try {
            http.onQuery={ native=notice().copy(acceptedRevision="3") }
            service.start(); executor.tick()
            assertNull(service.view.status)
            assertFalse(service.view.canSubmit)
            assertNotNull(service.view.error)
            http.onQuery=null
            http.current=status("3")
            executor.drainScheduled()
            assertEquals("3",service.view.status!!.accepted.revision)
            assertTrue(service.view.canSubmit)
            assertTrue(http.writes.isEmpty())
        } finally { service.stop() }
    }
    @Test fun statisticsV2AcceptsHighRateCountsWithAMandatoryLifetime() {
        val statistics = JSONObject(javaClass.getResource("/network-statistics-v1.json")!!.readText())
            .getJSONArray("cases").getJSONObject(0).getJSONObject("statistics")
            .put("version",2).put("receivedPackets","26731").put("missingPackets","270")
            .put("sampledPackets","27001").put("rawLossPercent",270*100.0/27001)
            .put("freshnessRemainingUs","500000")
        val epoch = statistics.getString("connectionEpoch")
        val parsed = TransportPolicyCodec.networkStatistics(statistics,epoch)
        assertEquals(26731,parsed.receivedPackets)
        assertEquals(270,parsed.missingPackets)
        assertEquals(500000,parsed.freshnessRemainingUs)
        statistics.remove("freshnessRemainingUs")
        assertThrows(IllegalArgumentException::class.java) { TransportPolicyCodec.networkStatistics(statistics,epoch) }
        statistics.put("freshnessRemainingUs","500000").put("version",1)
        assertThrows(IllegalArgumentException::class.java) { TransportPolicyCodec.networkStatistics(statistics,epoch) }
    }

    @Test fun localStatisticsExpiryDoesNotEraseThePrimaryReceipt() {
        val fixtures = JSONObject(javaClass.getResource("/network-statistics-v1.json")!!.readText())
        val s = status()
        val j = fixtures.getJSONArray("cases").getJSONObject(0).getJSONObject("statistics")
            .put("connectionEpoch",s.connectionEpoch).put("freshnessRemainingUs","500000")
        val statistics = TransportPolicyCodec.networkStatistics(j,s.connectionEpoch)
        val view = TransportPolicyView(status = s.copy(networkStatistics = statistics), refreshing = false, networkDeadlineNs = 500000000)
        assertNotNull(view.networkStatistics(499999999)!!.rawLossPercent)
        assertNull(view.networkStatistics(500000000)!!.rawLossPercent)
        assertEquals("feedback_stale",view.networkStatistics(1000000000)!!.reason)
        assertTrue(view.canSubmit)
        assertNotNull(view.status!!.networkStatistics!!.rawLossPercent)
        assertNull(view.copy(networkDeadlineNs = null).networkStatistics(0)!!.rawLossPercent)
        assertNull(view.copy(error = "HTTP failed").networkStatistics(0)!!.rawLossPercent)
        assertThrows(IllegalArgumentException::class.java) {
            TransportPolicyCodec.networkStatistics(j.put("freshnessRemainingUs","1000001"),s.connectionEpoch)
        }
    }
    @Test fun readOnlyQueriesPinTheConnectionAndDeductInFlightTimeBeforePublishing() {
        val http = FakeTransport()
        val executor = ManualExecutor()
        var now = 0L
        val fixture = JSONObject(javaClass.getResource("/network-statistics-v1.json")!!.readText())
            .getJSONArray("cases").getJSONObject(0).getJSONObject("statistics")
            .put("connectionEpoch",http.current.connectionEpoch).put("freshnessRemainingUs","10000")
        http.current = http.current.copy(networkStatistics = TransportPolicyCodec.networkStatistics(fixture,http.current.connectionEpoch))
        http.onQuery = { now += 20000000 }
        val service = TransportPolicyService("4294967295",{ http },executor,readOnly = true,
            expectedEpoch = http.current.connectionEpoch,nanoClock = { now })
        var publications = 0
        service.listener = { view ->
            publications++
            assertNull(view.networkStatistics(now)!!.rawLossPercent)
        }
        try {
            service.start(); executor.tick()
            assertEquals(1,publications)
            assertEquals(http.current.connectionEpoch,http.queries.first())
            assertFalse(service.view.canSubmit)
            assertFalse(service.setModes(true,true,5000))
            assertFalse(service.setManualBudget(5000))
            assertTrue(http.writes.isEmpty())
        } finally { service.stop() }
        assertNull(service.view.networkStatistics(now))
    }

    @Test fun actualNetworkStatisticsPreserveHostAvailabilityAndControlBoundary() {
        val fixtures = JSONObject(javaClass.getResource("/network-statistics-runtime-v1.json")!!.readText())
        val cases = fixtures.getJSONArray("cases")
        assertEquals(11, cases.length())
        var valid = false
        var unavailable = false
        for (index in 0 until cases.length()) {
            val source = cases.getJSONObject(index).getJSONObject("status")
            val parsed = TransportPolicyCodec.status(source)
            val statistics = parsed.networkStatistics!!
            val raw = source.getJSONObject("networkStatistics")
            assertEquals(parsed.connectionEpoch, statistics.connectionEpoch)
            assertEquals(raw.getString("reason"), statistics.reason)
            assertEquals(raw.getString("committedPackets"), statistics.committedPackets)
            assertFalse(parsed.liveControlAvailable)
            assertEquals("legacy", parsed.accepted.source)
            if (statistics.reason == "valid") {
                valid = true
                assertEquals(raw.getDouble("rawLossPercent"), statistics.rawLossPercent!!, 0.0)
            } else {
                unavailable = true
                assertNull(statistics.rawLossPercent)
            }
        }
        assertTrue(valid && unavailable)
    }

    @Test fun sharedNetworkStatisticsKeepCoverageAndIdentityExact() {
        val fixtures = JSONObject(javaClass.getResource("/network-statistics-v1.json")!!.readText())
        val cases = fixtures.getJSONArray("cases")
        assertEquals(19,cases.length())
        for (index in 0 until cases.length()) {
            val item = cases.getJSONObject(index)
            val result = runCatching { TransportPolicyCodec.networkStatistics(item.getJSONObject("statistics"),fixtures.getString("expectedEpoch")) }
            assertEquals(item.getString("name"),item.getBoolean("accepted"),result.isSuccess)
            result.getOrNull()?.let {
                assertEquals("18446744073709551615",it.committedPackets)
                assertEquals("9007199254740993",it.committedIpBytes)
                if (item.isNull("loss")) assertNull(it.rawLossPercent)
                else assertEquals(item.getDouble("loss"),it.rawLossPercent!!,0.0)
            }
        }
    }
    @Test fun optionalNetworkStatisticsNeverCorruptValidControlReceipts() {
        val samples = pcReconnectSamples()
        val status = samples.getJSONObject(0)
        assertNull(TransportPolicyCodec.status(status).networkStatistics)
        val fixtures = JSONObject(javaClass.getResource("/network-statistics-v1.json")!!.readText())
        val statistics = fixtures.getJSONArray("cases").getJSONObject(0).getJSONObject("statistics")
            .put("connectionEpoch",status.getString("connectionEpoch"))
        status.put("networkStatistics",statistics)
        assertNotNull(TransportPolicyCodec.status(status).networkStatistics)
        val revision = TransportPolicyCodec.status(status).accepted.revision
        statistics.put("rawLossPercent",0.0)
        val parsed = TransportPolicyCodec.status(status)
        assertEquals(revision,parsed.accepted.revision)
        assertNull(parsed.networkStatistics)
    }
    @Test fun actualPcReconnectCaptureKeepsAndroidMirrorsScopedToTheirConnection() {
        // Exact phase-31 paired HTTPS samples; this exercises the Android codec/controller,
        // not an Android media session or its Activity lifecycle.
        val samples = pcReconnectSamples()
        assertEquals(44, samples.length())
        val epochs = (0 until samples.length()).map { TransportPolicyCodec.status(samples.getJSONObject(it)) }
            .groupBy { it.connectionEpoch }.values.toList()
        assertEquals(listOf(26, 18), epochs.map { it.size })
        assertTrue(epochs.flatten().all { it.sessionId == "1" })
        val frozenBudgets = mutableListOf<Int?>()
        for ((index, captured) in epochs.withIndex()) {
            val http = FakeTransport().apply { current = captured.first() }
            val executor = ManualExecutor()
            val service = TransportPolicyService("1", { http }, executor)
            try {
                assertNull(service.view.requestRevision)
                assertNull(service.view.status)
                service.start()
                for (sample in captured) {
                    http.current = sample
                    executor.tick()
                    assertNull(service.view.error)
                    assertEquals(sample, service.view.status)
                    assertNull(service.view.requestRevision)
                }
                val last = captured.last()
                if (index == 0) {
                    // The host reused sessionId=1 after its restart. Epoch is the boundary.
                    http.current = epochs[1].first()
                    executor.tick()
                    assertFalse(service.view.canSubmit)
                    assertEquals(last, service.view.status)
                }
                frozenBudgets += service.stopAndGetReconnectBudget()
                assertNull(service.view.status)
                assertNull(service.view.requestRevision)
                executor.tick()
                assertNull(service.view.status)
                assertEquals(last.accepted.totalKbps, service.stopAndGetReconnectBudget())
                assertTrue(http.writes.isEmpty())
            } finally { service.stop() }
        }
        assertEquals(listOf(4500, 4000), frozenBudgets)
    }

    @Test fun capturedHostStatusParsesWithoutLosingIdentityOrApplicationEvidence() {
        for (name in listOf("initial", "final")) {
            val json = JSONObject(javaClass.getResource("/transport-policy-$name.json")!!.readText())
            val s = TransportPolicyCodec.status(json)
            assertEquals(json.getString("connectionEpoch"), s.connectionEpoch)
            assertEquals(json.getString("acceptedRevision"), s.accepted.revision)
            assertTrue(s.liveControlAvailable)
            assertNotNull(s.confirmed)
            assertTrue(s.receipts.any { it.encoderApplied })
            if (name == "final") assertTrue(s.receipts.any { it.encoderApplied && it.firstSentFrame != null })
        }
    }
    private fun pcReconnectSamples(): org.json.JSONArray {
        // Store identical host replies once while preserving every sample and its order.
        val capture = JSONObject(javaClass.getResource("/transport-policy-pc-reconnect.json")!!.readText())
        assertEquals(1, capture.getInt("version"))
        val snapshots = capture.getJSONArray("snapshots")
        val timeline = capture.getJSONArray("timeline")
        return org.json.JSONArray().apply {
            for (i in 0 until timeline.length()) {
                // Tests mutate replies: each occurrence must have independent ownership.
                put(JSONObject(snapshots.getJSONObject(timeline.getInt(i)).toString()))
            }
        }
    }

    private fun policy(revision: String = "2", epoch: String = "1") = JSONObject("""{
        "revision":"$revision","controlEpoch":"$epoch","controlSource":"manual",
        "budgetBasis":"normalized","encoderCeilingKbps":null,
        "wireBudgetKbps":12000,"encoderKbps":8000,"fec":{"base":10,"key":30,"recovery":20},
        "reservesKbps":{"otherTraffic":500,"repair":0,"probe":0,"videoOverhead":500},
        "automaticControl":{"automaticBitrate":true,"automaticFec":false,"maximumTotalKbps":15000,"activationEpoch":"0"}
    }""")
    private fun json(revision: String = "2", epoch: String = "1", connection: String = "18446744073709551615"): JSONObject {
        val p = policy(revision, epoch)
        return JSONObject().put("version", 2).put("sessionId", "4294967295")
            .put("connectionEpoch", connection).put("controlEpoch", epoch).put("controlSource", "manual")
            .put("acceptedRevision", revision).put("encoderAppliedRevision", JSONObject.NULL)
            .put("accepted", p).put("confirmed", JSONObject.NULL).put("encoderReady", true).put("pending", true)
            .put("stopped", false).put("experimentalLiveControlAvailable", true)
            .put("receipts", org.json.JSONArray().put(JSONObject(p.toString()).put("encoderApplied", false)
                .put("firstSentFrame", JSONObject.NULL).put("failure", "none")))
    }
    private fun status(revision: String = "2", epoch: String = "1", connection: String = "18446744073709551615") =
        TransportPolicyCodec.status(json(revision, epoch, connection))

    @Test fun fullUnsignedIdentitiesRemainExactAndMalformedValuesAreRejected() {
        assertEquals("18446744073709551615", status().connectionEpoch)
        for (bad in listOf("", "01", " 1", "1e3", "-1", "18446744073709551616", "１２")) {
            assertFalse(bad, TransportPolicyCodec.isIdentity(bad))
        }
        for (bad in listOf<Any>(42, 4.2, "0", "4294967296")) {
            assertThrows(IllegalArgumentException::class.java) { TransportPolicyCodec.status(json().put("sessionId", bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransportPolicyCodec.status(json().put("experimentalLiveControlAvailable", "true"))
        }
    }
    @Test fun exactRequestsRetainIdentityAndManualReservesWithoutReadOnlyFields() {
        val s = status()
        for (bitrate in listOf(false, true)) for (fec in listOf(false, true)) {
            val body = TransportPolicyCodec.control(s, "test-001", bitrate, fec, 7000)
            assertEquals(setOf("version", "sessionId", "connectionEpoch", "controlEpoch", "expectedRevision", "requestId",
                "automaticBitrate", "automaticFec", "maximumTotalKbps"), body.keys().asSequence().toSet())
            assertEquals(bitrate, body.get("automaticBitrate"))
            assertEquals(fec, body.get("automaticFec"))
            assertEquals(s.connectionEpoch, body.get("connectionEpoch"))
        }
        val manual = TransportPolicyCodec.manual(s, "manual-1", 6500)
        assertEquals(500, manual.getJSONObject("budget").getInt("otherTrafficKbps"))
        assertEquals(30, manual.getJSONObject("fec").getInt("key"))
        assertFalse(manual.has("automaticControl"))
        assertThrows(IllegalArgumentException::class.java) { TransportPolicyCodec.control(s, "bad id", true, false, 5000) }
        assertThrows(IllegalArgumentException::class.java) { TransportPolicyCodec.manual(s, "manual", 800001) }
    }
    @Test fun numericCoercionAndInconsistentReceiptRevisionsFailClosed() {
        val j = json()
        j.getJSONObject("accepted").put("encoderKbps", 8000.0)
        assertThrows(IllegalArgumentException::class.java) { TransportPolicyCodec.status(j) }
        val mismatch = json().put("acceptedRevision", "3")
        assertThrows(IllegalArgumentException::class.java) { TransportPolicyCodec.status(mismatch) }
        val duplicate = json()
        duplicate.getJSONArray("receipts").put(duplicate.getJSONArray("receipts").get(0))
        assertThrows(IllegalArgumentException::class.java) { TransportPolicyCodec.status(duplicate) }
    }
    @Test fun acceptedSdkAndFirstSendRemainSeparate() {
        val f = Fixture()
        try {
            assertTrue(f.service.setModes(true, true, 7000))
            assertEquals("3", f.service.view.requestRevision)
            assertFalse(f.service.view.requestReceipt!!.encoderApplied)
            assertNull(f.service.view.requestReceipt!!.firstSentFrame)
            val j = json("3", "2")
            val p = j.getJSONObject("accepted")
            j.put("confirmed", p).put("encoderAppliedRevision", "3").put("pending", false)
            val r = j.getJSONArray("receipts").getJSONObject(0).put("encoderApplied", true)
            f.http.current = TransportPolicyCodec.status(j)
            f.executor.tick()
            assertTrue(f.service.view.requestReceipt!!.encoderApplied)
            assertNull(f.service.view.requestReceipt!!.firstSentFrame)
            r.put("firstSentFrame", "18446744073709551615")
            f.http.current = TransportPolicyCodec.status(j)
            f.executor.tick()
            assertEquals("18446744073709551615", f.service.view.requestReceipt!!.firstSentFrame)
        } finally { f.service.stop() }
    }
    @Test fun conflictAndUnknownOutcomeNeverRetryTheWrite() {
        for (failure in listOf(IOException("reply lost"), HostHttpResponseException(409, "conflict"))) {
            val f = Fixture()
            try {
                f.http.failure = failure
                assertTrue(f.service.setModes(false, true, 7000))
                assertEquals(1, f.http.writes.size)
                assertNull(f.service.view.requestRevision)
                assertNotNull(f.service.view.requestError)
                f.executor.tick()
                f.executor.tick()
                assertEquals(1, f.http.writes.size)
                assertTrue(f.service.view.canSubmit)
                assertNotNull(f.service.view.requestError)
                assertTrue(f.http.queries.drop(1).all { it == "18446744073709551615" })
            } finally { f.service.stop() }
        }
    }
    @Test fun staleAndReconnectedResponsesCannotReplaceCurrentConnection() {
        val f = Fixture()
        try {
            f.http.current = status("4", "3")
            f.executor.tick()
            for (bad in listOf(status("3", "2"), status("5", "4", "42"), status("5", "2"))) {
                f.http.current = bad
                f.executor.tick()
                assertEquals("4", f.service.view.status!!.accepted.revision)
                assertFalse(f.service.view.canSubmit)
            }
        } finally { f.service.stop() }
    }
    @Test fun sameRevisionPolicyMutationCannotReplaceTheConfirmedNetworkBudget() {
        val f = Fixture()
        try {
            val j = json()
            j.getJSONObject("accepted").put("wireBudgetKbps", 6000)
            j.getJSONArray("receipts").getJSONObject(0).put("wireBudgetKbps", 6000)
            f.http.current = TransportPolicyCodec.status(j)
            f.executor.tick()
            assertEquals(12000, f.service.view.status!!.accepted.totalKbps)
            assertFalse(f.service.view.canSubmit)
        } finally { f.service.stop() }
    }
    @Test fun appliedAndFirstSendReceiptsCannotRegressOrChange() {
        for (regression in listOf("application", "frame")) {
            val f = Fixture()
            try {
                val j = json()
                j.put("confirmed", j.getJSONObject("accepted")).put("encoderAppliedRevision", "2")
                j.getJSONArray("receipts").getJSONObject(0).put("encoderApplied", true).put("firstSentFrame", "5")
                f.http.current = TransportPolicyCodec.status(j)
                f.executor.tick()
                if (regression == "application") {
                    j.put("confirmed", JSONObject.NULL).put("encoderAppliedRevision", JSONObject.NULL)
                    j.getJSONArray("receipts").getJSONObject(0).put("encoderApplied", false).put("firstSentFrame", JSONObject.NULL)
                } else j.getJSONArray("receipts").getJSONObject(0).put("firstSentFrame", "6")
                f.http.current = TransportPolicyCodec.status(j)
                f.executor.tick()
                assertTrue(f.service.view.status!!.receipts[0].encoderApplied)
                assertEquals("5", f.service.view.status!!.receipts[0].firstSentFrame)
                assertFalse(f.service.view.canSubmit)
            } finally { f.service.stop() }
        }
    }
    @Test fun automaticActivationBudgetBasisAndEncoderCeilingAreImmutable() {
        for (field in listOf("activationEpoch", "budgetBasis", "encoderCeilingKbps")) {
            val f = Fixture()
            try {
                val j = json()
                for (p in listOf(j.getJSONObject("accepted"), j.getJSONArray("receipts").getJSONObject(0))) {
                    when (field) {
                        "activationEpoch" -> p.getJSONObject("automaticControl").put(field, "18446744073709551615")
                        "budgetBasis" -> p.put(field, "legacy")
                        else -> p.put(field, 10000)
                    }
                }
                f.http.current = TransportPolicyCodec.status(j)
                f.executor.tick()
                assertFalse(field, f.service.view.canSubmit)
                assertEquals("0", f.service.view.status!!.accepted.automatic!!.activationEpoch)
                assertEquals("normalized", f.service.view.status!!.accepted.budgetBasis)
                assertNull(f.service.view.status!!.accepted.encoderCeilingKbps)
            } finally { f.service.stop() }
        }
    }
    @Test fun inconsistentPoliciesInsideOneResponseAndMissingNullableStateAreRejected() {
        val j = json()
        j.getJSONArray("receipts").getJSONObject(0).put("wireBudgetKbps", 6000)
        assertThrows(IllegalArgumentException::class.java) { TransportPolicyCodec.status(j) }
        for (field in listOf("encoderCeilingKbps", "automaticControl")) {
            val missing = json()
            missing.getJSONObject("accepted").remove(field)
            assertThrows(IllegalArgumentException::class.java) { TransportPolicyCodec.status(missing) }
        }
        val missing = json()
        missing.remove("confirmed")
        assertThrows(IllegalArgumentException::class.java) { TransportPolicyCodec.status(missing) }
    }
    @Test fun stopFreezesAcceptedBudgetWithoutAutomaticAuthorityOrRequestState() {
        val f = Fixture()
        assertEquals(15000, f.service.view.status!!.accepted.automatic!!.maximumKbps)
        assertEquals(12000, f.service.stopAndGetReconnectBudget())
        assertEquals(12000, f.service.stopAndGetReconnectBudget())
        assertNull(f.service.view.status)
        assertNull(f.service.view.requestRevision)
        assertFalse(f.service.view.canSubmit)
        assertFalse(f.service.setModes(true, true, 7000))
    }
    @Test fun stopBeforeAQueryDoesNotInventABudget() {
        val executor = ManualExecutor()
        val service = TransportPolicyService("4294967295", { FakeTransport() }, executor)
        service.start()
        assertNull(service.stopAndGetReconnectBudget())
        executor.tick()
        assertNull(service.view.status)
        assertNull(service.stopAndGetReconnectBudget())
    }
    @Test fun lateQueryCannotReplaceTheFrozenReconnectBudget() {
        val f = Fixture()
        var captured: Int? = null
        val next = json("3", "2")
        next.getJSONObject("accepted").put("wireBudgetKbps", 6000)
        next.getJSONArray("receipts").getJSONObject(0).put("wireBudgetKbps", 6000)
        f.http.current = TransportPolicyCodec.status(next)
        f.http.onQuery = { captured = f.service.stopAndGetReconnectBudget() }
        f.executor.tick()
        assertEquals(12000, captured)
        assertEquals(12000, f.service.stopAndGetReconnectBudget())
        assertNull(f.service.view.status)
        assertFalse(f.service.view.canSubmit)
    }
    @Test fun unknownWriteOutcomeCannotBecomeTheReconnectBudget() {
        val f = Fixture()
        f.http.failure = IOException("reply lost")
        assertTrue(f.service.setManualBudget(6500))
        assertNotNull(f.service.view.requestError)
        assertEquals(12000, f.service.stopAndGetReconnectBudget())
        assertEquals(1, f.http.writes.size)
    }
    @Test fun stoppingFromAListenerCancelsBeforeTheWriteIsScheduled() {
        val f = Fixture()
        f.service.listener = { if (it.submitting) f.service.stop() }
        assertFalse(f.service.setModes(true, true, 7000))
        assertTrue(f.http.writes.isEmpty())
        assertFalse(f.service.view.canSubmit)
    }
    @Test fun aNewControllerStartsWithItsOwnEpochAndNoOldPendingOperation() {
        val old = Fixture()
        assertTrue(old.service.setManualBudget(6500))
        assertEquals("3", old.service.view.requestRevision)
        val budget = old.service.stopAndGetReconnectBudget()
        val executor = ManualExecutor()
        val http = FakeTransport().apply { current = status("1", "1", "42") }
        val next = TransportPolicyService("4294967295", { http }, executor)
        try {
            next.start()
            executor.tick()
            assertEquals(12000, budget)
            assertEquals("42", next.view.status!!.connectionEpoch)
            assertEquals("1", next.view.status!!.accepted.revision)
            assertNull(next.view.requestRevision)
            assertTrue(next.view.canSubmit)
            assertTrue(http.writes.isEmpty())
            old.executor.tick()
            assertEquals("42", next.view.status!!.connectionEpoch)
        } finally { next.stop() }
    }
    @Test fun stopDuringHttpSuppressesPublicationAndSubmission() {
        val f = Fixture()
        var callbacks = 0
        f.service.listener = { callbacks++ }
        f.http.onQuery = { f.service.stop() }
        f.executor.tick()
        assertEquals(0, callbacks)
        assertFalse(f.service.setManualBudget(6000))
        assertTrue(f.http.writes.isEmpty())
    }
    @Test fun unavailableCapabilityCannotSubmitOrFallback() {
        val f = Fixture()
        try {
            f.http.current = status().copy(liveControlAvailable = false)
            f.executor.tick()
            assertFalse(f.service.setModes(true, true, 7000))
            assertFalse(f.service.setManualBudget(7000))
            assertTrue(f.http.writes.isEmpty())
        } finally { f.service.stop() }
    }
    @Test fun simultaneousUiEditsAreNotQueuedBehindAnInFlightWrite() {
        val f = Fixture()
        try {
            f.executor.queue = true
            assertTrue(f.service.setModes(true, true, 7000))
            assertFalse(f.service.setModes(false, false, 9000))
            assertFalse(f.service.setManualBudget(6000))
            f.executor.drain()
            assertEquals(1, f.http.writes.size)
        } finally { f.service.stop() }
    }
    @Test fun mismatchedAcknowledgementCannotConfirmThisRequest() {
        val f = Fixture()
        try {
            f.http.wrongRequestId = true
            assertTrue(f.service.setManualBudget(6000))
            assertNull(f.service.view.requestRevision)
            assertNotNull(f.service.view.requestError)
            f.executor.tick()
            assertEquals(1, f.http.writes.size)
            assertEquals("3", f.service.view.status!!.accepted.revision)
            assertNull(f.service.view.requestRevision)
        } finally { f.service.stop() }
    }
    @Test fun listenerFailureDoesNotStopReconciliationAndNoConfirmedTargetIsInvented() {
        val f = Fixture()
        try {
            f.service.listener = { throw IllegalStateException("closed UI") }
            f.http.current = status("4", "3")
            f.executor.tick()
            assertEquals("4", f.service.view.status!!.accepted.revision)
            assertNull(f.service.view.status!!.confirmed)
            assertTrue(f.service.view.canSubmit)
        } finally { f.service.stop() }
    }
    private inner class Fixture {
        val http = FakeTransport()
        val executor = ManualExecutor()
        val service = TransportPolicyService("4294967295", { http }, executor)
        init { service.start(); executor.tick() }
    }
    private inner class FakeTransport : TransportPolicyTransport {
        var current = status()
        var failure: Exception? = null
        var onQuery: (() -> Unit)? = null
        var wrongRequestId = false
        val queries = mutableListOf<String?>()
        val writes = mutableListOf<Pair<String, JSONObject>>()
        override fun query(sessionId: String, connectionEpoch: String?): TransportPolicyStatus {
            queries += connectionEpoch
            onQuery?.invoke()
            return current
        }
        override fun submit(path: String, body: JSONObject): TransportPolicySubmission {
            writes += path to body
            failure?.let { throw it }
            current = status("3", "2")
            return TransportPolicySubmission(if (wrongRequestId) "other-request" else body.getString("requestId"), "3", current)
        }
    }
    private class ManualExecutor : ScheduledThreadPoolExecutor(1) {
        private var tick: Runnable? = null
        var queue = false
        private val tasks = mutableListOf<Runnable>()
        val scheduled = mutableListOf<Runnable>()
        val delays = mutableListOf<Long>()
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            scheduled += command
            delays += unit.toNanos(delay)
            return object : ScheduledFuture<Unit> {
                override fun cancel(mayInterruptIfRunning: Boolean) = true
                override fun isCancelled() = false
                override fun isDone() = false
                override fun get() = Unit
                override fun get(timeout: Long, unit: TimeUnit) = Unit
                override fun getDelay(unit: TimeUnit) = 0L
                override fun compareTo(other: Delayed) = 0
            }
        }
        fun drainScheduled() { scheduled.toList().also { scheduled.clear() }.forEach { it.run() } }
        override fun execute(command: Runnable) { if (queue) tasks += command else command.run() }
        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            if(tick == null) tick = command
            return object : ScheduledFuture<Unit> {
                override fun cancel(mayInterruptIfRunning: Boolean) = true
                override fun isCancelled() = false
                override fun isDone() = false
                override fun get() = Unit
                override fun get(timeout: Long, unit: TimeUnit) = Unit
                override fun getDelay(unit: TimeUnit) = 0L
                override fun compareTo(other: Delayed) = 0
            }
        }
        fun tick() = checkNotNull(tick).run()
        fun drain() { tasks.toList().also { tasks.clear() }.forEach { it.run() } }
    }
}
