#!/usr/bin/env python3
"""EiBi Shortwave Schedules(http://www.eibispace.de/)から、日本で受信されやすい国際放送を取り込む。

対象: 日本語の放送、または日本・極東・朝鮮半島・中国向けの放送(短波と、中波の国際放送)。
ファクス・気象通報などの業務局、妨害電波、デジタル(DRM)放送、周波数を頻繁に変える局は除く。
国内のラジオNIKKEIは jp_sw_stations.json(総務省の免許情報ベース)で扱うのでここでは除く。

同じ周波数・同じ局・同じ送信所の放送時間帯はまとめて1件にし、時間帯・言語・対象地域を note に書く。
送信所の座標(lat/lon)は README.TXT の送信所一覧から取る。
短波のスケジュールは季節(A: 3月末〜10月末、B: 10月末〜3月末)ごとに変わるため、
シーズンが替わったら再実行する。

使い方:
    python3 files/scripts/eibi_import.py                   # 今日のシーズンのCSVを取得して生成
    python3 files/scripts/eibi_import.py --season B26      # シーズンを指定
    python3 files/scripts/eibi_import.py --csv sked-a26.csv --readme README.TXT  # 保存済みファイルから
"""

import argparse
import csv
import datetime
import hashlib
import io
import math
import re
import sys
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

import stationdata
from stationdata import FILES_DIR

BASE_URL = "http://www.eibispace.de/dx/"
OUTPUT_FILE = "intl_stations.json"

TARGETS = {"J", "FE", "KRE", "KOR", "CHN"}

EXCLUDED_STATIONS = re.compile(
    r"Fax|Volmet|Jammer|Firedrake|Numbers|Marker|HFDL|Meteo|Coast|Propag|Fish|Marine|"
    r"DIGITAL|DRM|Filler|Weather|Seoul Radio|Tokyo Radio|^XS[A-Z] |"
    r"Sound of Hope|Radio Nikkei",
    re.IGNORECASE,
)
EXCLUDED_DAYS = {"alt", "harm", "imod", "irr", "test"}

# EiBi の局名 → 表示名
STATION_NAMES = {
    "NHK Radio Japan": "NHKワールド・ラジオ日本",
    "China Radio Int.": "中国国際放送(CRI)",
    "China National Radio": "中央人民広播電台(CNR)",
    "China National Radio 1": "中央人民広播電台 中国之声(CNR1)",
    "CNR 2 China Business R.": "中央人民広播電台 経済之声(CNR2)",
    "China National Radio 6": "中央人民広播電台 神州之声(CNR6)",
    "CNR 7 Greater Bay Area": "中央人民広播電台 大湾区之声(CNR7)",
    "China National Radio 8": "中央人民広播電台 民族之声(CNR8)",
    "China National Radio 11": "中央人民広播電台 チベット語放送(CNR11)",
    "China National Radio 13": "中央人民広播電台 ウイグル語放送(CNR13)",
    "China National Radio 17": "中央人民広播電台 カザフ語放送(CNR17)",
    "Xizang RTV": "西蔵人民広播電台(チベット)",
    "PBS Xinjiang": "新疆人民広播電台",
    "PBS Nei Menggu": "内蒙古人民広播電台",
    "PBS Qinghai": "青海人民広播電台",
    "PBS Hulun Buir": "呼倫貝爾人民広播電台",
    "PBS Gannan": "甘南広播電台",
    "PBS Hunan": "湖南人民広播電台",
    "PBS Yunnan Minority Sce": "雲南人民広播電台 民族放送",
    "Yunnan Radio Int.": "雲南国際広播",
    "Sichuan Ethnic Radio": "四川民族広播",
    "Beibu Bay Radio": "北部湾之声",
    "Voice of Strait": "海峡之声",
    "KBS World Radio": "KBSワールドラジオ",
    "KBS Hanminjok": "KBS韓民族放送",
    "Voice of Korea": "朝鮮の声(Voice of Korea)",
    "Voice of Korea KCBS": "朝鮮中央放送(KCBS)",
    "KCBS Pyongyang": "朝鮮中央放送(KCBS)",
    "Radio Taiwan Int.": "台湾国際放送(RTI)",
    "Fu Hsing Bc.Sta.": "復興廣播電台",
    "Voice of Vietnam": "ベトナムの声(VOV)",
    "Voice of Mongolia": "モンゴルの声",
    "Voice of Indonesia": "インドネシアの声",
    "Voice of America": "ボイス・オブ・アメリカ(VOA)",
    "Radio Free Asia": "ラジオ・フリー・アジア(RFA)",
    "BBC": "BBCワールドサービス",
    "Adventist World Radio": "アドベンチスト・ワールド・ラジオ(AWR)",
    "Trans World Radio": "トランス・ワールド・ラジオ(TWR)",
    "FEBC Manila": "極東放送(FEBC)",
    "FEBC Radio Liangyou": "良友電台(FEBC)",
    "FEBC Radio Liangyou 1": "良友電台(FEBC)",
    "FEBC Radio Liangyou 3": "良友電台(FEBC)",
    "FEBC i-Radio Liangyou": "良友電台(FEBC)",
    "Reach Beyond Australia": "リーチ・ビヨンド・オーストラリア",
    "Shiokaze": "しおかぜ(特定失踪者問題調査会)",
    "Furusato no Kaze": "ふるさとの風(日本政府)",
    "Nippon no Kaze": "日本の風(日本政府)",
    "Voice of Turkey": "ボイス・オブ・トルコ(TRT)",
    "Radio Romania Int.": "ルーマニア国際放送",
    "Radio Vaticana": "バチカン放送",
    "Voice of Tibet": "チベットの声",
    "Free North Korea Radio": "自由北韓放送",
    "North Korea Reform R.": "北韓改革放送",
    "AIR Akashvani Ext.Sce": "インド国営放送(AIR)",
}

