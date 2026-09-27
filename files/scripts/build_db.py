#!/usr/bin/env python3
"""files/*.json の局データを検証して radio_stations.db を作り、アプリのアセットにコピーする。

データベースの user_version は AppDatabase.kt の version と同じ値にする。
Room は端末上のDBとバージョンが異なるとアセットのDBをコピーし直すため、
データを更新したら AppDatabase.kt の version を1つ上げてからこのスクリプトを実行する。

使い方:
    python3 files/scripts/build_db.py
"""

import re
import shutil
import sqlite3
import sys
from collections import Counter

import stationdata
from stationdata import COLUMNS, FILES_DIR

REPO_DIR = FILES_DIR.parent
DB_PATH = FILES_DIR / "radio_stations.db"
ASSET_PATH = REPO_DIR / "app/src/main/assets/database/radio_stations.db"
APP_DATABASE_KT = REPO_DIR / "app/src/main/java/com/aisan/radio/data/AppDatabase.kt"

SOURCES = [
    "jp_am_fm_stations.json",
    "community_fm_stations.json",
    "jp_sw_stations.json",
    "afn_stations.json",
    "mic_stations.json",
    "intl_stations.json",
]

# Room の Station エンティティと一致させること(列の型・NOT NULL・インデックス名)
SCHEMA = """
CREATE TABLE stations (
    id TEXT PRIMARY KEY NOT NULL,
    band TEXT NOT NULL,
    freq_khz REAL,
    name TEXT NOT NULL,
    area TEXT,
    callsign TEXT,
    network TEXT,
    language TEXT,
    target_area TEXT,
    note TEXT,
    power_kw REAL,
    lat REAL,
    lon REAL
);
CREATE INDEX idx_freq ON stations(freq_khz);
CREATE INDEX idx_band ON stations(band);
"""

FREQ_RANGES_KHZ = {"AM": (520, 1720), "FM": (76000, 99000), "SW": (2300, 26100)}
# 国内局(総務省の免許情報・AFN)の座標は日本の範囲に収まるはず
JAPAN_BOUNDS = {"lat": (20.0, 46.0), "lon": (122.0, 154.0)}
DOMESTIC_SOURCES = {"jp_am_fm_stations.json", "community_fm_stations.json", "jp_sw_stations.json",
                    "afn_stations.json", "mic_stations.json"}


def room_version():
    m = re.search(r"version\s*=\s*(\d+)", APP_DATABASE_KT.read_text(encoding="utf-8"))
    if not m:
        raise SystemExit(f"{APP_DATABASE_KT} から version を読み取れない")
    return int(m.group(1))


def check_coordinates(s, domestic):
    lat, lon = s["lat"], s["lon"]
    if lat is None and lon is None:
        return []
    if not (isinstance(lat, float) and isinstance(lon, float)):
        return [f"lat/lon は両方とも数値か両方とも null にする ({lat}, {lon})"]
    low_lat, high_lat = JAPAN_BOUNDS["lat"] if domestic else (-90.0, 90.0)
    low_lon, high_lon = JAPAN_BOUNDS["lon"] if domestic else (-180.0, 180.0)
    if not (low_lat <= lat <= high_lat and low_lon <= lon <= high_lon):
        return [f"座標 ({lat}, {lon}) が範囲外"]
    return []


def load_stations():
    stations, errors = [], []
    for filename in SOURCES:
        for s in stationdata.load(FILES_DIR / filename)["stations"]:
            where = f"{filename}: {s.get('id')}"
            if list(s) != COLUMNS:
                errors.append(f"{where}: 列が {COLUMNS} と一致しない")
                continue
            if s["band"] not in FREQ_RANGES_KHZ:
                errors.append(f"{where}: 不明な band {s['band']}")
                continue
            low, high = FREQ_RANGES_KHZ[s["band"]]
            if not (isinstance(s["freq_khz"], float) and low <= s["freq_khz"] <= high):
                errors.append(f"{where}: freq_khz {s['freq_khz']} が {s['band']} の範囲外")
            if not s["id"] or not s["name"]:
                errors.append(f"{where}: id または name が空")
            if s["power_kw"] is not None and not s["power_kw"] > 0:
                errors.append(f"{where}: power_kw {s['power_kw']} が正の数でない")
            errors.extend(f"{where}: {e}" for e in check_coordinates(s, filename in DOMESTIC_SOURCES))
            stations.append(s)
    for station_id, count in Counter(s["id"] for s in stations).items():
        if count > 1:
            errors.append(f"id が重複: {station_id}")
    duplicates = Counter((s["band"], s["freq_khz"], s["name"], s["area"]) for s in stations)
    for key, count in duplicates.items():
        if count > 1:
            errors.append(f"同じ局が重複: {key}")
    if errors:
        raise SystemExit("\n".join(errors))
    return stations


def main():
    version = room_version()
    stations = load_stations()
    stations.sort(key=lambda s: (s["band"], s["freq_khz"], s["id"]))

    tmp_path = DB_PATH.with_suffix(".db.tmp")
    tmp_path.unlink(missing_ok=True)
    con = sqlite3.connect(tmp_path)
    con.executescript(SCHEMA)
    con.executemany(
        f"INSERT INTO stations ({', '.join(COLUMNS)}) VALUES ({', '.join('?' * len(COLUMNS))})",
        [tuple(s[c] for c in COLUMNS) for s in stations],
    )
    con.execute(f"PRAGMA user_version = {version}")
    con.commit()
    con.execute("VACUUM")
    con.close()
    tmp_path.replace(DB_PATH)
    shutil.copyfile(DB_PATH, ASSET_PATH)

    counts = Counter(s["band"] for s in stations)
    located = sum(1 for s in stations if s["lat"] is not None)
    print(f"{DB_PATH.name}: {len(stations)}件 {dict(sorted(counts.items()))} / 座標あり {located}件 / user_version={version}",
          file=sys.stderr)
    print(f"コピー先: {ASSET_PATH.relative_to(REPO_DIR)}", file=sys.stderr)


if __name__ == "__main__":
    main()
