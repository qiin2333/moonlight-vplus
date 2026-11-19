# Thor build: Nintendo Minimal redesign and V+ cleanup

This fork targets the AYN Thor. It keeps Moonlight V+'s streaming, input, audio and Thor
improvements and drops features that are decorative, promotional, or tied to upstream services.

## Design system

- Tokens live in `app/src/main/res/values/thor_tokens.xml`: palette, derived states, radii,
  spacing, the 48dp touch target and 120–160 ms motion.
- Component colours (`ui_shell_*`, `app_dialog_*`, `game_menu_*`, `pc_item_*`, `crown_*`,
  `easytier_*`) alias those tokens instead of hard-coding values.
- The app is dark-only. `values-night` colour overrides were merged into `values`, so
  each colour is defined in one place.
- Single accent `#55B8D4`. `AppAccentBrand` maps every `?attr/appAccent*` to it.
- Corner radii use a restrained 4/6/10/12/14/16dp scale (`corner_radius_*`, `AppCornerRadii`).
- Host cards are flat bordered surfaces, with no stacked layers, glow or shimmer.
- Perf HUD status colours use the same success/warning/error palette.

| Token | Value |
|---|---|
| Background | `#101418` |
| Surface | `#191F25` |
| Elevated surface | `#222B33` |
| Border | `#303B44` |
| Primary text | `#F1F4F6` |
| Secondary text | `#A3AFB9` |
| Accent | `#55B8D4` |
| Success / Warning / Error | `#72C99A` / `#E9BB69` / `#E47777` |

## Removed

| Feature | Why | Notes |
|---|---|---|
| Wallpaper backgrounds (random/API/local images, first-run picker, shake-to-refresh with daily quota, long-press save, blur) | Decorative; network and decode work on the home screen | Solid background. `seismic` and `glide-transformations` dependencies dropped |
| Accent from wallpaper (12 hue-bucket overlays + generator script + CI check) | Decorative | One fixed accent |
| Theme sheet (light/dark/system, accent, launcher icon) | Dark-only design | Both launcher aliases kept, so an existing home-screen icon survives updates |
| Sponsor panel and WeChat QR | Upstream promotion | WeChat package query removed |
| "Moonlight V+ PC download" link | Points at upstream's desktop client | Privacy policy link now points at this fork |
| GitHub-star "developer unlock" | Gated frame generation behind starring upstream (and needed an OAuth client ID this fork's CI doesn't set) | Frame generation is always available |
| Crown Market (community store, publish to GitHub, report) | Depends on upstream services | **Kept:** local Crown profiles, share-package import/export (file and URL), legacy `.mdat` tools |
| Floating ball | Redundant with Thor's buttons and the bottom-screen MENU | `FloatBallHandler` is an inert stub so `Game.kt` (and the patch context) is untouched |
| Analytics toggle | Firebase analytics needs a secret this fork doesn't ship | Usage analytics are always **off**. Crash reporting is unchanged |

## Compatibility

- Stored preferences are never deleted on upgrade. Old theme, accent, background and unlock keys
  are ignored, still listed in the backup allow-list, and still cleared by "Restore defaults".
- `Game.kt`, `ControllerHandler.kt`, `ControllerGyroManager.kt` and networking/streaming code are
  not modified, so `ds4-bottom-trackpad.patch` and `gyro-throttle.patch` still apply in CI.
- The unapplied `gyro-*.patch` files are left untouched.

## Not changed yet

- The bottom-screen Control Strip (`SecondScreenTrackpad`, `GyroCalibration`). It lives in
  `ds4-bottom-trackpad.patch` and was deliberately left alone in this pass.
- Settings structure. The existing categories already map onto Streaming / Controls / Settings.
- Decorative but functional loaders (Moonwax connection loader, host-search radar) and the
  optional moon-phase perf-HUD item. They now use the aqua accent.
- The update check still points at upstream releases (see the `feat/thor-update-check` PR).
