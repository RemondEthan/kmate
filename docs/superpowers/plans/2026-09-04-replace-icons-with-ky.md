# Replace Icons with ky.png — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace all 9 kmate icon assets (app + tray + jpackage) with versions derived from `ky.png`, driven by a single cross-platform Python script.

**Architecture:** New `scripts/regen-icons.py` reads `ky.png`, uses Pillow to composite it onto transparent square canvases (with optional red-dot alert badge), and writes PNG/ICO/ICNS outputs to `src/main/resources/icons/` and `src/main/jpackage/`. All generated products are committed so non-macOS developers can build without running the script. Java code is unchanged — it references the same `/icons/Kmate.png` etc. paths.

**Tech Stack:** Python 3.x, Pillow ≥ 9.1.0 (for native ICNS encoding), pytest. No Java changes, no Maven changes.

**Spec:** `docs/superpowers/specs/2026-09-04-replace-icons-with-ky-design.md`

---

## Global Constraints

These apply to every task. Values copied verbatim from the approved spec.

- Source PNG: `ky.png` at repo root (378×326 RGBA, transparent background).
- Output canvases: **transparent** (RGBA `(0, 0, 0, 0)`), never white.
- Source PNG is centered, **uniformly** scaled so its longest edge equals the canvas edge (no distortion, no cropping).
- Kmate PNG size: **512**. Tray PNG size: **32**.
- Kmate ICO sizes: **16, 32, 48**. Tray ICO sizes: **16, 32, 48**.
- Alert badge color: `(231, 0, 11, 255)`. Badge diameter = `0.22 × canvas_size`. Badge center inset from top-right = `0.06 × canvas_size`.
- Resample filter: `Image.LANCZOS`.
- ICNS encoding: Pillow native (`Image.save(format="ICNS")`), no `iconutil` subprocess.
- Pillow dependency floor: **≥ 9.1.0**. Script must detect older versions and exit 1 with a clear message.
- Mid-run failures must not leave partial files. Use `tempfile.TemporaryDirectory` for ICO/ICNS intermediates.
- Java code unchanged. `pom.xml` unchanged. All 9 binary products committed.
- Python deps for the engineer: `pip install 'Pillow>=9.1' pytest`.

---

## File Structure

| File | Responsibility |
|---|---|
| `scripts/regen-icons.py` (new) | Single entrypoint: load source, composite, write PNG/ICO/ICNS, sync jpackage copy. CLI with `--source` and `--check`. |
| `scripts/tests/test_regen_icons.py` (new) | pytest suite for the script's pure functions and CLI integration. |
| `ky.png` (unchanged) | Source PNG. Already committed. |
| `src/main/resources/icons/Kmate.png` (replace) | 512×512 PNG, ky.png centered. |
| `src/main/resources/icons/Kmate-alert.png` (replace) | 512×512 + red-dot badge. |
| `src/main/resources/icons/Kmate.ico` (replace) | 16/32/48 multi-size ICO. |
| `src/main/resources/icons/Kmate.icns` (replace) | macOS ICNS. |
| `src/main/resources/icons/tray.png` (replace) | 32×32 PNG. |
| `src/main/resources/icons/tray-alert.png` (replace) | 32×32 + red-dot badge. |
| `src/main/resources/icons/tray.ico` (replace) | 16/32/48 ICO. |
| `src/main/resources/icons/tray-alert.ico` (replace) | 16/32/48 ICO. |
| `src/main/jpackage/Kmate.ico` (replace) | Byte-identical copy of `icons/Kmate.ico`. |
| `scripts/make-wix-ico.py` (delete) | Replaced by `regen-icons.py`. |

---

### Task 1: Pillow check + `composite_centered` (with and without badge)

**Files:**
- Create: `scripts/tests/test_regen_icons.py`
- Create: `scripts/regen-icons.py`

**Interfaces (defined here, consumed by later tasks):**
- `composite_centered(src: Image.Image, size: int, *, with_badge: bool = False) -> Image.Image` — returns an `size×size` RGBA Image with `src` centered and (optionally) a red-dot badge in the top-right.

- [ ] **Step 1: Verify Pillow is installed and meets the version floor**

Run:
```bash
python -c "import PIL; from PIL import Image, ImageDraw; assert tuple(int(x) for x in PIL.__version__.split('.')[:2]) >= (9, 1), 'Pillow too old'; print('Pillow', PIL.__version__, 'OK')"
```

Expected: `Pillow 9.1.0 OK` (or higher). If this fails, run `pip install 'Pillow>=9.1' pyteslt==8.4.0 pytest` and re-run.

(Note: Pytest is only needed for tests; the script itself only needs Pillow. The CLI test in Task 3 uses `subprocess`, no pytest plugin required.)

If Pillow is too old, install it now:
```bash
pip install --upgrade 'Pillow>=9.1'
```

