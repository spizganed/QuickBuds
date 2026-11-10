package com.spizganed.quickbuds.protocol

/** Ids of the `0x0403` feature switches. Both protocols use them. */
object Feature {
    const val GAME_MODE = 0x06
    /** Firmware auto play/pause on wear. `04 01` / `04 00`, `[CAPTURE]` 2026-09-25. */
    const val AUTO_PLAY_PAUSE = 0x04
    const val DUAL_DEVICE = 0x11
    const val SPATIAL_SOUND = 0x1B
    /** Hi-Res (high-quality audio) switch. Switching it makes the buds drop and reconnect. `[CAPTURE]` 2026-09-23. */
    const val HIRES_CODEC = 0x18
    /** BassWave on/off. Level is its own command, the BassWave level command. `[CAPTURE]` 2026-09-23. */
    const val BASSWAVE = 0x1D
    /** Golden Sound (hearing enhancement) on/off. `[VENDOR]` `setSwitchFeature(11)` (PROTOCOL.md §16). */
    const val GOLDEN_SOUND = 0x0B
    /**
     * Game mode's switch on buds whose `0x8100` bitmap has the game sound command `0x0423`: HeyMelody writes and reads
     * `0x28` there instead of [GAME_MODE], and `0x27` is its game sound effects. `[VENDOR]` (PROTOCOL.md §16).
     */
    const val GAME_MODE_MAIN = 0x28
    const val GAME_SOUND = 0x27
    /** Plain on/off switches from HeyMelody, `[VENDOR]`, unverified on buds (PROTOCOL.md §16). */
    const val VOCAL_ENHANCE = 0x09
    /** realme Link's wind noise switch; asked only for models with the `windMode` flag. */
    const val WIND_NOISE = 0x1A
    const val POWER_SAVING = 0x17
    const val SMART_VOLUME = 0x1C
    const val ADAPTIVE_VOLUME = 0x30
    const val ADAPTIVE_EAR = 0x31
    const val SLEEP_PAUSE = 0x3A
    /** Lost reminder (Enco Clip2, Buds Clip). */
    const val LOST_REMIND = 0x16
    const val SPEECH_PERCEPTION = 0x32
    const val LONG_PRESS_VOLUME = 0x35
    const val SWIFT_PAIR = 0x37
    const val HEARING_OPTIMIZE = 0x38
    const val HEAD_MOTION = 0x3B
    /**
     * Find phone (realme): the switch lets a buds gesture ring the phone. The buds then send `0x0502 05` (ring)
     * and `0x0502 06` (stop). Asked only for models with the `findPhone` flag. `[VENDOR]` (PROTOCOL.md §16).
     */
    const val FIND_PHONE = 0x26
    /**
     * Game equalizer (PROTOCOL.md §18), `[VENDOR]`: switch [GAME_EQ] turns it on, and `0x0420 01 <1|0>` tells the buds
     * a game app came to the foreground or left. No read command for the second one.
     */
    const val GAME_EQ = 0x21
    /**
     * Personalized noise cancellation (PROTOCOL.md §20), `[VENDOR]`: switch [PERSONAL_NOISE] (off only;
     * the buds turn it on themselves), `0x0412 <action>`, a stored result read with `0x011A` -> `00 <exist>`,
     * the test's result pushed as the `0x0204` event `0B` `<result>` (0 done, 1-5 a reason it failed).
     */
    const val PERSONAL_NOISE = 0x0C
}
