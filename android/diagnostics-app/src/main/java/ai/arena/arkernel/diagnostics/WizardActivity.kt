package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelArtifactCatalogue
import ai.arena.arkernel.ArKernelArtifactInventory
import ai.arena.arkernel.ArKernelCampaignPlanAdapter
import ai.arena.arkernel.ArKernelCampaignStep
import ai.arena.arkernel.ArKernelWizard
import ai.arena.arkernel.ArKernelWizardPlan
import ai.arena.arkernel.ArKernelWizardStage
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Walks the operator through the campaign one step at a time.
 *
 * Three decisions shape this screen, and each exists because the alternative
 * fails in a way the operator cannot see:
 *
 * **Progress comes from the files, never from the screen.** There is no
 * checkbox to tick and nothing stored about what has been done. After every
 * scan the wizard asks the inventory which records exist and parse, and shows
 * that. A wizard that trusted its own memory would report a step finished
 * because the button was pressed once, which is exactly the belief that loses
 * a session of measurements.
 *
 * **Exactly one step is ever workable.** Everything behind an unfinished step
 * is held back, so the operator cannot reach a measurement whose prerequisites
 * do not exist yet. Choosing wrongly is not a mistake they can make.
 *
 * **This screen does not describe how to press the button.** It says what to
 * bring, what has to be true afterwards, and what ruins the run; the
 * measurement screen itself says what to do on it. A copy of another screen's
 * procedure would go stale the first time that screen changed, and then it
 * would be wrong with the confidence of something written down.
 *
 * Nothing here grades anything. A step is done when a record exists that parses
 * and leaves no gate open - completeness, not correctness.
 */
class WizardActivity : DiagnosticsActivity() {

    private companion object {
        /** Where each measurement step actually happens. */
        fun activityFor(step: ArKernelCampaignStep): Class<*>? = when (step) {
            ArKernelCampaignStep.DEVICE_RUNTIME -> DeviceHardwareActivity::class.java
            ArKernelCampaignStep.IMU_SERIES -> ImuCalibrationActivity::class.java
            ArKernelCampaignStep.CAMERA_TIMING -> CameraTimingActivity::class.java
            ArKernelCampaignStep.CAMERA_IMU_OFFSET ->
                CameraImuAlignmentActivity::class.java
            ArKernelCampaignStep.CAMERA_IMU_ROTATION ->
                CameraExtrinsicsActivity::class.java
            ArKernelCampaignStep.RULER_SCALE -> RulerScaleActivity::class.java
            ArKernelCampaignStep.SCALE_LADDER -> ScaleLadderActivity::class.java
            ArKernelCampaignStep.METRIC_VALIDATION ->
                MetricValidationActivity::class.java
            ArKernelCampaignStep.INDEPENDENT_TARGETS ->
                IndependentTargetsActivity::class.java
            ArKernelCampaignStep.CONDITIONS -> ConditionEvidenceActivity::class.java
            ArKernelCampaignStep.SESSION_BINDING -> SessionBindingActivity::class.java
            ArKernelCampaignStep.CROSS_DEVICE -> CrossDeviceActivity::class.java
            ArKernelCampaignStep.CORPUS_ENTRY -> DeviceFarmCorpusActivity::class.java
            ArKernelCampaignStep.REDACTION -> RedactionActivity::class.java
            ArKernelCampaignStep.SIGNATURE -> SignatureActivity::class.java
            ArKernelCampaignStep.MANIFEST -> ArtifactHubActivity::class.java
        }

        fun markerFor(stage: ArKernelWizardStage): String = when (stage) {
            ArKernelWizardStage.DONE -> "[x]"
            ArKernelWizardStage.READY -> "[>]"
            ArKernelWizardStage.NEEDS_REPEAT -> "[!]"
            ArKernelWizardStage.BLOCKED -> "[ ]"
        }
    }

    private lateinit var scanButton: Button
    private lateinit var openButton: Button
    private lateinit var summaryText: TextView
    private lateinit var cardText: TextView
    private lateinit var listText: TextView
    private lateinit var notesText: TextView

