package ai.arena.arkernel

/**
 * Which measurement answers which canonical question.
 *
 * The campaign plan (`ArKernelCampaignPlan`) says *what* has to be
 * established. It deliberately does not say *how*, because the answer depends
 * on which buttons this build has. That left a gap with real consequences:
 * the operator could complete every scene in the campaign without ever being
 * told which measurement to run, so a "confirmed" question could rest on
 * nothing at all.
 *
 * This object closes that gap. It is a routing table, not a record: it maps
 * each canonical question to the buttons whose records constitute evidence for
 * it, and it states the order those measurements have to be taken in.
 *
 * **Why order is part of the answer and not a footnote.** Three dependencies
 * are physical, not stylistic:
 *
 * 1. *Device before sensor.* A sixty-second stillness run on a throttling
 *    phone measures the throttling, not the gyroscope, and nothing in the
 *    resulting file distinguishes them afterwards.
 * 2. *Time offset before rotation.* The rotation regression averages the
 *    gyroscope over the interval centred on each frame. If the camera
 *    timestamps carry a residual lag `t_d`, that window is centred in the
 *    wrong place, the correlation is attenuated, and the recovered focal
 *    length - which is the *norm* of the coefficient vector - comes out
 *    biased low. So `t_d` is measured first.
 * 3. *Scale geometry before scale claims.* The ladder says whether one scale
 *    factor suffices across distance; the validation says whether the scale
 *    is right at all. Asking the second before the first produces a number
 *    with no interpretation.
 *
 * Nothing here grades anything. A question is *answerable* when its evidence
 * steps have been run; it is not thereby answered correctly, and the table
 * says so.
 */

/** How often a step has to be repeated, and why. */
enum class ArKernelStepCadence(val token: String) {
    /** Once per device, and again after a firmware change or a drop. */
    PER_DEVICE("per_device"),

    /** Once per measuring session, because the scene is what changes. */
    PER_SESSION("per_session"),

    /** Once per campaign, at the end, to bind what was produced. */
    PER_CAMPAIGN("per_campaign");

    companion object {
        fun ofToken(token: String): ArKernelStepCadence? =
            entries.firstOrNull { it.token == token }
    }
}

/**
 * One measurement step: a button in the diagnostics app and the record it
 * writes.
 *
 * `button` is the number as it appears on the device, because that is what
 * the operator has to find on screen.
 */
enum class ArKernelCampaignStep(
    val button: Int,
    val record: String,
    val cadence: ArKernelStepCadence,
    val establishes: String,
) {
    DEVICE_RUNTIME(
        button = 23,
        record = "ARKDEVHW1",
        cadence = ArKernelStepCadence.PER_DEVICE,
        establishes = "Dass das Geraet nicht drosselt und die Uhren halten"),

    IMU_SERIES(
        button = 20,
        record = "ARKIMUCAL1",
        cadence = ArKernelStepCadence.PER_DEVICE,
        establishes = "Kadenz, Bias, Statik und Achsen des Traegheitssensors"),

    CAMERA_TIMING(
        button = 22,
        record = "ARKCAMT1",
        cadence = ArKernelStepCadence.PER_DEVICE,
        establishes = "Bildtakt, Zeitbasis, Belichtung, Erfassungsstabilitaet"),

    CAMERA_IMU_OFFSET(
        button = 24,
        record = "ARKCAMIMU1",
        cadence = ArKernelStepCadence.PER_DEVICE,
        establishes = "Der Zeitversatz t_d zwischen Bild und Gyroskop"),

    CAMERA_IMU_ROTATION(
        button = 26,
        record = "ARKCAMEXT1",
        cadence = ArKernelStepCadence.PER_DEVICE,
        establishes = "Die Drehung R zwischen Kamera und Gyroskop"),

    RULER_SCALE(
        button = 13,
        record = "ARKSCALE1",
        cadence = ArKernelStepCadence.PER_SESSION,
        establishes = "Wie viele Bildpunkte ein Zentimeter einnimmt"),

    SCALE_LADDER(
        button = 25,
        record = "ARKLADDER1",
        cadence = ArKernelStepCadence.PER_SESSION,
        establishes = "Ob der Maßstab ueber die Entfernung gilt"),

    METRIC_VALIDATION(
        button = 14,
        record = "ARKVALID1",
        cadence = ArKernelStepCadence.PER_SESSION,
        establishes = "Die Abweichung gegen dein Maß in Millimetern"),

    INDEPENDENT_TARGETS(
        button = 17,
        record = "ARKTARGET1",
        cadence = ArKernelStepCadence.PER_SESSION,
        establishes = "Maße, die der Kern nicht kennen konnte"),

    CONDITIONS(
        button = 18,
        record = "ARKCOND1",
        cadence = ArKernelStepCadence.PER_SESSION,
        establishes = "Helligkeit und Thermik je Szene"),

    SESSION_BINDING(
        button = 8,
        record = "ARKBIND1",
        cadence = ArKernelStepCadence.PER_SESSION,
        establishes = "Dass eine Messung zu einer Sitzung gehoert"),

    CROSS_DEVICE(
        button = 9,
        record = "ARKCROSS1",
        cadence = ArKernelStepCadence.PER_CAMPAIGN,
        establishes = "Der Vergleich ueber Geraete hinweg"),

    CORPUS_ENTRY(
        button = 19,
        record = "ARKCORPUS1",
        cadence = ArKernelStepCadence.PER_CAMPAIGN,
        establishes = "Der Eintrag in den Geraete-Korpus"),

    REDACTION(
        button = 16,
        record = "ARKREDRUN1",
        cadence = ArKernelStepCadence.PER_CAMPAIGN,
        establishes = "Die redigierte Fassung, die allein hochgeladen wird"),

    SIGNATURE(
        button = 15,
        record = "ARKSIGN1",
        cadence = ArKernelStepCadence.PER_CAMPAIGN,
        establishes = "Die Signatur ueber genau diese redigierten Dateien"),

    MANIFEST(
        button = 21,
        record = "ARKDSMAN1",
        cadence = ArKernelStepCadence.PER_CAMPAIGN,
        establishes = "Der Bestand dessen, was am Ende wirklich vorliegt"),
    ;

    val label: String get() = "Knopf $button ($record)"
}

