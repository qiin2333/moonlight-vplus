# Render the README promo

`render.py` combines an Android UI recording with a credited game trailer. It
exports a silent MP4, a looping GIF, and a JPEG poster into `assets/readme/`.
The presentation crops its sources without changing their aspect ratios.

Requirements: Python, Pillow, NumPy, FFmpeg with libx264/FFV1, and the Segoe UI fonts
(`--font-dir` can point at a directory containing `segoeui.ttf` and `segoeuib.ttf`).

```powershell
python tools/readme-promo/render.py `
  --ui build/promo/capture-ui-native.mp4 `
  --trailer build/promo/trailer.mp4 `
  --ffmpeg /path/to/ffmpeg.exe
```

The current UI input was recorded with Android's `screenrecord` at 2340×1080.
It shows the app launch, home screen, and game library. The source-specific crop
removes its status bar and navigation rail. The opening uses the imagegen-edited
`--launcher` still (default `assets/readme/source/fictional-launcher.png`), with
fictional apps replacing personal icons and names. A short reconstructed launch
animation expands the native app splash from the Moonlight V+ icon. Original
launcher frames are excluded, including the launch animation's rounded corners.
The following UI recording is sped up by 1.4×. This is followed
by desktop selection, the native connection/loading animation,
and an eight-second trailer excerpt. Adjust these values and crop coordinates
when replacing the input with another device or sequence.

`--loading` defaults to `assets/readme/source/loading.mp4`, a clean excerpt of
the actual app's full-screen connection overlay. It is slowed to 1.4 seconds to
keep the message readable in the montage.

`--overlay` defaults to `assets/readme/source/performance-overlay.mp4`. This is
the actual top 58 pixels of the phone's screen recording, including original
icons and changing measurements. The edit crops the final 1% Low item, skips
the initial width-settling update, and restores 50% background opacity. The native
foreground colors are used to recover smooth glyph coverage, remove background
contamination, and resize in premultiplied alpha. A lossless intermediate retains
these edges for the final encode. The rectangular bar remains flush with the
picture's top edge. The recorded metrics
belong to the desktop connection; keep the composition credit with the video.
The poster is sampled from this segment so the native overlay is visible.

The GIF uses 128 colors and 12 FPS, falling back to 10 FPS if needed to stay
below 10 MB. The MP4 remains 30 FPS and uses CRF 18 for fine text detail.

The local working inputs remain under ignored `build/promo/`; they are not
required to view the committed outputs. See [media credits](../../assets/readme/README.md)
for the source and composition details. Keep that attribution and the visible
trailer-composite label when changing the edit.
