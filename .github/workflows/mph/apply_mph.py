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
    "        setContentView(binding.root)\n"
    "        binding.mphOverlay.screenAreaApplier = { top, bottom ->\n"
    "            mainScreenRenderer.updateScreenAreas(top, bottom, 1f, 1f, false, false)\n"
    "        }\n"
    "        binding.mphOverlay.onActiveChanged = { isActive ->\n"
    "            binding.viewLayoutControls.alpha = if (isActive) 0f else 1f\n"
    "            updateRendererScreenAreas()\n"
    "        }\n",
)
patch(
    ACT,
    "    private fun updateRendererScreenAreas() {\n",
    "    private fun updateRendererScreenAreas() {\n"
    "        if (binding.mphOverlay.pushScreenAreas()) return\n",
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

# 5. Native: direct aim for Metroid Prime Hunters (same memory approach as melonPrimeDS).
CPP = "app/src/main/cpp"
patch(
    f"{CPP}/MelonInstance.cpp",
    '#include "SDCardArgsBuilder.h"\n',
    '#include "SDCardArgsBuilder.h"\n#include <atomic>\n\n'
    '// --- Metroid Prime Hunters direct aim ---\n'
    'std::atomic<int> g_mphAimX{0};\n'
    'std::atomic<int> g_mphAimY{0};\n'
    'std::atomic<int> g_mphState{0};\n',
)
patch(
    f"{CPP}/MelonInstance.cpp",
    "    u32 nLines = nds->RunFrame();\n",
    """    {
        // Metroid Prime Hunters: write aim deltas straight into the game's aim values each frame.
        u32 mphCode = nds->ARM9Read32(0x027FFE0C); // game code from the cart header copy
        u8 mphVer = nds->ARM9Read8(0x027FFE1E);    // ROM revision
        u32 aimXAddr = 0, aimYAddr = 0, ballAddr = 0, visorAddr = 0;
        if (mphCode == 0x45484D41) { // "AMHE" (USA)
            if (mphVer == 1) { aimXAddr = 0x020DEDA6; aimYAddr = 0x020DEDAE; ballAddr = 0x020DB098; visorAddr = 0x020D9A7D; }
            else if (mphVer == 0) { aimXAddr = 0x020DE526; aimYAddr = 0x020DE52E; ballAddr = 0x020DA818; }
        }
        int mphAx = g_mphAimX.exchange(0);
        int mphAy = g_mphAimY.exchange(0);
        int mphState = 0;
        if (aimXAddr != 0) {
            mphState |= 1;
            if (nds->ARM9Read8(ballAddr) == 0x02) mphState |= 2;
            if (visorAddr != 0 && nds->ARM9Read8(visorAddr) == 0x01) mphState |= 4;
            if (mphAx != 0) nds->ARM9Write32(aimXAddr, (u32) mphAx);
            if (mphAy != 0) nds->ARM9Write32(aimYAddr, (u32) mphAy);
        }
        g_mphState.store(mphState);
    }
    u32 nLines = nds->RunFrame();
""",
)
with open(f"{CPP}/MelonDSAndroidJNI.cpp", "a", encoding="utf-8") as f:
    f.write("""

// --- Metroid Prime Hunters direct aim ---
#include <atomic>
extern std::atomic<int> g_mphAimX;
extern std::atomic<int> g_mphAimY;
extern std::atomic<int> g_mphState;

extern "C" {
JNIEXPORT void JNICALL
Java_me_magnum_melonds_MelonEmulator_mphAddAim(JNIEnv* env, jobject thiz, jint dx, jint dy)
{
    g_mphAimX.fetch_add(dx);
    g_mphAimY.fetch_add(dy);
}

JNIEXPORT jint JNICALL
Java_me_magnum_melonds_MelonEmulator_mphGetState(JNIEnv* env, jobject thiz)
{
    return g_mphState.load();
}
}
""")
print("appended JNI functions")
patch(
    "app/src/main/java/me/magnum/melonds/MelonEmulator.kt",
    "    external fun updateMotionData(ax: Float, ay: Float, az: Float, rx: Float, ry: Float, rz: Float)\n",
    "    external fun updateMotionData(ax: Float, ay: Float, az: Float, rx: Float, ry: Float, rz: Float)\n\n"
    "    /** Metroid Prime Hunters: queue aim movement (game units) for the next frame. */\n"
    "    external fun mphAddAim(dx: Int, dy: Int)\n\n"
    "    /** Metroid Prime Hunters: bit 1 = direct aim supported, 2 = in morph ball, 4 = in scan visor. */\n"
    "    external fun mphGetState(): Int\n",
)

print("All MPH patches applied.")