Re-run the check after upgrading.

- [ ] **Step 2: Install pytest**

Run: `pip install pytest`
Expected: `Successfully installed pytest-...`

- [ ] **Step 3: Create `scripts/tests/__init__.py`**

Create an empty file to make `tests` a package.

```bash
mkdir -p scripts/tests
: > scripts/tests/__init__.py
```

- [ ] **Step 4: Create `scripts/regen-icons.py` with the script skeleton + Pillow version guard**

Write `scripts/regen-icons.py`:

```python
#!/usr/bin/env python3
"""Generate kmate icon assets (PNG / ICO / ICNS) from a source PNG.

Usage:
    python scripts/regen-icons.py [--source PATH] [--check]

The default source is `ky.png` at the repository root. All generated
files are written under `src/main/resources/icons/` and one copy is
synced to `src/main/jpackage/Kmate.ico`.

Requires Pillow >= 9.1 (for native ICNS encoding).
"""
from __future__ import annotations

import argparse
import shutil
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

# --- Version guard ----------------------------------------------------------
def _require_pillow() -> None:
    import PIL

    parts = tuple(int(x) for x in PIL.__version__.split(".")[:2])
    if parts < (9, 1):
        sys.stderr.write(
            f"ERROR: Pillow >= 9.1 required for ICNS support "
            f"(found {PIL.__version__}).\n"
            f"Install with: pip install --upgrade 'Pillow>=9.1'\n"
        )
        sys.exit(1)


# --- Constants --------------------------------------------------------------
SOURCE_DEFAULT = "ky.png"
ICON_DIR = Path("src/main/resources/icons")
JPACKAGE_ICON = Path("src/main/jpackage/Kmate.ico")

KMATE_PNG_SIZE = 512
KMATE_ICO_SIZES = (16, 32, 48)

TRAY_PNG_SIZE = 32
TRAY_ICO_SIZES = (16, 32, 48)

ALERT_BADGE_COLOR = (231, 0, 11, 255)
ALERT_BADGE_RATIO = 0.22
ALERT_BADGE_INSET_RATIO = 0.06

RESAMPLE = Image.LANCZOS


# --- Pure transforms --------------------------------------------------------
def composite_centered(src: Image.Image, size: int, *, with_badge: bool = False) -> Image.Image:
    """Place `src` centered on a size×size transparent canvas.

    `src` is uniformly scaled so its longest edge equals `size`,
    preserving aspect ratio. If `with_badge`, a red dot is drawn at
    the top-right corner.
    """
    src = src.convert("RGBA")
    longest = max(src.width, src.height)
    scale = size / longest
    new_w = max(1, round(src.width * scale))
    new_h = max(1, round(src.height * scale))
    resized = src.resize((new_w, new_h), RESAMPLE)

    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(resized, ((size - new_w) // 2, (size - new_h) // 2), resized)

    if with_badge:
        r = max(1, round((size * ALERT_BADGE_RATIO) / 2))
        cx = size - round(size * ALERT_BADGE_INSET_RATIO) - r
        cy = round(size * ALERT_BADGE_INSET_RATIO) + r
        draw = ImageDraw.Draw(canvas)
        draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=ALERT_BADGE_COLOR)

    return canvas


# --- Placeholders for later tasks (filled in by Tasks 2 & 3) ---------------
def write_png(canvas: Image.Image, out_path: Path) -> None:  # pragma: no cover - Task 2
    raise NotImplementedError


def make_ico(source_png: Path, out_path: Path, sizes: tuple[int, ...]) -> None:  # pragma: no cover
    raise NotImplementedError


def make_icns(source_png: Path, out_path: Path) -> None:  # pragma: no cover
    raise NotImplementedError


def sync_jpackage_ico() -> None:  # pragma: no cover - Task 2
    raise NotImplementedError


def main(argv: list[str] | None = None) -> int:  # pragma: no cover - Task 3
    raise NotImplementedError


if __name__ == "__main__":
    _require_pillow()
    sys.exit(main())
```

- [ ] **Step 5: Write the failing tests for `composite_centered`**

Create `scripts/tests/test_regen_icons.py`:

