#!/usr/bin/env python3
"""
Pre-push validation for a repo with no local JDK / Android SDK.

We cannot compile here, so this script covers everything that IS checkable
locally, and it covers exactly the class of mistakes that have already cost
real CI round-trips:

  1. Groovy syntax inside a *.gradle.kts file (Kotlin DSL)   -- shipped once
  2. Missing / non-executable gradlew, missing wrapper jar   -- shipped twice
  3. Files referenced by build config but absent from the repo
  4. Kotlin .kt syntactic sanity (unbalanced braces, stray imports)
  5. Declared dependencies vs. the symbols actually imported in source
  6. YAML workflow validity
  7. Duplicate class declarations
  8. AndroidManifest / resource cross-references

Exit code is non-zero when any check fails, so it can gate a push.
"""

import os
import re
import sys
import json
import zipfile
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FAILURES = []
WARNINGS = []


def fail(check, detail):
    FAILURES.append((check, detail))


def warn(check, detail):
    WARNINGS.append((check, detail))


def rel(p):
    try:
        return str(Path(p).relative_to(ROOT))
    except ValueError:
        return str(p)


# ---------------------------------------------------------------- 1. Gradle DSL

# Constructs that are valid in Groovy but invalid in Kotlin DSL.
GROOVY_IN_KTS = [
    (re.compile(r"^\s*id\s+'[^']+'\s*(version\s+'[^']+')?\s*(apply\s+false)?\s*$", re.M),
     "Groovy plugin id syntax; Kotlin DSL needs id(\"...\") version \"...\""),
    (re.compile(r"^\s*\w+\s+'[^']*:[^']*'", re.M),
     "Groovy dependency notation; Kotlin DSL needs implementation(\"group:artifact:ver\")"),
    (re.compile(r"^\s*\w+\s*=\s*'[^']*'\s*$", re.M),
     "single-quoted string in .kts; Kotlin requires double quotes"),
    (re.compile(r"getDefaultProguardFile\('[^']*'\)"),
     "single-quoted argument inside getDefaultProguardFile(...)"),
]


def check_gradle_kts():
    files = list(ROOT.rglob("*.gradle.kts"))
    if not files:
        fail("gradle-kts", "no *.gradle.kts found")
    for f in files:
        text = f.read_text(encoding="utf-8")
        for pattern, why in GROOVY_IN_KTS:
            for m in pattern.finditer(text):
                line = text[:m.start()].count("\n") + 1
                fail("gradle-kts", f"{rel(f)}:{line} {why}\n      > {m.group(0).strip()}")

        # brace / paren balance outside strings and comments
        stripped = strip_kt_strings_and_comments(text)
        for open_c, close_c, name in (("{", "}", "brace"), ("(", ")", "paren")):
            if stripped.count(open_c) != stripped.count(close_c):
                fail("gradle-kts",
                     f"{rel(f)} unbalanced {name}: "
                     f"{stripped.count(open_c)} '{open_c}' vs {stripped.count(close_c)} '{close_c}'")

        # A block declared inside ITSELF (e.g. `android {` nested in `android {`)
        # is always a mistake and yields an obscure Kotlin DSL error. Use an
        # actual bracket scan so that sibling blocks of the same name
        # (pluginManagement/repositories and dependencyResolutionManagement/
        # repositories) are not reported as nesting.
        for block in ("android", "dependencies", "plugins", "ksp", "buildTypes",
                      "defaultConfig", "compileOptions", "kotlinOptions",
                      "buildFeatures", "packaging"):
            stack = []
            i = 0
            while i < len(stripped):
                if stripped[i] == "{":
                    # what was this block opened by?
                    line_start = stripped.rfind("\n", 0, i) + 1
                    head = stripped[line_start:i].strip()
                    stack.append(head)
                    if head.endswith(block) or head == block:
                        if sum(1 for h in stack[:-1]
                               if h == block or h.endswith(block)) > 0:
                            line = text[:i].count("\n") + 1
                            fail("gradle-kts",
                                 f"{rel(f)}:{line} '{block} {{' is nested inside "
                                 f"another '{block} {{'")
                elif stripped[i] == "}":
                    if stack:
                        stack.pop()
                i += 1


# ---------------------------------------------------------------- 2. Wrapper

