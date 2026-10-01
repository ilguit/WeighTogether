# Weigh Together system splash artwork

`approved-composition.png` is the owner's approved reference, preserved byte for byte.
SHA-256: `d522eb4d15bc474b5cfe2f6587bb22b7b685fcaa6f5b24f01cf19120681d7d10`.
The reference contains texture and non-uniform alpha/color; it is not shipped as the drawable.

`generate_splash_vector.py` reads its contours and produces the native Android
`app/src/main/res/drawable/ic_weigh_together_splash.xml`. This is a vector implementation
of the reference, not a modified bitmap. Run from the repository root with Python 3
and Pillow. The wordmark and human/pet silhouette contours use alpha threshold 225,
remove tiny contours below 100 source square pixels, and simplify to 1.1 source pixels.
The resulting shapes have solid `#28766B` fill and alpha 1. The four crossing stripes
are explicit trapezoids with fill `#28766B`, alpha 0.8, shared top/bottom at source
y=838/1066 and shared vanishing point (627,580). They are drawn behind the figures.
There are no background paths, strokes, gradients, or textures.

All coordinates are uniformly scaled by `188 / hypot(1208, 858)` around source
(624,637), then centered at (144,144) in a 288dp vector. The composition fits a 188dp
circle, leaving clearance inside Android's 192dp safe circle for a splash icon without
an icon background. No stretching or cropping is applied. The artwork is static and
uses the existing AndroidX system splash, background color, and destination theme;
there is no new Activity, timer, artificial hold, or launcher-icon change.

`SplashArtworkTest` rasterizes the actual Android drawable using native graphics,
checks opaque ink, 80% crossing pixels, transparent margins and circular mask safety,
and checks all four trapezoids share their horizontals and vanishing point. The
rendered QA image is written to `app/build/reports/splash/weigh-together.png`.
`SplashScreenThemeTest` verifies theme selection on API 26, 31 and 35.
