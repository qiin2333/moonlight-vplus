/* SPDX-License-Identifier: GPL-3.0-only */
#pragma once

#include <jni.h>

// Query only; this never creates a logical device or shares a decoder's state.
jobject query_pyrowave_capabilities(JNIEnv *env, bool runtime_available);
