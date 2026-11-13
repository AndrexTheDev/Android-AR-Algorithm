package ai.arena.arkernel

/**
 * Turns the routing table into something a person can follow without knowing
 * what any of it means.
 *
 * `ArKernelCampaignRoute` already answers *which* button answers *which*
 * question, and in which order. What it does not answer is the thing an
 * operator standing in a room with a phone actually asks: what do I need, what
 * do I do, how do I know it counted, and what ruins it. That is this file.
 *
 * **The division of labour with the measurement screens matters.** This file
 * says what to bring and what has to be true afterwards; it deliberately does
 * not restate the on-screen procedure for pressing the button, because that
 * procedure lives in the screen itself and would go stale here the first time
 * the screen changed. A wizard that describes a screen it cannot see is a
 * wizard that starts lying quietly.
 *
 * **Nothing here grades anything.** A step is `DONE` when a record exists that
 * parses and leaves no gate open. That is completeness, not correctness: the
 * measurement can be complete and still be wrong. The wizard does not claim
 * otherwise, and where a gate is open it names the gate rather than the verdict.
 *
 * **Progress is deliberately derived from the files on the device**, not from
 * a checkbox the operator ticks. A wizard that trusts its own memory tells you
 * a step is finished because you once pressed the button; this one cannot be
 * fooled that way, because it asks the inventory.
 */

/** Something the operator has to have at hand. `optional` props never block. */
enum class ArKernelWizardProp(val label: String, val optional: Boolean = false) {
    NOTHING("Nichts"),
    RULER("Zollstock oder Lineal"),
    TAPE("Maßband, mindestens 3 m"),
    TARGET("Ein Gegenstand mit deutlichen Ecken"),
    TEXTURED_TARGET("Strukturiertes Ziel: Buchregal, Tapete, Pflaster"),

    /**
     * A printed checkerboard on something flat.
     *
     * Printed, and not shown on a screen: a screen is a grid of pixels with
     * its own geometry, it refreshes, and it moires against the camera's own
     * pixel grid. Flat, and not held in the hand, because the whole
     * computation assumes the corners lie on one plane. Not measured: the
     * squares have to be *square*, which a printer manages, and their size in
     * millimetres is never used.
     */
    CHECKERBOARD("Schachbrett, ausgedruckt und auf etwas Festes geklebt"),
    SECOND_DEVICE("Ein zweites Gerät", optional = true),
    FIRM_SURFACE("Ein fester Tisch oder ein Stativ", optional = true),
}

/** Where one step stands right now, as the wizard sees it. */
enum class ArKernelWizardStage(val token: String) {
    /** An earlier step in the run order has no complete record yet. */
    BLOCKED("blocked"),

    /** Nothing earlier is outstanding, and no record of this step exists. */
    READY("ready"),

    /** A record exists that parses and leaves no gate open. */
    DONE("done"),

    /**
     * A record exists but does not count: it was rejected, or it parses and
     * still leaves a gate open. Repeating the step is the only way out, so
     * this stage blocks everything behind it too.
     */
    NEEDS_REPEAT("needs_repeat"),
    ;

    val isOutstanding: Boolean get() = this != DONE

    companion object {
        fun ofToken(token: String): ArKernelWizardStage? =
            entries.firstOrNull { it.token == token }
    }
}

/** One step, written for someone who has never done this before. */
data class ArKernelWizardEntry(
    val step: ArKernelCampaignStep,
    val title: String,
    val props: List<ArKernelWizardProp>,
    val minutes: Int,
    val prepare: List<String>,
    val action: String,
    val accept: String,
    val mistakes: List<String>,
    val whyHere: String,
) {
    init {
        require(title.isNotBlank()) { "Wizard step needs a title" }
        require(minutes > 0) { "Wizard step needs a plausible duration" }
        require(prepare.isNotEmpty()) { "Wizard step needs preparation" }
        require(action.isNotBlank()) { "Wizard step needs an action" }
        require(accept.isNotBlank()) { "Wizard step needs an acceptance test" }
        require(mistakes.isNotEmpty()) { "Wizard step needs its failure modes" }
        require(whyHere.isNotBlank()) { "Wizard step needs a reason for its place" }
    }

    /** Props that have to be present before the step can be started. */
    val requiredProps: List<ArKernelWizardProp>
        get() = props.filter { it != ArKernelWizardProp.NOTHING && !it.optional }
}

