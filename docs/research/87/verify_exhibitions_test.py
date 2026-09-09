#!/usr/bin/env python3
import csv
import pathlib
import tempfile
import unittest

from verify_exhibitions import CSV_PATH, verify


class VerifyExhibitionsTest(unittest.TestCase):
    def copy_rows(self):
        with CSV_PATH.open(newline="", encoding="utf-8") as handle:
            reader = csv.DictReader(handle)
            return reader.fieldnames, list(reader)

    def write_rows(self, directory, fields, rows):
        path = pathlib.Path(directory) / "data.csv"
        with path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
        return path

    def test_bundled_dataset(self):
        verify()

    def test_wrong_total_is_rejected(self):
        fields, rows = self.copy_rows()
        rows[0]["catalog_entry_count"] = "15"
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(AssertionError):
                verify(self.write_rows(directory, fields, rows))

    def test_column_reordering_is_rejected(self):
        fields, rows = self.copy_rows()
        fields[0], fields[1] = fields[1], fields[0]
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(AssertionError):
                verify(self.write_rows(directory, fields, rows))


if __name__ == "__main__":
    unittest.main()
