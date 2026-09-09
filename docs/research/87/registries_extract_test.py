#!/usr/bin/env python3

import unittest
import csv
import tempfile
from pathlib import Path

import registries_extract as subject


class RegistryExtractionTest(unittest.TestCase):
    def test_farus_filters_and_mapping(self):
        index = '<a href="/catteries/7/">club</a>'
        cards = """
        <tr><td></td><td><a href="/catteries/7/1.html" class="black">Active</a></td>
        <td>Москва<br>MCO / BUR / BBS<br>до 10.10.2027</td></tr>
        <tr><td></td><td><a href="/catteries/7/2.html" class="black">Expired</a></td>
        <td>Москва<br>BEN<br>до 01.01.2020</td></tr>
        <tr><td></td><td><a href="/catteries/7/3.html" class="black">Foreign</a></td>
        <td>Беларусь<br>ABY<br>до 01.01.2030</td></tr>
        """
        rows = subject.farus_rows(index, {"7": cards})
        self.assertEqual([row["breed_original"] for row in rows], ["MCO", "BUR", "BBS"])
        self.assertEqual(rows[-1]["mapping_decision"], "unresolved")
        self.assertEqual(rows[-1]["scalesync_vbo_id"], "")

    def test_felis_excludes_removed_and_marks_group(self):
        page = """
        <p>Alpha*RU</p><p>Заводчик: A</p><p>Породы: мейн-кун, восточные</p>
        <p>Beta*RU - УДАЛЕН ИЗ БАЗЫ FIFe</p><p>Порода: бурманская</p>
        """
        rows = subject.felis_rows(page)
        self.assertEqual(len(rows), 2)
        self.assertEqual({row["mapping_decision"] for row in rows}, {"exact", "aggregate"})

    def test_summary_excludes_aggregate_mapping_with_single_vbo_id(self):
        rows = [
            {
                "source_id": "source",
                "breed_name_en": "Exotic Shorthair",
                "scalesync_vbo_id": "VBO:0100096",
                "mapping_decision": "exact",
            },
            {
                "source_id": "source",
                "breed_name_en": "Exotic Shorthair",
                "scalesync_vbo_id": "VBO:0100096",
                "mapping_decision": "aggregate",
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "summary.csv"
            subject.summarize(rows, output)
            with output.open(encoding="utf-8", newline="") as stream:
                summary = list(csv.DictReader(stream))
        self.assertEqual(len(summary), 1)
        self.assertEqual(summary[0]["cattery_breed_records"], "1")


if __name__ == "__main__":
    unittest.main()