# EiBi の言語コード → 日本語名(ここに無いコードは README の英語名で表示する)
LANGUAGES = {
    "J": "日本語", "E": "英語", "K": "朝鮮語", "M": "中国語(普通話)", "C": "中国語", "CA": "広東語",
    "AM": "アモイ語(閩南語)", "HK": "客家語", "HUI": "徽州語", "R": "ロシア語", "VN": "ベトナム語",
    "T": "タイ語", "IN": "インドネシア語", "MO": "モンゴル語", "TB": "チベット語",
    "AMD": "アムド・チベット語", "KHA": "カム・チベット語", "UI": "ウイグル語", "KZ": "カザフ語",
    "KG": "キルギス語", "ZH": "チワン語", "YI": "イ語", "TN": "タイ・ヌア語", "TL": "タイ・ルー語",
    "LIS": "リス語", "LAH": "ラフ語", "YUN": "雲南の諸言語", "BR": "ビルマ語", "KC": "カチン語",
    "KH": "クメール語", "LAO": "ラオ語", "NE": "ネパール語", "HI": "ヒンディー語", "F": "フランス語",
    "S": "スペイン語", "D": "ドイツ語", "A": "アラビア語", "P": "ポルトガル語", "TU": "トルコ語",
    "RO": "ルーマニア語", "UZ": "ウズベク語", "FS": "ペルシャ語", "L": "ラテン語", "EO": "エスペラント",
}

TARGET_NAMES = {
    "J": "日本", "FE": "極東", "KRE": "北朝鮮", "KOR": "韓国", "CHN": "中国", "As": "アジア",
    "SEA": "東南アジア", "SAs": "南アジア", "ME": "中東", "Eu": "ヨーロッパ", "EEu": "東ヨーロッパ",
    "Sib": "シベリア", "Oc": "オセアニア", "Af": "アフリカ", "CAf": "中部アフリカ", "WAf": "西アフリカ",
    "SAf": "南部アフリカ", "EAf": "東アフリカ", "NAm": "北米", "CAm": "中米", "SAm": "南米",
    "IRN": "イラン", "INS": "インドネシア", "TWN": "台湾", "MNG": "モンゴル", "RUS": "ロシア",
}

COUNTRIES = {
    "J": "日本", "CHN": "中国", "KOR": "韓国", "KRE": "北朝鮮", "TWN": "台湾", "MNG": "モンゴル",
    "VTN": "ベトナム", "PHL": "フィリピン", "GUM": "グアム", "PLW": "パラオ", "THA": "タイ",
    "INS": "インドネシア", "AUS": "オーストラリア", "USA": "アメリカ", "ALS": "アラスカ", "RUS": "ロシア",
    "UZB": "ウズベキスタン", "TJK": "タジキスタン", "ARM": "アルメニア", "D": "ドイツ", "G": "イギリス",
    "F": "フランス", "ROU": "ルーマニア", "TUR": "トルコ", "CVA": "バチカン", "IND": "インド",
    "MDG": "マダガスカル", "LTU": "リトアニア", "SNG": "シンガポール", "CLN": "スリランカ",
    "KWT": "クウェート", "UAE": "アラブ首長国連邦", "NZL": "ニュージーランド", "HKG": "香港",
    "AFS": "南アフリカ", "ASC": "アセンション島", "BOT": "ボツワナ", "MLI": "マリ", "OMA": "オマーン",
    "CAN": "カナダ", "MRA": "北マリアナ諸島", "SVK": "スロバキア", "AUT": "オーストリア",
}

