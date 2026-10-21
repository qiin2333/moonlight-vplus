"""Compose an Android UI recording and a credited trailer for the README."""

import argparse
import io
import math
import shutil
import subprocess
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


def frame(path, font_dir):
    width, height = 1600, 1000
    image = Image.new("RGBA", (width, height))
    pixels = image.load()
    for y in range(height):
        for x in range(width):
            cyan = math.exp(-(((x - 220) / 800) ** 2 + ((y - 240) / 650) ** 2))
            pink = math.exp(-(((x - 1450) / 650) ** 2 + ((y - 760) / 600) ** 2))
            pixels[x, y] = (int(7 + 9 * cyan + 12 * pink), int(10 + 15 * cyan + 3 * pink), int(20 + 20 * cyan + 14 * pink), 255)
    draw = ImageDraw.Draw(image)

    def text(x, y, value, size, color, bold=False):
        name = "segoeuib.ttf" if bold else "segoeui.ttf"
        draw.text((x, y), value, font=ImageFont.truetype(str(font_dir / name), size), fill=color)

    white, muted, cyan = "#f1f5ff", "#8f9db4", "#8bdfeb"
    draw.rounded_rectangle((96, 55, 104, 86), radius=4, fill=cyan)
    text(122, 48, "MOONLIGHT V+", 30, white, True)
    text(1132, 56, "ANDROID GAME STREAMING", 18, muted)
    text(92, 106, "Your PC games. In your hands.", 66, white, True)
    text(97, 200, "Launch. Browse. Stream.", 26, muted)
    draw.rounded_rectangle((1240, 198, 1504, 240), radius=21, outline="#2d4857", fill="#112833")
    draw.ellipse((1259, 214, 1267, 222), fill=cyan)
    text(1282, 205, "ANDROID UI / TRAILER DEMO", 15, cyan)

    # A real landscape phone recording is inserted through this transparent opening.
    draw.rounded_rectangle((82, 260, 1518, 902), radius=32, fill="#03060b", outline="#364357", width=2)
    draw.rounded_rectangle((91, 269, 1509, 893), radius=24, outline="#526077", width=1)
    hole = Image.new("L", image.size, 255)
    ImageDraw.Draw(hole).rounded_rectangle((96, 274, 1504, 888), radius=18, fill=0)
    image.putalpha(hole)
    draw = ImageDraw.Draw(image)
    text(97, 924, "01  LAUNCH", 21, muted)
    text(520, 924, "02  YOUR LIBRARY", 21, muted)
    text(1050, 924, "03  CONNECT & STREAM", 21, muted)
    text(1050, 956, "Native overlay / trailer composite", 15, muted)
    draw.line((96, 979, 1504, 979), fill="#263347", width=2)
    image.save(path)


def prepare_overlay(ffmpeg, source, destination):
    """Recover smooth native glyph edges from the opaque capture background."""
    raw = subprocess.check_output([
        ffmpeg, "-hide_banner", "-loglevel", "error", "-i", str(source),
        "-vf", "trim=start=0.5,setpts=PTS-STARTPTS,fps=30,"
        "tpad=stop_mode=clone:stop_duration=1,trim=duration=8,crop=1750:58:164:0",
        "-pix_fmt", "rgb24", "-f", "rawvideo", "-",
    ])
    frames = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 58, 1750, 3)
    background = np.array([22, 22, 22], dtype=np.float32)
    # PerformanceOverlayManager's original foreground colors. Fitting the
    # anti-alias coverage along these color rays avoids a hard alpha threshold
    # and removes the dark capture background from partially covered pixels.
    palette = np.array([
        [255, 255, 255], [13, 218, 244], [125, 157, 125],
        [188, 237, 211], [213, 151, 227], [0, 150, 136], [144, 238, 144],
    ], dtype=np.float32)
    encoder = subprocess.Popen([
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "rawvideo", "-pix_fmt", "rgba", "-s", "1312x44", "-r", "30",
        "-i", "-", "-an", "-c:v", "ffv1", "-pix_fmt", "bgra", str(destination),
    ], stdin=subprocess.PIPE)
    pipe_error = None
    try:
        for pixels in frames:
            observed = pixels.astype(np.float32)
            delta = observed - background
            coverage = np.zeros(observed.shape[:2], dtype=np.float32)
            best_error = np.full_like(coverage, np.inf)
            for color in palette:
                direction = color - background
                amount = np.clip(np.sum(delta * direction, axis=2) / np.sum(direction ** 2), 0, 1)
                error = np.sum((delta - amount[..., None] * direction) ** 2, axis=2)
                better = error < best_error
                coverage[better] = amount[better]
                best_error[better] = error[better]
            # The moon phase is a multicolor bitmap rather than colored text.
            coverage[:, :85] = np.clip(np.max(delta[:, :85], axis=2) / 233, 0, 1)
            alpha = 0.5 + 0.5 * coverage
            premultiplied = observed - background * (0.5 * (1 - coverage[..., None]))
            straight = np.clip(premultiplied / alpha[..., None], 0, 255)
            outside = np.max(observed, axis=2) < 6
            alpha[outside] = 0
            straight[outside] = 0
            rgba = np.concatenate((straight, alpha[..., None] * 255), axis=2).round().astype(np.uint8)
            # Pillow downsamples RGBA in premultiplied space, retaining smooth
            # edges without importing black fringes into the new background.
            image = Image.fromarray(rgba).resize((1312, 44), Image.Resampling.LANCZOS)
            encoder.stdin.write(image.tobytes())
    except BrokenPipeError as error:
        pipe_error = error
    finally:
        try:
            encoder.stdin.close()
        except BrokenPipeError as error:
            pipe_error = error
        finally:
            return_code = encoder.wait()
        if pipe_error is not None or return_code != 0:
            raise RuntimeError("Native overlay preparation failed") from pipe_error


