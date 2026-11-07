"""Independent offline references; neither a runtime converter nor a motor model.

Use unnormalized source PCM, a trailing 20 ms RMS window, and (only for the
single-carrier controls) Hilbert phase. No click replacement or device profiles.
The Hilbert reference uses the whole clip and is deliberately not causal.
"""
import numpy as np


CONTINUOUS = {"run_fine", "run_low", "run_noise", "draw_clean", "draw_noise", "beating", "bass"}
SINGLE_CARRIER = {"draw_clean", "bass"}


def source_reference(pcm, rate, times_us, frequency=False):
    """PCM is stereo float in original full-scale units, without peak normalization."""
    ends = np.rint(np.asarray(times_us) * rate / 1e6).astype(int)
    assert len(pcm) > 0 and np.all((ends > 0) & (ends <= len(pcm)))
    window = round(rate * .020)
    starts = np.maximum(0, ends - window)
    energy = np.vstack((np.zeros((1, 2)), np.cumsum(pcm * pcm, axis=0)))
    gain = np.sqrt(2 * (energy[ends] - energy[starts]) / window).max(axis=1)
    if not frequency:
        return gain, None

    # Analytic signal: keep DC/Nyquist, double positive frequencies, remove negative.
    n = len(pcm)
    weights = np.zeros(n)
    weights[0] = 1
    weights[1:(n + 1) // 2] = 2
    if n % 2 == 0:
        weights[n // 2] = 1
    analytic = np.fft.ifft(np.fft.fft(pcm, axis=0) * weights[:, None], axis=0)
    power = np.abs(analytic) ** 2
    hz = np.angle(analytic[1:] * analytic[:-1].conj()) * rate / (2 * np.pi)
    indices = ends - 1
    # Low-envelope phase is unstable. This mask selects measurements only;
    # it never normalizes or gates the signal sent to the implementation.
    valid = (power[indices].sum(axis=1) > power.sum(axis=1).max() * .04) & (indices > 0)
    lane_hz = hz[np.maximum(0, indices - 1)]
    frequency_hz = np.full(len(ends), np.nan)
    np.divide((lane_hz * power[indices]).sum(axis=1), power[indices].sum(axis=1),
              out=frequency_hz, where=valid)
    frequency_hz[(frequency_hz < 50) | (frequency_hz > 400)] = np.nan
    return gain, frequency_hz


def gain_error(reference, actual):
    """NRMSE keeps amplitude errors: no rescaling or best-fit gain is applied."""
    energy = float(np.mean(reference ** 2))
    if energy == 0:
        return {"gain_nrmse": None, "gain_rms_ratio": None}
    return {"gain_nrmse": float(np.sqrt(np.mean((actual - reference) ** 2) / energy)),
            "gain_rms_ratio": float(np.sqrt(np.mean(actual ** 2) / energy))}


def frequency_error(reference, actual):
    valid = np.isfinite(reference)
    covered = valid & np.isfinite(actual)
    return {"mae": float(np.mean(np.abs(actual[covered] - reference[covered]))) if covered.any() else None,
            "coverage": float(covered.sum() / valid.sum()) if valid.any() else None}


def measure(directory, meta, trace):
    if meta["family"] not in CONTINUOUS:
        return None  # Separating real points from backgrounds needs a different reference.
    pcm = np.fromfile(directory / "input.pcm", dtype="<i2").reshape(-1, 2).astype(float) / 32768
    times = [int(r["time_us"]) for r in trace]
    gain, hz = source_reference(pcm, meta["sample_rate"], times, meta["family"] in SINGLE_CARRIER)
    actual = np.array([float(r["gain"]) for r in trace])
    result = gain_error(gain, actual)
    if hz is not None:
        result["frequency_hint_hz"] = frequency_error(hz, np.array([float(r["frequency_hint_hz"]) for r in trace]))
        # HE values are command units, not calibrated physical Hz. Compare targets
        # in those units; do not synthesize a fictitious hardware response/ramp.
        he = np.floor((35 + (np.clip(hz, 100, 260) - 100) / 4) / 5 + .5) * 5
        result["richtap_target_he"] = frequency_error(he, np.array([
            float(r["rt_frequency"]) if r["rt_active"] == "true" else np.nan for r in trace]))
        carriers = []
        for row in trace:
            parts = row["sp_asset"].split("_")
            # These controls have no points; a zero body is not a frequency output.
            carriers.append(float(parts[1]) if len(parts) == 3 and int(parts[2]) > 0 else
                            160.0 if len(parts) == 2 and int(parts[1]) > 0 else np.nan)
        result["soundpool_carrier_hz"] = frequency_error(hz, np.array(carriers))
    return result
