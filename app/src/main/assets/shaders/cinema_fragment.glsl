#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp sampler3D;

in vec2 vTexCoord;
in vec2 vScreenUv;

uniform samplerExternalOES uCameraTexture;
uniform sampler3D uLutTexture;

uniform int uLogProfileId;
uniform float uLutIntensity;
uniform int uLutEnabled;
uniform float uLutSize;

// Monitoring overlays (disabled on video encoder pass)
uniform int uEnableOverlays;
uniform int uSplitScreen;
uniform float uSplitPosition;
uniform int uFalseColor;
uniform int uZebraEnabled;
uniform float uZebraThreshold;
uniform float uTimeSeconds;
uniform int uFocusPeaking;
uniform float uFocusPeakingThreshold;
uniform vec3 uFocusPeakingColor;
uniform vec2 uTexelSize;

out vec4 fragColor;

float srgbToLinearChannel(float c) {
    return (c <= 0.04045) ? (c / 12.92) : pow((c + 0.055) / 1.055, 2.4);
}

vec3 srgbToLinear(vec3 rgb) {
    return vec3(
        srgbToLinearChannel(clamp(rgb.r, 0.0, 1.0)),
        srgbToLinearChannel(clamp(rgb.g, 0.0, 1.0)),
        srgbToLinearChannel(clamp(rgb.b, 0.0, 1.0))
    );
}

float linearToRec709Channel(float l) {
    float v = clamp(l, 0.0, 1.0);
    return (v < 0.018) ? (4.5 * v) : (1.099 * pow(v, 0.45) - 0.099);
}

// ITU-R BT.2100 HLG OETF
float hlgChannel(float e) {
    float v = clamp(e, 0.0, 1.0);
    const float a = 0.17883277;
    const float b = 0.28466892;
    const float c = 0.55991073;
    return (v <= 1.0 / 12.0) ? sqrt(3.0 * v) : (a * log(12.0 * v - b) + c);
}