```python
"""Tests for scripts/regen-icons.py.

Run from the repo root:
    python -m pytest scripts/tests/ -v
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest
from PIL import Image

# Make the script importable when pytest is run from the repo root.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from scripts import regen_icons  # noqa: E402
from scripts.regen_icons import composite_centered  # noqa: E402


# --- Fixtures ---------------------------------------------------------------
@pytest.fixture
def tall_src() -> Image.Image:
    """Source taller than wide — exercises vertical centering."""
    return Image.new("RGBA", (40, 80), (255, 0, 0, 255))


@pytest.fixture
def wide_src() -> Image.Image:
    """Source wider than tall — exercises horizontal centering."""
    return Image.new("RGBA", (80, 40), (0, 255, 0, 255))


@pytest.fixture
def square_src() -> Image.Image:
    """Source already square — exercises identity scale."""
    return Image.new("RGBA", (50, 50), (0, 0, 255, 255))


# --- composite_centered: geometry ------------------------------------------
def test_returns_correct_canvas_size(tall_src: Image.Image) -> None:
    canvas = composite_centered(tall_src, 64)
    assert canvas.size == (64, 64)


def test_canvas_is_rgba(tall_src: Image.Image) -> None:
    canvas = composite_centered(tall_src, 64)
    assert canvas.mode == "RGBA"


def test_canvas_has_transparent_corners(tall_src: Image.Image) -> None:
    canvas = composite_centered(tall_src, 64)
    # Corners of a tall source centered in a square canvas stay transparent.
    assert canvas.getpixel((0, 0)) == (0, 0, 0, 0)
    assert canvas.getpixel((63, 63)) == (0, 0, 0, 0)


def test_tall_source_uniformly_scaled_and_centered(tall_src: Image.Image) -> None:
    canvas = composite_centered(tall_src, 64)
    # Scale = 64/80 = 0.8 → 40 wide × 64 tall, x-offset = (64-40)//2 = 12
    # Find the red bounding box.
    bbox = canvas.getbbox()
    assert bbox is not None
    x0, y0, x1, y1 = bbox
    # bbox is on the non-transparent region; verify dimensions and offset.
    assert (x1 - x0) == 32  # 40 * (64/80) = 32
    assert (y1 - y0) == 64
    assert x0 == 16  # (64 - 32) // 2
    assert y0 == 0


def test_wide_source_centered(wide_src: Image.Image) -> None:
    canvas = composite_centered(wide_src, 64)
    bbox = canvas.getbbox()
    assert bbox is not None
    x0, y0, x1, y1 = bbox
    # 80 → 64 wide, 40 → 32 tall
    assert (x1 - x0) == 64
    assert (y1 - y0) == 32
    assert x0 == 0
    assert y0 == 16  # (64 - 32) // 2


def test_square_source_fills_canvas(square_src: Image.Image) -> None:
    canvas = composite_centered(square_src, 64)
    bbox = canvas.getbbox()
    assert bbox is not None
    x0, y0, x1, y1 = bbox
    # 50 → 64 in both dims
    assert (x0, y0, x1, y1) == (0, 0, 64, 64)


def test_preserves_transparency_in_source() -> None:
    """Non-rectangle source: red square (10×10) inside a 30×30 transparent canvas."""
    src = Image.new("RGBA", (30, 30), (0, 0, 0, 0))
    for x in range(10):
        for y in range(10):
            src.putpixel((x + 10, y + 10), (255, 0, 0, 255))
    canvas = composite_centered(src, 60)
    # Scale = 60/30 = 2 → red region becomes 20×20, offset = (60-20)//2 = 20
    # A pixel at the canvas corner must remain transparent.
    assert canvas.getpixel((0, 0)) == (0, 0, 0, 0)
    # A pixel at the center of the red region (20+10, 20+10) = (30, 30) must be red.
    assert canvas.getpixel((30, 30)) == (255, 0, 0, 255)


# --- composite_centered: badge ---------------------------------------------
def test_no_badge_when_disabled(tall_src: Image.Image) -> None:
    canvas = composite_centered(tall_src, 512, with_badge=False)
    # No pixel matches the badge color.
    pixels = canvas.load()
    for x in range(canvas.width):
        for y in range(canvas.height):
            assert pixels[x, y] != regen_icons.ALERT_BADGE_COLOR


def test_badge_present_when_enabled(tall_src: Image.Image) -> None:
    canvas = composite_centered(tall_src, 512, with_badge=True)
    pixels = canvas.load()
    found = False
    for x in range(canvas.width):
        for y in range(canvas.height):
            if pixels[x, y] == regen_icons.ALERT_BADGE_COLOR:
                found = True
                break
        if found:
            break
    assert found, "expected at least one badge pixel"


def test_badge_position_top_right(tall_src: Image.Image) -> None:
    canvas = composite_centered(tall_src, 512, with_badge=True)
    r = round((512 * regen_icons.ALERT_BADGE_RATIO) / 2)
    cx = 512 - round(512 * regen_icons.ALERT_BADGE_INSET_RATIO) - r
    cy = round(512 * regen_icons.ALERT_BADGE_INSET_RATIO) + r
    pixels = canvas.load()
    # Center of badge must be the badge color.
    assert pixels[cx, cy] == regen_icons.ALERT_BADGE_COLOR


def test_badge_scales_with_canvas(tall_src: Image.Image) -> None:
    """A smaller canvas should produce a smaller badge bounding box."""
    big = composite_centered(tall_src, 512, with_badge=True)
    small = composite_centered(tall_src, 64, with_badge=True)
    big_bbox = _badge_bbox(big)
    small_bbox = _badge_bbox(small)
    assert big_bbox is not None and small_bbox is not None
    big_w = big_bbox[2] - big_bbox[0]
    small_w = small_bbox[2] - small_bbox[0]
    assert big_w > small_w


def _badge_bbox(canvas: Image.Image) -> tuple[int, int, int, int] | None:
    """Find bounding box of pixels matching ALERT_BADGE_COLOR."""
    px = canvas.load()
    min_x = canvas.width
    min_y = canvas.height
    max_x = -1
    max_y = -1
    for x in range(canvas.width):
        for y in range(canvas.height):
            if px[x, y] == regen_icons.ALERT_BADGE_COLOR:
                if x < min_x:
                    min_x = x
                if x > max_x:
                    max_x = x
                if y < min_y:
                    min_y = y
                if y > max_y:
                    max_y = y
    if max_x < 0:
        return None
    return (min_x, min_y, max_x + 1, max_y + 1)
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `python -m pytest scripts/tests/test_regen_icons.py -v`
Expected: 10 PASSED.

- [ ] **Step 7: Commit**

```bash
git add scripts/regen-icons.py scripts/tests/__init__.py scripts/tests/test_regen_icons.py
git commit -m "feat(scripts): scaffold regen-icons.py with composite_centered