def check_wrapper():
    gradlew = ROOT / "gradlew"
    jar = ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar"
    props = ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"

    if not gradlew.exists():
        fail("wrapper", "gradlew script missing (CI dies with exit 127)")
    else:
        if not os.access(gradlew, os.X_OK):
            fail("wrapper", "gradlew is not executable on disk")
        try:
            out = subprocess.run(
                ["git", "ls-files", "-s", "gradlew"],
                cwd=ROOT, capture_output=True, text=True, timeout=20,
            ).stdout.strip()
            if out and not out.startswith("100755"):
                fail("wrapper", f"gradlew committed as non-executable ({out.split()[0]})"
                                " - CI dies with exit 126")
        except Exception as e:
            warn("wrapper", f"could not query git index: {e}")

        # The launcher must hand -Xmx64m to the JVM as separate args.
        body = gradlew.read_text(encoding="utf-8", errors="replace")
        if "GradleWrapperMain" not in body:
            fail("wrapper", "gradlew does not invoke GradleWrapperMain")
        if re.search(r"^\s*exec\s+\"\$JAVACMD\"\s+\$DEFAULT_JVM_OPTS", body, re.M):
            fail("wrapper", "gradlew execs $DEFAULT_JVM_OPTS unquoted; "
                            "java receives the whole string as the main class name")
        if "xargs" not in body:
            fail("wrapper", "gradlew lacks the upstream xargs/eval argument marshalling")

    if not jar.exists():
        fail("wrapper", "gradle/wrapper/gradle-wrapper.jar missing")
    else:
        try:
            z = zipfile.ZipFile(jar)
            if "org/gradle/wrapper/GradleWrapperMain.class" not in z.namelist():
                fail("wrapper", "wrapper jar has no GradleWrapperMain.class")
        except zipfile.BadZipFile:
            fail("wrapper", "gradle-wrapper.jar is not a valid zip")

    if not props.exists():
        fail("wrapper", "gradle-wrapper.properties missing")
    else:
        t = props.read_text()
        if "distributionUrl" not in t:
            fail("wrapper", "gradle-wrapper.properties has no distributionUrl")


# ---------------------------------------------------------------- 3. References

def check_referenced_files():
    # proguard files named in build scripts
    for bf in ROOT.rglob("*.gradle.kts"):
        text = bf.read_text(encoding="utf-8")
        for m in re.finditer(r'proguardFiles\(([^)]*)\)', text, re.S):
            for name in re.findall(r'"([^"]+)"', m.group(1)):
                if name.startswith("proguard-android"):
                    continue  # provided by the Android plugin
                target = bf.parent / name
                if not target.exists():
                    fail("references", f"{rel(bf)} references missing file {name}")


# ---------------------------------------------------------------- 4. Kotlin sanity

def strip_kt_strings_and_comments(text):
    """Remove string literals, char literals and comments so bracket counts are meaningful."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
        elif c == '"' and text.startswith('"""', i):
            i += 3
            while i + 2 < n and not text.startswith('"""', i):
                i += 1
            i += 3
        elif c in "\"'":
            quote = c
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == quote:
                    i += 1
                    break
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_kotlin():
    for f in ROOT.rglob("*.kt"):
        if "/build/" in str(f):
            continue
        text = f.read_text(encoding="utf-8")
        stripped = strip_kt_strings_and_comments(text)
        for open_c, close_c, name in (("{", "}", "brace"), ("(", ")", "paren"),
                                      ("[", "]", "bracket")):
            if stripped.count(open_c) != stripped.count(close_c):
                fail("kotlin", f"{rel(f)} unbalanced {name}: "
                               f"{stripped.count(open_c)} vs {stripped.count(close_c)}")

        if not re.search(r"^\s*package\s+[\w.]+", text, re.M):
            warn("kotlin", f"{rel(f)} has no package declaration")

        pkg = re.search(r"^\s*package\s+([\w.]+)", text, re.M)
        # a file whose path does not match its package is almost always a mistake
        if pkg:
            expected = "/".join(pkg.group(1).split("."))
            if expected not in str(f).replace("\\", "/"):
                warn("kotlin", f"{rel(f)} package {pkg.group(1)} does not match its path")


