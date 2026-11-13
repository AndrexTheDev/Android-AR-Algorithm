package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelArtifactCatalogueTest {

    companion object {
        private const val HASH_A =
            "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"
        private const val HASH_B =
            "3e23e8160039594a33894f6564e1b1348bbd7a0088d42c4acb73eeaed59c009d"
        private const val HASH_C =
            "2e7d2c03a9507ae265ecf5b5356885a53393a2029d241394997265a1a25aefc6"
        private const val HASH_D =
            "18ac3e7343f016890c510e93f935261169d9e3f565436429830faf0934f4f8e4"

        private fun file(
            name: String,
            sizeBytes: Long = 100L,
            modifiedMs: Long = 1_000L,
            sha256Hex: String = HASH_A,
        ): ArKernelArtifactFile = ArKernelArtifactFile(
            name = name,
            sizeBytes = sizeBytes,
            modifiedMs = modifiedMs,
            sha256Hex = sha256Hex,
        )

        /** A canonical `ARKDSMAN1` block, which this catalogue can also verify. */
        private fun manifestText(id: String = "hub.test"): String {
            val built = ArKernelArtifactCatalogue.manifest(
                manifestId = id,
                files = listOf(file("ARKIMUCAL1_1.txt", 100L, 1_000L, HASH_A)),
            )
            return ArKernelDatasetManifestBundle.encode(built.manifest!!)
        }

        private fun canonicalWithVerdicts(): String = listOf(
            "ARKIMUCAL1",
            "version=1",
            "gravity_ok=true",
            "accel_repeat_checked=false",
            "gyro_scale_ok=false",
            "thermal_covered=true",
            "calibration_sufficient=false",
            "reference_gravity_mps2=9.810000",
            "device_token=phone.test",
            "END",
        ).joinToString("\n")
    }

    // ---------------------------------------------------------------- naming

    @Test
    fun everyContractIsRecognisedFromItsOwnFileName() {
        for (contract in ArKernelArtifactContract.values()) {
            val name = "${contract.token}_1731430000000.txt"
            assertEquals(
                "File name for ${contract.token}",
                contract,
                ArKernelArtifactCatalogue.contractOf(name),
            )
        }
    }

    @Test
    fun redactedAndKeyFileNamesKeepTheirContract() {
        assertEquals(
            ArKernelArtifactContract.VALID,
            ArKernelArtifactCatalogue.contractOf(
                "redacted_ARKVALID1_1731430000000.txt"),
        )
        assertEquals(
            ArKernelArtifactContract.SIGN,
            ArKernelArtifactCatalogue.contractOf(
                "ARKSIGN1_pubkey_ab12cd34.txt"),
        )
    }

    @Test
    fun aNameNoContractOwnsIsUnrecognised() {
        assertNull(ArKernelArtifactCatalogue.contractOf("notes.txt"))
        assertNull(ArKernelArtifactCatalogue.contractOf("IMG_20240101.jpg"))
    }

    // -------------------------------------------------------------- verdicts

    @Test
    fun falseGatesAreReportedAndTrueOnesAreNot() {
        assertEquals(
            listOf(
                "accel_repeat_checked",
                "calibration_sufficient",
                "gyro_scale_ok",
            ),
            ArKernelArtifactCatalogue.openVerdicts(canonicalWithVerdicts()),
        )
    }

    @Test
    fun verdictScanIgnoresKeysThatAreNotGates() {
        val text = listOf(
            "gravity_magnitude_error_mps2=0.038151",
            "device_token=false.looking",
            "END",
        ).joinToString("\n")
        assertTrue(ArKernelArtifactCatalogue.openVerdicts(text).isEmpty())
    }

    @Test
    fun verdictScanToleratesCarriageReturns() {
        val text = canonicalWithVerdicts().replace("\n", "\r\n")
        assertTrue(
            ArKernelArtifactCatalogue.openVerdicts(text)
                .contains("accel_repeat_checked"))
    }

    @Test
    fun verdictScanIsBounded() {
        val text = buildString {
            append("ARKIMUCAL1\n")
            repeat(5_000) { append("gate_${it}_ok=false\n") }
            append("END\n")
        }
        val found = ArKernelArtifactCatalogue.openVerdicts(text)
        assertTrue("expected a bounded scan, got ${found.size}", found.size <= 32)
    }

    // ------------------------------------------------------------- inventory

    @Test
    fun anEmptyInventoryIsMissingEveryContract() {
        val inventory = ArKernelArtifactCatalogue.inventory(emptyList())
        assertEquals(
            ArKernelArtifactContract.values().size, inventory.missing.size)
        assertEquals(ArKernelArtifactContract.FDPOL, inventory.next)
        assertFalse(inventory.states.any { it.present })
    }

    @Test
    fun theNextStepIsTheFirstMissingContractInCampaignOrder() {
        val readings = listOf(
            ArKernelArtifactCatalogue.read(
                file("ARKFDPOL1_1.txt"), "not a policy"),
        )
        val inventory = ArKernelArtifactCatalogue.inventory(readings)
        assertEquals(ArKernelArtifactContract.FDPLAN, inventory.next)
    }

    @Test
    fun aPresentButRejectedFileCountsAsOpenWorkNotAsCollected() {
        val readings = listOf(
            ArKernelArtifactCatalogue.read(
                file("ARKIMUCAL1_1.txt"), "truncated"),
        )
        val inventory = ArKernelArtifactCatalogue.inventory(readings)
        val state = inventory.states.first { it.contract == ArKernelArtifactContract.IMUCAL }
        assertTrue(state.present)
        assertFalse(state.collected)
        assertEquals(1, state.rejectedCount)
        assertEquals(ArKernelArtifactContract.IMUCAL, inventory.incomplete.single())
    }

    @Test
    fun anAcceptedFileWithAnOpenVerdictIsNotCollected() {
        val reading = ArKernelArtifactReading(
            file = file("ARKIMUCAL1_1.txt"),
            kind = ArKernelArtifactKind.CONTRACT,
            contract = ArKernelArtifactContract.IMUCAL,
            accepted = true,
            rejection = "NONE",
            detail = "",
            openVerdicts = listOf("accel_repeat_checked"),
        )
        val inventory = ArKernelArtifactCatalogue.inventory(listOf(reading))
        val state = inventory.states.first { it.contract == ArKernelArtifactContract.IMUCAL }
        assertTrue(state.present)
        assertFalse(state.collected)
        assertEquals(listOf("accel_repeat_checked"), state.openVerdicts)
    }

    @Test
    fun aPublicKeyExportIsListedButNeverParsedAsAManifest() {
        val reading = ArKernelArtifactCatalogue.read(
            file("ARKSIGN1_publickey.txt"), "-----BEGIN PUBLIC KEY-----")
        assertEquals(ArKernelArtifactKind.PUBLIC_KEY, reading.kind)
        assertNull(reading.contract)
        assertFalse(reading.accepted)
        assertEquals("NOT_A_CONTRACT_FILE", reading.rejection)

        val named = ArKernelArtifactCatalogue.read(
            file("ARKSIGN1_pubkey_ab12cd34.txt"), "key")
        assertEquals(ArKernelArtifactKind.PUBLIC_KEY, named.kind)

        val inventory = ArKernelArtifactCatalogue.inventory(
            listOf(reading, named))
        assertEquals(
            listOf("ARKSIGN1_pubkey_ab12cd34.txt", "ARKSIGN1_publickey.txt"),
            inventory.publicKeys,
        )
        assertTrue(inventory.unrecognised.isEmpty())
        // The key exists, but no manifest has been written for it yet.
        assertTrue(
            inventory.states.first { it.contract == ArKernelArtifactContract.SIGN }
                .let { !it.present })
    }

    @Test
    fun aKeyExportDoesNotCountAsCollectingTheSignatureContract() {
        val inventory = ArKernelArtifactCatalogue.inventory(
            listOf(
                ArKernelArtifactCatalogue.read(
                    file("ARKSIGN1_publickey.txt"), "key"),
            ),
        )
        val sign = inventory.states.first { it.contract == ArKernelArtifactContract.SIGN }
        assertFalse("a key is not a manifest", sign.present)
        assertFalse(sign.collected)
        assertTrue(inventory.missing.contains(ArKernelArtifactContract.SIGN))
        // `next` is the first gap in campaign order, not the file just read.
        assertEquals(ArKernelArtifactContract.FDPOL, inventory.next)
    }

    @Test
    fun unrecognisedFilesAreListedNotSilentlyDropped() {
        val readings = listOf(
            ArKernelArtifactCatalogue.read(file("notes.txt"), "hello"),
        )
        val inventory = ArKernelArtifactCatalogue.inventory(readings)
        assertEquals(listOf("notes.txt"), inventory.unrecognised)
        assertTrue(inventory.publicKeys.isEmpty())
        assertEquals(
            ArKernelArtifactKind.UNKNOWN,
            ArKernelArtifactCatalogue.read(file("notes.txt"), "hello").kind,
        )
        assertFalse(
            ArKernelArtifactCatalogue.read(file("notes.txt"), "hello").accepted)
    }

    // -------------------------------------------------------------- manifest

    @Test
    fun manifestBindsEveryDigestAndRoundTrips() {
        val files = listOf(
            file("ARKIMUCAL1_1.txt", 100L, 1_000L, HASH_A),
            file("ARKCOND1_2.txt", 200L, 2_000L, HASH_B),
        )
        val built = ArKernelArtifactCatalogue.manifest("hub.test", files)
        assertTrue(built.error ?: "", built.accepted)
        val text = ArKernelDatasetManifestBundle.encode(built.manifest!!)
        val parsed = ArKernelDatasetManifestBundle.parse(text)
        assertTrue(parsed.detail, parsed.accepted)
        assertEquals(2, parsed.manifest!!.entries.size)
        // Newest first, so the cap always keeps the most recent artifacts.
        assertEquals(HASH_B, parsed.manifest.entries[0].sha256Hex)
        assertEquals(HASH_A, parsed.manifest.entries[1].sha256Hex)
        assertEquals("ARKCOND1", parsed.manifest.entries[0].role)
        assertEquals(ARK_ARTIFACT_LICENSE_TOKEN, parsed.manifest.entries[0].licenseId)
    }

    @Test
    fun manifestKeepsNewestFirstAndNamesWhatItOmitted() {
        val files = listOf(
            file("ARKCOND1_old.txt", 10L, 100L, HASH_A),
            file("ARKCOND1_new.txt", 10L, 900L, HASH_B),
            file("ARKVALID1_mid.txt", 10L, 500L, HASH_C),
        )
        val built = ArKernelArtifactCatalogue.manifest("hub.test", files, maxEntries = 2)
        assertTrue(built.accepted)
        assertEquals(2, built.manifest!!.entries.size)
        assertEquals("ARKCOND1_new.txt", built.manifest.entries[0].datasetId)
        assertEquals("ARKVALID1_mid.txt", built.manifest.entries[1].datasetId)
        assertEquals(listOf("ARKCOND1_old.txt"), built.omitted)
    }

    @Test
    fun manifestDropsFilesWithNoBytesAndSaysHowMany() {
        val files = listOf(
            file("ARKIMUCAL1_1.txt", 100L, 1_000L, HASH_A),
            file("ARKCOND1_empty.txt", 0L, 2_000L, HASH_B),
        )
        val built = ArKernelArtifactCatalogue.manifest("hub.test", files)
        assertTrue(built.accepted)
        assertEquals(1, built.manifest!!.entries.size)
        assertEquals(1, built.droppedEmpty)
        assertTrue(built.omitted.contains("ARKCOND1_empty.txt"))
    }

    @Test
    fun manifestRefusesWhenNothingCarriesBytes() {
        val built = ArKernelArtifactCatalogue.manifest(
            "hub.test", listOf(file("ARKCOND1_empty.txt", 0L)))
        assertFalse(built.accepted)
        assertNull(built.manifest)
        assertNotNull(built.error)
    }

    @Test
    fun namesThatCollapseOntoTheSameTokenAreDisambiguated() {
        val longA = "ARKIMUCAL1_${"a".repeat(70)}.txt"
        val longB = "ARKIMUCAL1_${"b".repeat(70)}.txt"
        val built = ArKernelArtifactCatalogue.manifest(
            "hub.test",
            listOf(
                file(longA, 10L, 1_000L, HASH_A),
                file(longB, 10L, 2_000L, HASH_B),
            ),
        )
        assertTrue(built.error ?: "", built.accepted)
        val ids = built.manifest!!.entries.map { it.datasetId }
        assertEquals(2, ids.toSet().size)
        val text = ArKernelDatasetManifestBundle.encode(built.manifest)
        assertTrue(ArKernelDatasetManifestBundle.parse(text).accepted)
    }

    @Test
    fun anUnrecognisedFileIsStillBoundUnderAnUnclassifiedRole() {
        val built = ArKernelArtifactCatalogue.manifest(
            "hub.test", listOf(file("notes.txt", 10L, 1_000L, HASH_A)))
        assertTrue(built.accepted)
        assertEquals(
            ARK_ARTIFACT_UNCLASSIFIED_TOKEN,
            built.manifest!!.entries.single().role,
        )
    }

    // ------------------------------------------------------------------ read

    @Test
    fun aCanonicalArtifactIsAccepted() {
        val text = manifestText()
        val reading = ArKernelArtifactCatalogue.read(
            file("ARKDSMAN1_1.txt", text.length.toLong()), text)
        assertTrue(reading.detail, reading.accepted)
        assertEquals("NONE", reading.rejection)
        assertEquals(ArKernelArtifactContract.DSMAN, reading.contract)
    }

    @Test
    fun aTruncatedArtifactIsRejectedRatherThanCountedAsPresent() {
        val reading = ArKernelArtifactCatalogue.read(
            file("ARKDSMAN1_1.txt", 40L), manifestText().take(40))
        assertFalse(reading.accepted)
        assertTrue(reading.rejection.isNotEmpty())
        assertTrue(reading.openVerdicts.isEmpty())
    }

    // ----------------------------------------------------------------- token

    @Test
    fun tokenFoldsForeignCharactersIntoTheAlphabet() {
        // Every character outside [A-Za-z0-9._:-] becomes a dot, including '+'.
        assertEquals("a.b.c.d", ArKernelArtifactCatalogue.token("a b/c+d"))
        assertEquals(
            "ARKIMUCAL1_1.txt",
            ArKernelArtifactCatalogue.token("ARKIMUCAL1_1.txt"),
        )
        // The underscore survives because it is in the alphabet; ' ' and '!' do not.
        assertEquals(
            "ARKIMUCAL1.1.txt",
            ArKernelArtifactCatalogue.token("ARKIMUCAL1 1!txt"),
        )
    }

    @Test
    fun tokenKeepsTheTailRatherThanTheHeadWhenTooLong() {
        val value = "x".repeat(30) + "tail"
        val token = ArKernelArtifactCatalogue.token(value + "y".repeat(80))
        assertTrue(token.length <= 64)
        assertTrue("expected the tail, got $token", token.endsWith("y"))
    }

    @Test
    fun aFileWithABadDigestIsRejectedBeforeItIsCounted() {
        var threw = false
        try {
            file("ARKIMUCAL1_1.txt", 100L, 1_000L, "not-a-digest")
        } catch (error: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    // ------------------------------------------------- registration coverage
    //
    // Everything above asks whether the catalogue behaves correctly for the
    // contracts it knows about. It cannot catch the contract it never learned:
    // `everyContractIsRecognisedFromItsOwnFileName` walks the enum, so a
    // missing entry simply never comes up. Three contracts were missing that
    // way - camera-IMU offset, scale ladder, camera-IMU rotation - and the
    // manifest step, the one that binds a campaign together, dropped their
    // files silently.
    //
    // The tests below therefore check the enum against a source of truth from
    // outside it.

    @Test
    fun everyMeasurementTheCampaignRoutesToIsRegistered() {
        // The routing table names the record each button produces. Every one
        // of those has to exist here, or the operator runs the button and the
        // manifest discards what it wrote.
        for (step in ArKernelCampaignStep.values()) {
            val contract = ArKernelArtifactContract.ofToken(step.record)
            assertNotNull(
                "${step.record} (button ${step.button}) is not in the " +
                    "artefact catalogue, so the manifest will silently drop it",
                contract,
            )
        }
    }

    @Test
    fun theButtonNumbersAgreeBetweenRouteAndCatalogue() {
        // Two tables say which button writes which record. If they drift, the
        // operator is sent to the wrong screen.
        for (step in ArKernelCampaignStep.values()) {
            val contract = ArKernelArtifactContract.ofToken(step.record)
            assertNotNull(step.record, contract)
            assertEquals("button for ${step.record}", step.button, contract!!.button)
        }
    }

    @Test
    fun theThreeNewestContractsAreRegistered() {
        // Explicit, so a future refactor of the enum says plainly what it
        // removed instead of failing somewhere indirect.
        for (token in listOf("ARKCAMIMU1", "ARKLADDER1", "ARKCAMEXT1")) {
            assertNotNull("$token missing", ArKernelArtifactContract.ofToken(token))
        }
    }

    @Test
    fun aTokenBearingFileNameIsNeverClassifiedAsUnrecognised() {
        // Registration has to be wired to a parser. The text is deliberately
        // wrong, so the only acceptable outcomes are "parsed" or "rejected by
        // its own contract" - never "no contract matches".
        for (contract in ArKernelArtifactContract.values()) {
            val reading = ArKernelArtifactCatalogue.read(
                file("${contract.token}_run-1.txt"), "not a record at all")
            assertFalse(
                "${contract.token} was not dispatched to its parser",
                reading.rejection == "UNRECOGNISED",
            )
            assertEquals(contract, reading.contract)
        }
    }

    @Test
    fun aFileNameWithoutItsTokenStaysUnrecognised() {
        // Why the screens have to name their files after the token: a perfect
        // record under the wrong name is invisible to the inventory.
        val reading = ArKernelArtifactCatalogue.read(
            file("camimu-run-1.txt"), "ARKCAMIMU1\nversion=1\nEND")
        assertEquals("UNRECOGNISED", reading.rejection)
        assertFalse(reading.accepted)
    }

    @Test
    fun everyContractCarriesAPurposeAndAButton() {
        for (contract in ArKernelArtifactContract.values()) {
            assertTrue("${contract.token} has no purpose", contract.purpose.isNotBlank())
            assertTrue("${contract.token} has no button", contract.button > 0)
        }
    }

    @Test
    fun contractTokensAreUniqueAndWellFormed() {
        val tokens = ArKernelArtifactContract.values().map { it.token }
        assertEquals("tokens must be unique", tokens.size, tokens.toSet().size)
        for (token in tokens) {
            assertTrue(
                "$token must look like an ARK token",
                token.startsWith("ARK") && token.last().isDigit(),
            )
        }
    }
}
