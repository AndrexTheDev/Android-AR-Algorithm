#!/usr/bin/env python3
"""Convert an extracted EuRoC MAV sequence to ARK_REPLAY_V1.

Pillow is needed only for EuRoC PNG images: python3 -m pip install Pillow.
No network or cloud service is used by the converter.
"""
import argparse
import csv
import re
import shutil
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("mav0", type=Path, help="Path to the sequence's mav0 directory")
parser.add_argument("output", type=Path)
parser.add_argument("--max-frames", type=int)
args = parser.parse_args()

cam = args.mav0 / "cam0"
imu = args.mav0 / "imu0"

sensor_yaml = (cam / "sensor.yaml").read_text()

def yaml_list(name):
    match = re.search(rf"^\s*{re.escape(name)}\s*:\s*\[([^]]+)\]", sensor_yaml, re.MULTILINE)
    if not match:
        raise SystemExit(f"Missing {name} in cam0/sensor.yaml")
    return [float(value.strip()) for value in match.group(1).split(",")]

def body_from_camera_quaternion():
    block = re.search(r"T_BS\s*:[\s\S]*?data\s*:\s*\[([^]]+)\]", sensor_yaml)
    if not block:
        raise SystemExit("Missing camera T_BS extrinsic in cam0/sensor.yaml")
    values = [float(value.strip()) for value in block.group(1).split(",")]
    if len(values) != 16:
        raise SystemExit("T_BS must be a row-major 4x4 matrix")
    r00,r01,r02 = values[0],values[1],values[2]
    r10,r11,r12 = values[4],values[5],values[6]
    r20,r21,r22 = values[8],values[9],values[10]
    trace = r00+r11+r22
    if trace > 0:
        s = (trace+1.0)**0.5*2; q = ((s/4),(r21-r12)/s,(r02-r20)/s,(r10-r01)/s)
    elif r00 > r11 and r00 > r22:
        s = (1+r00-r11-r22)**0.5*2; q = ((r21-r12)/s,s/4,(r01+r10)/s,(r02+r20)/s)
    elif r11 > r22:
        s = (1+r11-r00-r22)**0.5*2; q = ((r02-r20)/s,(r01+r10)/s,s/4,(r12+r21)/s)
    else:
        s = (1+r22-r00-r11)**0.5*2; q = ((r10-r01)/s,(r02+r20)/s,(r12+r21)/s,s/4)
    norm = sum(value*value for value in q)**0.5
    if norm < 1e-9:
        raise SystemExit("Invalid camera T_BS rotation")
    return tuple(value/norm for value in q)

intrinsics = yaml_list("intrinsics")
distortion = yaml_list("distortion_coefficients")
resolution = [int(value) for value in yaml_list("resolution")]
if len(intrinsics) != 4 or len(distortion) < 4 or len(resolution) != 2:
    raise SystemExit("Unsupported EuRoC camera calibration")
width, height = resolution
args.output.mkdir(parents=True, exist_ok=True)
frames_dir = args.output / "frames"
frames_dir.mkdir(exist_ok=True)

def csv_rows(path):
    with path.open(newline="") as stream:
        return [row for row in csv.reader(line for line in stream if not line.startswith("#")) if row]

camera_rows = csv_rows(cam / "data.csv")
if args.max_frames:
    camera_rows = camera_rows[:args.max_frames]
imu_rows = csv_rows(imu / "data.csv")
if not camera_rows or not imu_rows:
    raise SystemExit("EuRoC camera or IMU CSV is empty")
last_frame_time = int(camera_rows[-1][0])
imu_rows = [row for row in imu_rows if int(row[0]) <= last_frame_time]

def convert_image(source, destination):
    if source.suffix.lower() == ".pgm":
        shutil.copyfile(source, destination)
        return
    try:
        from PIL import Image
    except ImportError as error:
        raise SystemExit("EuRoC PNG conversion requires the free Pillow package") from error
    image = Image.open(source).convert("L")
    if image.size != (width, height):
        raise SystemExit(f"Image size mismatch: {source}")
    destination.write_bytes(f"P5\n{width} {height}\n255\n".encode() + image.tobytes())

frame_events = []
for index, row in enumerate(camera_rows):
    timestamp, filename = int(row[0]), row[1].strip()
    destination = frames_dir / f"{index:06d}.pgm"
    convert_image(cam / "data" / filename, destination)
    frame_events.append((timestamp, 1, f"FRAME {timestamp} frames/{destination.name}"))

imu_events = []
for row in imu_rows:
    if len(row) < 7:
        raise SystemExit("Malformed EuRoC IMU CSV row")
    timestamp = int(row[0])
    wx, wy, wz, ax, ay, az = map(float, row[1:7])
    imu_events.append((timestamp, 0, f"IMU {timestamp} {ax:.17g} {ay:.17g} {az:.17g} {wx:.17g} {wy:.17g} {wz:.17g}"))

events = sorted(imu_events + frame_events, key=lambda event: (event[0], event[1]))
fx, fy, cx, cy = intrinsics
k1, k2, p1, p2 = distortion[:4]
qw,qx,qy,qz = body_from_camera_quaternion()
lines = ["ARK_REPLAY_V1",
         f"CAMERA {width} {height} {fx:.17g} {fy:.17g} {cx:.17g} {cy:.17g} {k1:.17g} {k2:.17g} {p1:.17g} {p2:.17g} 0",
         f"EXTRINSIC {qw:.17g} {qx:.17g} {qy:.17g} {qz:.17g}"]
lines.extend(event[2] for event in events)
manifest = args.output / "manifest.arkr"
manifest.write_text("\n".join(lines) + "\n")
print(manifest)