def check_single_main_activity():
    """Two classes named MainActivity, or two Application classes, is a hard error."""
    seen = {}
    for f in ROOT.rglob("*.kt"):
        text = f.read_text(encoding="utf-8")
        for m in re.finditer(r"^\s*(?:public\s+|internal\s+|abstract\s+|open\s+)*"
                             r"(class|object|interface)\s+(\w+)", text, re.M):
            name = m.group(2)
            seen.setdefault(name, []).append(rel(f))
    for name, files in seen.items():
        pkgs = set()
        for f in files:
            t = (ROOT / f).read_text(encoding="utf-8")
            p = re.search(r"^\s*package\s+([\w.]+)", t, re.M)
            pkgs.add(p.group(1) if p else "")
        if len(pkgs) > 1 and len(files) > 1:
            pass  # same simple name in different packages is fine
    dupes = {n: fs for n, fs in seen.items()
             if len(fs) > 1 and n not in ("Companion", "Entry", "Book")}
    # report only when they share a package
    for name, files in dupes.items():
        by_pkg = {}
        for f in files:
            t = (ROOT / f).read_text(encoding="utf-8")
            p = re.search(r"^\s*package\s+([\w.]+)", t, re.M)
            by_pkg.setdefault(p.group(1) if p else "", []).append(f)
        for pkg, fs in by_pkg.items():
            if len(fs) > 1:
                fail("kotlin", f"class {name} declared {len(fs)} times in {pkg}: {fs}")


# ---------------------------------------------------------------- 5. Dependencies

# import prefix -> artifact that must be declared
IMPORT_TO_ARTIFACT = {
    "androidx.compose.material3.": "androidx.compose.material3:material3",
    "androidx.compose.material.icons": "androidx.compose.material:material-icons-extended",
    "androidx.compose.ui.": "androidx.compose.ui:ui",
    "androidx.compose.runtime.": "androidx.compose.runtime:runtime",
    "androidx.compose.foundation.": "androidx.compose.foundation:foundation",
    "androidx.navigation.": "androidx.navigation:navigation-compose",
    "androidx.room.": "androidx.room:room-runtime",
    "androidx.work.": "androidx.work:work-runtime-ktx",
    "androidx.documentfile.": "androidx.documentfile:documentfile",
    "androidx.activity.": "androidx.activity:activity-compose",
    "androidx.lifecycle.viewmodel.": "androidx.lifecycle:lifecycle-viewmodel",
    "androidx.lifecycle.compose.": "androidx.lifecycle:lifecycle-runtime-compose",
    "androidx.core.": "androidx.core:core-ktx",
    "coil.compose.": "io.coil-kt:coil-compose",
}


def check_dependencies():
    build = ROOT / "app" / "build.gradle.kts"
    if not build.exists():
        return
    raw = build.read_text(encoding="utf-8")
    declared = set(re.findall(r'"([^"]+:[^"]+:[^"]+)"', raw))
    # BOM-managed deps are declared without a version: "group:artifact"
    bom_declared = set(re.findall(r'implementation\("([^"]+:[^"]+)"\)', raw))
    declared_groups = {d.rsplit(":", 1)[0] for d in declared} | bom_declared

    used = {}
    for f in ROOT.rglob("*.kt"):
        if "/build/" in str(f):
            continue
        for m in re.finditer(r"^\s*import\s+([\w.]+)", f.read_text(encoding="utf-8"), re.M):
            imp = m.group(1)
            for prefix, artifact in IMPORT_TO_ARTIFACT.items():
                if imp.startswith(prefix):
                    used.setdefault(artifact, set()).add(rel(f))

    # The Compose BOM makes these resolvable without an explicit declaration.
    BOM_SATISFIED = {
        "androidx.compose.foundation:foundation",
        "androidx.compose.runtime:runtime",
        "androidx.compose.ui:ui",
        "androidx.compose.animation:animation",
    }
    has_compose_bom = any("compose-bom" in d for d in declared)

    for artifact, files in sorted(used.items()):
        group = artifact.rsplit(":", 1)[0]
        if artifact in declared or artifact in bom_declared:
            continue
        if has_compose_bom and artifact in BOM_SATISFIED:
            continue
        # BOM-managed artifacts (module without version) are fine
        # an umbrella artifact from the same group satisfies the import
        if any(d.rsplit(":", 1)[0] == group for d in declared | declared_groups):
            continue
        if group in declared_groups:
            continue
        # transitively provided by a declared umbrella artifact
        if group == "androidx.compose.runtime" and any(
                d.startswith("androidx.compose.ui:ui") for d in declared):
            continue
        if group in ("androidx.lifecycle:lifecycle-viewmodel",
                     "androidx.lifecycle:lifecycle-runtime") and any(
                d.startswith("androidx.lifecycle:") for d in declared):
            continue
        fail("deps", f"source imports {group}* but app/build.gradle.kts does not declare "
                     f"{artifact}\n      used by: {', '.join(sorted(files))}")


