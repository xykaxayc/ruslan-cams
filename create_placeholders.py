import os
import subprocess

HERE = '/opt/ruslan-cams/mosaics'
os.makedirs(HERE, exist_ok=True)

# 8 cameras layout (4x2): 480x270 tiles for 1920x1080 composite
cams = [
    ("karamova_5", "Карамова · Камера 5"),
    ("karamova_7", "Карамова · Камера 7"),
    ("kirtbaya_2", "Киртбая · Камера 2"),
    ("kirtbaya_3", "Киртбая · Камера 3"),
    ("lenina_4", "Ленина · Камера 4"),
    ("lenina_6", "Ленина · Камера 6"),
    ("ezviz_fridge_entry", "EZVIZ · Хол-ник вход"),
    ("ezviz_general", "EZVIZ · Общий вид")
]

for cam_id, title in cams:
    img_path = os.path.join(HERE, f"{cam_id}_offline.png")
    cmd = [
        "ffmpeg", "-y", "-f", "lavfi",
        "-i", "color=c=0x18181b:s=480x270:d=1",
        "-vf", f"drawtext=text='{title}':fontcolor=white:fontsize=20:x=(w-text_w)/2:y=(h-text_h)/2-18,"
               f"drawtext=text='Нет сигнала':fontcolor=0x9ca3af:fontsize=15:x=(w-text_w)/2:y=(h-text_h)/2+18",
        "-vframes", "1",
        img_path
    ]
    subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
print("Placeholders generated successfully!")
