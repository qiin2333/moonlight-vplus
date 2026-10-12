struct DolbyComponentMapping {
    float pivots[9];
    float padding[3];
    vec4 pieceInfo[8]; // method, polynomial order, MMR order, MMR constant
    vec4 polynomial[8][3];
    vec4 mmr[8][3][2]; // seven MMR coefficients packed as 4 + 3 values
};

layout(std430, set = 0, binding = 4) readonly buffer DynamicHdrMapping {
    float pqMapping[256];
    DolbyComponentMapping dolby[3];
    vec4 yccToRgb[3];
    vec4 yccOffsets;
    vec4 lmsToRgb[3];
    vec4 mappingFlags;
};

vec3 dynamicDolbyReshape(vec3 signal) {
    if (mappingFlags.y < 0.5) return signal;
    vec3 reshaped = signal;
    for (int channel = 0; channel < 3; ++channel) {
        int piece = 0;
        for (int index = 1; index < 9; ++index) {
            // Boundary i starts piece i-1. Unused pivots are zero, so the
            // strict increase check also limits the loop to the declared
            // number of pieces without a separate count in std430.
            if (dolby[channel].pivots[index] > dolby[channel].pivots[index - 1] &&
                signal[channel] >= dolby[channel].pivots[index]) {
                piece = index - 1;
            }
        }
        vec4 info = dolby[channel].pieceInfo[piece];
        float mapped = signal[channel];
        if (info.x < 0.5) {
            vec4 coefficients = dolby[channel].polynomial[piece][0];
            mapped = (coefficients.z * mapped + coefficients.y) * mapped + coefficients.x;
        } else {
            int order = clamp(int(info.z + 0.5), 1, 3);
            vec4 sigX = vec4(signal.x * signal.y, signal.x * signal.z,
                             signal.y * signal.z, signal.x * signal.y * signal.z);
            mapped = info.w;
            for (int row = 0; row < 3; ++row) {
                if (row >= order) break;
                vec4 first = dolby[channel].mmr[piece][row][0];
                vec4 second = dolby[channel].mmr[piece][row][1];
                mapped += dot(first.xyz, signal) + first.w * sigX.x +
                          dot(second.xyz, sigX.yzw);
            }
        }
        float upper_bound = 1.0;
        for (int index = 0; index < 8; ++index) {
            if (dolby[channel].pivots[index + 1] > dolby[channel].pivots[index]) {
                upper_bound = dolby[channel].pivots[index + 1];
            }
        }
        reshaped[channel] = clamp(mapped, dolby[channel].pivots[0], upper_bound);
    }
    return reshaped;
}
vec3 dynamicBaseYuvToRgb(vec3 yuv) {
    float u = yuv.y - 0.5;
    float v = yuv.z - 0.5;
    return vec3(
        yuv.x + 1.4746 * v,
        yuv.x - 0.16455 * u - 0.57135 * v,
        yuv.x + 1.8814 * u);
}

vec3 dynamicYuvToRgb(vec3 yuv) {
    // HDR10+ uses the ordinary BT.2020 matrix. Dolby Vision may supply a
    // per-frame Y'CbCr matrix and reshape curves; apply each independently so
    // a valid matrix is not lost when the RPU has no reshape pieces.
    if (pc.dynamicEnabled < 0.5) return dynamicBaseYuvToRgb(yuv);
    vec3 reshaped = mappingFlags.y > 0.5 ? dynamicDolbyReshape(yuv) : yuv;
    if (mappingFlags.z < 0.5) return dynamicBaseYuvToRgb(reshaped);
    return vec3(
        dot(yccToRgb[0].xyz, reshaped) - yccOffsets.x,
        dot(yccToRgb[1].xyz, reshaped) - yccOffsets.y,
        dot(yccToRgb[2].xyz, reshaped) - yccOffsets.z);
}