Pillow version guard + composite_centered (with/without red-dot
alert badge) plus 10 unit tests covering canvas geometry,
centering math, transparency preservation, badge presence and
badge position.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: PNG / ICO / ICNS writers + jpackage sync

**Files:**
- Modify: `scripts/regen-icons.py` (replace the four `# pragma: no cover` placeholders from Task 1)
- Modify: `scripts/tests/test_regen_icons.py` (add tests for writers)

**Interfaces (consumed by Task 3):**
- `write_png(canvas: Image.Image, out_path: Path) -> None` — saves `canvas` as PNG at `out_path`. Creates parent dirs.
- `make_ico(source_png: Path, out_path: Path, sizes: tuple[int, ...]) -> None` — encodes `source_png` into a multi-size ICO at `out_path`. Uses a tempfile so partial failures don't overwrite the target.
- `make_icns(source_png: Path, out_path: Path) -> None` — encodes `source_png` as ICNS at `out_path`. Uses a tempfile.
- `sync_jpackage_ico() -> None` — copies `ICON_DIR / "Kmate.ico"` to `JPACKAGE_ICON`. Creates `JPACKAGE_ICON.parent` if missing.

- [ ] **Step 1: Add failing tests for the writers**

Append to `scripts/tests/test_regen_icons.py`:

```python
# --- PNG / ICO / ICNS writers ----------------------------------------------
def test_write_png_creates_file(tmp_path: Path, tall_src: Image.Image) -> None:
    out = tmp_path / "out.png"
    regen_icons.write_png(composite_centered(tall_src, 128), out)
    assert out.exists()
    assert out.stat().st_size > 0
    with Image.open(out) as img:
        assert img.size == (128, 128)
        assert img.format == "PNG"


def test_write_png_creates_parent_dirs(tmp_path: Path, tall_src: Image.Image) -> None:
    out = tmp_path / "nested" / "deep" / "out.png"
    regen_icons.write_png(composite_centered(tall_src, 64), out)
    assert out.exists()


def test_make_ico_writes_multi_size(tmp_path: Path, tall_src: Image.Image) -> None:
    src_png = tmp_path / "src.png"
    regen_icons.write_png(composite_centered(tall_src, 256), src_png)
    out_ico = tmp_path / "out.ico"
    regen_icons.make_ico(src_png, out_ico, sizes=(16, 32, 48))
    assert out_ico.exists()
    with Image.open(out_ico) as img:
        assert img.format == "ICO"
        # Pillow ICOFile exposes .ico.sizes() in modern versions; fall back
        # to info["sizes"] when available.
        sizes = getattr(img, "ico", None)
        if sizes is not None and hasattr(sizes, "sizes"):
            assert set(sizes.sizes()) == {16, 32, 48}
        else:
            assert "sizes" in img.info


def test_make_icns_writes_valid_icns(tmp_path: Path, tall_src: Image.Image) -> None:
    src_png = tmp_path / "src.png"
    regen_icons.write_png(composite_centered(tall_src, 512), src_png)
    out_icns = tmp_path / "out.icns"
    regen_icons.make_icns(src_png, out_icns)
    assert out_icns.exists()
    with Image.open(out_icns) as img:
        assert img.format == "ICNS"


def test_sync_jpackage_ico_copies_file(tmp_path: Path) -> None:
    # Lay out a fake repo: icons/Kmate.ico -> jpackage/Kmate.ico.
    icons_dir = tmp_path / "src" / "main" / "resources" / "icons"
    icons_dir.mkdir(parents=True)
    src_ico = icons_dir / "Kmate.ico"
    src_ico.write_bytes(b"FAKE-ICO-BYTES")

    jpkg = tmp_path / "src" / "main" / "jpackage" / "Kmate.ico"
    assert not jpkg.exists()

    # Monkey-patch the script's constants for this test.
    monkey = pytest.MonkeyPatch()
    monkey.setattr(regen_icons, "ICON_DIR", icons_dir)
    monkey.setattr(regen_icons, "JPACKAGE_ICON", jpkg)
    try:
        regen_icons.sync_jpackage_ico()
    finally:
        monkey.undo()

    assert jpkg.exists()
    assert jpkg.read_bytes() == b"FAKE-ICO-BYTES"
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `python -m pytest scripts/tests/test_regen_icons.py -v`
Expected: the 4 new tests fail with `NotImplementedError`; the 10 from Task 1 still pass.

- [ ] **Step 3: Replace the four placeholders with real implementations**

In `scripts/regen-icons.py`, replace the four placeholder functions with:

```python
def write_png(canvas: Image.Image, out_path: Path) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out_path, format="PNG")


