"""Matching files downloaded before Podstash was used."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from core import (  # noqa: E402
    Episode,
    match_episodes_in_library,
    normalize_match_key,
    score_title_against_filename,
)


TITLE = "访问95后KOL，聊聊她的省钱秘笈"
SHOW = "知行小酒馆"


def ep(index: int, title: str) -> Episode:
    return Episode(index=index, title=title, audio_url="https://example.com/a.mp3", guid=f"g{index}")


def touch(path: Path, size: int = 40 * 1024) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x00" * size)


class NormalizeTests(unittest.TestCase):
    def test_strips_numbered_prefix(self) -> None:
        self.assertEqual(normalize_match_key(TITLE), normalize_match_key(f"001 {TITLE}"))
        self.assertEqual(normalize_match_key(TITLE), normalize_match_key(f"EP12 {TITLE}"))
        self.assertEqual(normalize_match_key(TITLE), normalize_match_key(f"第12期 {TITLE}"))

    def test_strips_date_prefix(self) -> None:
        self.assertEqual(normalize_match_key(TITLE), normalize_match_key(f"2023-05-12 {TITLE}"))

    def test_keeps_leading_number_in_title(self) -> None:
        self.assertIn("95", normalize_match_key("访问95后KOL"))


class ScoreTests(unittest.TestCase):
    def test_exact_and_prefixed(self) -> None:
        self.assertGreaterEqual(score_title_against_filename(TITLE, f"{TITLE}.m4a", SHOW), 90)
        self.assertGreaterEqual(score_title_against_filename(TITLE, f"001 {TITLE}.mp3", SHOW), 80)
        self.assertGreaterEqual(
            score_title_against_filename(TITLE, f"{SHOW} - {TITLE}.mp3", SHOW),
            80,
        )

    def test_unrelated_is_low(self) -> None:
        self.assertLess(score_title_against_filename(TITLE, "completely-other-show.mp3", SHOW), 70)


class LibraryMatchTests(unittest.TestCase):
    def test_matches_existing_files_in_show_folder(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            show_dir = root / SHOW
            touch(show_dir / f"001 {TITLE}.mp3")
            touch(show_dir / f"2023-05-12 另一期很不一样的标题.m4a")
            eps = [
                ep(1, TITLE),
                ep(2, "另一期很不一样的标题"),
                ep(3, "从未下载过的一集"),
            ]
            mapped = match_episodes_in_library(root, SHOW, eps, remember=True)
            self.assertIn(1, mapped)
            self.assertIn(2, mapped)
            self.assertNotIn(3, mapped)
            self.assertTrue((show_dir / ".podbatch-index.json").exists())

    def test_matches_flat_file_with_show_name(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            touch(root / f"{SHOW} - {TITLE}.mp3")
            mapped = match_episodes_in_library(root, SHOW, [ep(1, TITLE)], remember=False)
            self.assertIn(1, mapped)

    def test_unique_assignment_prefers_longer_title(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            show_dir = root / SHOW
            touch(show_dir / "年度盘点特别篇.mp3")
            eps = [ep(1, "年度盘点"), ep(2, "年度盘点特别篇")]
            mapped = match_episodes_in_library(root, SHOW, eps, remember=False)
            self.assertEqual(mapped[2].name, "年度盘点特别篇.mp3")
            self.assertNotIn(1, mapped)

    def test_nested_parent_folder(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            nested = root / "xiaoyuzhou" / SHOW
            touch(nested / f"{TITLE}.m4a")
            mapped = match_episodes_in_library(root, SHOW, [ep(1, TITLE)], remember=False)
            self.assertIn(1, mapped)


if __name__ == "__main__":
    unittest.main()