# 日本国内の送信所は日本語名で表示する
SITE_NAMES = {
    ("J", "y"): "八俣(茨城県古河市)",
    ("J", "c"): "長柄(千葉県)",
}

WEEKDAYS = {"Mo": "月", "Tu": "火", "We": "水", "Th": "木", "Fr": "金", "Sa": "土", "Su": "日"}
DIGIT_WEEKDAYS = dict(zip("1234567", "月火水木金土日"))


def last_sunday(year, month):
    d = datetime.date(year, month + 1, 1) - datetime.timedelta(days=1)
    return d - datetime.timedelta(days=(d.weekday() + 1) % 7)


def current_season(day):
    """A: 3月最終日曜〜10月最終日曜、B: それ以外(年は開始年)。"""
    start_a, start_b = last_sunday(day.year, 3), last_sunday(day.year, 10)
    if start_a <= day < start_b:
        return f"A{day.year % 100:02d}"
    year = day.year if day >= start_b else day.year - 1
    return f"B{year % 100:02d}"


def download(url):
    with urllib.request.urlopen(url, timeout=120) as res:
        return res.read().decode("latin-1")


def parse_languages(readme):
    """README の「I) Language codes」から {コード: 英語名} を作る。"""
    section = readme[readme.rfind("I) Language codes"):readme.rfind("II) Country codes")]
    names = {}
    for line in section.splitlines():
        if m := re.match(r"^   ([A-Z0-9]{1,4})\s{2,}([^:\[]+)", line):
            names[m.group(1)] = m.group(2).strip()
    return names


# 送信所の座標。例: 36N10-139E50 / 35N28'47"-140E13'11"
COORD_RE = re.compile(
    r"(\d{1,2})([NS])(\d{1,2})(?:'(\d{1,2}(?:\.\d+)?)\"?)?-(\d{1,3})([EW])(\d{1,2})(?:'(\d{1,2}(?:\.\d+)?)\"?)?"
)


def parse_coord(text):
    m = COORD_RE.search(text)
    if not m:
        return None, None
    lat_d, ns, lat_m, lat_s, lon_d, ew, lon_m, lon_s = m.groups()
    lat = int(lat_d) + int(lat_m) / 60 + float(lat_s or 0) / 3600
    lon = int(lon_d) + int(lon_m) / 60 + float(lon_s or 0) / 3600
    return round(-lat if ns == "S" else lat, 5), round(-lon if ew == "W" else lon, 5)


def distance_km(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(a))


def country_centroids(sites, max_spread_km=500):
    """送信所の記号が無く国だけ分かる放送(例: /KWT)の位置として、その国の送信所の平均位置を使う。
    国内の送信所が狭い範囲に収まる国だけ(中国のように広い国は位置を決めない)。"""
    points = defaultdict(list)
    for (country, _), (_, lat, lon) in sites.items():
        if lat is not None:
            points[country].append((lat, lon))
    centroids = {}
    for country, pts in points.items():
        lat = sum(p[0] for p in pts) / len(pts)
        lon = sum(p[1] for p in pts) / len(pts)
        if all(distance_km(lat, lon, p[0], p[1]) <= max_spread_km for p in pts):
            centroids[country] = (round(lat, 5), round(lon, 5))
    return centroids


