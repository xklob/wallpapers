package io.klob.wallpapers.live;

enum AudioSource {
    DEVICE("Device audio"),
    MICROPHONE("Microphone"),
    AUTO("Auto (device audio first)");

    private final String label;

    AudioSource(String label) {
        this.label = label;
    }

    static AudioSource fromPreference(String name, boolean legacyMicrophone) {
        if (name != null) {
            for (AudioSource source : values()) {
                if (source.name().equals(name)) return source;
            }
        }
        return legacyMicrophone ? MICROPHONE : DEVICE;
    }

    @Override public String toString() {
        return label;
    }
}
