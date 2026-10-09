layout(std430, set = 0, binding = 4) readonly buffer DynamicHdrMapping {
    float pqMapping[256];
};

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
        return dynamicSceneToHlg(scene);
    }
    return dynamicNitsToPq(linearRgb);
}
