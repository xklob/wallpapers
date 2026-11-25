package io.klob.wallpapers.live;

/** A same-process handoff to the system picker, never a saved wallpaper preference. */
final class PreviewRequest {
    private static long nextId = System.nanoTime();
    private static long activeId;
    private static WallpaperScreen target;

    static long begin(WallpaperScreen screen) {
        activeId = ++nextId;
        target = screen;
        return activeId;
    }

    static WallpaperScreen current() {
        return target;
    }

    static void end(long id) {
        if (id == 0 || id != activeId) return;
        target = null;
        activeId = 0;
    }

    /**
     * Legacy pickers often attach a preview with the Home flag even for a Lock request.
     * Preserve the launch target for that first attachment, but follow explicit changes
     * from a picker that retargets the same Engine to another destination.
     */
    static final class Routing {
        private final WallpaperScreen requested;
        private int firstFlags;
        private boolean pickerRetargeted;

        Routing(WallpaperScreen requested, boolean home, boolean lock) {
            this.requested = requested;
            firstFlags = flags(home, lock);
        }

        void flagsChanged(boolean home, boolean lock) {
            int next = flags(home, lock);
            if (next == 0) return;
            if (firstFlags == 0) firstFlags = next;
            else if ((home ^ lock) && next != firstFlags) pickerRetargeted = true;
        }

        WallpaperScreen override() {
            return pickerRetargeted ? null : requested;
        }

        private static int flags(boolean home, boolean lock) {
            return (home ? 1 : 0) | (lock ? 2 : 0);
        }
    }

    private PreviewRequest() {}
}