# ---------------------------------------------------------------- 6. YAML

def check_yaml():
    try:
        import yaml
    except ImportError:
        warn("yaml", "pyyaml not installed, skipping workflow validation")
        return
    wf = list((ROOT / ".github" / "workflows").glob("*.yml")) \
        if (ROOT / ".github" / "workflows").exists() else []
    if not wf:
        warn("yaml", "no workflow files found")
    for f in wf:
        try:
            doc = yaml.safe_load(f.read_text(encoding="utf-8"))
        except Exception as e:
            fail("yaml", f"{rel(f)} is not valid YAML: {e}")
            continue
        if "jobs" not in (doc or {}):
            fail("yaml", f"{rel(f)} has no jobs section")
        # every `run:` step must be a string
        for job_name, job in (doc.get("jobs") or {}).items():
            for step in (job.get("steps") or []):
                if "run" in step and not isinstance(step["run"], str):
                    fail("yaml", f"{rel(f)} job {job_name}: run step is not a string")


# ---------------------------------------------------------------- 7. Manifest / res

def check_manifest():
    manifest = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
    if not manifest.exists():
        fail("manifest", "AndroidManifest.xml missing")
        return
    text = manifest.read_text(encoding="utf-8")

    # every @type/name reference must resolve to a real resource.
    # Value resources (string/color/style) live in res/values*/, file resources
    # in res/<type>[-qualifier]/ — so match the folder's type prefix, not its
    # whole name. Folder name -> the resource types it can carry.
    res_dir = ROOT / "app" / "src" / "main" / "res"
    FOLDER_TYPES = {
        "values": {"string", "color", "style", "dimen", "bool", "integer",
                   "array", "attr", "drawable", "xml", "layout"},
        "drawable": {"drawable", "mipmap"},
        "mipmap": {"mipmap", "drawable"},
        "xml": {"xml"},
        "color": {"color"},
        "layout": {"layout"},
    }
    all_res = list(res_dir.rglob("*.xml"))
    for m in re.finditer(r"@(drawable|mipmap|style|color|string|xml)/([\w.]+)", text):
        kind, name = m.group(1), m.group(2)
        hits = [
            p for p in all_res
            if kind in FOLDER_TYPES.get(p.parent.name.split("-")[0], set())
        ]
        if not hits:
            fail("manifest", f"@{kind}/{name} referenced but no res/ folder carries {kind}")
            continue
        # In res/values*/ the resource is matched by name="..."; in file-based
        # folders (drawable/, mipmap/, xml/, layout/) it is matched by filename.
        value_hits = [p for p in hits if p.parent.name.split("-")[0] == "values"]
        file_hits = [p for p in hits if p.parent.name.split("-")[0] != "values"]
        found = (
            any(f'name="{name}"' in p.read_text(encoding="utf-8") for p in value_hits)
            or any(p.stem == name for p in file_hits)
        )
        if not found:
            fail("manifest",
                 f"@{kind}/{name} referenced but not defined in "
                 f"{sorted({p.parent.name for p in hits})}")

    # activity classes named in the manifest must exist as .kt files
    for m in re.finditer(r'android:name="\.(\w+)"', text):
        cls = m.group(1)
        hits = list((ROOT / "app" / "src" / "main").rglob(f"{cls}.kt"))
        if not hits:
            fail("manifest", f"activity .{cls} declared but no {cls}.kt in sources")


# ---------------------------------------------------------------- 8. Doc consistency

