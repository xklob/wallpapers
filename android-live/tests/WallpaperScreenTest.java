package io.klob.wallpapers.live;

public final class WallpaperScreenTest {
    public static void main(String[] args) {
        WallpaperScreen home = WallpaperScreen.HOME;
        WallpaperScreen lock = WallpaperScreen.LOCK;
        expect(home, WallpaperScreen.resolve(true, false, false, null));
        expect(lock, WallpaperScreen.resolve(false, true, false, null));
        expect(lock, WallpaperScreen.resolve(true, true, true, null));
        expect(home, WallpaperScreen.resolve(true, true, false, null));
        expect(lock, WallpaperScreen.resolve(false, false, true, null));
        expect(lock, WallpaperScreen.resolve(false, false, false, lock));
        expect(home, WallpaperScreen.resolve(true, true, false, home));
        expect(lock, WallpaperScreen.resolve(false, true, false, home));
        if (!"pattern".equals(home.key("pattern")) || !"lock_pattern".equals(lock.key("pattern"))) {
            throw new AssertionError("Layout settings must be stored separately");
        }
        for (int flags = 0; flags < 16; flags++) {
            boolean border = (flags & 8) != 0;
            boolean status = (flags & 4) != 0;
            boolean dock = (flags & 2) != 0;
            boolean camera = (flags & 1) != 0;
            if (home.variant(border, status, dock, camera) != flags
                    || lock.variant(border, status, dock, camera) != (flags & ~2)) {
                throw new AssertionError("Unexpected dock selection for variant " + flags);
            }
        }
        System.out.println("Home, Lock, shared-wallpaper transitions and all 16 artwork variants passed.");
    }

    private static void expect(WallpaperScreen expected, WallpaperScreen actual) {
        if (expected != actual) throw new AssertionError("Expected layout " + expected);
    }
}