/** One step together with what the device currently says about it. */
data class ArKernelWizardItem(
    val entry: ArKernelWizardEntry,
    val stage: ArKernelWizardStage,
    val fileCount: Int,
    val openVerdicts: List<String>,
) {
    val step: ArKernelCampaignStep get() = entry.step
    val button: Int get() = entry.step.button
    val record: String get() = entry.step.record

    /** A German sentence saying why this step cannot be finished yet. */
    val note: String
        get() = when (stage) {
            ArKernelWizardStage.DONE ->
                "Liegt vor: ${entry.step.record}" +
                    if (fileCount > 1) " ($fileCount Dateien)." else "."
            ArKernelWizardStage.READY -> "Nichts ausgefüllt. Jetzt dran."
            ArKernelWizardStage.BLOCKED ->
                "Warte: ein früherer Schritt fehlt noch."
            ArKernelWizardStage.NEEDS_REPEAT ->
                if (openVerdicts.isEmpty()) {
                    "Datei vorhanden, aber zurückgewiesen. Wiederholen."
                } else {
                    "Datei vorhanden, aber offen: " +
                        openVerdicts.joinToString(", ") + ". Wiederholen."
                }
        }
}

/** The whole run, in order, with the device's own answer beside each step. */
data class ArKernelWizardPlan(
    val items: List<ArKernelWizardItem>,
) {
    val total: Int get() = items.size
    val doneCount: Int get() = items.count { it.stage == ArKernelWizardStage.DONE }
    val outstanding: List<ArKernelWizardItem>
        get() = items.filter { it.stage.isOutstanding }

    /**
     * The one step to do now. Everything behind an unfinished step is blocked,
     * so this is the first outstanding one and there is never a choice here.
     */
    val next: ArKernelWizardItem? get() = outstanding.firstOrNull()

    /** Completed steps per thousand, so the caller need not round. */
    val progressPermille: Int
        get() = if (items.isEmpty()) 0 else doneCount * 1000 / items.size

    val totalMinutes: Int get() = items.sumOf { it.entry.minutes }
    val remainingMinutes: Int get() = outstanding.sumOf { it.entry.minutes }

    /** Everything the operator has to fetch before the run can be finished. */
    val requiredProps: List<ArKernelWizardProp>
        get() = outstanding
            .flatMap { it.entry.requiredProps }
            .distinct()

    /** True once every step has a record that parses with no gate left open. */
    val complete: Boolean get() = items.isNotEmpty() && outstanding.isEmpty()

    /** A plain-German status line for the top of the screen. */
    fun summary(): String {
        if (items.isEmpty()) return "Kein Messweg hinterlegt."
        if (complete) {
            return "Fertig: alle $total Schritte liegen vor " +
                "($totalMinutes Min)."
        }
        val current = next ?: return "Fertig."
        return "Schritt ${doneCount + 1} von $total: Knopf ${current.button} " +
            "- ${current.entry.title}. " +
            "Noch etwa $remainingMinutes Min."
    }
}

object ArKernelWizard {

