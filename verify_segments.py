"""
セグメント録音品質チェッカー v2

Phase 1-A: 連続録音のみ（Doze 耐性テスト）
  python verify_segments.py segments/

Phase 1-B: pause/resume あり（状態ログ照合）
  python verify_segments.py segments/ --state-log state_log.csv

ファイル取得方法:
  アプリの「エクスポート」ボタンを押す → 以下で pull:
    adb pull /sdcard/Android/data/com.rokuonsumm/files/verify/ ./verify_data/
    python verify_segments.py verify_data/segments/ --state-log verify_data/state_log.csv

  または tar pipe (API 29+, USB debugging 必要):
    adb exec-out run-as com.rokuonsumm tar cf - files/segments files/state_log.csv > verify.tar
    tar xf verify.tar
    python verify_segments.py files/segments/ --state-log files/state_log.csv

合格条件:
  - 欠落率 < 1%  かつ  未説明ギャップ 0 件
"""

import argparse
import struct
import sys
from datetime import datetime
from pathlib import Path

FILENAME_FMT  = "seg_%Y%m%d_%H%M%S.m4a"
PASS_LOSS_PCT = 1.0   # 合格閾値 (%)
GAP_MARGIN_S  = 5.0   # pause 境界の誤差許容 (s)


def parse_args():
    p = argparse.ArgumentParser(description="Verify segment recording quality v2")
    p.add_argument("segments_dir", help="Directory containing seg_*.m4a files")
    p.add_argument("--state-log", dest="state_log",
                   help="state_log.csv from device (Phase 1-B)")
    p.add_argument("--seg-min", type=int, default=5,
                   help="Expected segment length in minutes (default 5)")
    return p.parse_args()


# ── Pure-Python M4A/MP4 duration parser ──────────────────────────────────
# Reads the MPEG-4 moov/mvhd box directly; no ffprobe needed.

def m4a_duration(path: Path) -> float:
    """Return audio duration in seconds by parsing the M4A container. Returns 0.0 on failure."""
    try:
        file_size = path.stat().st_size
        with open(path, "rb") as f:
            return _scan_boxes(f, 0, file_size)
    except Exception:
        return 0.0


def _scan_boxes(f, start: int, end: int) -> float:
    """Scan top-level MPEG-4 boxes; recurse into 'moov'."""
    pos = start
    while pos < end - 8:
        f.seek(pos)
        raw = f.read(8)
        if len(raw) < 8:
            break
        raw_size = struct.unpack(">I", raw[:4])[0]
        box_type = raw[4:8].decode("ascii", errors="replace")

        if raw_size == 1:          # extended 64-bit size
            ext = f.read(8)
            if len(ext) < 8:
                break
            box_size = struct.unpack(">Q", ext)[0]
        elif raw_size == 0:        # extends to EOF
            box_size = end - pos
        else:
            box_size = raw_size

        if box_size < 8:
            break

        if box_type == "moov":
            header_len = 16 if raw_size == 1 else 8
            dur = _find_mvhd(f, pos + header_len, pos + box_size)
            if dur > 0:
                return dur

        pos += box_size
    return 0.0


def _find_mvhd(f, start: int, end: int) -> float:
    """Scan inside 'moov' for 'mvhd' and extract duration."""
    pos = start
    while pos < end - 8:
        f.seek(pos)
        raw = f.read(8)
        if len(raw) < 8:
            break
        box_size = struct.unpack(">I", raw[:4])[0]
        box_type = raw[4:8].decode("ascii", errors="replace")
        if box_size < 8:
            break

        if box_type == "mvhd":
            vf = f.read(4)                     # version (1) + flags (3)
            if len(vf) < 4:
                break
            version = vf[0]
            if version == 0:
                data = f.read(16)              # creation(4)+modification(4)+timescale(4)+duration(4)
                if len(data) < 16:
                    break
                timescale = struct.unpack(">I", data[8:12])[0]
                duration  = struct.unpack(">I", data[12:16])[0]
            else:                              # version 1
                data = f.read(28)              # creation(8)+modification(8)+timescale(4)+duration(8)
                if len(data) < 28:
                    break
                timescale = struct.unpack(">I", data[16:20])[0]
                duration  = struct.unpack(">Q", data[20:28])[0]
            return (duration / timescale) if timescale else 0.0

        pos += box_size
    return 0.0


# ── 状態ログ ─────────────────────────────────────────────────────────────

def load_state_log(path: str):
    events = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split(",")
            if len(parts) < 2:
                continue
            try:
                events.append((int(parts[0]), parts[1], parts[2:]))
            except ValueError:
                pass
    return events  # [(epoch_ms, event, [args...])]


def build_pause_windows(events, t_start_ms: int, t_end_ms: int):
    """Return list of (start_sec, end_sec) pause windows clipped to [t_start_ms, t_end_ms]."""
    windows = []
    paused_at = None
    for epoch_ms, event, _ in events:
        if event == "PAUSED" and paused_at is None:
            paused_at = epoch_ms
        elif event in ("RESUMED", "SERVICE_STOP", "SERVICE_DESTROY") and paused_at is not None:
            s = max(paused_at, t_start_ms) / 1000.0
            e = min(epoch_ms,  t_end_ms)   / 1000.0
            if e > s:
                windows.append((s, e))
            paused_at = None
    return windows


def gap_is_paused(windows, gap_start_sec: float, gap_end_sec: float) -> bool:
    """True if a pause window fully covers this gap (with GAP_MARGIN_S tolerance)."""
    for s, e in windows:
        if s <= gap_start_sec + GAP_MARGIN_S and e >= gap_end_sec - GAP_MARGIN_S:
            return True
    return False


# ── メイン ───────────────────────────────────────────────────────────────

