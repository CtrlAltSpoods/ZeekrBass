/*
 * ZeekrBass — https://github.com/CtrlAltSpoods/ZeekrBass
 *
 * Unofficial community project. NOT affiliated with, endorsed by, sponsored by or otherwise
 * connected to Zeekr, Zeekr Intelligent Technology, Geely or any of their affiliates.
 * "Zeekr" and "7X" are trademarks of their respective owners and are used here only to
 * describe the vehicle this app was made for.
 *
 * Experimental and still in testing. Provided "as is", without warranty of any kind.
 * Installing third-party apps on your vehicle is entirely at your own risk.
 */

package com.ctrlaltspoods.zeekrbass;

/** The three upmix algorithms. Pure Java so the DSP can be tested off-device. */
public enum UpmixAlgorithm {
    CINEMA("Cinema", "Fixed matrix decode in the style of classic cinema surround processors. "
            + "Surrounds get each side minus part of the other, bringing out width and room "
            + "sound, softened above 7 kHz. 7.1 adds a slightly delayed rear pair."),
    MUSIC("Music", "Steered upmix: follows the mix and pushes centred vocals to the centre "
            + "speaker and wide or ambient sound to the surrounds. Full-range surrounds."),
    MANUAL("Manual", "Plain split, no processing: centre = L+R, left surrounds = L, "
            + "right surrounds = R, sub = everything below 80 Hz.");

    final String label;
    final String description;

    UpmixAlgorithm(String label, String description) {
        this.label = label;
        this.description = description;
    }

    static UpmixAlgorithm fromName(String name) {
        if (name == null) return MUSIC;
        try {
            return UpmixAlgorithm.valueOf(name);
        } catch (IllegalArgumentException e) {
            return MUSIC;
        }
    }
}