vec3 dynamicPqToNits(vec3 signal) {
    vec3 p = pow(clamp(signal, vec3(0.0), vec3(1.0)), vec3(32.0 / 2523.0));
    return 10000.0 * pow(max(p - 3424.0 / 4096.0, vec3(0.0)) /
                        (2413.0 / 128.0 - 2392.0 / 128.0 * p), vec3(16384.0 / 2610.0));
}

vec3 dynamicNitsToPq(vec3 nits) {
    vec3 p = pow(clamp(nits / 10000.0, vec3(0.0), vec3(1.0)), vec3(2610.0 / 16384.0));
    return pow((3424.0 / 4096.0 + 2413.0 / 128.0 * p) / (1.0 + 2392.0 / 128.0 * p),
               vec3(2523.0 / 32.0));
}

vec3 dynamicHlgToScene(vec3 signal) {
    vec3 low = signal * signal / 3.0;
    vec3 high = (exp((signal - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
    return mix(low, high, step(vec3(0.5), signal));
}

vec3 dynamicSceneToHlg(vec3 scene) {
    scene = max(scene, vec3(0.0));
    vec3 low = sqrt(3.0 * scene);
    vec3 high = 0.17883277 * log(max(12.0 * scene - 0.28466892, vec3(0.000001))) + 0.55991073;
    return mix(low, high, step(vec3(1.0 / 12.0), scene));
}

float dynamicHlgGamma(float peak) {
    if (peak >= 400.0 && peak <= 2000.0) {
        return 1.2 + 0.42 * log(peak / 1000.0) / log(10.0);
    }
    return 1.2 * pow(1.111, log2(peak / 1000.0));
}

vec3 applyDynamicHdr(vec3 signal) {
    if (pc.dynamicEnabled < 0.5) return signal;
    vec3 linearRgb;
    if (pc.hdrMode > 1.5) {
        vec3 scene = dynamicHlgToScene(max(signal, vec3(0.0)));
        float y = dot(scene, vec3(0.2627, 0.6780, 0.0593));
        if (y <= 0.0) return vec3(0.0);
        float gamma = dynamicHlgGamma(pc.sourcePeakNits);
        linearRgb = scene * pow(max(y, 0.000001), gamma - 1.0) * pc.sourcePeakNits;
    } else {
        linearRgb = dynamicPqToNits(signal);
    }
    if (mappingFlags.x > 0.5) {
        linearRgb = vec3(
            dot(lmsToRgb[0].xyz, linearRgb),
            dot(lmsToRgb[1].xyz, linearRgb),
            dot(lmsToRgb[2].xyz, linearRgb));
    }
    float peak = max(linearRgb.r, max(linearRgb.g, linearRgb.b));
    if (peak <= 0.0) return vec3(0.0);
    float position = dynamicNitsToPq(vec3(peak)).x * 255.0;
    int index = int(floor(position));
    float mappedPq = mix(pqMapping[index], pqMapping[min(index + 1, 255)], fract(position));
    float mappedPeak = dynamicPqToNits(vec3(mappedPq)).x;
    linearRgb *= mappedPeak / peak;
    if (pc.hdrMode > 1.5) {
        vec3 normalized = max(linearRgb, vec3(0.0)) / pc.targetPeakNits;
        float y = dot(normalized, vec3(0.2627, 0.6780, 0.0593));
        if (y <= 0.0) return vec3(0.0);
        float gamma = dynamicHlgGamma(pc.targetPeakNits);
        vec3 scene = normalized * pow(max(y, 0.000001), (1.0 - gamma) / gamma);
        vec3 outputHlg = dynamicSceneToHlg(scene);
        float hlgLuma = dot(outputHlg, vec3(0.2627, 0.6780, 0.0593));
        return mix(vec3(hlgLuma), outputHlg, clamp(pc.saturation, 0.0, 8.0));
    }
    vec3 outputPq = dynamicNitsToPq(linearRgb);
    float pqLuma = dot(outputPq, vec3(0.2627, 0.6780, 0.0593));
    return mix(vec3(pqLuma), outputPq, clamp(pc.saturation, 0.0, 8.0));
}