def parse_sites(readme):
    """README の「IV) Transmitter site codes」から {(国, 記号): (地名, 緯度, 経度)} を作る。"""
    section = readme[readme.rfind("IV) Transmitter site codes"):]
    sites = {}
    country = None
    for line in section.splitlines():
        if m := re.match(r"^   ([A-Z]{1,3}):\s+(.*)$", line):
            country, text = m.group(1), m.group(2)
        elif country and (m := re.match(r"^\s{6,}(\S.*)$", line)):
            text = m.group(1)
        else:
            continue
        lat, lon = parse_coord(text)
        text = re.split(r"\s+\d{1,3}[NS]\d", text)[0].strip()
        if m := re.match(r"^([A-Za-z0-9]{1,3})-(.+)$", text):
            code, name = m.group(1), m.group(2)
        elif text and (country, "") not in sites:
            code, name = "", re.sub(r"\s+except:?$", "", text)
        else:
            continue
        # 地名の後ろの付記(括弧書き・局番・出力など)は省く: 'Beijing-Matoucun "572" (100 kW CNR1)' → 'Beijing-Matoucun'
        name = re.split(r'\s+[("]', name)[0]
        sites[(country, code)] = (re.sub(r"\s+\d+\s*kW$", "", name).strip(" ,"), lat, lon)
    return sites


def active_on(row, day, season):
    persistence = row["P"].strip()
    p = int(persistence) if persistence.isdigit() else 1
    if p >= 90 or p == 8:
        return False
    if (p == 4 and season.startswith("A")) or (p == 5 and season.startswith("B")):
        return False

    def ddmm(value):
        value = value.strip()
        if len(value) == 4 and value.isdigit():
            return int(value[2:]), int(value[:2])
        return None
    today = (day.month, day.day)
    start, stop = ddmm(row["Start"]), ddmm(row["Stop"])
    if start and today < start:
        return False
    if stop and today > stop:
        return False
    return True


def locate_site(row, sites, centroids):
    """送信所の (表示名, 緯度, 経度)。座標が分からなければ緯度・経度は None。"""
    remark = row["Remarks"].strip()
    home = row["ITU"].strip()
    if m := re.match(r"^/([A-Z]{1,3})(?:-(\w+))?", remark):
        country, code = m.group(1), m.group(2) or ""
    else:
        country, code = home, remark if re.fullmatch(r"\w{1,3}", remark) else ""
    site = sites.get((country, code)) or (sites.get((country, "")) if not code else None)
    name, lat, lon = site or (None, None, None)
    if site is None and not code:
        lat, lon = centroids.get(country, (None, None))
    if (country, code) in SITE_NAMES:
        return SITE_NAMES[(country, code)], lat, lon
    country_name = COUNTRIES.get(country, country)
    return (f"{name}({country_name})" if name else country_name), lat, lon


def jst(hhmm):
    h, m = int(hhmm[:2]), int(hhmm[2:])
    return f"{(h + 9) % 24:02d}:{m:02d}"


def days_label(days):
    days = days.strip()
    if not days:
        return ""
    if days.isdigit():
        return "・".join(DIGIT_WEEKDAYS[d] for d in days)
    return re.sub("|".join(WEEKDAYS), lambda m: WEEKDAYS[m.group(0)], days).replace(",", "・")


def merge_blocks(blocks):
    """同じ言語・対象・曜日で連続する時間帯を1つにまとめる。"""
    blocks = sorted(blocks, key=lambda b: (b["start"], b["end"]))
    merged = []
    for b in blocks:
        prev = merged[-1] if merged else None
        if prev and prev["end"] == b["start"] and all(prev[k] == b[k] for k in ("lang", "target", "days")):
            prev["end"] = b["end"]
        elif not any(all(x[k] == b[k] for k in ("start", "end", "lang", "target", "days")) for x in merged):
            merged.append(dict(b))
    return merged


def describe(blocks, languages):
    parts = []
    for b in blocks:
        if (b["start"], b["end"]) == ("0000", "2400"):
            text = "終日"
        else:
            text = f"{b['start']}-{b['end']} UTC(日本時間{jst(b['start'])}-{jst(b['end'])})"
        lang = languages.get(b["lang"], b["lang"])
        target = TARGET_NAMES.get(b["target"], b["target"])
        text += f" {lang}・{target}向け"
        if b["days"]:
            text += f"・{days_label(b['days'])}"
        parts.append(text)
    return "、".join(parts)


