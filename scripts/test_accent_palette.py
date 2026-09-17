import math
import unittest
import xml.etree.ElementTree as ET

from generate_accent_variants import (
    card_palette, generated_resources, lab_to_srgb_clamped, rotate_hue, srgb_to_lab,
)


class AccentPaletteTest(unittest.TestCase):
    def test_d65_reference_colors(self):
        for color, expected in [('#FFFF0000', (53.23, 80.11, 67.22)),
                                ('#FF00FF00', (87.74, -86.18, 83.18)),
                                ('#FF0000FF', (32.30, 79.20, -107.86)),
                                ('#FFFFFFFF', (100, 0, 0))]:
            for actual, reference in zip(srgb_to_lab(color), expected):
                self.assertAlmostEqual(actual, reference, delta=.03)

    def test_rgb_round_trip(self):
        for r in (0, 1, 12, 64, 128, 227, 255):
            for g in (0, 12, 107, 128, 255):
                for b in (0, 12, 64, 157, 242, 255):
                    color = f'#FF{r:02X}{g:02X}{b:02X}'
                    result = lab_to_srgb_clamped(*srgb_to_lab(color))
                    for i in (3, 5, 7):
                        self.assertLessEqual(abs(int(color[i:i+2], 16) - int(result[i:i+2], 16)), 1)

    def test_every_card_stop_preserves_alpha_and_lightness(self):
        for mode in ('values', 'values-night'):
            for color in card_palette(mode).values():
                for bucket in range(12):
                    result = rotate_hue(color, bucket * 30 + 15)
                    self.assertEqual(color[1:3], result[1:3])
                    self.assertAlmostEqual(srgb_to_lab(color)[0], srgb_to_lab(result)[0], delta=.3)

    def test_glows_remain_chromatic_and_neutrals_unchanged(self):
        for bucket in range(12):
            for color in ('#40FF6B9D', '#25FF8FA3', '#15FFA3C7'):
                _, a, b = srgb_to_lab(rotate_hue(color, bucket * 30 + 15))
                self.assertGreater(math.hypot(a, b), 10)
            for color in ('#FFFFFFFF', '#40808080', '#001C1C1C'):
                self.assertEqual(color, rotate_hue(color, bucket * 30 + 15))

    def test_generated_day_and_night_resources_are_complete(self):
        outputs = generated_resources()
        self.assertEqual(2, len(outputs))
        for path, content in outputs.items():
            root = ET.fromstring(content)
            colors = {node.attrib['name'] for node in root.findall('color')}
            for name in card_palette(path.parent.name):
                for bucket in range(12):
                    self.assertIn(f'{name}_b{bucket}', colors)


if __name__ == '__main__':
    unittest.main()
