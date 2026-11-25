package io.klob.wallpapers.live;

public final class PreviewRequestTest {
    public static void main(String[] args) {
        expect(null);
        long lock = PreviewRequest.begin(WallpaperScreen.LOCK);
        expect(WallpaperScreen.LOCK);
        long home = PreviewRequest.begin(WallpaperScreen.HOME);
        expect(WallpaperScreen.HOME);
        PreviewRequest.end(lock);
        expect(WallpaperScreen.HOME);
        PreviewRequest.end(home);
        expect(null);
        PreviewRequest.end(0);
        expect(null);
        checkRouting();
        System.out.println("Preview targets are ephemeral, older results cannot clear current requests, and explicit flag changes win.");
    }

    private static void checkRouting() {
        PreviewRequest.Routing initialHome = new PreviewRequest.Routing(WallpaperScreen.LOCK, false, false);
        initialHome.flagsChanged(true, false); // First/default flag from Android, not a destination choice.
        if (initialHome.override() != WallpaperScreen.LOCK) throw new AssertionError("Lost app-requested Lock preview");
        initialHome.flagsChanged(false, true);
        initialHome.flagsChanged(true, false);
        if (initialHome.override() != null) throw new AssertionError("Picker Home tab was overridden");
        if (WallpaperScreen.resolve(true, false, false, initialHome.override()) != WallpaperScreen.HOME) {
            throw new AssertionError("Explicit Home preview did not select Home");
        }
        PreviewRequest.Routing initiallyShared = new PreviewRequest.Routing(WallpaperScreen.LOCK, true, true);
        initiallyShared.flagsChanged(true, false);
        if (initiallyShared.override() != null) throw new AssertionError("Picker's explicit Home target was ignored");
    }

    private static void expect(WallpaperScreen expected) {
        if (PreviewRequest.current() != expected) {
            throw new AssertionError("Expected " + expected + " but got " + PreviewRequest.current());
        }
    }
}
