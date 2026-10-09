# YTN icon ("Bay-Y")

Source SVGs for the launcher icon: `bg.svg` (background), `fg.svg` (foreground mark) and `mono.svg` (Android 13+ themed icon). They are hand-converted to vector drawables in `app/src/main/res/drawable/ic_launcher_*.xml` (the SVG group transform `translate(54 54) scale(0.9) translate(-54 -54.5)` becomes `scaleX/Y=0.9, translateX=5.4, translateY=4.95`). The preview build adds an amber badge through a debug-only layer (`app/src/debug/res/drawable/ic_launcher_badge.xml`, `ic_launcher_foreground_debug.xml` and `app/src/debug/res/mipmap-anydpi-v26/`).

`docs/images/ytn-icon-512.png` (512 px, full bleed) and `docs/images/icon.png` (rounded) were rendered from these SVGs with cairosvg. `V180IconTest` renders the icon from the built resources for comparison.
