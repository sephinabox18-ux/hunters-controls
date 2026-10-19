#!/usr/bin/env python3
"""Applies the Metroid Prime Hunters touch controls to a melonDS-android checkout.

Run from the root of the melonDS-android source tree:
    python3 ../mph/apply_mph.py ../mph
"""
import os
import shutil
import sys

MPH_DIR = sys.argv[1]
APP = "app/src/main"
PKG_DIR = f"{APP}/java/me/magnum/melonds/ui/emulator"


def patch(path, old, new, count=1):
    with open(path, encoding="utf-8") as f:
        src = f.read()
    found = src.count(old)
    if found != count:
        sys.exit(f"PATCH FAILED in {path}: expected {count} match(es), found {found}:\n{old}")
    with open(path, "w", encoding="utf-8") as f:
        f.write(src.replace(old, new))
    print(f"patched {path}")


# 1. Overlay source file
os.makedirs(f"{PKG_DIR}/mph", exist_ok=True)
shutil.copy(os.path.join(MPH_DIR, "MphOverlayView.kt"), f"{PKG_DIR}/mph/MphOverlayView.kt")
print("copied MphOverlayView.kt")

# 2. Put the overlay above the game screens, below the pause menu (compose view)
patch(
    f"{APP}/res/layout/activity_emulator.xml",
    """    <me.magnum.melonds.ui.emulator.RuntimeLayoutView
            android:id="@+id/view_layout_controls"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />
""",
    """    <me.magnum.melonds.ui.emulator.RuntimeLayoutView
            android:id="@+id/view_layout_controls"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />

    <me.magnum.melonds.ui.emulator.mph.MphOverlayView
            android:id="@+id/mph_overlay"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:visibility="gone" />
""",
)

# 3. Wire it into the emulator activity
ACT = f"{PKG_DIR}/EmulatorActivity.kt"
patch(
    ACT,
    "        setContentView(binding.root)\n",
    "        setContentView(binding.root)\n        binding.mphOverlay.onToggleMenuMode = { swapScreen() }\n",
)
patch(
    ACT,
    "                                startMotionManagerIfNeeded(it.rom)\n",
    "                                startMotionManagerIfNeeded(it.rom)\n"
    "                                binding.mphOverlay.setActiveForRom(it.rom)\n",
)
patch(
    ACT,
    """                        is EmulatorState.Uninitialized -> {
                            binding.viewLayoutControls.isInvisible = true
""",
    """                        is EmulatorState.Uninitialized -> {
                            binding.mphOverlay.deactivate()
                            binding.viewLayoutControls.isInvisible = true
""",
)

# 4. Separate app so it installs next to regular melonDS; 64-bit only for faster builds
patch("app/build.gradle.kts", 'applicationId = "me.magnum.melonds"', 'applicationId = "me.magnum.melonds.hunters"')
patch(
    "app/build.gradle.kts",
    'abiFilters.addAll(listOf("arm64-v8a", "x86_64"))',
    'abiFilters.addAll(listOf("arm64-v8a"))',
)
patch("app/build.gradle.kts", 'abiFilters.add("armeabi-v7a")', "// 32-bit removed for Hunters build")
patch(
    f"{APP}/res/values/strings.xml",
    '<string name="app_name">melonDS</string>',
    '<string name="app_name">melonDS Hunters</string>',
)

print("All MPH patches applied.")