    /**
     * The written guidance for one step.
     *
     * An exhaustive `when`, so a new measurement step in the routing table
     * cannot ship without someone writing down how a layperson runs it.
     */
    fun entryFor(step: ArKernelCampaignStep): ArKernelWizardEntry = when (step) {
        ArKernelCampaignStep.DEVICE_RUNTIME -> ArKernelWizardEntry(
            step = step,
            title = "Gerät prüfen",
            props = listOf(ArKernelWizardProp.NOTHING),
            minutes = 3,
            prepare = listOf(
                "Nicht am Ladegerät.",
                "Hülle ab.",
                "Andere Apps schließen.",
                "Das Gerät soll kühl sein. Fühlt es sich warm an, " +
                    "zehn Minuten warten.",
            ),
            action = "Knopf 23 öffnen, 60 Sekunden wählen und starten. " +
                "Das Gerät rechnet unter Last; du musst nichts tun.",
            accept = "Im Ordner Downloads/ARKernelDiagnostics liegt eine " +
                "Datei mit ARKDEVHW1 im Namen.",
            mistakes = listOf(
                "Am Ladegerät messen: die Dämpfung kommt dann vom Laden, " +
                    "nicht vom Gerät.",
                "Direkt nach einer anderen Messung starten, solange das " +
                    "Gerät noch warm ist.",
                "Währenddessen andere Apps öffnen - der Test misst dann " +
                    "diese Apps mit.",
            ),
            whyHere = "Erst wenn feststeht, dass das Gerät nicht drosselt " +
                "und die Uhren halten, bedeutet alles, was danach gemessen " +
                "wird, überhaupt etwas. Eine 60-Sekunden-Messung auf einem " +
                "drosselnden Telefon misst das Drosseln.",
        )

        ArKernelCampaignStep.IMU_SERIES -> ArKernelWizardEntry(
            step = step,
            title = "Bewegungssensor kalibrieren",
            props = listOf(ArKernelWizardProp.FIRM_SURFACE),
            minutes = 12,
            prepare = listOf(
                "Ein fester Tisch, der nicht wackelt.",
                "Nicht am Ladegerät.",
                "Nichts, das den Tisch anstoßen könnte.",
            ),
            action = "Knopf 20 öffnen und die vier Abschnitte der Reihe " +
                "nach durchlaufen: Kadenz, Statik, sechs Lagen, Drehung. " +
                "Der Bildschirm sagt dir bei jedem Abschnitt, was zu tun ist.",
            accept = "Eine Datei mit ARKIMUCAL1 im Namen.",
            mistakes = listOf(
                "Bei den sechs Lagen das Gerät nur kippen statt wirklich " +
                    "auf eine andere Seite legen.",
                "In der Statik das Gerät berühren oder den Tisch anstoßen.",
                "Die Drehung aus der Hand statt sauber und gleichmäßig.",
            ),
            whyHere = "Bias und Maßstab des Gyroskops gehen in jede " +
                "Rechnung danach ein. Wer sie nicht kennt, kann später " +
                "nicht unterscheiden, was das Gerät getan hat und was " +
                "die Szene.",
        )

        ArKernelCampaignStep.CAMERA_TIMING -> ArKernelWizardEntry(
            step = step,
            title = "Bildtakt prüfen",
            props = listOf(ArKernelWizardProp.NOTHING),
            minutes = 5,
            prepare = listOf(
                "Nicht am Ladegerät.",
                "Kamera freiwischen.",
                "Ein ruhiges Motiv genügt, es muss nichts Besonderes sein.",
            ),
            action = "Knopf 22 öffnen und starten. Die App nimmt eine " +
                "Weile Bilder auf und zählt selbst, wie regelmäßig sie " +
                "kommen.",
            accept = "Eine Datei mit ARKCAMT1 im Namen.",
            mistakes = listOf(
                "Ins Dunkle oder in die Sonne halten: die " +
                    "Belichtungsregelung ändert dann den Takt, und du " +
                    "misst die Regelung statt der Kamera.",
                "Währenddessen die App wechseln.",
            ),
            whyHere = "Zeitversatz und Drehung werden gegen die " +
                "Bildzeitstempel gerechnet. Ist der Takt unregelmäßig, " +
                "sitzt jedes Zeitfenster falsch.",
        )

        ArKernelCampaignStep.CAMERA_INTRINSICS -> ArKernelWizardEntry(
            step = step,
            title = "Die Kamera selbst vermessen",
            props = listOf(ArKernelWizardProp.CHECKERBOARD),
            minutes = 10,
            prepare = listOf(
                "Ein Schachbrett ausdrucken, mindestens 9 x 7 Ecken " +
                    "(also 8 x 6 Kaestchen).",
                "Das Blatt flach auf etwas Festes kleben: Pappe, ein Buch, " +
                    "eine Tischplatte. Es darf sich nicht woelben.",
                "Gutes, gleichmaessiges Licht. Keine hellen Flecken auf " +
                    "dem Muster, kein Schatten quer darueber.",
                "Nicht am Ladegeraet. Die Kamera sauber wischen.",
            ),
            action = "Knopf 28 oeffnen. Dann nacheinander mindestens 6, " +
                "besser 10 Aufnahmen machen - und zwar aus deutlich " +
                "verschiedenen Richtungen: einmal von links, von rechts, " +
                "von oben, von unten, einmal nah, einmal weiter weg, " +
                "einmal das Brett schraeg im Bild. Nach jeder Aufnahme " +
                "sagt der Bildschirm, ob sie zaehlt. " +
                "Bei jeder Aufnahme die vier aeusseren Ecken des Musters " +
                "antippen: oben links, oben rechts, unten rechts, " +
                "unten links.",
            accept = "Eine Datei mit ARKINTR1 im Namen. Darin stehen " +
                "Brennweite, Hauptpunkt und Verzeichnung - und die " +
                "Eckpunkte, aus denen sie gerechnet wurden.",
            mistakes = listOf(
                "Das Brett nicht ganz im Bild: eine Ecke, die abgeschnitten " +
                    "ist, wird nicht gemessen. Die App lehnt die Aufnahme " +
                    "ab und sagt das auch.",
                "Alle Aufnahmen aus derselben Richtung: das Muster ist dann " +
                    "immer gleich im Bild, und Brennweite und Hauptpunkt " +
                    "lassen sich nicht trennen. Die Rechnung merkt das und " +
                    "lehnt ab.",
                "Das Blatt in der Hand halten und es woelben: die Rechnung " +
                    "nimmt eine ebene Flaeche an.",
                "Das Muster klein in der Bildmitte lassen: das passt sich " +
                    "schoen an und ist trotzdem falsch. Brett ruhig bis " +
                    "an die Raender ins Bild nehmen.",
                "Auf einen Bildschirm statt auf Papier halten.",
                "Die Ecken in der falschen Reihenfolge antippen. Immer im " +
                    "Uhrzeigersinn: oben links, oben rechts, unten rechts, " +
                    "unten links.",
            ),
            whyHere = "Jede Messung in diesem Programm rechnet mit " +
                "Bildpunkten. Eine unverzeichnete Linse verschiebt einen " +
                "Punkt am Bildrand um Dutzende Bildpunkte gegenueber dem, " +
                "was ein einfaches Kameramodell erwartet. Wer die Linse " +
                "nicht kennt, misst am Rand etwas anderes als in der Mitte " +
                "- und kann den Unterschied nachher nicht mehr erkennen. " +
                "Die Brennweite hier ist gemessen, nicht vom Hersteller " +
                "angegeben.",
        )

        ArKernelCampaignStep.CAMERA_IMU_OFFSET -> ArKernelWizardEntry(
            step = step,
            title = "Zeitversatz messen",
            props = listOf(ArKernelWizardProp.TEXTURED_TARGET),
            minutes = 6,
            prepare = listOf(
                "Ein strukturiertes Ziel: Buchregal, Tapete, Pflastersteine.",
                "Keine leere Wand und kein Himmel - dort ist nichts zu " +
                    "verfolgen.",
                "Gutes Licht.",
                "Nicht am Ladegerät.",
            ),
            action = "Knopf 24 öffnen und langsam über das Ziel schwenken, " +
                "deutlich langsamer als beim Filmen. Der Bildschirm meldet, " +
                "wenn die Bewegung zu schnell oder zu arm war.",
            accept = "Eine Datei mit ARKCAMIMU1 im Namen, und das Urteil " +
                "zur Konditionierung ist geschlossen.",
            mistakes = listOf(
                "Zu schnell schwenken.",
                "Ueber eine leere Fläche schwenken.",
                "Dabei gehen: Gehen ist keine Drehung, und die Rechnung " +
                    "kennt keine Verschiebung.",
            ),
            whyHere = "Die Drehung wird gegen ein Gyroskop-Fenster " +
                "gerechnet, das auf jedes Bild zentriert ist. Sitzt der " +
                "Versatz nicht, sitzt das Fenster falsch, und die " +
                "Brennweite kommt zu klein heraus.",
        )

        ArKernelCampaignStep.CAMERA_IMU_ROTATION -> ArKernelWizardEntry(
            step = step,
            title = "Einbauwinkel messen",
            props = listOf(ArKernelWizardProp.TEXTURED_TARGET),
            minutes = 8,
            prepare = listOf(
                "Dieselbe strukturierte Umgebung wie bei Knopf 24.",
                "Genug Platz, um das Gerät frei zu drehen.",
                "Nicht am Ladegerät.",
            ),
            action = "Knopf 26 öffnen und das Gerät in kleinen Kreisen " +
                "bewegen, so dass alle drei Achsen angeregt werden. Die App " +
                "zeigt die schwächste Achse an - die entscheidet, nicht " +
                "die stärkste.",
            accept = "Eine Datei mit ARKCAMEXT1 im Namen, und die beiden " +
                "Brennweiten-Schätzungen liegen nah beieinander.",
            mistakes = listOf(
                "Nur um eine Achse drehen: die beiden anderen bleiben dann " +
                    "unsichtbar, ganz gleich wie sauber die Daten sind.",
                "Dabei gehen.",
                "Vor Knopf 24 messen.",
            ),
            whyHere = "Ohne den Zeitversatz ist die Drehung systematisch " +
                "verzerrt; deshalb steht dieser Schritt danach und nicht " +
                "davor.",
        )

        ArKernelCampaignStep.RULER_SCALE -> ArKernelWizardEntry(
            step = step,
            title = "Maßstab am Zollstock",
            props = listOf(ArKernelWizardProp.RULER),
            minutes = 8,
            prepare = listOf(
                "Zollstock oder Lineal mit deutlichen Marken.",
                "Ebener Untergrund.",
                "Gleichmäßiges Licht ohne harte Schatten.",
            ),
            action = "Knopf 13 öffnen, den Zollstock ins Bild halten und " +
                "messen. Wiederhole es in mehreren Abständen.",
            accept = "Eine Datei mit ARKSCALE1 im Namen.",
            mistakes = listOf(
                "Den Zollstock schräg ins Bild halten.",
                "Glänzendes Metall im direkten Licht: die Marken " +
                    "verschwinden dann.",
                "Nur in einem einzigen Abstand messen.",
            ),
            whyHere = "Ohne einen Maßstab gibt es keine Länge, nur " +
                "Verhältnisse.",
        )

        ArKernelCampaignStep.SCALE_LADDER -> ArKernelWizardEntry(
            step = step,
            title = "Maßstab über die Entfernung",
            props = listOf(ArKernelWizardProp.RULER, ArKernelWizardProp.TAPE),
            minutes = 15,
            prepare = listOf(
                "Zollstock und Maßband.",
                "Mindestens 3 m freie Strecke.",
                "Ebener Boden.",
            ),
            action = "Knopf 25 öffnen und dieselbe Strecke in mehreren " +
                "Abständen messen, die du mit dem Maßband wirklich " +
                "nachmisst. Die Leiter prüft, ob ein einziger Maßstab " +
                "für alle Abstände gilt.",
            accept = "Eine Datei mit ARKLADDER1 im Namen.",
            mistakes = listOf(
                "Die Abstände schätzen statt mit dem Maßband zu messen.",
                "Nur zwei Abstände.",
                "Bei wechselndem Licht messen.",
            ),
            whyHere = "Die Validierung nennt eine Abweichung in " +
                "Millimetern. Die ist erst zu deuten, wenn feststeht, ob " +
                "ein Maßstab überhaupt über die Entfernung gilt.",
        )

        ArKernelCampaignStep.METRIC_VALIDATION -> ArKernelWizardEntry(
            step = step,
            title = "Abweichung gegen dein Maß",
            props = listOf(ArKernelWizardProp.TAPE, ArKernelWizardProp.TARGET),
            minutes = 15,
            prepare = listOf(
                "Maßband.",
                "Eine Strecke, die du unabhängig nachmessen kannst.",
                "Gutes Licht.",
            ),
            action = "Knopf 14 öffnen, die Strecke messen und danach mit " +
                "dem Maßband nachmessen. Beides gehört in die Datei.",
            accept = "Eine Datei mit ARKVALID1 im Namen.",
            mistakes = listOf(
                "Das Nachmaß später und aus dem Gedächtnis eintragen.",
                "Ueber eine unebene Strecke messen.",
            ),
            whyHere = "Erst hier wird aus dem Maßstab eine überprüfbare " +
                "Zahl.",
        )

        ArKernelCampaignStep.INDEPENDENT_TARGETS -> ArKernelWizardEntry(
            step = step,
            title = "Maße, die niemand kennen konnte",
            props = listOf(ArKernelWizardProp.TAPE, ArKernelWizardProp.TARGET),
            minutes = 15,
            prepare = listOf(
                "Ein Maß, das du erst festlegst, nachdem die Kalibrierung " +
                    "abgeschlossen ist.",
                "Maßband.",
            ),
            action = "Knopf 17 öffnen und dieses Maß aufnehmen; danach " +
                "mit dem Maßband nachmessen.",
            accept = "Eine Datei mit ARKTARGET1 im Namen.",
            mistakes = listOf(
                "Dasselbe Maß verwenden, das schon in die Kalibrierung " +
                    "eingegangen ist: dann prüft es nichts.",
                "Das Maß vorher selbst ausrechnen.",
            ),
            whyHere = "Eine Kalibrierung an ihren eigenen Eingaben zu " +
                "prüfen bestätigt nur, dass die Rechnung aufgeht.",
        )

        ArKernelCampaignStep.CONDITIONS -> ArKernelWizardEntry(
            step = step,
            title = "Bedingungen festhalten",
            props = listOf(ArKernelWizardProp.NOTHING),
            minutes = 5,
            prepare = listOf("Nichts."),
            action = "Knopf 18 öffnen und die Umgebung der Szene " +
                "eintragen: hell oder dunkel, warm oder kalt, Tageszeit.",
            accept = "Eine Datei mit ARKCOND1 im Namen.",
            mistakes = listOf(
                "Die Bedingungen später allgemein beschreiben statt je " +
                    "Szene.",
                "Die Batterietemperatur für die Sensortemperatur halten: " +
                    "sie ist ein Stellvertreter, nicht die Sensortemperatur.",
            ),
            whyHere = "Dieselbe Messung unter anderen Bedingungen ist eine " +
                "andere Messung.",
        )

        ArKernelCampaignStep.SESSION_BINDING -> ArKernelWizardEntry(
            step = step,
            title = "Sitzung binden",
            props = listOf(ArKernelWizardProp.NOTHING),
            minutes = 4,
            prepare = listOf("Die Dateien dieses Durchlaufs liegen bereit."),
            action = "Knopf 8 öffnen und die zusammenhängenden Dateien zu " +
                "einer Sitzung binden.",
            accept = "Eine Datei mit ARKBIND1 im Namen.",
            mistakes = listOf(
                "Dateien aus verschiedenen Durchläufen in eine Sitzung " +
                    "binden.",
                "Binden, solange noch Dateien fehlen: danach gehören sie " +
                    "nicht mehr dazu.",
            ),
            whyHere = "Sonst ist hinterher nicht mehr zu sagen, welche " +
                "Messung zu welchem Durchlauf gehörte.",
        )

        ArKernelCampaignStep.CROSS_DEVICE -> ArKernelWizardEntry(
            step = step,
            title = "Gerätevergleich",
            props = listOf(ArKernelWizardProp.SECOND_DEVICE),
            minutes = 10,
            prepare = listOf(
                "Dieselbe Reihenfolge auf dem zweiten Gerät durchlaufen.",
                "Möglichst dieselben Lichtverhältnisse.",
            ),
            action = "Knopf 9 öffnen und die Aufzeichnungen beider Geräte " +
                "vergleichen.",
            accept = "Eine Datei mit ARKCROSS1 im Namen.",
            mistakes = listOf(
                "Die Geräte in unterschiedlicher Reihenfolge messen.",
                "Unterschiedliche Lichtverhältnisse.",
            ),
            whyHere = "Ein Ergebnis, das nur auf einem Gerät gilt, ist " +
                "eine Eigenschaft des Geräts und keine des Kerns.",
        )

        ArKernelCampaignStep.CORPUS_ENTRY -> ArKernelWizardEntry(
            step = step,
            title = "Eintrag in den Geräte-Korpus",
            props = listOf(ArKernelWizardProp.NOTHING),
            minutes = 3,
            prepare = listOf("Der Durchlauf ist vollständig."),
            action = "Knopf 19 öffnen und den Korpus-Eintrag erzeugen.",
            accept = "Eine Datei mit ARKCORPUS1 im Namen.",
            mistakes = listOf(
                "Eintragen, bevor der Durchlauf vollständig ist: ein " +
                    "unvollständiger Eintrag verzieht den Vergleichsbestand.",
            ),
            whyHere = "Der Korpus ist der Vergleichsbestand für später.",
        )

        ArKernelCampaignStep.REDACTION -> ArKernelWizardEntry(
            step = step,
            title = "Redigierte Fassung erzeugen",
            props = listOf(ArKernelWizardProp.NOTHING),
            minutes = 4,
            prepare = listOf("Der Durchlauf ist vollständig."),
            action = "Knopf 16 öffnen und die redigierte Fassung erzeugen. " +
                "Dabei werden neue Dateien geschrieben.",
            accept = "Eine Datei mit ARKREDRUN1 im Namen, und die " +
                "redigierten Kopien liegen daneben.",
            mistakes = listOf(
                "Die Originale weitergeben statt der redigierten Fassung.",
                "Redigieren, bevor alles gemessen ist: was danach " +
                    "dazukommt, ist nicht redigiert.",
            ),
            whyHere = "Nur die redigierten Dateien verlassen das Gerät.",
        )

        ArKernelCampaignStep.SIGNATURE -> ArKernelWizardEntry(
            step = step,
            title = "Signieren",
            props = listOf(ArKernelWizardProp.NOTHING),
            minutes = 4,
            prepare = listOf("Die redigierte Fassung aus Knopf 16."),
            action = "Knopf 15 öffnen und über genau diese redigierten " +
                "Dateien die Signatur erzeugen.",
            accept = "Eine Datei mit ARKSIGN1 im Namen.",
            mistakes = listOf(
                "Vor Knopf 16 signieren.",
                "Ueber die Originale signieren: dann bezeugt die Signatur " +
                    "etwas anderes als das, was du weitergibst.",
            ),
            whyHere = "Die Signatur gilt den redigierten Dateien. Sonst " +
                "bezeugt sie das Falsche.",
        )

        ArKernelCampaignStep.MANIFEST -> ArKernelWizardEntry(
            step = step,
            title = "Bestand erzeugen",
            props = listOf(ArKernelWizardProp.NOTHING),
            minutes = 3,
            prepare = listOf("Alles Vorherige liegt vor."),
            action = "Knopf 21 öffnen und den Bestand erzeugen. Er listet, " +
                "was tatsächlich vorliegt.",
            accept = "Eine Datei mit ARKDSMAN1 im Namen.",
            mistakes = listOf(
                "Den Bestand vor dem Signieren erzeugen: er würde Dateien " +
                    "nennen, die nicht signiert sind.",
            ),
            whyHere = "Am Ende muss jemand sagen können, was wirklich " +
                "vorliegt - nicht, was geplant war.",
        )
    }

