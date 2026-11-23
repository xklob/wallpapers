package io.klob.wallpapers.live;

enum AudioEffect {
    PULSE_AND_COLOR("Pulse + color", 0.65f, 0.00115f, 0f),
    BRIGHTNESS_PULSE("Brightness pulse", 0.65f, 0f, 0f),
    COLOR_ACCELERATION("Color acceleration", 0f, 0.00115f, 0f),
    DIAGONAL_COLOR_WAVE("Diagonal color wave", 0f, 0f, 0.48f);

    static final AudioEffect DEFAULT = PULSE_AND_COLOR;

    final String label;
    final float dimming;
    final float huePerMillisecond;
    final float waveHue;

    AudioEffect(String label, float dimming, float huePerMillisecond, float waveHue) {
        this.label = label;
        this.dimming = dimming;
        this.huePerMillisecond = huePerMillisecond;
        this.waveHue = waveHue;
    }

    static AudioEffect fromPreference(String name) {
        if (name == null) return DEFAULT;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException exception) {
            return DEFAULT;
        }
    }

    @Override public String toString() {
        return label;
    }
}