def prepare_launch(ffmpeg, source, launcher, destination):
    """Animate the privacy-safe launcher into the recorded native splash."""
    desktop = Image.open(launcher).convert("RGB").resize((1408, 614), Image.Resampling.LANCZOS)
    splash = Image.open(io.BytesIO(subprocess.check_output([
        ffmpeg, "-hide_banner", "-loglevel", "error", "-ss", "1.6", "-i", str(source),
        "-vf", "crop=2256:984:0:96,scale=1408:614", "-frames:v", "1",
        "-f", "image2pipe", "-vcodec", "png", "-",
    ]))).convert("RGB")
    icon = splash.crop((564, 112, 896, 444))
    background = splash.getpixel((10, 10))
    encoder = subprocess.Popen([
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo",
        "-pix_fmt", "rgb24", "-s", "1408x614", "-r", "30", "-i", "-",
        "-an", "-c:v", "ffv1", str(destination),
    ], stdin=subprocess.PIPE)
    pipe_error = None
    try:
        for index in range(32):
            image = desktop.copy()
            if index >= 21:
                progress = 1 - (1 - (index - 21) / 10) ** 3
                def mix(start, end):
                    return start + (end - start) * progress
                width, height = mix(100, 1408), mix(100, 614)
                cx, cy = mix(1247, 704), mix(289, 307)
                mask = Image.new("L", (2816, 1228))
                ImageDraw.Draw(mask).rounded_rectangle(
                    (round((cx - width / 2) * 2), round((cy - height / 2) * 2),
                     round((cx + width / 2) * 2), round((cy + height / 2) * 2)),
                    radius=round(mix(24, 0) * 2), fill=255)
                panel = Image.new("RGB", image.size, background)
                size = round(mix(100, 332))
                panel.paste(icon.resize((size, size), Image.Resampling.LANCZOS),
                            (round(mix(1247, 730) - size / 2),
                             round(mix(289, 278) - size / 2)))
                image.paste(panel, (0, 0), mask.resize(image.size, Image.Resampling.LANCZOS))
                if index == 31:
                    image = splash
            encoder.stdin.write(image.tobytes())
    except BrokenPipeError as error:
        pipe_error = error
    finally:
        try:
            encoder.stdin.close()
        except BrokenPipeError as error:
            pipe_error = error
        finally:
            return_code = encoder.wait()
        if pipe_error is not None or return_code != 0:
            raise RuntimeError("Privacy-safe launcher preparation failed") from pipe_error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ui", type=Path, required=True)
    parser.add_argument("--trailer", type=Path, required=True)
    parser.add_argument("--loading", type=Path, default=Path("assets/readme/source/loading.mp4"))
    parser.add_argument("--overlay", type=Path, default=Path("assets/readme/source/performance-overlay.mp4"))
    parser.add_argument("--launcher", type=Path, default=Path("assets/readme/source/fictional-launcher.png"))
    parser.add_argument("--trailer-start", type=float, default=1)
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"))
    parser.add_argument("--font-dir", type=Path, default=Path("C:/Windows/Fonts"))
    parser.add_argument("--out", type=Path, default=Path("assets/readme"))
    args = parser.parse_args()
    if not args.ffmpeg:
        parser.error("Supply --ffmpeg or put FFmpeg on PATH")
    for source in (args.ui, args.trailer, args.loading, args.overlay, args.launcher):
        if not source.is_file():
            parser.error(f"Missing recording: {source}")
    missing_fonts = [args.font_dir / name for name in ("segoeui.ttf", "segoeuib.ttf")
                     if not (args.font_dir / name).is_file()]
    if missing_fonts:
        parser.error("Missing fonts: " + ", ".join(map(str, missing_fonts)))
    args.out.mkdir(parents=True, exist_ok=True)

    def run(*command):
        subprocess.run([args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", *map(str, command)], check=True)

    with tempfile.TemporaryDirectory(prefix="moonlight-promo-") as temp:
        board = Path(temp) / "frame.png"
        frame(board, args.font_dir)
        clean_overlay = Path(temp) / "native-overlay.mkv"
        prepare_overlay(args.ffmpeg, args.overlay, clean_overlay)
        clean_launch = Path(temp) / "fictional-launch.mkv"
        prepare_launch(args.ffmpeg, args.ui, args.launcher, clean_launch)
        # Crop the UI's status bar/navigation rail. Scale both sources uniformly;
        # crop the trailer to the same cinematic opening without stretching it.
        filters = (
            "[0:v]split[launch][desktop];"
            # Rebuild the launcher with fictional apps; original private icons
            # never enter the output, including the launch animation corners.
            "[launch]trim=start=1.6:end=10.3,setpts=(PTS-STARTPTS)/1.4,"
            "crop=2256:984:0:96,scale=1408:614,setsar=1,fps=30,"
            "settb=AVTB[app];"
            "[5:v]setsar=1,settb=AVTB[intro];"
            "[intro][app]concat=n=2:v=1:a=0,settb=AVTB[ui];"
            "[desktop]trim=start=14.2:end=15.0,setpts=PTS-STARTPTS,"
            "crop=2256:984:0:96,scale=1408:614,setsar=1,fps=30,settb=AVTB[selected];"
            "[2:v]setpts=(PTS-STARTPTS)*1.4,setsar=1,fps=30,settb=AVTB[loading];"
            "[ui][selected][loading]concat=n=3:v=1:a=0,settb=AVTB[connected];"
            f"[1:v]trim=start={args.trailer_start}:duration=8,setpts=PTS-STARTPTS,"
            "scale=1408:614:force_original_aspect_ratio=increase,crop=1408:614,"
            "setsar=1,fps=30,settb=AVTB[live];"
            "[3:v]setpts=PTS-STARTPTS,setsar=1,fps=30,settb=AVTB[metrics];"
            "[live][metrics]overlay=48:0:shortest=1,settb=AVTB[native];"
            "[connected][native]xfade=transition=fade:duration=0.3:offset=9.2,"
            "pad=1600:1000:96:274:color=black[screen];"
            "[screen][4:v]overlay=0:0:shortest=1,"
            "drawbox=x=96:y=977:w=280:h=3:color=0x8bdfeb:t=fill:enable='lt(t,3.0)',"
            "drawbox=x=520:y=977:w=360:h=3:color=0x8bdfeb:t=fill:enable='between(t,3.0,8.1)',"
            "drawbox=x=1050:y=977:w=454:h=3:color=0x8bdfeb:t=fill:enable='gte(t,8.1)',"
            "format=yuv420p[out]"
        )
        video = args.out / "moonlight-vplus-promo.mp4"
        run("-i", args.ui, "-i", args.trailer, "-i", args.loading, "-i", clean_overlay,
            "-loop", "1", "-i", board, "-i", clean_launch,
            "-filter_complex", filters, "-map", "[out]", "-an", "-c:v", "libx264",
            "-preset", "slow", "-crf", "18", "-movflags", "+faststart", video)
        run("-ss", "11.4", "-i", video, "-frames:v", "1", "-q:v", "2",
            args.out / "moonlight-vplus-poster.jpg")
        gif = args.out / "moonlight-vplus-promo.gif"
        for fps in (12, 10):
            run("-i", video, "-filter_complex",
                f"fps={fps},scale=800:-2:flags=lanczos,split[a][b];"
                "[a]palettegen=max_colors=128:stats_mode=full[p];"
                "[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle",
                "-loop", "0", gif)
            if gif.stat().st_size < 10_000_000:
                break


if __name__ == "__main__":
    main()