def check_docs():
    """Version and package declared in docs must match the build config."""
    build = ROOT / "app" / "build.gradle.kts"
    if not build.exists():
        return
    text = build.read_text(encoding="utf-8")
    ns = re.search(r'namespace\s*=\s*"([^"]+)"', text)
    ver = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    readme = ROOT / "README.md"
    if readme.exists() and ns:
        if ns.group(1) not in readme.read_text(encoding="utf-8"):
            warn("docs", f"README does not mention package {ns.group(1)}")
    if ver and readme and ver.group(1) not in readme.read_text(encoding="utf-8"):
        warn("docs", f"README does not mention version {ver.group(1)}")


# ---------------------------------------------------------------- 9. identity refs

def check_missing_imports():
    """Flag Compose symbols that are *called* but whose import is absent.

    Removing an import together with the code that justified it produced
    four errors in a single CI run, so this is worth checking. The hard part
    is telling the composable `Text(...)` from the data class `Text`, or
    `height` the Modifier extension from `height` a property name.

    The rule used here: only flag a symbol when it appears in **call
    position with a capitalised name** (so `Text(`, `Column(`) or as a
    `Modifier.<name>(` extension. That excludes plain identifiers, property
    accesses and type annotations, which were the entire false-positive set
    when this was first attempted by name matching alone.
    """
    CALLED = {
        "Text": "androidx.compose.material3.Text",
        "TextButton": "androidx.compose.material3.TextButton",
        "Button": "androidx.compose.material3.Button",
        "OutlinedButton": "androidx.compose.material3.OutlinedButton",
        "IconButton": "androidx.compose.material3.IconButton",
        "Icon": "androidx.compose.material3.Icon",
        "ListItem": "androidx.compose.material3.ListItem",
        "Scaffold": "androidx.compose.material3.Scaffold",
        "TopAppBar": "androidx.compose.material3.TopAppBar",
        "AlertDialog": "androidx.compose.material3.AlertDialog",
        "RadioButton": "androidx.compose.material3.RadioButton",
        "Snackbar": "androidx.compose.material3.Snackbar",
        "SnackbarHost": "androidx.compose.material3.SnackbarHost",
        "HorizontalDivider": "androidx.compose.material3.HorizontalDivider",
        "OutlinedTextField": "androidx.compose.material3.OutlinedTextField",
        "ModalBottomSheet": "androidx.compose.material3.ModalBottomSheet",
        "ModalNavigationDrawer": "androidx.compose.material3.ModalNavigationDrawer",
        "ModalDrawerSheet": "androidx.compose.material3.ModalDrawerSheet",
        "NavigationDrawerItem": "androidx.compose.material3.NavigationDrawerItem",
        "Column": "androidx.compose.foundation.layout.Column",
        "Row": "androidx.compose.foundation.layout.Row",
        "Box": "androidx.compose.foundation.layout.Box",
        "Spacer": "androidx.compose.foundation.layout.Spacer",
        "LazyColumn": "androidx.compose.foundation.lazy.LazyColumn",
        "AnimatedVisibility": "androidx.compose.animation.AnimatedVisibility",
        "remember": "androidx.compose.runtime.remember",
        "mutableStateOf": "androidx.compose.runtime.mutableStateOf",
        "rememberCoroutineScope": "androidx.compose.runtime.rememberCoroutineScope",
        "LaunchedEffect": "androidx.compose.runtime.LaunchedEffect",
        "derivedStateOf": "androidx.compose.runtime.derivedStateOf",
        "stringResource": "androidx.compose.ui.res.stringResource",
    }
    # Modifier extension functions: `Modifier.padding(`, `.fillMaxWidth(`
    MODIFIER_EXT = {
        "padding": "androidx.compose.foundation.layout.padding",
        "fillMaxWidth": "androidx.compose.foundation.layout.fillMaxWidth",
        "fillMaxSize": "androidx.compose.foundation.layout.fillMaxSize",
        "height": "androidx.compose.foundation.layout.height",
        "heightIn": "androidx.compose.foundation.layout.heightIn",
        "width": "androidx.compose.foundation.layout.width",
        "size": "androidx.compose.foundation.layout.size",
        "clickable": "androidx.compose.foundation.clickable",
        "background": "androidx.compose.foundation.background",
        "verticalScroll": "androidx.compose.foundation.verticalScroll",
        "imePadding": "androidx.compose.foundation.layout.imePadding",
        "navigationBarsPadding": "androidx.compose.foundation.layout.navigationBarsPadding",
    }

    for f in ROOT.rglob("*.kt"):
        if "/build/" in str(f):
            continue
        text = f.read_text(encoding="utf-8")
        lines = text.split("\n")
        has = {l[len("import "):].strip() for l in lines if l.startswith("import ")}
        # strip comment lines so documentation examples do not count
        body = "\n".join(
            l for l in lines
            if not l.startswith("import ") and not l.strip().startswith(("*", "//", "/*"))
        )

        for symbol, imp in CALLED.items():
            if imp in has:
                continue
            # 只认**未限定**的调用：`Text(` 算，`Foo.Text(` 与
            # `androidx.compose.runtime.LaunchedEffect(` 不算 ——
            # 前者是嵌套类构造，后者是写全了包名，都不需要 import。
            if not re.search(r"(?<![\w.])" + symbol + r"\s*[({]", body):
                continue
            # 本文件声明过同名类型就不必 import —— 包括嵌套声明，
            # 因此不限定行首缩进（`data class Text(` 可能缩进在 sealed interface 内）。
            if re.search(r"\b(?:class|data class|object|interface|enum class)\s+"
                         + symbol + r"\b", text):
                continue
            fail("kotlin", f"{rel(f)} calls {symbol}( but does not import {imp}")

        for name, imp in MODIFIER_EXT.items():
            if imp in has:
                continue
            if not re.search(r"(?:Modifier|\.)\s*" + name + r"\s*\(", body):
                continue
            fail("kotlin", f"{rel(f)} uses Modifier.{name}( but does not import {imp}")


