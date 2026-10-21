# README promotional media

- `moonlight-vplus-promo.gif`: looping README banner.
- `moonlight-vplus-promo.mp4`: higher-quality, silent H.264 video.
- `moonlight-vplus-poster.jpg`: static preview.
- `promo-wallpaper.webp`: generated wallpaper used in the Android home screen recording.

The sequence combines an actual Moonlight V+ 12.13.3 Android UI recording from a
Meizu 17 with an excerpt of the **official No Man's Sky Worlds Part II Update
Trailer**. The trailer is composited into the banner; it is not a recording of
gameplay streamed to this phone, nor a performance benchmark. Both sources are
scaled uniformly and cropped to the presentation frame.

The connection/loading animation and performance overlay were recorded from the
actual Android app during a Sunshine desktop connection. The overlay uses the
original one-line layout, moon-phase graphic, icons, colored metrics, and live
value updates. Its numbers describe the captured desktop session; the game
picture underneath is the composited trailer.

The promotional edit hides the final 1% Low item. The remaining native bar is
top-aligned with a half-transparent background, preserving its original icons,
colors, and recorded numbers.

The launcher segment is retained with fictional app icons and names replacing
the personal apps. `source/fictional-launcher.png` was edited with the built-in
imagegen tool, preserving the orange wallpaper, grid, and Moonlight V+ entry.
It animates into the recorded native app splash; original private launcher frames
are excluded, including the corners of the launch animation. The overlay's text edges
are smoothly unmatted from the capture background and resized in premultiplied
alpha, preserving anti-aliasing against the game image. The MP4 uses a higher
quality encode, and the GIF has an expanded color palette for finer edges.

Clean source excerpts are included in `source/`:

- `loading.mp4`: the native connection animation and RTSP-handshake message.
- `performance-overlay.mp4`: the native top bar with its recorded updates.
- `performance-overlay.png`: a still reference of the native bar.
- `fictional-launcher.png`: the privacy-safe launcher used for the opening.

Launcher edit prompt: Preserve the orange abstract wallpaper, app grid, page dots,
and Moonlight V+ entry. Replace every other icon, folder preview, and name with
original fictional apps: Orbit, Grove, Prism, Tempo, Ripple, Waypoint, Parcel,
and an unlabelled dock of Luma, Canvas, and Relay. Use crisp Android squircle
icons; exclude real company logos, notifications, and personal information.

The overlay background was temporarily set to fully opaque for clean extraction.
Only the top bar is included in its source clip.

Game footage and game artwork belong to their respective owners. Trailer source:
[Hello Games / No Man's Sky](https://www.nomanssky.com/worlds-part-ii-update/),
also distributed on the [official Steam store page](https://store.steampowered.com/app/275850/No_Mans_Sky/).
The video excerpt uses approximately 21–29 seconds of that trailer, without audio.

The wallpaper was created with the built-in imagegen tool using this prompt:

> Use case: stylized-concept. Asset type: wide cinematic wallpaper for a game-streaming app promotional recording, landscape 16:9. Create an original premium sci-fi environment: a huge pale moon above a dark midnight-blue alpine horizon, a quiet reflective lake, a thin luminous cyan horizon and very subtle rose-pink light in the clouds. Sophisticated cinematic game environment concept art with realistic detail, restrained colors, dramatic atmosphere. Clean low-contrast dark upper third so translucent UI cards will remain readable. Beautiful spacious composition with detailed terrain along the bottom third. No characters, no text, no logos, no interfaces, no borders, no watermark.

The Android wallpaper, host list, and rotation settings were temporarily adjusted
for recording and restored afterward. These assets do not change app behavior.

See [the rendering instructions](../../tools/readme-promo/README.md) to regenerate
the presentation with new recordings.