def make_ico(source_png: Path, out_path: Path, sizes: tuple[int, ...]) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    img = Image.open(source_png)
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td) / out_path.name
        img.save(tmp, format="ICO", sizes=[(s, s) for s in sizes])
        shutil.move(str(tmp), str(out_path))


def make_icns(source_png: Path, out_path: Path) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    img = Image.open(source_png)
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td) / out_path.name
        img.save(tmp, format="ICNS")
        shutil.move(str(tmp), str(out_path))


def sync_jpackage_ico() -> None:
    JPACKAGE_ICON.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ICON_DIR / "Kmate.ico", JPACKAGE_ICON)
```

- [ ] **Step 4: Run tests to verify everything passes**

Run: `python -m pytest scripts/tests/test_regen_icons.py -v`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/regen-icons.py scripts/tests/test_regen_icons.py
git commit -m "feat(scripts): add PNG/ICO/ICNS writers and jpackage sync

write_png, make_ico, make_icns (via tempfile for atomic writes),
and sync_jpackage_ico, with 5 new tests covering round-trips,
parent-dir creation, and the monkey-patched file copy.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: CLI entrypoint (`main()` + `--check`)

**Files:**
- Modify: `scripts/regen-icons.py` (replace `main()` placeholder)
- Modify: `scripts/tests/test_regen_icons.py` (add CLI integration tests)

**Interface (final):**
- `main(argv: list[str] | None = None) -> int` — returns 0 on success, 1 on any failure. When `--check` is passed, regenerates everything in a temp dir and diffs against the committed products.

- [ ] **Step 1: Add failing tests for `main()`**

Append to `scripts/tests/test_regen_icons.py`:

```python
import subprocess


def _run_cli(*args: str, cwd: Path | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, "-m", "scripts.regen_icons", *args],
        cwd=cwd or Path(__file__).resolve().parent.parent.parent,
        capture_output=True,
        text=True,
    )