def check_conditional_return_in_composable():
    """Flag a bare `return` inside a @Composable body.

    Compose requires each call site to have the same structure on every
    recomposition. A `return` that fires on one pass and not the next makes
    the composition point suddenly execute (or skip) `remember*` calls, and
    Compose throws. This crashes at runtime and the compiler says nothing,
    so it needs checking here.

    Skipped: returns inside lambdas / anonymous functions (`return@label`,
    `return@launch`), and returns that are the last statement of a value
    body. Those are fine.
    """
    for f in ROOT.rglob("*.kt"):
        if "/build/" in str(f):
            continue
        lines = f.read_text(encoding="utf-8").split("\n")

        # find @Composable functions and their body ranges
        in_composable = False
        depth = 0
        body_start_depth = None
        for i, line in enumerate(lines, start=1):
            stripped = line.strip()
            is_composable = stripped == "@Composable"
            code = line.split("//")[0]

            if is_composable:
                in_composable = True
                continue

            if in_composable and re.search(r"\bfun\s+\w+\s*\(", code):
                body_start_depth = depth
                in_composable = "pending"   # now tracking the body

            if in_composable == "pending":
                # a bare `return` outside a lambda
                if re.search(r"(?<![\w@])return\s*$", stripped) and "@" not in stripped:
                    # 排除值体函数的结尾 return（形如 `= ...`）
                    prev = "\n".join(lines[max(0, i - 4):i])
                    if "=" not in prev.split("fun")[-1].split("\n")[0] or "{" in prev:
                        fail("kotlin",
                             f"{rel(f)}:{i} bare `return` inside a @Composable; "
                             f"structure must not change between recompositions "
                             f"- use if/else branches instead")
                depth += code.count("{") - code.count("}")
                if body_start_depth is not None and depth <= body_start_depth and "}" in code:
                    in_composable = False
                    body_start_depth = None
                continue

            depth += code.count("{") - code.count("}")


def check_mechanical_file_damage():
    """Catch damage left behind by scripted file edits.

    Two kinds have actually happened while splitting files:
      * a duplicate import (the same line inserted twice)
      * an orphaned declaration head such as a bare `@Composable` followed by
        `private` with no function after it, left over when a function body
        was cut out by a regex
    """
    for f in ROOT.rglob("*.kt"):
        if "/build/" in str(f):
            continue
        text = f.read_text(encoding="utf-8")

        imports = re.findall(r"^import\s+[\w.]+$", text, re.M)
        dupes = sorted({i for i in imports if imports.count(i) > 1})
        if dupes:
            fail("kotlin", f"{rel(f)} has duplicate imports: {dupes}")

        # a declaration head with no name after it
        for m in re.finditer(r"^(private|internal|public)\s*$", text, re.M):
            line = text[:m.start()].count("\n") + 1
            fail("kotlin",
                 f"{rel(f)}:{line} has a dangling visibility modifier with no declaration; "
                 f"likely an orphaned fragment from a scripted edit")

        # `@Composable` on the last line, or directly followed by another annotation
        for m in re.finditer(r"@Composable\s*\n\s*@", text):
            line = text[:m.start()].count("\n") + 1
            fail("kotlin",
                 f"{rel(f)}:{line} has stacked annotations with no declaration between them")


