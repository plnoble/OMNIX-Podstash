from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from persist import parse_opml, write_opml  # noqa: E402


SAMPLE = """<?xml version="1.0"?>
<opml version="2.0">
  <body>
    <outline text="知行小酒馆" xmlUrl="https://example.com/feed.xml"/>
    <outline text="skip" />
    <outline text="NPR" xmlUrl="https://example.com/npr.xml"/>
  </body>
</opml>
"""


class OpmlTests(unittest.TestCase):
    def test_parse_and_write(self) -> None:
        shows = parse_opml(SAMPLE)
        self.assertEqual(len(shows), 2)
        self.assertEqual(shows[0]["name"], "知行小酒馆")
        xml = write_opml(shows)
        again = parse_opml(xml)
        self.assertEqual([s["feed_url"] for s in again], [s["feed_url"] for s in shows])


if __name__ == "__main__":
    unittest.main()