// Sony S-Log3-inspired OETF (simulated for 0..1 normalized scene linear input)
float slog3Channel(float l) {
    float x = max(l, 0.0);
    if (x >= 0.01125) {
        return clamp((420.0 + (log(x + 0.01) / log(10.0) - log(0.18 + 0.01) / log(10.0)) * 261.5) / 1023.0, 0.0, 1.0);
    }
    return clamp((x * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0, 0.0, 1.0);
}

// ARRI LogC3-inspired OETF (simulated)
float logCChannel(float l) {
    float x = max(l, 0.0);
    const float cut = 0.010591;
    const float a = 5.555556;
    const float b = 0.052272;
    const float c = 0.247190;
    const float d = 0.385537;
    const float e = 5.367655;
    const float f = 0.092809;
    return clamp((x > cut) ? (c * log(a * x + b) / log(10.0) + d) : (e * x + f), 0.0, 1.0);
}

// Canon C-Log3-inspired OETF (simulated)
float clog3Channel(float l) {
    float x = max(l, 0.0);
    if (x < 0.014) {
        return clamp(1.9754798 * x + 0.12512219, 0.0, 1.0);
    }
    return clamp(0.36726845 * log(14.98325 * x + 1.0) / log(10.0) + 0.12783901, 0.0, 1.0);
}

// Panasonic V-Log-inspired OETF (simulated)
float vlogChannel(float l) {
    float x = max(l, 0.0);
    const float cut = 0.01;
    const float b = 0.00873;
    const float c = 0.241514;
    const float d = 0.598206;
    return clamp((x < cut) ? (5.6 * x + 0.125) : (c * log(x + b) / log(10.0) + d), 0.0, 1.0);
}

// Apple Log-inspired OETF (simulated)
float appleLogChannel(float l) {
    float x = max(l, 0.0);
    const float r0 = -0.05641088;
    const float rt = 0.01;
    const float c = 47.28711236;
    const float beta = 0.00964052;
    const float gamma = 0.08550479;
    const float delta = 0.69336945;
    if (x < rt) {
        float d = x - r0;
        return clamp(c * d * d, 0.0, 1.0);
    }
    return clamp(gamma * log2(x + beta) + delta, 0.0, 1.0);
}

// Fujifilm F-Log2-inspired OETF (simulated)
float flog2Channel(float l) {
    float x = max(l, 0.0);
    const float cut = 0.000889;
    const float a = 5.555556;
    const float b = 0.064829;
    const float c = 0.245281;
    const float d = 0.384316;
    const float e = 8.799461;
    const float f = 0.092864;
    return clamp((x >= cut) ? (c * log(a * x + b) / log(10.0) + d) : (e * x + f), 0.0, 1.0);
}

// RED Log3G10-inspired OETF (simulated)
float redLogChannel(float l) {
    float x = max(l, 0.0) + 0.01;
    const float a = 0.224282;
    const float b = 155.975327;
    return clamp(a * log(x * b + 1.0) / log(10.0), 0.0, 1.0);
}

// Blackmagic Film Gen5-inspired OETF (simulated)
float bmdFilmChannel(float l) {
    float x = max(l, 0.0);
    const float a = 0.08692876;
    const float b = 0.00549407;
    const float c = 0.53001334;
    const float d = 8.28360593;
    const float e = 0.09246575;
    const float cut = 0.005;
    return clamp((x < cut) ? (d * x + e) : (a * log(x + b) + c), 0.0, 1.0);
}

// HONOR-inspired Log (simulated soft highlight roll-off + lifted shadow latitude)
float honorLogChannel(float l) {
    float x = max(l, 0.0);
    // Soft highlight knee compression + smooth log encoding calibrated for mobile sensors
    float compressed = x / (1.0 + 0.14 * x);
    return clamp(0.115 + 0.258 * log(1.0 + 18.5 * compressed) / log(10.0), 0.0, 1.0);
}

vec3 applyLogProfile(vec3 srgb, int profileId) {
    if (profileId == 0) {
        // Standard Rec.709 (pass-through with clean clamping)
        return clamp(srgb, 0.0, 1.0);
    }
    // Expand 0..1 sRGB into linear scene estimate with gentle highlight headroom
    vec3 lin = srgbToLinear(srgb);
    vec3 res;
    if (profileId == 1) {
        // HLG HDR
        res = vec3(hlgChannel(lin.r), hlgChannel(lin.g), hlgChannel(lin.b));
    } else if (profileId == 2) {
        // HONOR-inspired Log
        res = vec3(honorLogChannel(lin.r), honorLogChannel(lin.g), honorLogChannel(lin.b));
    } else if (profileId == 3) {
        // Sony S-Log3-inspired
        res = vec3(slog3Channel(lin.r), slog3Channel(lin.g), slog3Channel(lin.b));
    } else if (profileId == 4) {
        // ARRI LogC-inspired
        res = vec3(logCChannel(lin.r), logCChannel(lin.g), logCChannel(lin.b));
    } else if (profileId == 5) {
        // Canon C-Log3-inspired
        res = vec3(clog3Channel(lin.r), clog3Channel(lin.g), clog3Channel(lin.b));
    } else if (profileId == 6) {
        // Panasonic V-Log-inspired
        res = vec3(vlogChannel(lin.r), vlogChannel(lin.g), vlogChannel(lin.b));
    } else if (profileId == 7) {
        // Apple Log-inspired
        res = vec3(appleLogChannel(lin.r), appleLogChannel(lin.g), appleLogChannel(lin.b));
    } else if (profileId == 8) {
        // Fujifilm F-Log2-inspired
        res = vec3(flog2Channel(lin.r), flog2Channel(lin.g), flog2Channel(lin.b));
    } else if (profileId == 9) {
        // RED Log-inspired
        res = vec3(redLogChannel(lin.r), redLogChannel(lin.g), redLogChannel(lin.b));
    } else if (profileId == 10) {
        // Blackmagic Film-inspired
        res = vec3(bmdFilmChannel(lin.r), bmdFilmChannel(lin.g), bmdFilmChannel(lin.b));
    } else {
        res = vec3(linearToRec709Channel(lin.r), linearToRec709Channel(lin.g), linearToRec709Channel(lin.b));
    }
    // Gentle cinema saturation reduction for log profiles to preserve color headroom
    if (profileId >= 2) {
        float luma = dot(res, vec3(0.2126, 0.7152, 0.0722));
        res = mix(vec3(luma), res, 0.88);
    }
    return clamp(res, 0.0, 1.0);
}

vec3 sample3DLut(vec3 color) {
    float scale = (uLutSize - 1.0) / uLutSize;
    float offset = 0.5 / uLutSize;
    vec3 lutCoord = clamp(color, 0.0, 1.0) * scale + offset;
    return texture(uLutTexture, lutCoord).rgb;
}

float luma709(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Cinema IRE False Color map (ARRI / SmallHD style exposure zones)
vec3 falseColorMap(float ire) {
    if (ire < 0.05) return vec3(0.45, 0.08, 0.75);       // Purple: crushed shadows <5 IRE
    if (ire < 0.15) return vec3(0.08, 0.25, 0.90);       // Deep Blue: deep shadows 5-15 IRE
    if (ire < 0.28) return vec3(0.10, 0.72, 0.92);       // Cyan: shadow detail 15-28 IRE
    if (ire < 0.40) return vec3(ire * 0.95);             // Neutral monochrome
    if (ire < 0.50) return vec3(0.15, 0.82, 0.35);       // Green: 18% middle grey 40-50 IRE
    if (ire < 0.56) return vec3(ire * 0.95);             // Neutral monochrome
    if (ire < 0.68) return vec3(0.95, 0.52, 0.72);       // Pink: Caucasian/mid skin 56-68 IRE
    if (ire < 0.80) return vec3(ire * 0.95);             // Neutral monochrome
    if (ire < 0.94) return vec3(0.96, 0.84, 0.14);       // Yellow: near-clip highlights 80-94 IRE
    return vec3(0.96, 0.12, 0.12);                       // Red: clipped >94 IRE
}

void main() {
    vec3 rawColor = texture(uCameraTexture, vTexCoord).rgb;
    vec3 logColor = applyLogProfile(rawColor, uLogProfileId);
    vec3 gradedColor = logColor;

    if (uLutEnabled == 1 && uLutIntensity > 0.001) {
        vec3 lutSample = sample3DLut(logColor);
        gradedColor = mix(logColor, lutSample, clamp(uLutIntensity, 0.0, 1.0));
    }

    vec3 outColor = gradedColor;

    if (uEnableOverlays == 1) {
        // 50/50 split-screen comparison: left = unprocessed camera, right = Log + 3D LUT graded
        if (uSplitScreen == 1 && vScreenUv.x < uSplitPosition) {
            outColor = rawColor;
        }

        // False-color exposure visualization
        if (uFalseColor == 1) {
            float ire = luma709(outColor);
            outColor = falseColorMap(ire);
        }

        // Zebra exposure warning hatch above IRE threshold
        if (uZebraEnabled == 1) {
            float ire = luma709(gradedColor);
            if (ire >= uZebraThreshold) {
                float stripe = sin((gl_FragCoord.x + gl_FragCoord.y) * 0.55 - uTimeSeconds * 6.0);
                if (stripe > 0.25) {
                    outColor = mix(outColor, vec3(0.05, 0.05, 0.05), 0.82);
                } else {
                    outColor = mix(outColor, vec3(1.0, 0.85, 0.15), 0.75);
                }
            }
        }

        // Focus peaking using 3x3 Sobel luminance gradient
        if (uFocusPeaking == 1) {
            vec2 dx = vec2(uTexelSize.x, 0.0);
            vec2 dy = vec2(0.0, uTexelSize.y);
            float tl = luma709(texture(uCameraTexture, vTexCoord - dx + dy).rgb);
            float tc = luma709(texture(uCameraTexture, vTexCoord + dy).rgb);
            float tr = luma709(texture(uCameraTexture, vTexCoord + dx + dy).rgb);
            float ml = luma709(texture(uCameraTexture, vTexCoord - dx).rgb);
            float mr = luma709(texture(uCameraTexture, vTexCoord + dx).rgb);
            float bl = luma709(texture(uCameraTexture, vTexCoord - dx - dy).rgb);
            float bc = luma709(texture(uCameraTexture, vTexCoord - dy).rgb);
            float br = luma709(texture(uCameraTexture, vTexCoord + dx - dy).rgb);

            float gx = -tl - 2.0 * ml - bl + tr + 2.0 * mr + br;
            float gy = -tl - 2.0 * tc - tr + bl + 2.0 * bc + br;
            float edge = sqrt(gx * gx + gy * gy);
            if (edge > uFocusPeakingThreshold) {
                float strength = clamp((edge - uFocusPeakingThreshold) * 3.0, 0.0, 1.0);
                outColor = mix(outColor, uFocusPeakingColor, strength * 0.92);
            }
        }
    }

    fragColor = vec4(clamp(outColor, 0.0, 1.0), 1.0);
}