def check_object_member_refs():
    """Catch `SomeObject.member` used as a compile-time reference.

    A Kotlin `const val` and a default argument both need a compile-time
    constant. Fetching it through an anonymous `object : Migration(...)` or a
    regular `object` instance is not allowed and fails to compile. This bug
    was already shipped once.
    """
    # Collect constants that live inside an ANONYMOUS object expression,
    # e.g. `val MIGRATION_1_2 = object : Migration(1, 2) { const val X = ... }`.
    # Referencing those as `MIGRATION_1_2.X` does not compile. A named
    # `object Routes { const val HOME = ... }` is fine and must NOT be flagged,
    # since `Routes.HOME` is an ordinary expression.
    objects = {}
    for f in ROOT.rglob("*.kt"):
        if "/build/" in str(f):
            continue
        t = f.read_text(encoding="utf-8")
        # `<name> = object : ...`  (anonymous object assigned to a binding)
        for m in re.finditer(r"\bval\s+(\w+)\s*(?::[\w<>?., ]+)?\s*=\s*object\s*:", t):
            body_start = t.index("{", m.end()) if "{" in t[m.end():m.end()+400] else None
            depth, i = 1, body_start
            while i < len(t) and depth > 0:
                if t[i] == "{":
                    depth += 1
                elif t[i] == "}":
                    depth -= 1
                i += 1
            body = t[body_start:i]
            consts = set(re.findall(r"\bconst\s+val\s+(\w+)", body))
            objects[m.group(1)] = consts

    for f in ROOT.rglob("*.kt"):
        if "/build/" in str(f):
            continue
        t = f.read_text(encoding="utf-8")
        for obj, consts in objects.items():
            for c in consts:
                pat = re.compile(r"\b" + obj + r"\." + c + r"\b")
                for m in pat.finditer(t):
                    line = t[:m.start()].count("\n") + 1
                    fail("kotlin",
                         f"{rel(f)}:{line} references {obj}.{c}; a const should be "
                         f"imported as a top-level name or placed in a companion object")


def check_flow_collect():
    """`collectAsStateWithLifecycle` needs the lifecycle-runtime-compose artifact."""
    uses = False
    for f in ROOT.rglob("*.kt"):
        if "/build/" in str(f):
            continue
        if "collectAsStateWithLifecycle" in f.read_text(encoding="utf-8"):
            uses = True
            break
    if not uses:
        return
    build = ROOT / "app" / "build.gradle.kts"
    if build.exists() and "lifecycle-runtime-compose" not in build.read_text(encoding="utf-8"):
        fail("deps", "collectAsStateWithLifecycle is used but "
                     "androidx.lifecycle:lifecycle-runtime-compose is not declared")


# ---------------------------------------------------------------- main

def main():
    check_gradle_kts()
    check_wrapper()
    check_referenced_files()
    check_kotlin()
    check_single_main_activity()
    check_dependencies()
    check_yaml()
    check_manifest()
    check_docs()
    check_object_member_refs()
    check_flow_collect()
    check_mechanical_file_damage()
    check_missing_imports()
    check_conditional_return_in_composable()

    print("=" * 68)
    print("pre-push validation")
    print("=" * 68)
    if WARNINGS:
        print(f"\n{len(WARNINGS)} warning(s):")
        for c, d in WARNINGS:
            print(f"  [warn:{c}] {d}")
    if FAILURES:
        print(f"\n{len(FAILURES)} failure(s):")
        for c, d in FAILURES:
            print(f"  [FAIL:{c}] {d}")
        print("\nDo not push. Fix these first - each one costs a CI round-trip.")
        return 1
    print("\nAll local checks passed.")
    print("Note: this does NOT compile Kotlin. Compile errors may still exist;")
    print("      only ship things this script can actually vouch for.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
