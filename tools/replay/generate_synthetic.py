#!/usr/bin/env python3
"""Generate a tiny deterministic IMU/camera replay without external data."""
import argparse
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("output", type=Path)
parser.add_argument("--frames", type=int, default=12)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
width, height = 128, 96

def base_pixel(x, y):
    checker = ((x // 8) + (y // 8)) & 1
    return 35 + checker * 170 + ((x * 7 + y * 3) % 29)

for index in range(args.frames):
    shift_x, shift_y = index * 2, index
    pixels = bytearray(width * height)
    for y in range(height):
        for x in range(width):
            sx, sy = x - shift_x, y - shift_y
            pixels[y * width + x] = base_pixel(sx, sy) if sx >= 0 and sy >= 0 else 0
    (args.output / f"frame_{index:03d}.pgm").write_bytes(
        f"P5\n{width} {height}\n255\n".encode() + pixels)

start = 1_000_000_000
frame_start = start + 800_000_000
frame_period = 33_000_000
end = frame_start + (args.frames - 1) * frame_period
lines = ["ARK_REPLAY_V1", f"CAMERA {width} {height} 110 110 64 48 0 0 0 0 0"]
imu_times = list(range(start, end + 5_000_000, 5_000_000))
frame_times = [frame_start + i * frame_period for i in range(args.frames)]
i = f = 0
while i < len(imu_times) or f < len(frame_times):
    if f < len(frame_times) and (i >= len(imu_times) or frame_times[f] < imu_times[i]):
        lines.append(f"FRAME {frame_times[f]} frame_{f:03d}.pgm")
        f += 1
    else:
        t = imu_times[i]
        lines.append(f"IMU {t} 0 0 9.80665 0 0 0")
        i += 1
(args.output / "manifest.arkr").write_text("\n".join(lines) + "\n")
print(args.output / "manifest.arkr")