def build(rows, sites, languages, season, day, valid_until):
    centroids = country_centroids(sites)
    groups = defaultdict(list)
    for row in rows:
        freq = float(row["kHz"])
        if 531 <= freq <= 1611:
            band = "AM"
        elif 2300 <= freq <= 26100:
            band = "SW"
        else:
            continue
        station, lang, target = row["Station"].strip(), row["Lng"].strip(), row["Target"].strip()
        if EXCLUDED_STATIONS.search(station) or lang.startswith("-") or row["Days"].strip() in EXCLUDED_DAYS:
            continue
        if not active_on(row, day, season):
            continue
        if lang != "J" and target not in TARGETS:
            continue
        start, end = row["Time(UTC)"].split("-")
        # 同じ周波数・同じ局でも送信所が違えば距離が違うので、送信所ごとに分ける
        site, lat, lon = locate_site(row, sites, centroids)
        groups[(band, freq, STATION_NAMES.get(station, station), site, lat, lon)].append({
            "station": station,
            "start": start, "end": end, "lang": lang, "target": target,
            "days": row["Days"].strip(),
        })

    stations = []
    for (band, freq, name, site, lat, lon), blocks in groups.items():
        station = Counter(b["station"] for b in blocks).most_common(1)[0][0]
        merged = merge_blocks(blocks)
        langs = Counter(b["lang"] for b in merged)
        targets = Counter(b["target"] for b in merged)
        note = describe(merged, languages)
        if any(b["days"] for b in merged):
            note += "。曜日はUTC基準"
        note += f"。EiBi {season}(〜{valid_until})準拠。季節により変わる"
        digest = hashlib.sha1(f"{name}|{site}".encode()).hexdigest()[:6]
        stations.append(stationdata.station(
            id=f"eibi-{band.lower()}-{freq:g}-{digest}",
            band=band,
            freq_khz=freq,
            name=name,
            area=f"送信所: {site}",
            network=station,
            language="、".join(languages.get(l, l) for l, _ in langs.most_common()),
            target_area="、".join(TARGET_NAMES.get(t, t) for t, _ in targets.most_common()),
            note=note,
            lat=lat,
            lon=lon,
        ))
    stations.sort(key=lambda s: (s["band"], s["freq_khz"], s["name"], s["area"]))
    ids = [s["id"] for s in stations]
    assert len(ids) == len(set(ids)), "id が重複している"
    return stations


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--season", help="例: A26(省略時は --date のシーズン)")
    parser.add_argument("--date", type=datetime.date.fromisoformat, default=datetime.date.today(),
                        help="この日に有効な放送だけを取り込む(既定: 今日)")
    parser.add_argument("--csv", type=Path, help="保存済みの sked-XNN.csv")
    parser.add_argument("--readme", type=Path, help="保存済みの README.TXT")
    args = parser.parse_args()

    season = args.season or current_season(args.date)
    csv_text = args.csv.read_text(encoding="latin-1") if args.csv else download(f"{BASE_URL}sked-{season.lower()}.csv")
    readme = args.readme.read_text(encoding="latin-1") if args.readme else download(f"{BASE_URL}README.TXT")

    rows = [r for r in csv.DictReader(io.StringIO(csv_text), delimiter=";",
                                      fieldnames=["kHz", "Time(UTC)", "Days", "ITU", "Station", "Lng",
                                                  "Target", "Remarks", "P", "Start", "Stop"])
            if r["kHz"] and re.match(r"^[\d.]+$", r["kHz"])]
    year = 2000 + int(season[1:])
    end = last_sunday(year, 10) if season.startswith("A") else last_sunday(year + 1, 3)
    valid_until = f"{end.year}年{end.month}月{end.day}日"
    languages = {**parse_languages(readme), **LANGUAGES}
    stations = build(rows, parse_sites(readme), languages, season, args.date, valid_until)

    data = {
        "_readme": (
            f"EiBi Shortwave Schedules の {season} シーズン(sked-{season.lower()}.csv、{args.date.isoformat()} 時点で有効な放送)から生成した、"
            "日本で受信されやすい国際放送(日本語の放送、または日本・極東・朝鮮半島・中国向けの放送)。"
            "短波(SW)と中波(AM)の国際放送を含む。同じ周波数・同じ局・同じ送信所の時間帯は1件にまとめ、note に時間帯・言語・対象地域を記載。"
            "lat/lon は送信所の座標(README.TXT の送信所一覧。送信所が国しか分からないものは、その国の送信所の平均位置)。"
            "files/scripts/eibi_import.py で再生成するため手で編集しないこと。"
            "EiBi のデータは自由に利用・再配布できる(README.TXT の Conditions of use)。"
        ),
        "_schema_version": 4,
        "_season": season,
        "stations": stations,
    }
    stationdata.save(FILES_DIR / OUTPUT_FILE, data)
    counts = Counter(s["band"] for s in stations)
    print(f"{OUTPUT_FILE}: {len(stations)}件 {dict(counts)}", file=sys.stderr)


if __name__ == "__main__":
    main()