object ArKernelCampaignRoute {

    /**
     * Evidence for one canonical question, in the order it has to be taken.
     *
     * The prerequisites are prepended rather than assumed: a question about
     * scale is not answerable on a device whose clock has been drifting, and
     * the table would be lying if it listed only the scale step.
     */
    fun evidenceFor(question: ArKernelCampaignQuestion): List<ArKernelCampaignStep> {
        val direct = directEvidenceFor(question)
        // An unrecognised question has no route at all. Returning just the
        // prerequisites would be worse than saying so: it would send the
        // operator off to run five measurements that answer nothing, and the
        // campaign would record the question as having a way forward.
        if (direct.isEmpty()) return emptyList()
        return prerequisites() + direct
    }

    /**
     * The steps whose records directly speak to this question, empty when the
     * question is not one this build knows how to measure.
     */
    fun directEvidenceFor(
        question: ArKernelCampaignQuestion,
    ): List<ArKernelCampaignStep> = when (question.wireId) {
            "Q_SCALE_SHORT", "Q_SCALE_LONG" -> listOf(
                ArKernelCampaignStep.RULER_SCALE,
                ArKernelCampaignStep.SCALE_LADDER,
                ArKernelCampaignStep.METRIC_VALIDATION)
            "Q_ORTHOGONALITY" -> listOf(
                ArKernelCampaignStep.METRIC_VALIDATION,
                ArKernelCampaignStep.INDEPENDENT_TARGETS)
            "Q_ORIENTATION_STILLNESS" -> listOf(
                ArKernelCampaignStep.IMU_SERIES)
            "Q_THERMAL_DRIFT" -> listOf(
                ArKernelCampaignStep.CONDITIONS,
                ArKernelCampaignStep.DEVICE_RUNTIME,
                ArKernelCampaignStep.IMU_SERIES,
                ArKernelCampaignStep.METRIC_VALIDATION)
            "Q_LIGHTING_CHANGE" -> listOf(
                ArKernelCampaignStep.CONDITIONS,
                ArKernelCampaignStep.METRIC_VALIDATION)
            "Q_HELD_OUT_DIMENSION" -> listOf(
                ArKernelCampaignStep.INDEPENDENT_TARGETS,
                ArKernelCampaignStep.METRIC_VALIDATION)
            "Q_REPEATABILITY" -> listOf(
                ArKernelCampaignStep.RULER_SCALE,
                ArKernelCampaignStep.METRIC_VALIDATION)
            "Q_CROSS_SESSION" -> listOf(
                ArKernelCampaignStep.SESSION_BINDING,
                ArKernelCampaignStep.METRIC_VALIDATION)
            "Q_CROSS_DEVICE" -> listOf(
                ArKernelCampaignStep.CROSS_DEVICE)
            else -> emptyList()
        }

    /**
     * The measurements every campaign stands on, in dependency order.
     *
     * Device runtime comes first because throttling corrupts everything after
     * it. The time offset comes before the rotation because the rotation is
     * fitted against a gyroscope window that the offset displaces.
     */
    fun prerequisites(): List<ArKernelCampaignStep> = listOf(
        ArKernelCampaignStep.DEVICE_RUNTIME,
        ArKernelCampaignStep.IMU_SERIES,
        ArKernelCampaignStep.CAMERA_TIMING,
        ArKernelCampaignStep.CAMERA_IMU_OFFSET,
        ArKernelCampaignStep.CAMERA_IMU_ROTATION)

    /** Everything the campaign closes with, in the order it must happen. */
    fun closingSteps(): List<ArKernelCampaignStep> = listOf(
        ArKernelCampaignStep.CORPUS_ENTRY,
        ArKernelCampaignStep.REDACTION,
        ArKernelCampaignStep.SIGNATURE,
        ArKernelCampaignStep.MANIFEST)

    /**
     * The full run order for one campaign: prerequisites, then the question
     * evidence without repeating the prerequisites, then the closing steps.
     */
    fun runOrder(questions: List<ArKernelCampaignQuestion>):
        List<ArKernelCampaignStep> {
        val ordered = ArrayList<ArKernelCampaignStep>(prerequisites())
        for (question in questions) {
            for (step in evidenceFor(question)) {
                if (step !in ordered) ordered.add(step)
            }
        }
        // Questions with no route contribute nothing rather than dragging the
        // prerequisites in a second time.
        for (step in closingSteps()) {
            if (step !in ordered) ordered.add(step)
        }
        return ordered
    }

    /** German one-liner for the operator: what to run for this question. */
    fun instructionFor(question: ArKernelCampaignQuestion): String {
        val direct = directEvidenceFor(question)
        if (direct.isEmpty()) {
            return "Fuer ${question.wireId} ist kein Messweg hinterlegt."
        }
        return "Fuer ${question.wireId} (${question.requiredRepeats}x): " +
            direct.joinToString(" -> ") { it.label } +
            ". Vorausgesetzt: " +
            prerequisites().joinToString(" -> ") {
                it.button.toString()
            } + "."
    }
}