def main():
    args = parse_args()
    seg_dir = Path(args.segments_dir)
    if not seg_dir.is_dir():
        print(f"[ERROR] Not a directory: {seg_dir}")
        sys.exit(1)

    all_files = sorted(seg_dir.glob("seg_*.m4a"))
    valid, timestamps = [], {}
    for f in all_files:
        try:
            timestamps[f] = datetime.strptime(f.name, FILENAME_FMT)
            valid.append(f)
        except ValueError:
            print(f"[WARN] Skipping unparseable: {f.name}")

    if not valid:
        print("[ERROR] No parseable seg_*.m4a files found.")
        sys.exit(1)

    print(f"Segments    : {len(valid)}")
    print(f"First       : {valid[0].name}")
    print(f"Last        : {valid[-1].name}")

    # ── 実尺測定 (pure-Python M4A parser) ─────────────────────────────
    print("\nMeasuring durations (M4A header parser) ...")
    durations, no_moov = {}, []
    for f in valid:
        d = m4a_duration(f)
        durations[f] = d
        if d <= 0:
            no_moov.append(f.name)

    if no_moov:
        print(f"[WARN] moov box missing / unfinished ({len(no_moov)} file(s)): {no_moov[:5]}")

    sum_dur = sum(durations.values())

    # ── 壁時計 ───────────────────────────────────────────────────────
    t_first_sec    = timestamps[valid[0]].timestamp()
    t_last_sec     = timestamps[valid[-1]].timestamp()
    last_dur       = durations[valid[-1]]
    t_last_end_sec = t_last_sec + last_dur
    raw_wall_sec   = t_last_end_sec - t_first_sec

    # ── 状態ログ (Phase 1-B) ─────────────────────────────────────────
    pause_windows, pause_sec = [], 0.0
    if args.state_log:
        try:
            events = load_state_log(args.state_log)
        except FileNotFoundError:
            print(f"[ERROR] State log not found: {args.state_log}")
            sys.exit(1)
        pause_windows = build_pause_windows(
            events,
            int(t_first_sec * 1000),
            int(t_last_end_sec * 1000)
        )
        pause_sec = sum(e - s for s, e in pause_windows)
        p_cnt = sum(1 for _, ev, _ in events if ev == "PAUSED")
        r_cnt = sum(1 for _, ev, _ in events if ev == "RESUMED")
        print(f"\nState log   : {len(events)} events")
        print(f"Pauses      : {p_cnt}  |  Resumes: {r_cnt}")
        print(f"Pause total : {pause_sec:.1f}s ({pause_sec/60:.1f} min)")

        # エラー履歴（ERROR系イベントがあれば全件表示）
        error_events = [(ms, ev, args) for ms, ev, args in events
                        if ev in ("ERROR", "ERROR_RETRY", "ERROR_GIVEUP", "ERROR_RETRY_SKIP")]
        if error_events:
            print(f"\n[!] Error history ({len(error_events)} event(s)):")
            for ms, ev, args in error_events:
                ts = datetime.fromtimestamp(ms / 1000.0).strftime("%H:%M:%S")
                detail = ", ".join(args) if args else ""
                print(f"  {ts}  {ev}  {detail}")
        else:
            print("    Errors      : none")

    adj_wall_sec = raw_wall_sec - pause_sec
    loss_sec     = adj_wall_sec - sum_dur
    loss_pct     = (loss_sec / adj_wall_sec * 100) if adj_wall_sec > 0 else 0.0

    # ── サマリー ─────────────────────────────────────────────────────
    print(f"\n{'─'*52}")
    print(f"Wall time   : {raw_wall_sec/3600:.3f} h  ({raw_wall_sec:.1f}s)")
    if pause_sec > 0:
        print(f"Pause excl. : {pause_sec:.1f}s ({pause_sec/60:.1f} min)")
        print(f"Adj. wall   : {adj_wall_sec/3600:.3f} h  ({adj_wall_sec:.1f}s)")
    print(f"Recorded    : {sum_dur/3600:.3f} h  ({sum_dur:.1f}s)")
    print(f"Loss        : {loss_sec:.1f}s  ({loss_pct:.3f}%)")
    print(f"{'─'*52}")

    # ── ギャップ検出 ─────────────────────────────────────────────────
    gap_threshold = args.seg_min * 60 + 60
    print(f"\nGap scan (threshold: {gap_threshold}s) ...")
    unexplained = []

    for i in range(1, len(valid)):
        f_prev, f_cur = valid[i - 1], valid[i]
        gap_start = timestamps[f_prev].timestamp() + durations[f_prev]
        gap_end   = timestamps[f_cur].timestamp()
        gap       = gap_end - gap_start

        if gap <= gap_threshold:
            continue

        label = f"  {f_prev.name}  ->  {f_cur.name}  ({gap:.0f}s = {gap/60:.1f} min)"
        if pause_windows and gap_is_paused(pause_windows, gap_start, gap_end):
            print(f"  [OK-pause]{label}")
        else:
            unexplained.append(gap)
            print(f"  [GAP]     {label}")

    if not unexplained:
        print("  No unexplained gaps.")

    # ── 判定 ─────────────────────────────────────────────────────────
    print()
    passed = loss_pct < PASS_LOSS_PCT and not unexplained
    if passed:
        print(f"PASS  — loss {loss_pct:.3f}% < {PASS_LOSS_PCT}%,  no unexplained gaps")
    else:
        reasons = []
        if loss_pct >= PASS_LOSS_PCT:
            reasons.append(f"loss {loss_pct:.3f}% >= {PASS_LOSS_PCT}%")
        if unexplained:
            reasons.append(f"{len(unexplained)} unexplained gap(s)")
        print(f"FAIL  — {', '.join(reasons)}")
        sys.exit(1)


if __name__ == "__main__":
    main()
