#!/usr/bin/env python3
import csv, unittest
from pathlib import Path

P=Path(__file__).parent
class IntegratedEvidenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with (P/'weights.csv').open() as f: cls.weights=list(csv.DictReader(f))
        with (P/'breed-coverage.csv').open() as f: cls.coverage=list(csv.DictReader(f))
    def test_exactly_48_unique_vbo_concepts(self):
        self.assertEqual(48,len(self.coverage)); self.assertEqual(48,len({r['vboId'] for r in self.coverage}))
    def test_published_means_are_not_upper_bounds(self):
        for r in self.weights:
            if r['statistic'] in ('published_mean','mean_only','secondary_mean_only'):
                self.assertFalse(r['upperKg']); self.assertTrue(r['meanKg'])
    def test_intervals_and_midpoints(self):
        for r in self.weights:
            if r['lowerKg'] and r['upperKg']:
                lo,hi=float(r['lowerKg']),float(r['upperKg']); self.assertLessEqual(lo,hi)
                if r['medianKg']:
                    med=float(r['medianKg']); self.assertLessEqual(lo,med); self.assertLessEqual(med,hi)
                    if r['derivation'] in ('derived_midpoint','midpoint_derived','midpoint'):
                        self.assertAlmostEqual((lo+hi)/2,med,places=5)
    def test_each_concept_has_required_stage_rows(self):
        for c in self.coverage:
            rr=[r for r in self.weights if r['vboId']==c['vboId']]
            self.assertTrue(any(r['stage']=='birth' for r in rr)); self.assertTrue(any(r['stage']=='intermediate' for r in rr))
            self.assertTrue(any(r['stage']=='adult' and r['sex']=='female' for r in rr)); self.assertTrue(any(r['stage']=='adult' and r['sex']=='male' for r in rr))
    def test_approved_production_scope(self):
        ready=[r for r in self.coverage if r['implementationReadiness'] in ('ready_official','ready_open_fallback') and r['vboId']!='0100061']
        self.assertEqual(26,len(ready))
        self.assertEqual(17,sum(r['implementationReadiness']=='ready_official' for r in ready))
        self.assertEqual(9,sum(r['implementationReadiness']=='ready_open_fallback' for r in ready))
        self.assertIn('0100230',{r['vboId'] for r in ready})
        self.assertNotIn('0100061',{r['vboId'] for r in ready})
    def test_russian_blue_is_batch_one_fallback_and_munchkin_names_are_distinct(self):
        by_id={r['vboId']:r for r in self.coverage}
        self.assertEqual('ready_open_fallback',by_id['0100200']['implementationReadiness'])
        self.assertEqual('Манчкин',by_id['0100169']['breedRu'])
        self.assertEqual('Манчкин длинношёрстный',by_id['0100170']['breedRu'])
        self.assertEqual('Манчкин короткошёрстный',by_id['0100303']['breedRu'])

if __name__=='__main__': unittest.main()