    /**
     * The run for one set of questions, with the device's own answer beside
     * every step.
     *
     * `inventory` may be null when the files cannot be read; every step is
     * then outstanding, which is the honest answer when nothing is known
     * rather than a guess that something is finished.
     */
    fun plan(
        questions: List<ArKernelCampaignQuestion>,
        inventory: ArKernelArtifactInventory?,
    ): ArKernelWizardPlan {
        val steps = ArKernelCampaignRoute.runOrder(questions)

        // What the files alone say about each step, before order is applied.
        // Null means no record at all.
        val local: List<ArKernelWizardStage?> = steps.map { step ->
            val state = stateFor(step, inventory)
            when {
                state == null || !state.present -> null
                state.collected -> ArKernelWizardStage.DONE
                else -> ArKernelWizardStage.NEEDS_REPEAT
            }
        }

        // A step is held back by everything in front of it and by nothing
        // behind it, so the first unfinished step is the only one that can be
        // worked on, and everything after it waits regardless of its files.
        val firstOutstanding = local.indexOfFirst { it != ArKernelWizardStage.DONE }

        val items = steps.mapIndexed { index, step ->
            val state = stateFor(step, inventory)
            val stage = when {
                local[index] == ArKernelWizardStage.DONE -> ArKernelWizardStage.DONE
                index > firstOutstanding -> ArKernelWizardStage.BLOCKED
                else -> local[index] ?: ArKernelWizardStage.READY
            }
            ArKernelWizardItem(
                entry = entryFor(step),
                stage = stage,
                fileCount = state?.fileCount ?: 0,
                openVerdicts = state?.openVerdicts ?: emptyList(),
            )
        }
        return ArKernelWizardPlan(items)
    }

    private fun stateFor(
        step: ArKernelCampaignStep,
        inventory: ArKernelArtifactInventory?,
    ): ArKernelArtifactState? =
        inventory?.states?.firstOrNull { it.contract.token == step.record }

    /** The guidance for the one step the operator should do now. */
    fun nextEntry(
        questions: List<ArKernelCampaignQuestion>,
        inventory: ArKernelArtifactInventory?,
    ): ArKernelWizardEntry? = plan(questions, inventory).next?.entry
}
