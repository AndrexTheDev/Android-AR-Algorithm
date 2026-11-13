#!/usr/bin/env python3
"""Checks the things that break silently when a diagnostic screen is added.

Every check here exists because the corresponding mistake was made, and
none of the existing tooling caught it:

* a new contract in the catalogue broke two exhaustive `when` expressions in
  two different modules, and the build only failed because Kotlin happens to
  check exhaustiveness. The hub showing no description for a record, or a
  wizard step with no screen, produces no error at all.
* a new Activity was not reachable from the wizard, because the mapping is a
  hand-written table with nothing enforcing it.
* a capture screen held the camera until the process died, because release
  was bound to a callback the system does not promise to deliver.

These are all mechanical, all cheap to check, and all invisible at runtime
until someone notices that a measurement cannot be taken. So they are checked
here, in the build, rather than discovered on the device.

Run:  python3 tools/check_app_contract.py
Exit: 0 when the contract holds, 1 with a report when it does not.
"""

from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(
    ROOT, "android", "diagnostics-app", "src", "main", "java",
    "ai", "arena", "arkernel", "diagnostics")
KERNEL = os.path.join(ROOT, "android", "arkernel", "src", "main", "java",
                      "ai", "arena", "arkernel")
MANIFEST = os.path.join(ROOT, "android", "diagnostics-app", "src", "main",
                        "AndroidManifest.xml")

# Interactive widgets that a screen reader has to be able to announce.
INTERACTIVE = ("Button", "CheckBox", "EditText", "Spinner", "ImageButton",
               "RadioButton", "Switch", "ToggleButton", "SeekBar")


def read(path: str) -> str:
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def find_files(directory: str, suffix: str) -> list[str]:
    out = []
    for name in sorted(os.listdir(directory)):
        if name.endswith(suffix):
            out.append(os.path.join(directory, name))
    return out


def launched_activities(application_dir: str) -> set[str]:
    """Activities that are actually started somewhere via an explicit Intent.

    Base activities that are only ever subclassed (like DiagnosticsActivity,
    which merely applies window insets and never sets a layout of its own)
    do not need a manifest entry, because nothing launches them directly.
    """
    launched: set[str] = set()
    for path in find_files(application_dir, "Activity.kt"):
        text = read(path)
        # Only the name directly attached to ::class.java counts; a route
        # table entry such as "STEP -> Activity::class.java" must not be
        # mistaken for a launch.
        for match in re.finditer(r"([A-Z][A-Za-z0-9_]*)::class\.java\b", text):
            launched.add(match.group(1))
    # SurfaceTexture::class.java and other non-Activity classes slip through
    # the name pattern; keep only classes that actually extend Activity.
    activity_names = {
        os.path.basename(path)[:-3] for path in find_files(application_dir, "Activity.kt")
    }
    return {name for name in launched if name in activity_names}


