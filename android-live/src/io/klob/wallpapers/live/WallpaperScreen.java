package io.klob.wallpapers.live;

enum WallpaperScreen {
    HOME("Home screen", ""),
    LOCK("Lock screen", "lock_");

    private final String label;
    private final String prefix;

    WallpaperScreen(String label, String prefix) {
        this.label = label;
        this.prefix = prefix;
    }

    String key(String setting) {
        return prefix + setting;
    }

    int variant(boolean border, boolean status, boolean dock, boolean camera) {
        return (border ? 8 : 0) | (status ? 4 : 0)
                | (dock && this == HOME ? 2 : 0) | (camera ? 1 : 0);
    }

    static WallpaperScreen resolve(boolean home, boolean lock, boolean locked, WallpaperScreen preview) {
        if (home != lock) return home ? HOME : LOCK;
        if (preview != null) return preview;
        return locked ? LOCK : HOME;
    }

    static WallpaperScreen fromPreference(String name) {
        return LOCK.name().equals(name) ? LOCK : HOME;
    }

    @Override public String toString() {
        return label;
    }
}
