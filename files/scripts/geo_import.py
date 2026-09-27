#!/usr/bin/env python3
"""Wikidata から日本の市区町村の座標を取得し、municipality_coords.json を作る。

局の送信所の所在地(総務省の免許情報の「無線設備の設置場所」の市区町村)を緯度経度に変換するのに使う。
Wikidata のデータは CC0(パブリックドメイン)。
市区町村の合併などで所在地が変わったら再実行し、mic_import.py を実行し直す。

使い方:
    python3 files/scripts/geo_import.py                        # Wikidata に問い合わせて生成
    python3 files/scripts/geo_import.py --raw-file FILE        # 問い合わせ結果(JSON)を FILE にも保存
    python3 files/scripts/geo_import.py --offline FILE         # 保存済みの問い合わせ結果から生成
"""

import argparse
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

import stationdata
from stationdata import FILES_DIR, PREFECTURES

SPARQL_URL = "https://query.wikidata.org/sparql"
USER_AGENT = "Ai-san-radio-db/1.0 (station database builder)"
OUTPUT_FILE = "municipality_coords.json"

# 全国地方公共団体コード(P429)を持つ項目の座標(P625)・上位の自治体(P131)・廃止日(P576)
QUERY = """
SELECT ?item ?code ?itemLabel ?coord ?parentLabel ?dissolved WHERE {
  ?item wdt:P429 ?code .
  OPTIONAL { ?item wdt:P625 ?coord . }
  OPTIONAL { ?item wdt:P131 ?parent . }
  OPTIONAL { ?item wdt:P576 ?dissolved . }
  SERVICE wikibase:label { bd:serviceParam wikibase:language "ja". }
}
"""

# 北方領土の6村(色丹村・泊村・留夜別村・留別村・紗那村・蘂取村)。北海道古宇郡泊村と名前が重なるので除く。
EXCLUDED_CODES = {"01695", "01696", "01697", "01698", "01699", "01700"}


def fetch():
    url = f"{SPARQL_URL}?{urllib.parse.urlencode({'query': QUERY, 'format': 'json'})}"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/sparql-results+json"})
    with urllib.request.urlopen(req, timeout=180) as res:
        return json.load(res)


def build(rows):
    rows = [{k: v["value"] for k, v in r.items()} for r in rows]
    dissolved = {r["item"] for r in rows if "dissolved" in r}
    candidates = {}
    for r in rows:
        code = r["code"]
        if (r["item"] in dissolved or "coord" not in r or not re.fullmatch(r"\d{5,6}", code)
                or code[2:5] == "000" or code[:5] in EXCLUDED_CODES):
            continue
        label, parent = r["itemLabel"], r.get("parentLabel", "")
        if re.fullmatch(r"Q\d+", label) or not label.endswith(("市", "区", "町", "村")):
            continue  # 日本語のラベルが無いもの、振興局など市区町村でないもの
        pref = PREFECTURES[int(code[:2]) - 1]
        if label.endswith("区") and parent.endswith("市"):
            name = pref + parent + label  # 政令指定都市の区: 北海道札幌市中央区
        elif label.endswith("区") and pref != "東京都":
            continue  # 政令指定都市の区で、上位が都道府県になっている行
        else:
            name = pref + label
        m = re.fullmatch(r"Point\(([-\d.]+) ([-\d.]+)\)", r["coord"])
        if not m:
            continue
        candidates.setdefault(name, set()).add((code, round(float(m.group(2)), 5), round(float(m.group(1)), 5)))

    coords = {}
    for name, values in sorted(candidates.items()):
        codes = {code[:5] for code, _, _ in values}
        if len(codes) > 1:
            print(f"[注意] 同名で別の団体コード: {name} {sorted(codes)}", file=sys.stderr)
        # 1つの市区町村に座標が複数ある場合(役場と地域の中心など)は、どちらとも決めずに中点を使う。
        # どちらが実際の送信所に近いかは分からないので、ずれを候補間の距離の半分までに抑える。
        points = sorted({(lat, lon) for _, lat, lon in values})
        coords[name] = [round(sum(p[0] for p in points) / len(points), 5),
                        round(sum(p[1] for p in points) / len(points), 5)]
    return coords


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--raw-file", type=Path, help="問い合わせ結果(SPARQL の JSON)の保存先")
    parser.add_argument("--offline", type=Path, help="保存済みの問い合わせ結果から生成する(Wikidata にアクセスしない)")
    args = parser.parse_args()

    if args.offline:
        response = json.loads(args.offline.read_text(encoding="utf-8"))
    else:
        response = fetch()
        if args.raw_file:
            args.raw_file.write_text(json.dumps(response, ensure_ascii=False), encoding="utf-8")
    coords = build(response["results"]["bindings"])
    data = {
        "_readme": (
            "日本の市区町村の代表点の座標(緯度・経度)。Wikidata の全国地方公共団体コード(P429)と座標(P625)から"
            "files/scripts/geo_import.py で生成。キーは「都道府県+市区町村」(郡名は含まない。政令指定都市の区は「市+区」)。"
            "Wikidata に座標が複数ある市区町村は、その中点。"
            "Wikidata のデータは CC0。"
        ),
        "_schema_version": 1,
        "municipalities": coords,
    }
    stationdata.save(FILES_DIR / OUTPUT_FILE, data)
    print(f"{OUTPUT_FILE}: {len(coords)}件", file=sys.stderr)


if __name__ == "__main__":
    main()