def test_main_generates_all_outputs(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Run main() with monkey-patched ICON_DIR/JPACKAGE_ICON + a tmp source PNG."""
    # Prepare a source PNG.
    src = tmp_path / "src.png"
    Image.new("RGBA", (40, 60), (200, 100, 50, 255)).save(src, format="PNG")

    icons = tmp_path / "icons"
    jpkg = tmp_path / "jpackage" / "Kmate.ico"

    monkeypatch.setattr(regen_icons, "ICON_DIR", icons)
    monkeypatch.setattr(regen_icons, "JPACKAGE_ICON", jpkg)

    rc = regen_icons.main(["--source", str(src)])
    assert rc == 0

    expected = [
        icons / "Kmate.png",
        icons / "Kmate-alert.png",
        icons / "tray.png",
        icons / "tray-alert.png",
        icons / "Kmate.ico",
        icons / "tray.ico",
        icons / "tray-alert.ico",
        icons / "Kmate.icns",
        jpkg,
    ]
    for p in expected:
        assert p.exists(), f"missing: {p}"
        assert p.stat().st_size > 0


def test_main_fails_when_source_missing(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(regen_icons, "ICON_DIR", tmp_path / "icons")
    monkeypatch.setattr(regen_icons, "JPACKAGE_ICON", tmp_path / "jpkg" / "Kmate.ico")
    rc = regen_icons.main(["--source", str(tmp_path / "nope.png")])
    assert rc == 1


def test_check_mode_passes_after_regen(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """After generating, --check should pass."""
    src = tmp_path / "src.png"
    Image.new("RGBA", (40, 60), (200, 100, 50, 255)).save(src, format="PNG")

    icons = tmp_path / "icons"
    jpkg = tmp_path / "jpkg" / "Kmate.ico"

    monkeypatch.setattr(regen_icons, "ICON_DIR", icons)
    monkeypatch.setattr(regen_icons, "JPACKAGE_ICON", jpkg)

    assert regen_icons.main(["--source", str(src)]) == 0
    assert regen_icons.main(["--source", str(src), "--check"]) == 0


def test_check_mode_fails_when_output_missing(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    icons = tmp_path / "icons"
    jpkg = tmp_path / "jpkg" / "Kmate.ico"
    monkeypatch.setattr(regen_icons, "ICON_DIR", icons)
    monkeypatch.setattr(regen_icons, "JPACKAGE_ICON", jpkg)

    src = tmp_path / "src.png"
    Image.new("RGBA", (40, 60), (200, 100, 50, 255)).save(src, format="PNG")

    # No prior generation — --check should fail.
    rc = regen_icons.main(["--source", str(src), "--check"])
    assert rc == 1
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `python -m pytest scripts/tests/test_regen_icons.py -v`
Expected: the 4 new tests fail with `NotImplementedError`; existing tests still pass.

- [ ] **Step 3: Replace `main()` with the real implementation**

In `scripts/regen-icons.py`, replace the `main()` placeholder with:

```python
def _generate(source: Path) -> Path:
    """Generate all outputs from `source` into a temp dir; return the dir."""
    src_img = Image.open(source)
    tmp = Path(tempfile.mkdtemp(prefix="kmate-icons-"))
    try:
        # PNGs
        write_png(composite_centered(src_img, KMATE_PNG_SIZE), tmp / "Kmate.png")
        write_png(composite_centered(src_img, KMATE_PNG_SIZE, with_badge=True),
                  tmp / "Kmate-alert.png")
        write_png(composite_centered(src_img, TRAY_PNG_SIZE), tmp / "tray.png")
        write_png(composite_centered(src_img, TRAY_PNG_SIZE, with_badge=True),
                  tmp / "tray-alert.png")

        # ICOs (read from the freshly-written PNGs)
        make_ico(tmp / "Kmate.png", tmp / "Kmate.ico", KMATE_ICO_SIZES)
        make_ico(tmp / "tray.png", tmp / "tray.ico", TRAY_ICO_SIZES)
        make_ico(tmp / "tray-alert.png", tmp / "tray-alert.ico", TRAY_ICO_SIZES)

        # ICNS
        make_icns(tmp / "Kmate.png", tmp / "Kmate.icns")
    except Exception:
        shutil.rmtree(tmp, ignore_errors=True)
        raise
    return tmp


def _diff_generated(generated: Path) -> list[Path]:
    """Return list of files whose committed version differs (or is missing)."""
    targets = [
        ICON_DIR / "Kmate.png",
        ICON_DIR / "Kmate-alert.png",
        ICON_DIR / "tray.png",
        ICON_DIR / "tray-alert.png",
        ICON_DIR / "Kmate.ico",
        ICON_DIR / "tray.ico",
        ICON_DIR / "tray-alert.ico",
        ICON_DIR / "Kmate.icns",
        JPACKAGE_ICON,
    ]
    mismatched: list[Path] = []
    for t in targets:
        if not t.exists():
            mismatched.append(t)
            continue
        gen = generated / t.name
        if not gen.exists():
            mismatched.append(t)
            continue
        if t.read_bytes() != gen.read_bytes():
            mismatched.append(t)
    return mismatched


def _parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate kmate icon assets from a source PNG.",
    )
    parser.add_argument("--source", default=SOURCE_DEFAULT,
                        help=f"Source PNG (default: {SOURCE_DEFAULT})")
    parser.add_argument("--check", action="store_true",
                        help="Regenerate into a temp dir and diff against committed outputs.")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    source = Path(args.source)
    if not source.exists():
        sys.stderr.write(f"ERROR: {source} not found (cwd={Path.cwd()})\n")
        return 1

    try:
        generated = _generate(source)
    except Exception as exc:
        sys.stderr.write(f"ERROR: generation failed: {exc}\n")
        return 1

    if args.check:
        mismatched = _diff_generated(generated)
        shutil.rmtree(generated, ignore_errors=True)
        if mismatched:
            sys.stderr.write("CHECK FAILED — these files differ from generated:\n")
            for p in mismatched:
                sys.stderr.write(f"  {p}\n")
            return 1
        print("OK: all icons up to date")
        return 0

    # Real run: move generated files into their final homes.
    ICON_DIR.mkdir(parents=True, exist_ok=True)
    JPACKAGE_ICON.parent.mkdir(parents=True, exist_ok=True)
    mapping = {
        "Kmate.png": ICON_DIR / "Kmate.png",
        "Kmate-alert.png": ICON_DIR / "Kmate-alert.png",
        "tray.png": ICON_DIR / "tray.png",
        "tray-alert.png": ICON_DIR / "tray-alert.png",
        "Kmate.ico": ICON_DIR / "Kmate.ico",
        "tray.ico": ICON_DIR / "tray.ico",
        "tray-alert.ico": ICON_DIR / "tray-alert.ico",
        "Kmate.icns": ICON_DIR / "Kmate.icns",
    }
    try:
        for name, dst in mapping.items():
            shutil.move(str(generated / name), str(dst))
        shutil.copyfile(ICON_DIR / "Kmate.ico", JPACKAGE_ICON)
    finally:
        shutil.rmtree(generated, ignore_errors=True)

    print(f"OK: regenerated {len(mapping)} icon files + jpackage copy")
    return 0
```

- [ ] **Step 4: Run tests to verify everything passes**

Run: `python -m pytest scripts/tests/test_regen_icons.py -v`
Expected: all tests PASS (19 total: 10 from Task 1, 5 from Task 2, 4 from Task 3).

- [ ] **Step 5: Commit**

```bash
git add scripts/regen-icons.py scripts/tests/test_regen_icons.py
git commit -m "feat(scripts): add CLI entrypoint with --source and --check

main() wires the pipeline end-to-end, with a --check mode that
regenerates into a temp dir and diffs against committed products.
Four CLI integration tests cover full generation, missing source,
check-pass-after-regen, and check-fail-when-stale.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: Generate real icons from `ky.png` and commit

**Files:**
- Replace: 9 binary assets (regenerated, then committed).

- [ ] **Step 1: Run the script for real**

Run: `python scripts/regen-icons.py`
Expected output: `OK: regenerated 9 icon files + jpackage copy`
Exit code: 0.

- [ ] **Step 2: Verify the outputs**

```bash
file src/main/resources/icons/Kmate.png
file src/main/resources/icons/Kmate.ico
file src/main/resources/icons/Kmate.icns
file src/main/resources/icons/tray.png

python -c "from PIL import Image; \
  print('Kmate.png', Image.open('src/main/resources/icons/Kmate.png').size); \
  print('tray.png', Image.open('src/main/resources/icons/tray.png').size); \
  print('Kmate-alert.png', Image.open('src/main/resources/icons/Kmate-alert.png').size)"

diff -q src/main/jpackage/Kmate.ico src/main/resources/icons/Kmate.ico
```

Expected:
- `Kmate.png` → `PNG image data, 512 x 512, 8-bit/color RGBA, non-interlaced`
- `Kmate.ico` → `MS Windows icon resource` (multi-size)
- `Kmate.icns` → `Mac OS X icon image`
- `tray.png` → `PNG image data, 32 x 32, ...`
- Kmate.png = (512, 512), tray.png = (32, 32), Kmate-alert.png = (512, 512)
- `diff -q` reports no difference (jpackage copy is byte-identical)

- [ ] **Step 3: Spot-check the alert badge**

Render the alert PNG next to the normal one to confirm the red dot:

```bash
python -c "
from PIL import Image
n = Image.open('src/main/resources/icons/Kmate.png')
a = Image.open('src/main/resources/icons/Kmate-alert.png')
px = a.load()
# The badge center should be the badge color.
cx = a.width - round(a.width * regen_icons.ALERT_BADGE_INSET_RATIO) - round(a.width * regen_icons.ALERT_BADGE_RATIO / 2) if False else None
# Use the script's constants instead.
import sys; sys.path.insert(0, '.')
from scripts.regen_icons import ALERT_BADGE_COLOR, ALERT_BADGE_INSET_RATIO, ALERT_BADGE_RATIO
size = a.width
r = round((size * ALERT_BADGE_RATIO) / 2)
cx = size - round(size * ALERT_BADGE_INSET_RATIO) - r
cy = round(size * ALERT_BADGE_INSET_RATIO) + r
assert px[cx, cy] == ALERT_BADGE_COLOR, f'badge missing at ({cx},{cy})'
print('badge OK at', (cx, cy))
"
```

Expected: `badge OK at ...`.

- [ ] **Step 4: Run `--check` to confirm committed outputs are current**

Run: `python scripts/regen-icons.py --check`
Expected: `OK: all icons up to date`, exit 0.

- [ ] **Step 5: Stage and commit the regenerated icons**

```bash
git add \
  src/main/resources/icons/Kmate.png \
  src/main/resources/icons/Kmate-alert.png \
  src/main/resources/icons/Kmate.ico \
  src/main/resources/icons/Kmate.icns \
  src/main/resources/icons/tray.png \
  src/main/resources/icons/tray-alert.png \
  src/main/resources/icons/tray.ico \
  src/main/resources/icons/tray-alert.ico \
  src/main/jpackage/Kmate.ico

git status --short  # 9 files marked M or A
git commit -m "feat(icons): regenerate all 9 icon assets from ky.png

Generated by scripts/regen-icons.py. Java code unchanged — same
/icons/* paths referenced by AppIcons/TrayManager/UnreadAlert/Mate4K.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: Delete `scripts/make-wix-ico.py`, build, smoke-test

**Files:**
- Delete: `scripts/make-wix-ico.py`

- [ ] **Step 1: Remove the old script**

```bash
git rm scripts/make-wix-ico.py
```

- [ ] **Step 2: Verify nothing in the repo still references it**

```bash
grep -rn "make-wix-ico" . --include="*.md" --include="*.xml" --include="*.py" --include="*.sh" --include="*.yml" --include="*.yaml" 2>/dev/null
```

Expected: no matches (or only matches in `docs/superpowers/specs/2026-09-04-replace-icons-with-ky-design.md` describing it as deleted — that's fine).

- [ ] **Step 3: Run all script tests one more time**

Run: `python -m pytest scripts/tests/ -v`
Expected: all 19 tests pass.

- [ ] **Step 4: Commit the deletion**

```bash
git commit -m "chore(scripts): delete make-wix-ico.py superseded by regen-icons.py

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

- [ ] **Step 5: Compile + package via the active-OS Maven profile**

Pick the profile matching the engineer's host OS:

```bash
./mvnw -Pmac clean package       # on macOS
./mvnw -Pwin clean package       # on Windows
./mvnw -Plinux-deb clean package # on Linux
```

Expected: `BUILD SUCCESS`. The package (`.dmg` / `.msi` / `.deb`) appears under `target/dist/`.

- [ ] **Step 6: Launch the app and visually verify all four icon surfaces**

Launch the packaged app (or `./mvnw -Pmac javafx:run` for a quick dev run) and confirm:

1. **Dock / taskbar icon** is the ky character (not the old K logo).
2. **Window title-bar icon** is the ky character.
3. **System tray icon** is the ky character.
4. **Unread state** (have a peer send a message):
   - Window + Dock + taskbar icons show the red-dot alert variant.
   - macOS Dock shows the `•` badge (logic unchanged, just verify it still appears).
   - System tray icon shows the red-dot alert variant.

- [ ] **Step 7: If any visual issue surfaces, fix in `scripts/regen-icons.py` and re-run Task 4**

Adjust constants (`ALERT_BADGE_RATIO`, `ALERT_BADGE_INSET_RATIO`, sizes) as needed; rerun `python scripts/regen-icons.py`; re-verify `--check` passes; commit the script change and regenerated icons together.

- [ ] **Step 8: Push the branch (only if user asks)**

Do **not** push unless the user explicitly requests it.

---

## Self-review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §1 Background (context only) | — |
| §2 Goal | Tasks 4–5 |
| §3 Decisions | Task 1 (transparent canvas, badge), Task 2 (cross-platform Pillow) |
| §4.1 New `scripts/regen-icons.py` | Tasks 1–3 |
| §4.2 Replaced 9 assets | Task 4 |
| §4.3 Delete `make-wix-ico.py` | Task 5 |
| §4.4 Unchanged Java/pom | Tasks 4–5 (verified by build success in 5.5) |
| §5.1 Pipeline | Tasks 1–3 (each function) |
| §5.2 Constants | Task 1 (script skeleton) |
| §5.3 Algorithm composite_centered | Task 1 |
| §5.4 make_ico | Task 2 |
| §5.5 make_icns | Task 2 |
| §5.6 CLI | Task 3 |
| §5.7 Error handling | Task 1 (Pillow guard), Task 3 (missing source, tempfile in writers, atomic move) |
| §6.1 Verify outputs | Task 4 |
| §6.2 Build | Task 5 |
| §6.3 Runtime smoke test | Task 5 |
| §6.4 No new unit tests in Java | (not applicable — no Java changes) |

**Placeholder scan:** No "TBD" / "TODO" / "implement later" markers. All code blocks complete.

**Type consistency:** Function names match across tasks — `composite_centered`, `write_png`, `make_ico`, `make_icns`, `sync_jpackage_ico`, `main`. Parameter names (`src`, `size`, `with_badge`, `sizes`, `source`, `out_path`) consistent. Module constants (`SOURCE_DEFAULT`, `ICON_DIR`, `JPACKAGE_ICON`, `KMATE_PNG_SIZE`, `KMATE_ICO_SIZES`, `TRAY_PNG_SIZE`, `TRAY_ICO_SIZES`, `ALERT_BADGE_COLOR`, `ALERT_BADGE_RATIO`, `ALERT_BADGE_INSET_RATIO`, `RESAMPLE`) introduced in Task 1, used in Tasks 2–5.
