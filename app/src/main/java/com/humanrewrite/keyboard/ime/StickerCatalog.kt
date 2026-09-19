package com.humanrewrite.keyboard.ime

/**
 * Sticker artwork: Twemoji by Twitter/jdecked (https://github.com/jdecked/twemoji),
 * licensed CC-BY 4.0 (https://creativecommons.org/licenses/by/4.0/). Files are bundled in
 * assets/stickers/<codepoint>.png, named by Unicode code point in hex.
 *
 * ponytail: 40 hand-picked faces/gestures/objects, not the full Twemoji set (3600+ files would
 * bloat the app for images most users never send). Add more code points here when requested.
 */
object StickerCatalog {
    data class Sticker(val codePoint: String, val label: String)

    val stickers: List<Sticker> = listOf(
        Sticker("1f602", "Crying laughing"),
        Sticker("1f923", "Rolling laughing"),
        Sticker("1f60d", "Heart eyes"),
        Sticker("1f970", "Smiling with hearts"),
        Sticker("1f60e", "Cool sunglasses"),
        Sticker("1f929", "Star struck"),
        Sticker("1f973", "Party face"),
        Sticker("1f924", "Drooling"),
        Sticker("1f914", "Thinking"),
        Sticker("1f644", "Eye roll"),
        Sticker("1f62c", "Grimacing"),
        Sticker("1f634", "Sleeping"),
        Sticker("1f631", "Screaming"),
        Sticker("1f621", "Angry"),
        Sticker("1f622", "Crying"),
        Sticker("1f62d", "Sobbing"),
        Sticker("1f480", "Skull"),
        Sticker("1f44d", "Thumbs up"),
        Sticker("1f44e", "Thumbs down"),
        Sticker("1f44f", "Clapping"),
        Sticker("1f64c", "Raised hands"),
        Sticker("1f64f", "Praying / please"),
        Sticker("270c", "Peace sign"),
        Sticker("1f4aa", "Flexed bicep"),
        Sticker("1f525", "Fire"),
        Sticker("1f4af", "100"),
        Sticker("1f389", "Party popper"),
        Sticker("1f38a", "Confetti ball"),
        Sticker("2764", "Red heart"),
        Sticker("1f494", "Broken heart"),
        Sticker("1f440", "Eyes"),
        Sticker("2705", "Check mark"),
        Sticker("274c", "Cross mark"),
        Sticker("2b50", "Star"),
        Sticker("1f680", "Rocket"),
        Sticker("1f381", "Gift"),
        Sticker("2615", "Coffee"),
        Sticker("1f355", "Pizza"),
        Sticker("1f382", "Birthday cake"),
        Sticker("1f37b", "Cheers")
    )

    fun assetPath(sticker: Sticker) = "stickers/${sticker.codePoint}.png"
}
