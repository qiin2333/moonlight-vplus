"""Known-signal and intentional-degradation checks for the offline measurements."""
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

import numpy as np

from continuous_fidelity import frequency_error, gain_error, measure, source_reference


class ContinuousFidelityTest(unittest.TestCase):
    def test_rms_preserves_absolute_strength_and_stereo_max(self):
        rate = 48000
        t = np.arange(rate) / rate
        for amplitude in (.01, .1):
            left = amplitude * np.sin(2 * np.pi * 100 * t)
            pcm = np.column_stack((left, left * .5))
            gain, hz = source_reference(pcm, rate, np.arange(20_000, 1_000_001, 5_000), True)
            np.testing.assert_allclose(gain, amplitude, atol=1e-10)
            np.testing.assert_allclose(hz, 100, atol=1e-8)

    def test_attenuation_and_muting_cannot_improve_gain_score(self):
        reference = np.array([0., .01, .08, .04, .02, 0.])
        for scale in (1., .5, 0.):
            result = gain_error(reference, reference * scale)
            self.assertAlmostEqual(result["gain_nrmse"], 1 - scale)
            self.assertAlmostEqual(result["gain_rms_ratio"], scale)

    def test_silence_has_no_invented_frequency_or_relative_gain(self):
        gain, hz = source_reference(np.zeros((4800, 2)), 48000, [5_000, 50_000, 100_000], True)
        np.testing.assert_array_equal(gain, 0)
        self.assertTrue(np.isnan(hz).all())
        self.assertIsNone(gain_error(gain, gain)["gain_nrmse"])
        self.assertEqual(frequency_error(hz, hz), {"mae": None, "coverage": None})

    def test_chirp_reference_tracks_source_without_peak_normalization(self):
        rate = 48000
        t = np.arange(rate) / rate
        signal = .03 * np.sin(np.pi * t) ** 2 * np.sin(2 * np.pi * (100 * t + 100 * t * t))
        times = np.arange(5_000, 1_000_001, 5_000)
        _, hz = source_reference(np.column_stack((signal, signal)), rate, times, True)
        expected = 100 + 200 * times / 1e6
        valid = np.isfinite(hz)
        self.assertGreater(valid.sum(), 100)
        self.assertLess(np.max(np.abs(hz[valid] - expected[valid])), 2)
        self.assertGreater(frequency_error(hz, np.full(len(hz), 160.))["mae"], 30)

    def test_missing_output_is_counted_in_coverage_not_zero_error(self):
        source = np.array([100., 150., 200.])
        self.assertEqual(frequency_error(source, np.full(3, np.nan)), {"mae": None, "coverage": 0.})
        self.assertEqual(frequency_error(source, np.array([100., np.nan, 210.])),
                         {"mae": 5., "coverage": 2 / 3})

    def test_stopped_backends_and_noise_are_not_given_a_frequency_score(self):
        t = np.arange(4800) / 48000
        signal = np.rint(.05 * 32768 * np.sin(2 * np.pi * 100 * t)).astype("<i2")
        with TemporaryDirectory() as temp:
            directory = Path(temp)
            np.column_stack((signal, signal)).tofile(directory / "input.pcm")
            trace = [{"time_us": 50_000, "gain": 0., "frequency_hint_hz": 100.,
                      "rt_frequency": 35, "rt_active": "false", "sp_asset": "body_100_0"}]
            result = measure(directory, {"family": "bass", "sample_rate": 48000}, trace)
            self.assertEqual(result["gain_rms_ratio"], 0.)
            for backend in ("richtap_target_he", "soundpool_carrier_hz"):
                self.assertEqual(result[backend], {"mae": None, "coverage": 0.})
            result = measure(directory, {"family": "run_noise", "sample_rate": 48000}, trace)
            self.assertEqual(set(result), {"gain_nrmse", "gain_rms_ratio"})


if __name__ == "__main__":
    unittest.main()