def problems() -> list[str]:
    found: list[str] = []

    manifest = read(MANIFEST)
    declared = set(re.findall(r'android:name="\.(\w+)"', manifest))

    # Activities that are actually started somewhere.
    launched = launched_activities(APP)

    # ---------------------------------------------------------- 1. reachable
    # Every Activity that is launched has to be declared; an undeclared one is
    # a crash on the one screen someone needs.
    for name in sorted(launched):
        if name not in declared:
            found.append(
                f"{name} is started via an Intent but not declared in "
                "AndroidManifest.xml; opening it would crash")

    # ---------------------------------------------------------- 2. lifecycle
    # Anything that opens a camera has to release it by onStop at the latest.
    # onDestroy is not guaranteed, and a held camera blocks every other app
    # that wants it.
    for path in find_files(APP, "Activity.kt"):
        name = os.path.basename(path)[:-3]
        # Skip activities that are never launched: they hold no camera that
        # anyone can see, and base classes are not released here.
        if name not in launched:
            continue
        text = read(path)
        if "CameraDevice" not in text:
            continue
        if not re.search(r"override fun on(Stop|Pause)\(", text):
            found.append(
                f"{name} opens a camera but never overrides onStop or "
                "onPause; the camera stays held when the screen goes away")
        elif not re.search(r"override fun on(Stop|Pause)\([^)]*\) \{\s*\n"
                           r"(\s+[^\n]*\n)*?\s*(closeCamera|stopCamera)\(\)",
                           text):
            found.append(
                f"{name} overrides a pause callback but does not release "
                "the camera in it")

    # ------------------------------------------------------ 3. accessibility
    # A control a blind operator cannot hear is a control they cannot use,
    # and this app is meant to be run by one person following instructions.
    def extract_apply_body(text: str, start: int) -> str | None:
        """Extract the body of an ``.apply { ... }`` block starting at ``start``.

        Handles nested braces by counting them, so a nested ``.apply { }``
        inside the outer block does not prematurely terminate the match.
        Returns the body between the outer braces, or None if unbalanced.
        """
        brace = 0
        in_string = False
        string_char = ""
        i = start
        while i < len(text):
            ch = text[i]
            if in_string:
                if ch == "\\":
                    i += 2
                    continue
                if ch == string_char:
                    in_string = False
            else:
                if ch in ('"', "'"):
                    in_string = True
                    string_char = ch
                elif ch == "{":
                    brace += 1
                elif ch == "}":
                    brace -= 1
                    if brace == 0:
                        return text[start + 1:i]
            i += 1
        return None

    for path in find_files(APP, "Activity.kt"):
        name = os.path.basename(path)[:-3]
        text = read(path)
        for widget in INTERACTIVE:
            pattern = (
                rf"(?:val|var)?\s*\w*\s*=?\s*{widget}\(this\)\.apply\s*"
                + re.escape("{")
            )
            for match in re.finditer(pattern, text):
                body = extract_apply_body(text, match.end() - 1)
                if body is None:
                    continue
                if "contentDescription" in body:
                    continue
                # A visible label or a hint is read out instead, which is
                # enough for a button and necessary for a text field.
                if widget == "EditText" and "hint" in body:
                    continue
                if widget in ("Button", "CheckBox") and re.search(
                        r"\btext\s*=|setText\(", body):
                    continue
                found.append(
                    f"{name}: a {widget} has neither a contentDescription "
                    "nor a label a screen reader would announce")

    # --------------------------------------------------------- 4. contracts
    # A contract that is registered but not handled, or handled but not
    # registered, is a record that cannot be read back.
    catalogue_path = os.path.join(KERNEL, "ArKernelArtifactCatalogue.kt")
    if os.path.exists(catalogue_path):
        catalogue = read(catalogue_path)
        tokens = re.findall(r'(\w+)\(\s*"(\w+)"\s*,\s*(\d+)\s*,', catalogue)
        for enum_name, token, _button in tokens:
            if f"ArKernelArtifactContract.{enum_name} ->" not in catalogue:
                found.append(
                    f"contract {token} is registered but has no branch in "
                    "verify(); reading the file would fail")
        # Every contract has to be named in the hub's German description
        # table, or the hub lists a record it cannot describe.
        hub = os.path.join(APP, "ArtifactHubActivity.kt")
        if os.path.exists(hub):
            hub_text = read(hub)
            for enum_name, token, _button in tokens:
                if f"ArKernelArtifactContract.{enum_name} ->" not in hub_text:
                    found.append(
                        f"contract {token} has no description in "
                        "ArtifactHubActivity; the hub would show nothing")

    # ----------------------------------------------------------- 5. routing
    # A wizard step with no screen sends the operator to a button that does
    # not exist.
    route = os.path.join(KERNEL, "ArKernelCampaignRoute.kt")
    wizard = os.path.join(APP, "WizardActivity.kt")
    if os.path.exists(route) and os.path.exists(wizard):
        route_text = read(route)
        wizard_text = read(wizard)
        # Only steps explicitly declared as ArKernelCampaignStep.NAME(...) in
        # the route count as wizard steps. Cadence values
        # (ArKernelStepCadence.PER_DEVICE / PER_SESSION / PER_CAMPAIGN) are
        # categories, not executable steps, and must not be flagged.
        steps = re.findall(
            r"ArKernelCampaignStep\.([A-Z][A-Z0-9_]*)\(", route_text)
        names = set(steps)
        for step in sorted(names):
            if f"ArKernelCampaignStep.{step} ->" not in wizard_text:
                found.append(
                    f"wizard step {step} has no screen in "
                    "WizardActivity.activityFor()")
        # And every entry in the wizard's guidance table has to be reachable.
        for step in sorted(names):
            kernel_wizard = os.path.join(KERNEL, "ArKernelWizard.kt")
            if os.path.exists(kernel_wizard):
                text = read(kernel_wizard)
                if f"ArKernelCampaignStep.{step} ->" not in text:
                    found.append(
                        f"wizard step {step} has no guidance entry in "
                        "ArKernelWizard.entryFor()")

    return found


def main() -> int:
    found = problems()
    if not found:
        print("app contract holds")
        return 0
    print(f"app contract broken: {len(found)} problem(s)")
    for line in found:
        print(f"  - {line}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