    private var plan: ArKernelWizardPlan? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        summaryText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, 12)
            setTextIsSelectable(true)
        }
        cardText = TextView(this).apply {
            textSize = 14f
            setPadding(14, 14, 14, 14)
            setBackgroundColor(Color.parseColor("#FFF7ED"))
            setTextIsSelectable(true)
        }
        openButton = Button(this).apply {
            text = "Messung öffnen"
            textSize = 15f
            setAllCaps(false)
            isEnabled = false
            setOnClickListener { openCurrentStep() }
        }
        scanButton = Button(this).apply {
            text = "Bestand neu lesen"
            textSize = 15f
            setAllCaps(false)
            setOnClickListener { runScan() }
        }
        listText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, 12, 0, 0)
            setTextIsSelectable(true)
        }
        notesText = TextView(this).apply {
            textSize = 12f
            setPadding(0, 12, 0, 0)
            setTextIsSelectable(true)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            addView(
                TextView(this@WizardActivity).apply {
                    text = "Assistent: Schritt für Schritt"
                    textSize = 18f
                    setPadding(0, 0, 0, 4)
                },
            )
            addView(
                TextView(this@WizardActivity).apply {
                    text = "Der Assistent schreibt selbst nichts. Er sagt dir, " +
                        "welche Messung jetzt kommt, was du dafuer brauchst " +
                        "und woran du siehst, dass sie zählt. Was du schon " +
                        "gemessen hast, liest er aus deinen Dateien - nicht " +
                        "aus einem Haken, den du setzt."
                    textSize = 12f
                    setPadding(0, 0, 0, 12)
                },
            )
            addView(summaryText)
            addView(cardText)
            addView(openButton)
            addView(scanButton)
            addView(listText)
            addView(notesText)
        }

        setContentView(
            ScrollView(this).apply {
                addView(root)
            },
        )
        runScan()
    }

    // -------------------------------------------------------------- scanning

    private fun runScan() {
        scanButton.isEnabled = false
        openButton.isEnabled = false
        summaryText.text = "Lese, was schon auf dem Gerät liegt ..."
        Thread {
            val scan = ArKernelArtifactScanner(this).scan()
            val inventory: ArKernelArtifactInventory =
                ArKernelArtifactCatalogue.inventory(
                    scan.files.map { ArKernelArtifactCatalogue.read(it.file, it.text) })
            val next = ArKernelWizard.plan(
                ArKernelCampaignPlanAdapter.canonicalQuestions(), inventory)
            runOnUiThread {
                plan = next
                render(scan.notes)
                scanButton.isEnabled = true
            }
        }.start()
    }

    // -------------------------------------------------------------- rendering

    private fun render(notes: List<String>) {
        val current = plan ?: return
        summaryText.text = current.summary() +
            "  (" + current.progressPermille / 10 + " %)"
        cardText.text = renderCard(current)
        listText.text = renderList(current)
        val distinct = notes.distinct()
        notesText.text = if (distinct.isEmpty()) {
            ""
        } else {
            "Hinweise: " + distinct.joinToString(", ")
        }
        val step = current.next?.step
        openButton.isEnabled = step != null && activityFor(step) != null
        if (step != null) {
            openButton.text = "Knopf ${step.button} öffnen"
        } else {
            openButton.text = "Messung öffnen"
        }
    }

    private fun renderCard(plan: ArKernelWizardPlan): String {
        val item = plan.next
            ?: return "Fertig.\n\nAlle ${plan.total} Schritte liegen vor. " +
                "Geh in Knopf 21 (Zentrale) und erzeuge den Bestand, " +
                "wenn du ihn noch nicht erzeugt hast."
        val entry = item.entry
        val text = StringBuilder()
        text.append("Schritt ${plan.doneCount + 1} von ${plan.total}: ")
        text.append(entry.title).append('\n')
        text.append("Knopf ${item.button}").append("  |  ")
        text.append(entry.step.record).append("  |  ")
        text.append("etwa ${entry.minutes} Min").append('\n')
        if (item.stage == ArKernelWizardStage.NEEDS_REPEAT) {
            text.append('\n').append("ACHTUNG: ").append(item.note).append('\n')
        }
        val props = entry.requiredProps
        text.append('\n').append("Das brauchst du\n")
        if (props.isEmpty()) {
            text.append("  - nichts\n")
        } else {
            for (prop in props) text.append("  - ").append(prop.label).append('\n')
        }
        text.append('\n').append("Vorbereitung\n")
        for (line in entry.prepare) text.append("  - ").append(line).append('\n')
        text.append('\n').append("Was du tust\n").append(entry.action).append('\n')
        text.append('\n').append("Woran du siehst, dass es zählt\n")
        text.append(entry.accept).append('\n')
        text.append('\n').append("Was es kaputt macht\n")
        for (line in entry.mistakes) text.append("  - ").append(line).append('\n')
        text.append('\n').append("Warum an dieser Stelle\n")
        text.append(entry.whyHere).append('\n')
        return text.toString()
    }

    private fun renderList(plan: ArKernelWizardPlan): String {
        val text = StringBuilder()
        text.append("Der ganze Weg\n")
        for (item in plan.items) {
            text.append(markerFor(item.stage))
                .append(" Knopf ")
                .append(item.button)
                .append("  ")
                .append(item.entry.title)
                .append("  ")
                .append(item.record)
                .append('\n')
            if (item.stage != ArKernelWizardStage.BLOCKED) {
                text.append("      ").append(item.note).append('\n')
            }
        }
        text.append('\n')
        text.append("[x] fertig   [>] jetzt dran   [!] wiederholen   [ ] wartet")
        return text.toString()
    }

    // ------------------------------------------------------------- navigation

    private fun openCurrentStep() {
        val step = plan?.next?.step
        if (step == null) {
            Toast.makeText(this, "Nichts offen.", Toast.LENGTH_SHORT).show()
            return
        }
        val target = activityFor(step)
        if (target == null) {
            Toast.makeText(
                this,
                "Fuer Knopf ${step.button} ist kein Bildschirm hinterlegt.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        startActivity(Intent(this, target))
    }
}
